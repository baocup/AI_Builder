package baocup.builder.agent;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import baocup.builder.BuilderConfig;
import baocup.builder.memory.BuildMemory;
import baocup.builder.util.Bounds;
import baocup.builder.util.Json;

/**
 * Agent 工具的执行器。模型调用工具 → 这里编译成体素操作 / 状态查询 / 拍照 → 结构化结果回执。
 *
 * 坐标纪律：所有坐标都是绝对世界整数坐标；越界、非法 id 一律拒绝并逐条回报；
 * 每次写入都回执真正落地的坐标范围与当前总格数，模型可以随时用 read_state 核对。
 */
public final class ToolExecutor {
    public static final class Outcome {
        public String result = "{}";
        public final List<byte[]> images = new ArrayList<>();
        public final List<BuildMemory.CaptureLine> captures = new ArrayList<>();
        public boolean finish;
        public String summary = "";
        public String title = "";
        public String log = "";
    }

    private final String taskId;
    private final Bounds bounds;
    private final VoxelWorld world;
    private final Path captureDir;
    private final int version;
    /** 外部 Agent（MCP）链路：照片只落盘，不回传图片字节。 */
    private final boolean external;
    private int captureCount;
    private int captureIndex;

    public ToolExecutor(String taskId, Bounds bounds, VoxelWorld world, Path captureDir, int version) {
        this(taskId, bounds, world, captureDir, version, false);
    }

    public ToolExecutor(String taskId, Bounds bounds, VoxelWorld world, Path captureDir,
                        int version, boolean external) {
        this.taskId = taskId;
        this.bounds = bounds;
        this.world = world;
        this.captureDir = captureDir;
        this.version = version;
        this.external = external;
    }

    public boolean hasCaptured() {
        return captureCount > 0;
    }

    public Outcome execute(LlmClient.ToolCall call) {
        Outcome outcome = new Outcome();
        JsonObject args;
        try {
            args = Json.GSON.fromJson(call.arguments(), JsonObject.class);
            if (args == null) {
                args = new JsonObject();
            }
        } catch (Exception e) {
            outcome.result = error("arguments 不是合法 JSON：" + e.getMessage());
            return outcome;
        }
        try {
            switch (call.name()) {
                case AgentTools.READ_STATE -> readState(args, outcome);
                case AgentTools.LIST_MATERIALS -> listMaterials(args, outcome);
                case AgentTools.PLACE_BLOCKS -> placeBlocks(args, outcome);
                case AgentTools.DRAW_SHAPE -> drawShape(args, outcome);
                case AgentTools.REMOVE_BLOCKS -> removeBlocks(args, outcome);
                case AgentTools.CAPTURE -> capture(args, outcome);
                case AgentTools.FINISH_BUILD -> finish(args, outcome);
                default -> outcome.result = error("未知工具 " + call.name());
            }
        } catch (Exception e) {
            outcome.result = error(call.name() + " 执行异常：" + e.getMessage());
        }
        return outcome;
    }

    // ------------------------------------------------------------------
    // 状态与材质
    // ------------------------------------------------------------------

    private void readState(JsonObject args, Outcome outcome) {
        String detail = Json.str(args, "detail", "summary");
        int maxBlocks = Math.max(1, Json.integer(args, "maxBlocks",
                BuilderConfig.agentMaxReadBlocks()));
        JsonObject result = new JsonObject();
        result.addProperty("coordinateSystem", "绝对世界整数坐标 [x,y,z]，与所有工具完全一致");
        result.add("selection", box(bounds.toArray()));
        int[] occupied = world.occupiedBounds();
        // 与写入回执统一：占用范围统一叫 occupiedBounds（数组），actualBounds 只在写入回执里表示本次落地范围
        result.add("occupiedBounds", occupied == null ? new JsonArray() : box(occupied));
        result.addProperty("totalBlocks", world.size());
        JsonObject materials = new JsonObject();
        for (Map.Entry<String, Integer> e : world.materials().entrySet()) {
            materials.addProperty(e.getKey(), e.getValue());
        }
        result.add("materials", materials);
        result.add("layers", world.layerSummary());

        if ("summary".equals(detail)) {
            result.addProperty("detail", "summary");
            outcome.result = Json.GSON.toJson(result);
            outcome.log = "读取状态摘要：" + world.size() + " 格";
            return;
        }
        List<VoxelWorld.Voxel> voxels;
        if ("region".equals(detail)) {
            int[] region = regionArg(args);
            if (region == null) {
                outcome.result = error("detail=region 需要 region=[x1,y1,z1,x2,y2,z2]");
                return;
            }
            voxels = world.inRegion(region);
            result.add("region", box(region));
        } else {
            voxels = world.all();
        }
        boolean truncated = voxels.size() > maxBlocks;
        result.addProperty("detail", detail);
        result.addProperty("returnedBlocks", Math.min(voxels.size(), maxBlocks));
        result.addProperty("truncated", truncated);
        JsonArray blocks = new JsonArray();
        for (int i = 0; i < Math.min(voxels.size(), maxBlocks); i++) {
            VoxelWorld.Voxel v = voxels.get(i);
            JsonArray item = new JsonArray();
            item.add(v.x());
            item.add(v.y());
            item.add(v.z());
            item.add(v.block());
            blocks.add(item);
        }
        result.add("blocks", blocks);
        if (truncated) {
            result.addProperty("hint", "体素超过 " + maxBlocks
                    + " 格，已截断；请用 detail=region 分批查询，或用 layers 定位后缩小范围");
        }
        outcome.result = Json.GSON.toJson(result);
        outcome.log = "读取建筑状态：" + detail + "，返回 " + Math.min(voxels.size(), maxBlocks) + " 格";
    }

    private void listMaterials(JsonObject args, Outcome outcome) {
        String query = Json.str(args, "query", "").trim();
        int limit = Math.max(1, Math.min(100, Json.integer(args, "limit", 40)));
        int offset = Math.max(0, Json.integer(args, "offset", 0));
        BlockIndex index = BlockIndex.get();
        List<BlockIndex.BlockInfo> palette;
        String mode;
        if (query.isBlank()) {
            // 没给关键词时只给一批通用基线帮模型起步，它不是可用材料的全集
            palette = index.paletteFor("", "", limit);
            mode = "baseline";
        } else {
            // 真正的关键词检索：任何阶段都能换关键词查别的材料，不受开局清单限制
            List<BlockIndex.BlockInfo> found = index.search(query, offset + limit);
            int from = Math.min(offset, found.size());
            int to = Math.min(found.size(), offset + limit);
            palette = new ArrayList<>(found.subList(from, to));
            mode = "search";
        }
        JsonObject result = new JsonObject();
        result.addProperty("query", query);
        result.addProperty("mode", mode);
        result.addProperty("offset", offset);
        result.addProperty("returned", palette.size());
        result.addProperty("indexSize", index.size());
        JsonArray lines = new JsonArray();
        for (String line : BlockIndex.compactLines(palette)) {
            lines.add(line);
        }
        result.add("materials", lines);
        result.addProperty("hint", "这不是全局固定清单：建造过程中随时可以用不同 query 重新查询、"
                + "加大 limit 或翻 offset 查更多材料；写入工具接受任何合法的 minecraft: 方块 id。"
                + "带「受重力影响 / 需支撑」说明的方块必须放在有实心支撑的位置");
        outcome.result = Json.GSON.toJson(result);
        outcome.log = "查询材质「" + (query.isBlank() ? "基线" : query) + "」：" + palette.size() + " 条";
    }

    // ------------------------------------------------------------------
    // 写入
    // ------------------------------------------------------------------

    private void placeBlocks(JsonObject args, Outcome outcome) {
        JsonArray blocks = args.getAsJsonArray("blocks");
        if (blocks == null) {
            outcome.result = error("place_blocks 需要 blocks 数组");
            return;
        }
        String mode = Json.str(args, "mode", "set");
        boolean addOnly = "add".equals(mode);
        int cap = BuilderConfig.agentMaxBlocksPerCall();
        VoxelWorld.WriteResult write = new VoxelWorld.WriteResult();
        int seen = 0;
        for (JsonElement element : blocks) {
            if (seen++ >= cap) {
                write.rejections.add(new VoxelWorld.Rejection(0, 0, 0,
                        "单次调用超过 " + cap + " 格上限，其余已忽略"));
                break;
            }
            try {
                JsonArray item = element.getAsJsonArray();
                int x = item.get(0).getAsInt();
                int y = item.get(1).getAsInt();
                int z = item.get(2).getAsInt();
                String block = item.get(3).getAsString();
                String state = item.size() > 4 ? item.get(4).getAsString() : "";
                world.write(write, x, y, z, block, state, addOnly);
            } catch (Exception e) {
                write.rejections.add(new VoxelWorld.Rejection(seen, 0, 0,
                        "条目格式错误：" + e.getMessage()));
            }
        }
        outcome.result = writeResult("place_blocks", write);
        outcome.log = "放置方块：" + write.placed + " 格，被拒 " + write.rejections.size() + " 条";
    }

    private void drawShape(JsonObject args, Outcome outcome) throws ShapeLanguage.ShapeException {
        String block = Json.str(args, "block", "");
        if (block.isBlank() && !"erase".equals(Json.str(args, "mode", "set"))) {
            outcome.result = error("draw_shape 需要 block（方块 id）");
            return;
        }
        String mode = Json.str(args, "mode", "set");
        boolean erase = "erase".equals(mode);
        boolean addOnly = "add".equals(mode);
        boolean replace = "replace".equals(mode);
        String replaceBlock = Json.str(args, "replaceBlock", "");
        String state = Json.str(args, "state", "");
        ShapeLanguage.Compiled compiled = ShapeLanguage.compile(args, bounds,
                BuilderConfig.agentMaxCellsPerCall());
        VoxelWorld.WriteResult write = new VoxelWorld.WriteResult();
        int cap = BuilderConfig.agentMaxBlocksPerCall();
        int used = 0;
        for (int[] cell : compiled.cells()) {
            if (used++ >= cap) {
                write.rejections.add(new VoxelWorld.Rejection(0, 0, 0,
                        "单次调用超过 " + cap + " 格上限，其余已忽略"));
                break;
            }
            int x = cell[0], y = cell[1], z = cell[2];
            if (erase) {
                world.erase(write, x, y, z);
                continue;
            }
            if (replace) {
                VoxelWorld.Voxel old = world.get(x, y, z);
                if (old == null || (!replaceBlock.isBlank()
                        && !replaceBlock.equals(old.block()))) {
                    continue;
                }
            }
            world.write(write, x, y, z, block, state, addOnly);
        }
        JsonObject result = writeResultObject("draw_shape", write);
        result.addProperty("note", compiled.note());
        result.addProperty("evaluatedCells", compiled.evaluated());
        result.add("compiledRegion", box(compiled.actualRegion()));
        outcome.result = Json.GSON.toJson(result);
        outcome.log = "形状编译：" + compiled.note() + "；写入 " + write.placed
                + " 格 / 清除 " + write.removed + " 格";
    }

    private void removeBlocks(JsonObject args, Outcome outcome) throws ShapeLanguage.ShapeException {
        VoxelWorld.WriteResult write = new VoxelWorld.WriteResult();
        int cap = BuilderConfig.agentMaxBlocksPerCall();
        JsonArray blocks = args.getAsJsonArray("blocks");
        if (blocks != null) {
            int seen = 0;
            for (JsonElement element : blocks) {
                if (seen++ >= cap) {
                    write.rejections.add(new VoxelWorld.Rejection(0, 0, 0,
                            "单次删除超过 " + cap + " 格上限，其余已忽略"));
                    break;
                }
                try {
                    JsonArray item = element.getAsJsonArray();
                    world.erase(write, item.get(0).getAsInt(), item.get(1).getAsInt(),
                            item.get(2).getAsInt());
                } catch (Exception ignored) {
                    // 忽略坏条目
                }
            }
        }
        if (args.has("region")) {
            boolean hasCondition = args.has("where") || args.has("shape");
            boolean all = args.has("all") && args.get("all").getAsBoolean();
            if (!hasCondition && !all) {
                outcome.result = error("为避免误删，remove_blocks 的 region 必须配 where 或 shape；"
                        + "若确实要清空整个 region，请显式传 all=true（仍受单次删除上限约束）。"
                        + "建议先用 read_state(detail=region) 确认范围");
                return;
            }
            JsonObject spec = new JsonObject();
            spec.add("region", args.get("region"));
            if (args.has("where")) {
                spec.add("where", args.get("where"));
            }
            if (args.has("shape")) {
                spec.add("shape", args.get("shape"));
            }
            if (args.has("params")) {
                spec.add("params", args.get("params"));
            }
            ShapeLanguage.Compiled compiled = ShapeLanguage.compile(spec, bounds,
                    BuilderConfig.agentMaxCellsPerCall());
            int used = 0;
            for (int[] cell : compiled.cells()) {
                if (used++ >= cap) {
                    write.rejections.add(new VoxelWorld.Rejection(0, 0, 0,
                            "单次删除超过 " + cap + " 格上限，其余已忽略"));
                    break;
                }
                world.erase(write, cell[0], cell[1], cell[2]);
            }
        }
        if (blocks == null && !args.has("region")) {
            outcome.result = error("remove_blocks 需要 blocks 或 region");
            return;
        }
        outcome.result = writeResult("remove_blocks", write);
        outcome.log = "删除方块：" + write.removed + " 格"
                + (write.rejections.isEmpty() ? "" : "，被拒 " + write.rejections.size() + " 条");
    }

    // ------------------------------------------------------------------
    // 拍照与结束
    // ------------------------------------------------------------------

    private void capture(JsonObject args, Outcome outcome) {
        String view = Json.str(args, "view", "around");
        String reason = Json.str(args, "reason", "");
        int maxCameras = Math.max(1, Math.min(
                Json.integer(args, "maxCameras", BuilderConfig.visionMaxImages()),
                BuilderConfig.visionMaxImages()));
        List<CameraPose> poses = new ArrayList<>();
        JsonArray cameras = args.getAsJsonArray("cameras");
        if (cameras != null && cameras.size() > 0) {
            for (JsonElement element : cameras) {
                if (poses.size() >= maxCameras || !element.isJsonObject()) {
                    break;
                }
                JsonObject o = element.getAsJsonObject();
                try {
                    double x = Json.dbl(o, "x", 0);
                    double y = Json.dbl(o, "y", 70);
                    double z = Json.dbl(o, "z", 0);
                    String name = Json.str(o, "name", "模型机位 " + (poses.size() + 1));
                    String why = Json.str(o, "reason", reason);
                    float fov = (float) Math.max(20.0, Math.min(110.0, Json.dbl(o, "fov", 70)));
                    String id = "ai-" + captureIndex + "-" + (poses.size() + 1);
                    CameraPose pose;
                    JsonArray lookAt = o.getAsJsonArray("lookAt");
                    if (lookAt != null && lookAt.size() == 3) {
                        pose = CameraPose.lookingAt(id, name, x, y, z,
                                lookAt.get(0).getAsDouble(),
                                lookAt.get(1).getAsDouble(),
                                lookAt.get(2).getAsDouble(), why);
                    } else {
                        pose = new CameraPose(id, name, x, y, z,
                                (float) Json.dbl(o, "yaw", 0),
                                (float) Json.dbl(o, "pitch", 0), why);
                    }
                    pose.fov = fov;
                    poses.add(pose);
                } catch (Exception ignored) {
                    // 单台机位参数非法时跳过，不影响其余机位
                }
            }
        }
        if (poses.isEmpty()) {
            if ("none".equals(view)) {
                outcome.result = error("view=none 但没有给出有效的 cameras 机位");
                return;
            }
            int count = "all".equals(view) ? BuilderConfig.VISUAL_VIEWS.getAsInt()
                    : Math.min(4, BuilderConfig.visionInspectionImages());
            List<CameraPose> all = CameraRig.around(bounds, Math.max(4, count));
            if ("top".equals(view)) {
                poses.add(all.get(all.size() - 2));
            } else if ("eye".equals(view)) {
                poses.add(all.get(all.size() - 1));
            } else {
                poses.addAll(CameraRig.representative(bounds, Math.min(count, all.size())));
            }
            while (poses.size() > maxCameras) {
                poses.remove(poses.size() - 1);
            }
        }
        captureIndex++;
        String prefix = "v" + (version + 1) + "-agent" + captureIndex;
        VisualInspection.Batch batch = VisualInspection.capture(taskId, version + 1, bounds,
                world.placements(), poses, captureDir,
                BuilderConfig.VISUAL_WIDTH.getAsInt(), BuilderConfig.VISUAL_HEIGHT.getAsInt(),
                prefix);
        boolean visionOk = BuilderConfig.supportsVision();
        if (visionOk) {
            outcome.images.addAll(batch.images);
        }
        // 照片无论如何都会渲染并落盘；关闭图片能力时只是不把 image_url 塞进下一条消息
        outcome.captures.addAll(batch.lines);
        captureCount++;

        JsonArray cameraInfo = new JsonArray();
        int insideSolidCount = 0;
        for (CameraPose pose : poses) {
            JsonObject info = new JsonObject();
            info.addProperty("name", pose.name);
            info.addProperty("x", pose.x);
            info.addProperty("y", pose.y);
            info.addProperty("z", pose.z);
            info.addProperty("yaw", pose.yaw);
            info.addProperty("pitch", pose.pitch);
            info.addProperty("fov", pose.fov);
            info.addProperty("reason", pose.reason);
            boolean insideSolid = world.get((int) Math.floor(pose.x), (int) Math.floor(pose.y),
                    (int) Math.floor(pose.z)) != null;
            info.addProperty("insideSolid", insideSolid);
            if (insideSolid) {
                insideSolidCount++;
                info.addProperty("hint", "该机位落在实心方块内；渲染器已跳过相机所在方块，"
                        + "建议改到空气位置（read_state 未列出的格子即空气）");
            }
            cameraInfo.add(info);
        }

        JsonObject result = new JsonObject();
        result.addProperty("tool", "capture");
        result.addProperty("photos", batch.images.size());
        result.addProperty("reason", reason);
        result.addProperty("coordinateSystem",
                "相机位置与 lookAt 均为绝对世界坐标；yaw=0 朝 +Z、90 朝 -X，pitch 正值朝下，fov 单位度");
        result.add("cameras", cameraInfo);
        if (insideSolidCount > 0) {
            result.addProperty("warning", insideSolidCount
                    + " 台相机落在实心方块内，建议用 read_state 找空气位置后重拍");
        }
        String attach = external
                ? "照片已落盘到本机（路径见 captures / localReport），MCP 只回文本、不回传图片字节；"
                        + "环境能读本地文件时可直接打开 PNG 核对"
                : visionOk
                        ? "照片已作为图片附在紧接着的下一条消息里"
                        : "图片能力已关闭（modelVision=no）：照片已落盘但不发给模型，"
                                + "请用 read_state 与下面 localReport / cameras 文本核对建筑状态";
        result.addProperty("note", attach
                + "；照片只含本次建造生成的方块，天空渐变与绿色棋盘格草地是占位背景，"
                + "真实地形 / 植被 / 相邻建筑 / 实体 / 光照都没有渲染，不要据此判断周边环境");
        result.addProperty("localReport", batch.localReport);
        result.addProperty("totalBlocks", world.size());
        if (external) {
            // 拍照是「这一段做完了」的自然决策点，这里直接提醒提交，避免攒到最后忘掉
            result.addProperty("submitHint",
                    "造型确认无误就立刻调用 submit_build 把这一段写进世界，再继续下一段；"
                            + "不要等全部做完才提交");
        }
        outcome.result = Json.GSON.toJson(result);
        outcome.log = "模型拍照查看：" + batch.images.size() + " 张 / " + poses.size()
                + " 台机位（绝对坐标由模型指定），理由「" + reason + "」";
    }

    private void finish(JsonObject args, Outcome outcome) {
        if (captureCount == 0) {
            outcome.result = error("还没拍过照。请先调用 capture 看图确认造型、比例与遮挡，再调用 finish_build");
            return;
        }
        outcome.finish = true;
        outcome.summary = Json.str(args, "summary", "");
        outcome.title = Json.str(args, "title", "");
        JsonObject result = new JsonObject();
        result.addProperty("accepted", true);
        result.addProperty("totalBlocks", world.size());
        result.addProperty("captures", captureCount);
        result.addProperty("message", "建造完成，正在归档版本");
        outcome.result = Json.GSON.toJson(result);
        outcome.log = "模型宣布建造完成：" + outcome.summary;
    }

    // ------------------------------------------------------------------

    private String writeResult(String tool, VoxelWorld.WriteResult write) {
        return Json.GSON.toJson(writeResultObject(tool, write));
    }

    private JsonObject writeResultObject(String tool, VoxelWorld.WriteResult write) {
        JsonObject result = new JsonObject();
        result.addProperty("tool", tool);
        result.addProperty("placed", write.placed);
        result.addProperty("removed", write.removed);
        result.add("actualBounds", write.actualBounds());
        int total = write.rejections.size();
        result.addProperty("rejected", total);
        if (total > 0) {
            JsonArray shown = write.rejectionJson();
            if (shown.size() > 10) {
                JsonArray first = new JsonArray();
                for (int i = 0; i < 10; i++) {
                    first.add(shown.get(i));
                }
                result.add("rejectionSamples", first);
                result.addProperty("rejectionHint", "仅显示前 10 条，共 " + total + " 条");
            } else {
                result.add("rejectionSamples", shown);
            }
        }
        result.addProperty("totalBlocks", world.size());
        int[] occupied = world.occupiedBounds();
        result.add("occupiedBounds", occupied == null ? new JsonArray() : box(occupied));
        result.add("selection", box(bounds.toArray()));
        result.addProperty("coordinateSystem", "绝对世界整数坐标；越界 / 非法 id 会被拒绝并回报，不会静默改动");
        return result;
    }

    private static String error(String message) {
        JsonObject o = new JsonObject();
        o.addProperty("error", message);
        return Json.GSON.toJson(o);
    }

    private static int[] regionArg(JsonObject args) {
        JsonArray array = args.getAsJsonArray("region");
        if (array == null || array.size() != 6) {
            return null;
        }
        int[] box = new int[6];
        try {
            for (int i = 0; i < 6; i++) {
                box[i] = array.get(i).getAsInt();
            }
        } catch (Exception e) {
            return null;
        }
        return box;
    }

    private static JsonArray box(int[] values) {
        JsonArray array = new JsonArray();
        for (int value : values) {
            array.add(value);
        }
        return array;
    }
}
