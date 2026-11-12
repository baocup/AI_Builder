package baocup.builder.client;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import baocup.builder.BuilderConfig;
import baocup.builder.BuilderMod;
import baocup.builder.agent.AgentTools;
import baocup.builder.agent.LlmClient;
import baocup.builder.agent.McpServer;
import baocup.builder.agent.ToolExecutor;
import baocup.builder.agent.VoxelWorld;
import baocup.builder.item.ExternalBuildWandItem;
import baocup.builder.item.ItemData;
import baocup.builder.net.ModPackets;
import baocup.builder.util.Bounds;
import baocup.builder.util.Json;
import net.minecraft.client.Minecraft;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * 客户端本地建造宿主：外部 Agent 的 MCP 接口、几何编译、VoxelWorld 与拍照渲染都在这里，
 * 服务端只接收最终方块（{@link ModPackets.C2SCommit}）。
 *
 * 这样每个玩家 / 每个客户端都有自己独立的端口与体素世界，多 Agent 并行不会互相覆盖，
 * 也不会占用服务端主线程去渲染图片。
 */
public final class ClientBuildHost {
    public static final ClientBuildHost INSTANCE = new ClientBuildHost();
    /** 单包最多多少格方块，留足余量给 1 MiB 协议上限。 */
    private static final int BATCH_BLOCKS = 4096;
    /** 只在本机做几何 / 渲染，单线程即可，避免和客户端渲染线程抢资源。 */
    private static final ExecutorService POOL = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "builder-client-host");
        thread.setDaemon(true);
        return thread;
    });

    private record HeldItem(String taskId, Bounds bounds, String dimension) {
    }

    private final Map<String, Session> sessions = new LinkedHashMap<>();
    private final Queue<Runnable> mainQueue = new ConcurrentLinkedQueue<>();
    /** 动作日志按 taskId 分组：不同手杖对应不同任务，日志不能互相串。 */
    private final Map<String, ConcurrentLinkedDeque<ClientState.LogItem>> logsByTask =
            new ConcurrentHashMap<>();
    private McpServer server;
    private int callSeq;
    /** 外部 Agent 请求设定范围后，等服务端把坐标写回物品 NBT 再回执。 */
    private PendingBounds pendingBounds;

    private ClientBuildHost() {
    }

    public void init() {
        if (!BuilderConfig.mcpEnabled()) {
            BuilderMod.LOGGER.info("[建造] 客户端 MCP 已关闭（mcpEnabled=false）");
            return;
        }
        server = new McpServer(BuilderConfig.mcpToken(), this::tools, this::callTool);
        try {
            server.start(BuilderConfig.mcpHost(), BuilderConfig.mcpPort());
            BuilderMod.LOGGER.info("[建造] 本地 MCP 已监听 http://{}:{}/mcp"
                            + "（客户端建模与渲染，只向服务端提交方块）",
                    BuilderConfig.mcpHost(), BuilderConfig.mcpPort());
        } catch (Exception e) {
            BuilderMod.LOGGER.warn("[建造] 本地 MCP 启动失败（{}:{} 可能被占用，改 mcpPort）：{}",
                    BuilderConfig.mcpHost(), BuilderConfig.mcpPort(), e.toString());
            server = null;
        }
    }

    public void shutdown() {
        if (server != null) {
            server.stop();
            server = null;
        }
        sessions.clear();
        mainQueue.clear();
        logsByTask.clear();
    }

    /** 客户端每 tick 驱动：主线程队列 + 日志裁剪。 */
    public void tick() {
        Runnable task;
        int guard = 0;
        while ((task = mainQueue.poll()) != null && guard++ < 64) {
            try {
                task.run();
            } catch (Exception e) {
                BuilderMod.LOGGER.warn("[建造] 客户端 MCP 任务异常：{}", e.toString());
            }
        }
        checkPendingBounds();
    }

    /** 服务端应用坐标后会同步物品 NBT；每 tick 检查一次，成功就立刻回执给 Agent。 */
    private void checkPendingBounds() {
        PendingBounds pending = pendingBounds;
        if (pending == null) {
            return;
        }
        if (--pending.remainingTicks <= 0) {
            pendingBounds = null;
            JsonObject out = new JsonObject();
            out.addProperty("status", "pending");
            out.add("requestedBounds", Json.GSON.toJsonTree(pending.target));
            out.addProperty("hint", "已提交给服务端，但没等到物品同步；"
                    + "稍后用 get_context 复查 bounds 是否生效，生效前不要按该范围建模");
            pending.future.complete(out);
            return;
        }
        HeldItem held = held();
        if (held == null) {
            return;
        }
        if (!pending.taskId.equals(held.taskId())) {
            // 手杖被切换：不能拿别的任务的范围当成本任务的确认，直接回 pending 让 Agent 复查
            pendingBounds = null;
            JsonObject out = new JsonObject();
            out.addProperty("status", "pending");
            out.add("requestedBounds", Json.GSON.toJsonTree(pending.target));
            out.addProperty("hint", "手杖已切换到其它任务，无法在本地确认范围；"
                    + "切回该任务的手杖后用 get_context 复查 bounds");
            pending.future.complete(out);
            return;
        }
        if (held.bounds() == null) {
            return;
        }
        if (!sameBounds(held.bounds().toArray(), pending.target)) {
            return;
        }
        pendingBounds = null;
        sessions.remove(pending.taskId);
        JsonObject out = new JsonObject();
        out.addProperty("approved", true);
        out.add("bounds", Json.GSON.toJsonTree(pending.target));
        out.addProperty("volume", Bounds.fromArray(pending.target).volume());
        out.addProperty("note", "范围已生效，本地 VoxelWorld 已重置为新范围");
        pending.future.complete(out);
        logLine(pending.taskId, "范围已生效 " + describe(pending.target)
                + (pending.reason.isBlank() ? "" : "：" + pending.reason));
    }

    /** 只返回该任务自己的动作日志，避免不同 taskId 的日志串在一起。 */
    public List<ClientState.LogItem> snapshotLogs(String taskId) {
        if (taskId == null || taskId.isBlank()) {
            return new ArrayList<>();
        }
        ConcurrentLinkedDeque<ClientState.LogItem> queue = logsByTask.get(taskId);
        return queue == null ? new ArrayList<>() : new ArrayList<>(queue);
    }

    private void logLine(String taskId, String text) {
        if (taskId == null || taskId.isBlank()) {
            BuilderMod.LOGGER.info("[建造][本地 MCP] {}", text);
            return;
        }
        ClientState.LogItem item = new ClientState.LogItem();
        item.ts = System.currentTimeMillis();
        item.stage = "外部 Agent";
        item.level = "INFO";
        item.text = text;
        ConcurrentLinkedDeque<ClientState.LogItem> queue =
                logsByTask.computeIfAbsent(taskId, id -> new ConcurrentLinkedDeque<>());
        queue.addLast(item);
        while (queue.size() > 400) {
            queue.pollFirst();
        }
        BuilderMod.LOGGER.info("[建造][本地 MCP][{}] {}", shortId(taskId), text);
    }

    private static String shortId(String taskId) {
        return taskId.length() <= 8 ? taskId : taskId.substring(0, 8);
    }

    // ------------------------------------------------------------------
    // MCP 工具
    // ------------------------------------------------------------------

    private JsonArray tools() {
        JsonArray array = new JsonArray();
        for (JsonElement element : AgentTools.definitions(true)) {
            JsonObject function = element.getAsJsonObject().getAsJsonObject("function");
            String name = Json.str(function, "name", "");
            if (AgentTools.FINISH_BUILD.equals(name)) {
                continue;
            }
            JsonObject tool = new JsonObject();
            tool.addProperty("name", name);
            tool.addProperty("description", Json.str(function, "description", ""));
            tool.add("inputSchema", function.getAsJsonObject("parameters"));
            array.add(tool);
        }
        array.add(tool("get_context",
                "读取当前手持「外部建造手杖」绑定的任务 id 与选区，以及本地建模状态。"
                        + "开始建造前先调它；返回 held=false 说明没拿手杖。",
                "{\"type\":\"object\",\"properties\":{}}"));
        array.add(tool("request_bounds",
                "自行设定建造范围（绝对世界坐标）。与玩家在 GUI 里手动输入坐标等效："
                        + "会提交给服务端写回物品并刷新选区，成功返回 approved=true 与生效的 bounds；"
                        + "返回 status=pending 说明还没同步完，稍后用 get_context 复查。"
                        + "范围一旦大于玩家原本的选区，建筑会被截断，所以动工前先确认。",
                """
                        {"type":"object","properties":{
                          "bounds":{"type":"array","items":{"type":"integer"},
                            "description":"[x1,y1,z1,x2,y2,z2] 六个绝对坐标"},
                          "reason":{"type":"string","description":"为什么需要这个范围，仅写进日志"}
                        },"required":["bounds"]}"""));
        array.add(tool("submit_build",
                "把本地建模结果提交给服务器写入世界。几何编译与拍照渲染都已在客户端完成，这一步只发送方块。"
                        + "**每完成一个可独立成立的建造部分就调用一次**（地基 / 墙体 / 屋顶 / 室内），"
                        + "不要攒到最后：工具轮用尽或对话中断时本地体素会直接丢失，世界一格都没有。"
                        + "服务端是覆盖写入，重复提交安全，提交后本地世界保留可继续加建。",
                """
                        {"type":"object","properties":{
                          "summary":{"type":"string","description":"本轮建造说明"}
                        }}"""));
        return array;
    }

    private static JsonObject tool(String name, String description, String schema) {
        JsonObject tool = new JsonObject();
        tool.addProperty("name", name);
        tool.addProperty("description", description);
        JsonObject parsed = Json.GSON.fromJson(schema, JsonObject.class);
        tool.add("inputSchema", parsed == null ? new JsonObject() : parsed);
        return tool;
    }

    private JsonObject callTool(String name, JsonObject args) {
        CompletableFuture<JsonObject> future = new CompletableFuture<>();
        mainQueue.add(() -> dispatch(name, args, future));
        try {
            return future.get(Math.max(30, BuilderConfig.mcpToolTimeoutSeconds()), TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            return error("工具 " + name + " 超时（客户端是否卡住 / 是否在单人存档中？）");
        } catch (Exception e) {
            return error("工具 " + name + " 失败：" + e);
        }
    }

    private void dispatch(String name, JsonObject args, CompletableFuture<JsonObject> future) {
        try {
            switch (name) {
                case "get_context" -> future.complete(context());
                case "request_bounds" -> requestBounds(args, future);
                case "submit_build" -> submitBuild(args, future);
                default -> submitTool(name, args, future);
            }
        } catch (Exception e) {
            future.complete(error(e.getMessage() == null ? e.toString() : e.getMessage()));
        }
    }

    private JsonObject context() {
        HeldItem held = held();
        JsonObject out = new JsonObject();
        if (held == null) {
            out.addProperty("held", false);
            out.addProperty("hint", "让玩家手持「外部建造手杖」后再调用；"
                    + "选区可由玩家左键两次、GUI 手动输入，或由你调用 request_bounds 设定");
            return out;
        }
        out.addProperty("held", true);
        out.addProperty("taskId", held.taskId() == null ? "" : held.taskId());
        if (held.bounds() != null) {
            out.add("bounds", Json.GSON.toJsonTree(held.bounds().toArray()));
            out.addProperty("volume", held.bounds().volume());
        } else {
            out.addProperty("hint", "还没有选区：可让玩家左键两次 / GUI 手动输入，"
                    + "或调用 request_bounds 由你设定");
        }
        Session session = held.taskId() == null ? null : sessions.get(held.taskId());
        out.addProperty("localBlocks", session == null ? 0 : session.world.size());
        out.addProperty("localCaptures", session == null ? 0 : session.captures.size());
        out.addProperty("note", "几何编译与渲染都在本地；submit_build 只把方块发给服务器");
        return out;
    }

    private void submitTool(String name, JsonObject args, CompletableFuture<JsonObject> future) {
        HeldItem held = held();
        if (held == null || held.taskId() == null) {
            future.complete(error("请让玩家手持「外部建造手杖」并选好范围"));
            return;
        }
        if (held.bounds() == null) {
            future.complete(error("物品还没有选区：可调用 request_bounds 自行设定，"
                    + "或让玩家左键两次 / 在 GUI 里手动输入坐标"));
            return;
        }
        Session session = sessions.computeIfAbsent(held.taskId(), id -> new Session(id, held.bounds()));
        POOL.submit(() -> {
            try {
                ToolExecutor.Outcome outcome = session.executor.execute(
                        new LlmClient.ToolCall("local_" + (++callSeq), name, Json.GSON.toJson(args)));
                session.collect(outcome);
                JsonElement parsed;
                try {
                    parsed = Json.GSON.fromJson(outcome.result, JsonElement.class);
                } catch (Exception e) {
                    parsed = Json.GSON.toJsonTree(outcome.result);
                }
                JsonObject out = new JsonObject();
                out.add("result", parsed == null ? new JsonObject() : parsed);
                // 每轮回执都提醒：建模只在客户端，必须显式提交才会进世界
                out.addProperty("localOnly", true);
                out.addProperty("submitHint",
                        "以上改动只在客户端本地（拍得到、世界看不到）；这一段确认后就立刻 submit_build，"
                                + "不要攒到最后一次性提交");
                if (!session.submitted && session.world.size() >= 1500) {
                    out.addProperty("warning", "本地已累计 " + session.world.size()
                            + " 格且一次都没提交过：立刻调用 submit_build 把这段落进世界，"
                            + "否则一旦中断世界会一格都没有");
                }
                future.complete(out);
                logLine(held.taskId(), name + " 完成，本地累计 " + session.world.size()
                        + " 格（尚未写入世界）");
            } catch (Exception e) {
                future.complete(error(name + " 执行异常：" + e));
            }
        });
    }

    /**
     * 外部 Agent 自主设定选区：走与 GUI「应用坐标」完全相同的服务端通道
     * （{@code C2SAction("bounds")}），由服务端改任务记忆并把坐标写回物品 NBT，
     * 客户端在 tick 里等到同步完成再回执。
     */
    private void requestBounds(JsonObject args, CompletableFuture<JsonObject> future) {
        HeldItem held = held();
        if (held == null || held.taskId() == null) {
            future.complete(error("请让玩家手持「外部建造手杖」后再设定范围"));
            return;
        }
        if (pendingBounds != null) {
            future.complete(error("任务 " + shortId(pendingBounds.taskId)
                    + " 的范围请求还在等服务端确认，稍后再试"));
            return;
        }
        JsonArray box = args.getAsJsonArray("bounds");
        if (box == null || box.size() != 6) {
            future.complete(error("bounds 必须是 [x1,y1,z1,x2,y2,z2] 六个整数"));
            return;
        }
        int[] values = new int[6];
        try {
            for (int i = 0; i < 6; i++) {
                values[i] = box.get(i).getAsInt();
            }
        } catch (Exception e) {
            future.complete(error("bounds 必须是整数"));
            return;
        }
        Bounds bounds = Bounds.fromArray(values);
        bounds.normalize();
        int[] target = bounds.toArray();
        String reason = Json.str(args, "reason", "").trim();
        JsonObject payload = new JsonObject();
        payload.add("bounds", Json.GSON.toJsonTree(target));
        PacketDistributor.sendToServer(new ModPackets.C2SAction(
                held.taskId(), "bounds", Json.GSON.toJson(payload)));
        // 5 秒（100 tick）足够服务端处理 + 物品同步；超时只回 pending，请求本身仍然生效
        pendingBounds = new PendingBounds(future, target, held.taskId(), reason, 100);
        sessions.remove(held.taskId());
        logLine(held.taskId(), "提交范围 " + describe(target) + "（" + bounds.volume() + " 格）"
                + (reason.isBlank() ? "" : "：" + reason));
    }

    /** 分批发包：每包最多 BATCH_BLOCKS 格，最后一批带 last=true。 */
    private void submitBuild(JsonObject args, CompletableFuture<JsonObject> future) {
        HeldItem held = held();
        if (held == null || held.taskId() == null) {
            future.complete(error("请让玩家手持「外部建造手杖」并选好坐标"));
            return;
        }
        Session session = sessions.get(held.taskId());
        if (session == null || session.world.size() == 0) {
            future.complete(error("本地还没有任何方块；先用 draw_shape / place_blocks 建模"));
            return;
        }
        List<VoxelWorld.Voxel> voxels = new ArrayList<>(session.world.all());
        int total = voxels.size();
        String dimension = dimension();
        String summary = Json.str(args, "summary", "");
        int sent = 0;
        int batches = 0;
        int payloadBytes = 0;
        for (int start = 0; start < total; start += BATCH_BLOCKS) {
            int end = Math.min(total, start + BATCH_BLOCKS);
            JsonObject payload = new JsonObject();
            if (start == 0) {
                if (held.bounds() != null) {
                    payload.add("bounds", Json.GSON.toJsonTree(held.bounds().toArray()));
                }
                if (!summary.isBlank()) {
                    payload.addProperty("summary", summary);
                }
            }
            JsonArray blocks = new JsonArray();
            for (int i = start; i < end; i++) {
                VoxelWorld.Voxel voxel = voxels.get(i);
                JsonArray one = new JsonArray();
                one.add(voxel.x());
                one.add(voxel.y());
                one.add(voxel.z());
                one.add(voxel.fullState());
                blocks.add(one);
            }
            payload.add("blocks", blocks);
            String json = Json.GSON.toJson(payload);
            payloadBytes += json.length();
            batches++;
            PacketDistributor.sendToServer(new ModPackets.C2SCommit(
                    held.taskId(), dimension, json, end >= total));
            sent += end - start;
        }
        // 不清空本地世界：服务端是覆盖写入，重复提交同一批是安全的；
        // 万一服务端写入失败，Agent 还能原样重试，而不是内容已经丢了。
        session.submitted = true;
        logLine(held.taskId(), "已提交 " + sent + " 格方块给服务器（" + batches + " 包，约 "
                + Math.max(1, payloadBytes / 1024) + " KB）"
                + (summary.isBlank() ? "" : "：" + summary));
        JsonObject out = new JsonObject();
        out.addProperty("submitted", sent);
        out.addProperty("batches", batches);
        out.addProperty("payloadKb", Math.max(1, payloadBytes / 1024));
        out.addProperty("note", "服务端按 blocksPerTick 分批覆盖写入并归档；"
                + "本地世界已保留，可安全重复提交，也可继续在其上加建");
        future.complete(out);
    }

    // ------------------------------------------------------------------
    // 手持物品与本地区块世界
    // ------------------------------------------------------------------

    private HeldItem held() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            return null;
        }
        for (InteractionHand hand : InteractionHand.values()) {
            ItemStack stack = mc.player.getItemInHand(hand);
            if (!(stack.getItem() instanceof ExternalBuildWandItem)) {
                continue;
            }
            CompoundTag tag = ItemData.read(stack);
            var p1 = ItemData.point(tag, ItemData.P1);
            var p2 = ItemData.point(tag, ItemData.P2);
            Bounds bounds = p1 != null && p2 != null ? Bounds.of(p1, p2) : null;
            return new HeldItem(ItemData.taskId(stack), bounds, dimension());
        }
        return null;
    }

    private static String dimension() {
        Minecraft mc = Minecraft.getInstance();
        return mc.level == null ? "minecraft:overworld"
                : mc.level.dimension().location().toString();
    }

    private Path captureDir(String taskId) {
        Minecraft mc = Minecraft.getInstance();
        // 用完整 taskId 做目录：只取前 8 位理论上可能碰撞，会让两个任务共用照片目录
        return mc.gameDirectory.toPath().resolve("builder-captures").resolve(taskId);
    }

    private static boolean sameBounds(int[] a, int[] b) {
        if (a == null || b == null || a.length < 6 || b.length < 6) {
            return false;
        }
        for (int i = 0; i < 6; i++) {
            if (a[i] != b[i]) {
                return false;
            }
        }
        return true;
    }

    private static String describe(int[] b) {
        if (b == null || b.length < 6) {
            return "—";
        }
        return "[" + b[0] + "," + b[1] + "," + b[2] + " → " + b[3] + "," + b[4] + "," + b[5] + "]";
    }

    /** 等服务端把新范围写回物品后回执；remainingTicks 用完就只回 pending。 */
    private static final class PendingBounds {
        final CompletableFuture<JsonObject> future;
        final int[] target;
        final String taskId;
        final String reason;
        int remainingTicks;

        PendingBounds(CompletableFuture<JsonObject> future, int[] target, String taskId,
                      String reason, int remainingTicks) {
            this.future = future;
            this.target = target;
            this.taskId = taskId;
            this.reason = reason;
            this.remainingTicks = remainingTicks;
        }
    }

    private static JsonObject error(String message) {
        JsonObject out = new JsonObject();
        out.addProperty("error", message == null ? "未知错误" : message);
        out.addProperty("isError", true);
        return out;
    }

    /** 一个任务的本地体素世界；提交后整个丢弃，下一轮从空开始（服务端是增量的）。 */
    private final class Session {
        final String taskId;
        final Bounds bounds;
        final VoxelWorld world;
        final ToolExecutor executor;
        final List<String> captures = new ArrayList<>();
        /** 是否已经提交过一次，用于在「攒太多还没提交」时主动警告。 */
        boolean submitted;

        Session(String taskId, Bounds bounds) {
            this.taskId = taskId;
            this.bounds = bounds;
            this.world = new VoxelWorld(bounds);
            this.executor = new ToolExecutor(taskId, bounds, world, captureDir(taskId), 0, true);
        }

        void collect(ToolExecutor.Outcome outcome) {
            for (var line : outcome.captures) {
                if (line != null && line.path != null && !line.path.isBlank()) {
                    captures.add(line.path);
                }
            }
        }
    }
}
