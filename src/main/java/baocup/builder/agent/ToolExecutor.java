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
        // 空气墓碑不算方块：totalBlocks / materials / layers 都按真实方块统计
        result.addProperty("totalBlocks", world.realSize());
        int airMarkers = world.size() - world.realSize();
        if (airMarkers > 0) {
            result.addProperty("airMarkers", airMarkers);
        }
        JsonObject materials = new JsonObject();
        for (Map.Entry<String, Integer> e : world.materials().entrySet()) {
            materials.addProperty(e.getKey(), e.getValue());
        }
        result.add("materials", materials);

        if ("summary".equals(detail)) {
            // 全量分层表只在总览里给；region / full 查询不该被 76 层统计淹没
            result.add("layers", world.layerSummary());
            result.addProperty("detail", "summary");
            outcome.result = Json.GSON.toJson(result);
            outcome.log = "读取状态摘要：" + world.realSize() + " 格真实方块";
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
            voxels = new ArrayList<>(world.all());
        }
        List<VoxelWorld.Voxel> solid = new ArrayList<>(voxels.size());
        for (VoxelWorld.Voxel v : voxels) {
            if (!VoxelWorld.isAir(v)) {
                solid.add(v);
            }
        }
        if ("region".equals(detail)) {
            result.add("regionOccupied", occupiedBoxOf(solid));
        }
        boolean truncated = solid.size() > maxBlocks;
        result.addProperty("detail", detail);
        result.addProperty("returnedBlocks", Math.min(solid.size(), maxBlocks));
        result.addProperty("truncated", truncated);
        JsonArray blocks = new JsonArray();
        for (int i = 0; i < Math.min(solid.size(), maxBlocks); i++) {
            VoxelWorld.Voxel v = solid.get(i);
            JsonArray item = new JsonArray();
            item.add(v.x());
            item.add(v.y());
            item.add(v.z());
            item.add(v.block());
            blocks.add(item);
        }
        result.add("blocks", blocks);
        if (truncated) {
            result.addProperty("hint", "真实方块超过 " + maxBlocks
                    + " 格，已截断；请用 detail=region 分批查询，或先 detail=summary 用 layers 定位");
        }
        outcome.result = Json.GSON.toJson(result);
        outcome.log = "读取建筑状态：" + detail + "，返回 "
                + Math.min(solid.size(), maxBlocks) + " 格真实方块（空气已排除）";
    }

    /** 只算这一批非空气体素的局部包围盒，不返回全量分层表。 */
    private static JsonArray occupiedBoxOf(List<VoxelWorld.Voxel> solid) {
        if (solid.isEmpty()) {
            return new JsonArray();
        }
        int[] b = {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE,
                Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE};
        for (VoxelWorld.Voxel v : solid) {
            b[0] = Math.min(b[0], v.x());
            b[1] = Math.min(b[1], v.y());
            b[2] = Math.min(b[2], v.z());
            b[3] = Math.max(b[3], v.x());
            b[4] = Math.max(b[4], v.y());
            b[5] = Math.max(b[5], v.z());
        }
        return box(b);
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
        // 结构化字段：物理特性不再只混在描述文字里，生物群系着色的方块显式标注颜色不可信
        JsonArray details = new JsonArray();
        for (BlockIndex.BlockInfo info : palette) {
            JsonObject item = new JsonObject();
            item.addProperty("id", info.id);
            item.addProperty("name", info.name);
            item.addProperty("hex", info.hex);
            item.addProperty("category", info.category);
            String note = info.physics == null ? "" : info.physics;
            JsonObject physics = new JsonObject();
            physics.addProperty("gravity", note.contains("重力") || note.contains("掉落"));
            physics.addProperty("needsSupport", note.contains("需支撑") || note.contains("重力"));
            physics.addProperty("randomTick", note.contains("随机刻") || note.contains("融化")
                    || note.contains("蔓延") || note.contains("生长"));
            physics.addProperty("note", note);
            item.add("physics", physics);
            String id = info.id.toLowerCase(java.util.Locale.ROOT);
            boolean tinted = id.endsWith("_leaves") || id.equals("minecraft:grass_block")
                    || id.equals("minecraft:grass") || id.equals("minecraft:tall_grass")
                    || id.equals("minecraft:vine") || id.equals("minecraft:lily_pad")
                    || id.contains("mycelium");
            item.addProperty("biomeTinted", tinted);
            if (tinted) {
                item.addProperty("colorNote", "游戏内颜色随生物群系变化；hex 为贴图平均色，不代表实际观感");
            }
            details.add(item);
        }
        result.add("materialDetails", details);
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
        long evaluated = compiled.evaluated();
        double ratio = evaluated <= 0 ? 0.0 : (double) write.placed / evaluated;
        result.addProperty("hitRatio", Math.round(ratio * 10000) / 10000.0);
        if (evaluated > 100 && ratio < 0.05) {
            result.addProperty("hint", "命中率仅 " + (Math.round(ratio * 10000) / 100.0)
                    + "%：region 与 where 很可能不匹配，先用 read_state(detail=region) "
                    + "核对墙体/地面实际所在坐标，别按记忆里的区间继续画");
        }
        outcome.result = Json.GSON.toJson(result);
        outcome.log = "形状编译：" + compiled.note() + "；写入 " + write.placed
                + " 格 / 清除 " + write.removed + " 格";
    }

    private void removeBlocks(JsonObject args, Outcome outcome) throws ShapeLanguage.ShapeException {
        VoxelWorld.WriteResult write = new VoxelWorld.WriteResult();
        int cap = BuilderConfig.agentMaxBlocksPerCall();
        java.util.Set<String> filter = blockFilter(args);
        boolean hasFilter = !filter.isEmpty();
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
                    int x = item.get(0).getAsInt(), y = item.get(1).getAsInt(),
                            z = item.get(2).getAsInt();
                    if (matchesBlock(filter, world.get(x, y, z))) {
                        world.erase(write, x, y, z);
                    }
                } catch (Exception ignored) {
                    // 忽略坏条目
                }
            }
        }
        if (args.has("region")) {
            boolean hasCondition = args.has("where") || args.has("shape") || hasFilter;
            boolean all = args.has("all") && args.get("all").getAsBoolean();
            if (!hasCondition && !all) {
                outcome.result = error("为避免误删，remove_blocks 的 region 必须配 where / shape / block；"
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
            if (!args.has("where") && !args.has("shape")) {
                // 只有类型过滤时全选该 region，再按方块 id 筛
                spec.addProperty("where", "1");
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
                if (matchesBlock(filter, world.get(cell[0], cell[1], cell[2]))) {
                    world.erase(write, cell[0], cell[1], cell[2]);
                }
            }
        } else if (hasFilter) {
            // 不给 region 时按类型删整个本地世界
            int used = 0;
            for (VoxelWorld.Voxel v : new ArrayList<>(world.all())) {
                if (used++ >= cap) {
                    write.rejections.add(new VoxelWorld.Rejection(0, 0, 0,
                            "单次删除超过 " + cap + " 格上限，其余已忽略"));
                    break;
                }
                if (matchesBlock(filter, v)) {
                    world.erase(write, v.x(), v.y(), v.z());
                }
            }
        }
        if (blocks == null && !args.has("region") && !hasFilter) {
            outcome.result = error("remove_blocks 需要 blocks / region / block 之一");
            return;
        }
        outcome.result = writeResult("remove_blocks", write);
        outcome.log = "删除方块：" + write.removed + " 格"
                + (hasFilter ? "（按类型 " + String.join("、", filter) + "）" : "")
                + (write.rejections.isEmpty() ? "" : "，被拒 " + write.rejections.size() + " 条");
    }

    /** block 可以是单个 id 字符串，也可以是 blockIds 数组；统一补上命名空间。 */
    private static java.util.Set<String> blockFilter(JsonObject args) {
        java.util.Set<String> ids = new java.util.HashSet<>();
        JsonElement value = args.get("block");
        if (value == null) {
            value = args.get("blockIds");
        }
        if (value == null) {
            return ids;
        }
        if (value.isJsonPrimitive()) {
            ids.add(normalizeBlockId(value.getAsString()));
        } else if (value.isJsonArray()) {
            for (JsonElement element : value.getAsJsonArray()) {
                ids.add(normalizeBlockId(element.getAsString()));
            }
        }
        return ids;
    }

    private static String normalizeBlockId(String id) {
        String text = id.trim().toLowerCase(java.util.Locale.ROOT);
        return text.contains(":") ? text : "minecraft:" + text;
    }

    private static boolean matchesBlock(java.util.Set<String> filter, VoxelWorld.Voxel v) {
        if (filter.isEmpty()) {
            return true;
        }
        return v != null && filter.contains(normalizeBlockId(v.block()));
    }

    // ------------------------------------------------------------------
    // 拍照与结束
    // ------------------------------------------------------------------

    private void capture(JsonObject args, Outcome outcome) {
        String view = Json.str(args, "view", "around");
        String reason = Json.str(args, "reason", "");
        boolean orthoSet = args.has("orthographic");
        boolean ortho = orthoSet && args.get("orthographic").getAsBoolean();
        double orthoWidth = Math.max(0, Json.dbl(args, "orthoWidth", 0));
        boolean groundGrid = !args.has("groundGrid") || args.get("groundGrid").getAsBoolean();
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
                    pose.orthographic = o.has("orthographic")
                            ? o.get("orthographic").getAsBoolean() : ortho;
                    pose.orthoWidth = Json.dbl(o, "orthoWidth", orthoWidth);
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
            if ("plan".equals(view)) {
                // 正投影俯视：塔楼顶部不会按透视向外发散，适合核对街区位置
                double cx = (bounds.minX() + bounds.maxX()) / 2.0;
                double cz = (bounds.minZ() + bounds.maxZ()) / 2.0;
                double top = bounds.maxY() + Math.max(24.0,
                        Math.max(bounds.sizeX(), bounds.sizeZ()) * 0.9);
                CameraPose plan = new CameraPose("plan", "正投影俯视", cx, top, cz,
                        0.0F, 90.0F, reason);
                plan.orthographic = true;
                plan.orthoWidth = Math.max(bounds.sizeX(), bounds.sizeZ()) * 1.15;
                poses.add(plan);
            } else {
                List<CameraPose> all = CameraRig.around(bounds, Math.max(4, count));
                if ("top".equals(view)) {
                    CameraPose top = all.get(all.size() - 2);
                    top.orthographic = true;
                    poses.add(top);
                } else if ("eye".equals(view)) {
                    poses.add(all.get(all.size() - 1));
                } else {
                    poses.addAll(CameraRig.representative(bounds, Math.min(count, all.size())));
                }
            }
            while (poses.size() > maxCameras) {
                poses.remove(poses.size() - 1);
            }
        }
        captureIndex++;
        String prefix = "v" + (version + 1) + "-agent" + captureIndex;
        // 机位卡在实心方块里会渲染出 100% 实心的废图：先向上外移找空气，找不到就丢弃
        List<CameraPose> usable = new ArrayList<>();
        int relocatedCameras = 0;
        int droppedCameras = 0;
        for (CameraPose pose : poses) {
            int px = (int) Math.floor(pose.x);
            int py = (int) Math.floor(pose.y);
            int pz = (int) Math.floor(pose.z);
            if (world.get(px, py, pz) == null) {
                usable.add(pose);
                continue;
            }
            boolean moved = false;
            for (int up = 1; up <= 8; up++) {
                if (world.get(px, py + up, pz) == null) {
                    pose.y = py + up + 0.5;
                    usable.add(pose);
                    relocatedCameras++;
                    moved = true;
                    break;
                }
            }
            if (!moved) {
                droppedCameras++;
            }
        }
        poses = usable;
        for (CameraPose pose : poses) {
            pose.groundGrid = groundGrid;
            if (orthoSet) {
                pose.orthographic = ortho;
            }
            if (orthoWidth > 0) {
                pose.orthoWidth = orthoWidth;
            }
        }
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
        result.addProperty("relocatedCameras", relocatedCameras);
        result.addProperty("droppedCameras", droppedCameras);
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
        result.addProperty("totalBlocks", world.realSize());
        // 照片落盘位置：MCP 不回传图片字节，必须给可直接读取的绝对路径
        result.addProperty("captureDir", captureDir.toAbsolutePath().toString());
        JsonArray photoPaths = new JsonArray();
        for (BuildMemory.CaptureLine line : batch.lines) {
            if (line != null && line.path != null && !line.path.isBlank()) {
                try {
                    photoPaths.add(Path.of(line.path).toAbsolutePath().toString());
                } catch (Exception ignored) {
                    photoPaths.add(line.path);
                }
            }
        }
        result.add("photoPaths", photoPaths);
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
        result.addProperty("totalBlocks", world.realSize());
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
        result.addProperty("totalBlocks", world.realSize());
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
