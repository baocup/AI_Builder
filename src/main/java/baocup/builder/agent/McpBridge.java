package baocup.builder.agent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
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
import baocup.builder.item.ExternalBuildWandItem;
import baocup.builder.item.ItemData;
import baocup.builder.memory.BuildMemory;
import baocup.builder.util.Bounds;
import baocup.builder.util.Json;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

/**
 * MCP 建造桥：外部 Agent 通过 MCP 的 {@code tools/call} 直接驱动建造，不再轮询任何文件。
 *
 * 所有决策与坐标都由外部 Agent 给出；游戏只做几何编译、坐标校验、逐 tick 写世界与归档。
 * 本类不引用任何 LlmClient 请求方法，因此整条链路 0 次模型调用。
 *
 * 坐标纪律：修改建造范围必须先调用 {@code request_bounds}，由玩家在游戏内
 * {@code /builder approve} 批准后才会生效；体积过大时玩家会看到实际格数自行决定。
 */
public final class McpBridge {
    private static final ExecutorService TOOL_POOL = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "builder-mcp-tools");
        thread.setDaemon(true);
        return thread;
    });

    private record Change(int x, int y, int z, String fullState) {
    }

    /** 待玩家批准的坐标请求；批准 / 拒绝 / 超时后完成 future。 */
    private static final class BoundsRequest {
        final BuildMemory memory;
        final Bounds requested;
        final String reason;
        final CompletableFuture<JsonObject> future;
        int remainingTicks;
        /** 先返回 status=pending 的倒计时，避免 MCP 客户端自己超时；请求本身继续等玩家。 */
        int waitTicks;

        BoundsRequest(BuildMemory memory, Bounds requested, String reason,
                      CompletableFuture<JsonObject> future, int remainingTicks, int waitTicks) {
            this.memory = memory;
            this.requested = requested;
            this.reason = reason;
            this.future = future;
            this.remainingTicks = remainingTicks;
            this.waitTicks = waitTicks;
        }

        String describe() {
            int[] b = requested.toArray();
            return "[" + b[0] + "," + b[1] + "," + b[2] + " → " + b[3] + "," + b[4] + "," + b[5] + "]";
        }
    }

    private final AgentManager manager;
    private final Map<String, Session> sessions = new LinkedHashMap<>();
    /** MCP 的 HTTP 线程把请求投递到这里，由服务端主线程执行（记忆 / 会话都不是线程安全的）。 */
    private final Queue<Runnable> mainQueue = new ConcurrentLinkedQueue<>();
    /** 每个任务各自挂一个待批准请求，多个 Agent 并行时互不阻塞。 */
    private final Map<String, BoundsRequest> boundsRequests = new LinkedHashMap<>();
    private McpServer server;
    private int callSeq;
    private long tickCounter;

    public McpBridge(AgentManager manager) {
        this.manager = manager;
    }

    public void reset() {
        mainQueue.clear();
        sessions.clear();
        boundsRequests.clear();
        callSeq = 0;
        tickCounter = 0;
    }

    public void init() {
        reset();
        if (!BuilderConfig.mcpEnabled()) {
            BuilderMod.LOGGER.info("[建造] MCP 已关闭（mcpEnabled=false）");
            return;
        }
        server = new McpServer(BuilderConfig.mcpToken(), this::tools, this::callTool);
        try {
            server.start(BuilderConfig.mcpHost(), BuilderConfig.mcpPort());
        } catch (Exception e) {
            BuilderMod.LOGGER.warn("[建造] MCP 启动失败（{}:{} 可能被占用）：{}",
                    BuilderConfig.mcpHost(), BuilderConfig.mcpPort(), e.toString());
            server = null;
        }
    }

    public void shutdown() {
        if (server != null) {
            server.stop();
            server = null;
        }
        reset();
    }

    public boolean isActive(String taskId) {
        return sessions.containsKey(taskId);
    }

    // ------------------------------------------------------------------
    // MCP 工具定义
    // ------------------------------------------------------------------

    private JsonArray tools() {
        JsonArray array = new JsonArray();
        for (JsonElement element : AgentTools.definitions(true)) {
            JsonObject function = element.getAsJsonObject().getAsJsonObject("function");
            String name = Json.str(function, "name", "");
            // finish_build 换成 MCP 版：归档由本类负责，不再强制"模型必须先看图"
            if (AgentTools.FINISH_BUILD.equals(name)) {
                continue;
            }
            array.add(mcpTool(name, Json.str(function, "description", ""),
                    function.getAsJsonObject("parameters")));
        }
        array.add(mcpTool("get_held_task",
                "读取当前手持「外部建造手杖」的玩家所绑定的任务 id 与选区。"
                        + "玩家选好坐标并保持手持时调用它，就能拿到要建造的 taskId 与 bounds。"
                        + "返回 held=false 说明没有玩家手持该物品；这套流程只用于任务开始前的任务 id 确认。",
                schema("""
                        {"type":"object","properties":{
                          "playerName":{"type":"string","description":"可选；指定玩家名，留空取第一个手持该物品的玩家"}
                        }}""")));
        array.add(mcpTool("list_tasks",
                "列出所有可用建造任务（taskId、选区、版本、已建格数、是否有活动会话）。"
                        + "外部 Agent 启动时先调它确认要建造哪个 taskId。",
                schema("""
                        {"type":"object","properties":{
                          "onlySelectable":{"type":"boolean","description":"只列有选区的任务，默认 true"}
                        }}""")));
        array.add(mcpTool("get_context",
                "读取某个任务的当前状态：选区范围、体积、版本号、已建格数、有没有活动会话与未写入差量。"
                        + "开始建造前先调它，不要凭记忆推测坐标。",
                schema("""
                        {"type":"object","properties":{
                          "taskId":{"type":"string","description":"留空取最近有选区的任务"}
                        }}""")));
        array.add(mcpTool("request_bounds",
                "请求设定或扩大建造范围。**必须由玩家在游戏内输入 /builder approve 批准后才会生效**，"
                        + "工具会挂起直到玩家批准、拒绝或超时（默认 45 秒后先返回 status=pending，请求仍有效）。"
                        + "体积不设上限，多大都可以请求；但选区不能与其它并行任务的选区重叠。"
                        + "建筑尺寸超出当前范围时必须先调用它，并向玩家说明用途；"
                        + "不要在未批准的情况下按超大坐标建模。"
                        + "返回 approved=true 与最终 bounds，approved=false 时按返回的实际范围缩小设计。",
                schema("""
                        {"type":"object","properties":{
                          "taskId":{"type":"string","description":"留空取最近有选区的任务"},
                          "bounds":{"type":"array","items":{"type":"integer"},"minItems":6,"maxItems":6,
                            "description":"[x1,y1,z1,x2,y2,z2] 绝对世界坐标，期望的新范围"},
                          "reason":{"type":"string","description":"为什么需要这个范围，会显示给玩家"}
                        },"required":["bounds"]}""")));
        array.add(mcpTool("finish_build",
                "结束本轮外部 Agent 建造：把 VoxelWorld 完整归档成新版本，返回版本号、总格数与照片路径。"
                        + "调用前建议先 capture 一次核对造型。"
                        + "同时存在多个并行会话时必须显式传 taskId，避免归档到别的任务。",
                schema("""
                        {"type":"object","properties":{
                          "taskId":{"type":"string","description":"留空取当前活动会话"},
                          "summary":{"type":"string","description":"成果说明，写入任务记忆"},
                          "title":{"type":"string","description":"建筑名"}
                        }}""")));
        return array;
    }

    private static JsonObject mcpTool(String name, String description, JsonObject inputSchema) {
        JsonObject tool = new JsonObject();
        tool.addProperty("name", name);
        tool.addProperty("description", description);
        if (inputSchema == null) {
            inputSchema = schema("{\"type\":\"object\",\"properties\":{}}");
        }
        tool.add("inputSchema", inputSchema);
        return tool;
    }

    private static JsonObject schema(String json) {
        JsonObject parsed = Json.GSON.fromJson(json, JsonObject.class);
        return parsed == null ? new JsonObject() : parsed;
    }

    // ------------------------------------------------------------------
    // 主线程调度
    // ------------------------------------------------------------------

    public void tick(MinecraftServer server) {
        if (server == null) {
            return;
        }
        tickCounter++;
        Runnable task;
        int guard = 0;
        while ((task = mainQueue.poll()) != null && guard++ < 128) {
            try {
                task.run();
            } catch (Exception e) {
                BuilderMod.LOGGER.warn("[建造] MCP 主线程任务异常：{}", e.toString());
            }
        }
        tickBoundsRequest();
        Iterator<Map.Entry<String, Session>> iterator = sessions.entrySet().iterator();
        while (iterator.hasNext()) {
            Session session = iterator.next().getValue();
            session.tick(server);
            if (session.finished) {
                iterator.remove();
            }
        }
    }

    /** MCP 的 HTTP 线程调用：投递到主线程，同步等结果。 */
    private JsonObject callTool(String name, JsonObject args) {
        CompletableFuture<JsonObject> future = new CompletableFuture<>();
        mainQueue.add(() -> dispatch(name, args, future));
        try {
            return future.get(Math.max(30, BuilderConfig.mcpToolTimeoutSeconds()), TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            return error("工具 " + name + " 超时未返回（游戏是否在运行 / MCP 请求是否卡住？）");
        } catch (Exception e) {
            return error("工具 " + name + " 调用失败：" + e);
        }
    }

    private void dispatch(String name, JsonObject args, CompletableFuture<JsonObject> future) {
        try {
            switch (name) {
                case "get_held_task" -> future.complete(getHeldTask(args));
                case "list_tasks" -> future.complete(listTasks(args));
                case "get_context" -> future.complete(getContext(args));
                case "request_bounds" -> requestBounds(args, future);
                case "finish_build" -> finish(args, future);
                default -> submitTool(name, args, future);
            }
        } catch (Exception e) {
            future.complete(error(e.getMessage() == null ? e.toString() : e.getMessage()));
        }
    }

    /** 只用于确认任务 id：读手持外部物品的玩家绑定的 taskId 与选区。 */
    private JsonObject getHeldTask(JsonObject args) {
        MinecraftServer server = manager.serverOrNull();
        if (server == null) {
            return error("服务端未就绪");
        }
        String wanted = Json.str(args, "playerName", "").trim();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (!wanted.isBlank()
                    && !player.getGameProfile().getName().equalsIgnoreCase(wanted)) {
                continue;
            }
            for (InteractionHand hand : InteractionHand.values()) {
                ItemStack stack = player.getItemInHand(hand);
                if (!(stack.getItem() instanceof ExternalBuildWandItem)) {
                    continue;
                }
                JsonObject out = new JsonObject();
                out.addProperty("held", true);
                out.addProperty("player", player.getGameProfile().getName());
                String taskId = ItemData.taskId(stack);
                if (taskId == null || taskId.isBlank()) {
                    out.addProperty("taskId", "");
                    out.addProperty("hint", "物品还没绑定任务：让玩家左键两次设定范围后再调用");
                    return out;
                }
                out.addProperty("taskId", taskId);
                BuildMemory memory = manager.memory(taskId);
                if (memory != null && memory.hasBounds && memory.bounds != null) {
                    out.add("bounds", Json.GSON.toJsonTree(memory.bounds.toArray()));
                    out.addProperty("volume", memory.bounds.volume());
                    out.addProperty("version", memory.version);
                    out.addProperty("blocks", memory.blocksPlacedTotal);
                    out.addProperty("mode", memory.linkMode);
                } else {
                    out.addProperty("hint", "还没有选区：让玩家左键两次设定起终点");
                }
                return out;
            }
        }
        JsonObject out = new JsonObject();
        out.addProperty("held", false);
        out.addProperty("hint", "让玩家手持「外部建造手杖」并保持手持后再调用本工具");
        return out;
    }

    private JsonObject listTasks(JsonObject args) {
        boolean onlySelectable = Json.bool(args, "onlySelectable", true);
        JsonArray tasks = new JsonArray();
        for (BuildMemory memory : manager.allMemories()) {
            if (!"external".equals(memory.linkMode)) {
                continue;
            }
            boolean selectable = memory.hasBounds && memory.bounds != null;
            if (onlySelectable && !selectable) {
                continue;
            }
            JsonObject one = new JsonObject();
            one.addProperty("taskId", memory.taskId);
            one.addProperty("status", memory.status);
            one.addProperty("version", memory.version);
            one.addProperty("dimension", memory.dimension);
            one.addProperty("selectable", selectable);
            one.addProperty("blocks", memory.blocksPlacedTotal);
            one.addProperty("session", sessions.containsKey(memory.taskId));
            if (selectable) {
                one.add("bounds", Json.GSON.toJsonTree(memory.bounds.toArray()));
                one.addProperty("volume", volume(memory.bounds));
            }
            BoundsRequest request = boundsRequests.get(memory.taskId);
            if (request != null) {
                one.addProperty("pendingBounds", request.describe());
            }
            tasks.add(one);
        }
        JsonObject out = new JsonObject();
        out.add("tasks", tasks);
        out.addProperty("hint", "选定 taskId 后先调 get_context，再用 request_bounds 确认范围");
        return out;
    }

    private JsonObject getContext(JsonObject args) {
        String taskId = Json.str(args, "taskId", "").trim();
        BuildMemory memory = resolveMemory(taskId);
        JsonObject out = new JsonObject();
        out.addProperty("taskId", memory.taskId);
        out.addProperty("dimension", memory.dimension);
        out.addProperty("status", memory.status);
        out.addProperty("version", memory.version);
        out.addProperty("blocks", memory.blocksPlacedTotal);
        out.addProperty("selectable", memory.hasBounds && memory.bounds != null);
        if (memory.hasBounds && memory.bounds != null) {
            out.add("bounds", Json.GSON.toJsonTree(memory.bounds.toArray()));
            out.addProperty("volume", volume(memory.bounds));
        }
        Session session = sessions.get(memory.taskId);
        out.addProperty("sessionActive", session != null);
        if (session != null) {
            out.addProperty("sessionPendingWrites", session.pending.size());
            out.addProperty("sessionBlocks", session.world.size());
            out.addProperty("sessionCaptures", session.capturePaths.size());
        }
        BoundsRequest request = boundsRequests.get(memory.taskId);
        if (request != null) {
            out.addProperty("pendingBoundsRequest", request.describe());
        }
        out.addProperty("tip", "所有坐标都是绝对世界整数坐标；越界会被逐条拒绝");
        return out;
    }

    // ------------------------------------------------------------------
    // 坐标授权
    // ------------------------------------------------------------------

    private void requestBounds(JsonObject args, CompletableFuture<JsonObject> future) {
        BuildMemory memory = resolveMemory(Json.str(args, "taskId", "").trim());
        BoundsRequest existing = boundsRequests.get(memory.taskId);
        if (existing != null) {
            future.complete(error("该任务已有待批准的坐标请求：" + existing.describe()
                    + "；请等玩家 /builder approve，或用 get_context 复查"));
            return;
        }
        JsonArray box = args.getAsJsonArray("bounds");
        if (box == null || box.size() != 6) {
            future.complete(error("bounds 必须是 [x1,y1,z1,x2,y2,z2] 六个整数"));
            return;
        }
        int[] values = new int[6];
        for (int i = 0; i < 6; i++) {
            values[i] = box.get(i).getAsInt();
        }
        Bounds requested = new Bounds(
                Math.min(values[0], values[3]), Math.min(values[1], values[4]),
                Math.min(values[2], values[5]), Math.max(values[0], values[3]),
                Math.max(values[1], values[4]), Math.max(values[2], values[5]));
        // 体积不设上限；多 Agent 并行时唯一要防的是两个任务抢同一片区域
        String conflict = conflictWith(memory.taskId, requested);
        if (conflict != null) {
            future.complete(error(conflict));
            return;
        }
        String reason = Json.str(args, "reason", "").trim();
        int timeoutTicks = Math.max(200, BuilderConfig.mcpBoundsTimeoutSeconds() * 20);
        int waitTicks = Math.max(20, BuilderConfig.mcpBoundsWaitSeconds() * 20);
        BoundsRequest request = new BoundsRequest(memory, requested, reason, future,
                timeoutTicks, waitTicks);
        boundsRequests.put(memory.taskId, request);
        manager.log(memory.taskId, "MCP", "INFO", "外部 Agent 请求范围 " + request.describe()
                + "（" + requested.volume() + " 格）" + (reason.isBlank() ? "" : "：" + reason));
        announce(memory, requested, reason);
    }

    private void announce(BuildMemory memory, Bounds requested, String reason) {
        MinecraftServer server = manager.serverOrNull();
        if (server == null) {
            return;
        }
        String text = "§e[Agent] 请求把建造范围设为 §f" + describeBounds(requested)
                + "§e（" + requested.volume() + " 格）"
                + (reason.isBlank() ? "" : "，理由：§f" + reason)
                + "\n§a/builder approve §e批准　§c/builder deny §e拒绝";
        Component message = Component.literal(text);
        boolean sent = false;
        ServerPlayer owner = owner(memory);
        if (owner != null) {
            owner.sendSystemMessage(message);
            sent = true;
        }
        if (!sent) {
            for (ServerPlayer player : server.getPlayerList().getPlayers()) {
                player.sendSystemMessage(message);
            }
        }
        BuilderMod.LOGGER.info("[建造] MCP 坐标请求已发出：{} {}", memory.taskId,
                describeBounds(requested));
    }

    private void tickBoundsRequest() {
        if (boundsRequests.isEmpty()) {
            return;
        }
        List<Map.Entry<String, BoundsRequest>> expired = new ArrayList<>();
        List<BoundsRequest> paused = new ArrayList<>();
        Iterator<Map.Entry<String, BoundsRequest>> iterator = boundsRequests.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, BoundsRequest> entry = iterator.next();
            BoundsRequest request = entry.getValue();
            if (--request.remainingTicks <= 0) {
                expired.add(entry);
                iterator.remove();
                continue;
            }
            // 同步等待窗口用完就先回 pending，避免 MCP 客户端自己的调用超时；请求继续等玩家批准
            if (request.waitTicks > 0 && --request.waitTicks == 0) {
                paused.add(request);
            }
        }
        for (Map.Entry<String, BoundsRequest> entry : expired) {
            entry.getValue().future.complete(rejected("等待玩家批准超时（"
                    + BuilderConfig.mcpBoundsTimeoutSeconds() + " 秒），请先按现有范围设计或再次请求"));
            manager.log(entry.getKey(), "MCP", "WARN", "坐标请求超时，已自动拒绝");
        }
        for (BoundsRequest request : paused) {
            JsonObject pending = new JsonObject();
            pending.addProperty("status", "pending");
            pending.add("requestedBounds", Json.GSON.toJsonTree(request.requested.toArray()));
            pending.addProperty("volume", request.requested.volume());
            pending.addProperty("hint", "已通过游戏聊天通知玩家，等待 /builder approve。"
                    + "批准后 get_context 会返回新的 bounds；未批准前不要按该范围建模。");
            request.future.complete(pending);
        }
    }

    /** /builder approve [taskId]：玩家批准待处理的坐标请求。 */
    public String approve(ServerPlayer player, String taskId) {
        Map.Entry<String, BoundsRequest> picked = pickBoundsRequest(taskId);
        if (picked == null) {
            return boundsRequests.isEmpty() ? "没有待批准的坐标请求"
                    : "有 " + boundsRequests.size() + " 个待批准请求，请用 /builder approve <taskId> 指定";
        }
        BoundsRequest request = picked.getValue();
        if (!canDecide(player, request.memory)) {
            return "这个坐标请求不属于你（需求方是其它玩家）";
        }
        // 等批准期间别的 Agent 可能已经建了会话，生效前再查一次重叠
        String conflict = conflictWith(request.memory.taskId, request.requested);
        if (conflict != null) {
            boundsRequests.remove(picked.getKey());
            request.future.complete(rejected("批准失败：" + conflict));
            return "无法批准：" + conflict;
        }
        boundsRequests.remove(picked.getKey());
        BuildMemory memory = request.memory;
        memory.bounds = request.requested.copy();
        memory.hasBounds = true;
        manager.saveMemory(memory);
        manager.markDirty(memory.taskId);
        Session session = sessions.get(memory.taskId);
        if (session != null) {
            session.rebindBounds(request.requested.copy());
        }
        manager.log(memory.taskId, "MCP", "INFO", "玩家批准新范围 " + describeBounds(request.requested));
        JsonObject payload = new JsonObject();
        payload.addProperty("approved", true);
        payload.add("bounds", Json.GSON.toJsonTree(request.requested.toArray()));
        payload.addProperty("volume", request.requested.volume());
        request.future.complete(payload);
        return "已批准 " + shortId(memory.taskId) + " 的范围 "
                + describeBounds(request.requested)
                + "（" + request.requested.volume() + " 格）";
    }

    /** /builder deny [taskId]：玩家拒绝待处理的坐标请求。 */
    public String deny(ServerPlayer player, String taskId) {
        Map.Entry<String, BoundsRequest> picked = pickBoundsRequest(taskId);
        if (picked == null) {
            return boundsRequests.isEmpty() ? "没有待批准的坐标请求"
                    : "有 " + boundsRequests.size() + " 个待批准请求，请用 /builder deny <taskId> 指定";
        }
        BoundsRequest request = picked.getValue();
        if (!canDecide(player, request.memory)) {
            return "这个坐标请求不属于你（需求方是其它玩家）";
        }
        boundsRequests.remove(picked.getKey());
        request.future.complete(rejected("玩家拒绝了坐标请求；请按现有范围重新设计，"
                + "或说明更具体的理由后再次请求"));
        manager.log(request.memory.taskId, "MCP", "WARN", "玩家拒绝坐标请求 "
                + describeBounds(request.requested));
        return "已拒绝 " + shortId(request.memory.taskId) + " 的 "
                + describeBounds(request.requested);
    }

    /** taskId 为空时只在唯一请求时自动选中；也支持传 taskId 前缀。 */
    private Map.Entry<String, BoundsRequest> pickBoundsRequest(String taskId) {
        if (taskId != null && !taskId.isBlank()) {
            String key = taskId.trim();
            for (Map.Entry<String, BoundsRequest> entry : boundsRequests.entrySet()) {
                if (entry.getKey().startsWith(key)) {
                    return entry;
                }
            }
            return null;
        }
        if (boundsRequests.size() == 1) {
            return boundsRequests.entrySet().iterator().next();
        }
        return null;
    }

    public String status() {
        StringBuilder sb = new StringBuilder();
        sb.append(BuilderConfig.mcpEnabled() ? "MCP 已启用" : "MCP 已关闭");
        if (BuilderConfig.mcpEnabled()) {
            sb.append("：http://").append(BuilderConfig.mcpHost())
                    .append(':').append(BuilderConfig.mcpPort()).append("/mcp");
            sb.append("；token ").append(BuilderConfig.mcpToken().isBlank() ? "未设置" : "已设置");
        }
        sb.append("；活动会话 ").append(sessions.size());
        for (Map.Entry<String, Session> entry : sessions.entrySet()) {
            Session session = entry.getValue();
            sb.append("\n§7- ").append(entry.getKey(), 0, 8)
                    .append("：").append(session.world.size()).append(" 格，待写入 ")
                    .append(session.pending.size()).append("，照片 ").append(session.capturePaths.size());
        }
        for (Map.Entry<String, BoundsRequest> entry : boundsRequests.entrySet()) {
            sb.append("\n§e待批准：§f").append(shortId(entry.getKey())).append(' ')
                    .append(entry.getValue().describe())
                    .append("（").append(entry.getValue().requested.volume()).append(" 格）");
        }
        return sb.toString();
    }

    private boolean canDecide(ServerPlayer player, BuildMemory memory) {
        if (player == null) {
            return false;
        }
        if (memory.ownerUuid == null || memory.ownerUuid.isBlank()) {
            return true;
        }
        return memory.ownerUuid.equalsIgnoreCase(player.getUUID().toString());
    }

    private ServerPlayer owner(BuildMemory memory) {
        MinecraftServer server = manager.serverOrNull();
        if (server == null || memory.ownerUuid == null || memory.ownerUuid.isBlank()) {
            return null;
        }
        try {
            return server.getPlayerList().getPlayer(UUID.fromString(memory.ownerUuid));
        } catch (Exception e) {
            return null;
        }
    }

    // ------------------------------------------------------------------
    // 工具执行
    // ------------------------------------------------------------------

    private void submitTool(String name, JsonObject args, CompletableFuture<JsonObject> future) {
        Session session = sessionOf(args);
        if (session == null) {
            String taskId = Json.str(args, "taskId", "").trim();
            BuildMemory memory = taskId.isBlank() ? latestExternalMemory()
                    : manager.memory(taskId);
            if (memory == null) {
                future.complete(error("没有可用的外部任务：请让玩家手持「外部建造手杖」"
                        + "左键两次选好范围，再用 get_held_task 取 taskId"));
                return;
            }
            // 复用已存在的会话，避免同一个任务被重复建 Session 而丢掉未同步的差量
            session = sessions.get(memory.taskId);
            if (session == null) {
                if (manager.hasActiveBuild(memory.taskId)) {
                    future.complete(error("任务 " + memory.taskId
                            + " 正在跑游戏内 Agent，请先中止它再通过 MCP 建造"));
                    return;
                }
                if (!memory.hasBounds || memory.bounds == null) {
                    future.complete(error("任务没有选区；请先用 request_bounds 请求一个范围并让玩家批准"));
                    return;
                }
                session = createSession(memory);
            }
        }
        session.submit(name, args, future);
    }

    private Session createSession(BuildMemory memory) {
        // 内部与外部链路完全分开：外部 Agent 只接管外部建造手杖创建的任务
        if (!"external".equals(memory.linkMode)) {
            throw new IllegalStateException("任务 " + shortId(memory.taskId)
                    + " 属于游戏内 Agent，外部 Agent 不能接管；请用外部建造手杖创建新任务");
        }
        // 两个 Agent 的选区重叠会互相覆盖方块，宁可拒绝也不静默打架
        String conflict = conflictWith(memory.taskId, memory.bounds);
        if (conflict != null) {
            throw new IllegalStateException(conflict);
        }
        List<Placement> previous = manager.loadPlacements(memory.taskId, memory.version);
        Session session = new Session(memory.taskId, memory.bounds.copy(), memory.version, previous);
        sessions.put(memory.taskId, session);
        manager.log(memory.taskId, "MCP", "INFO", "外部 Agent 通过 MCP 接入（0 次模型调用），"
                + "载入已有 " + previous.size() + " 格基线");
        return session;
    }

    /** 有多个并行会话时强制显式 taskId，避免两个 Agent 的调用串到同一个任务。 */
    private Session sessionOf(JsonObject args) {
        String taskId = Json.str(args, "taskId", "").trim();
        if (!taskId.isBlank()) {
            Session session = sessions.get(taskId);
            if (session != null) {
                return session;
            }
            for (Map.Entry<String, Session> entry : sessions.entrySet()) {
                if (entry.getKey().startsWith(taskId)) {
                    return entry.getValue();
                }
            }
            return null;
        }
        if (sessions.size() == 1) {
            return sessions.values().iterator().next();
        }
        if (sessions.size() > 1) {
            throw new IllegalStateException("当前有 " + sessions.size()
                    + " 个并行会话，必须显式传 taskId；可选："
                    + String.join("、", shortIds()));
        }
        return null;
    }

    private List<String> shortIds() {
        List<String> ids = new ArrayList<>();
        for (String taskId : sessions.keySet()) {
            ids.add(shortId(taskId));
        }
        return ids;
    }

    private void finish(JsonObject args, CompletableFuture<JsonObject> future) {
        Session session = sessionOf(args);
        if (session == null) {
            future.complete(error("没有活动会话；先调用任意建造工具建立会话，或指定 taskId"));
            return;
        }
        if (session.finishFuture != null) {
            future.complete(error("已经有一轮归档在进行中"));
            return;
        }
        session.finishSummary = Json.str(args, "summary", "");
        session.finishTitle = Json.str(args, "title", "");
        session.finishFuture = future;
        builderLog(session.taskId, "收到 finish_build，等 "
                + session.pending.size() + " 格差量写入世界后归档");
    }

    private void builderLog(String taskId, String text) {
        manager.log(taskId, "MCP", "INFO", text);
    }

    /** MCP 只看外部物品创建的任务，内部任务对它不可见。 */
    private BuildMemory latestExternalMemory() {
        BuildMemory best = null;
        for (BuildMemory memory : manager.allMemories()) {
            if (!"external".equals(memory.linkMode) || !memory.hasBounds
                    || memory.bounds == null) {
                continue;
            }
            if (best == null || memory.updatedAt > best.updatedAt) {
                best = memory;
            }
        }
        return best;
    }

    private BuildMemory resolveMemory(String taskId) {
        BuildMemory memory = taskId.isBlank() ? latestExternalMemory()
                : manager.memory(taskId);
        if (memory == null) {
            throw new IllegalArgumentException(taskId.isBlank()
                    ? "找不到外部建造手杖创建的任务；请让玩家手持物品选好范围"
                    : "未知 taskId：" + taskId);
        }
        if (!"external".equals(memory.linkMode)) {
            throw new IllegalArgumentException("任务 " + shortId(memory.taskId)
                    + " 属于游戏内 Agent，外部 Agent 不能操作它");
        }
        return memory;
    }

    /** 与其他活动会话的选区是否重叠；重叠会让两个 Agent 互相覆盖方块。 */
    private String conflictWith(String taskId, Bounds bounds) {
        for (Map.Entry<String, Session> entry : sessions.entrySet()) {
            if (entry.getKey().equals(taskId)) {
                continue;
            }
            Bounds other = entry.getValue().bounds;
            if (overlaps(bounds, other)) {
                return "选区与并行任务 " + shortId(entry.getKey()) + " 重叠（"
                        + describeBounds(other) + "），两个 Agent 会互相覆盖方块；"
                        + "请换一块不重叠的范围";
            }
        }
        return null;
    }

    private static boolean overlaps(Bounds a, Bounds b) {
        return a.x1 <= b.x2 && b.x1 <= a.x2
                && a.y1 <= b.y2 && b.y1 <= a.y2
                && a.z1 <= b.z2 && b.z1 <= a.z2;
    }

    private static String shortId(String taskId) {
        return taskId == null ? "?"
                : taskId.substring(0, Math.min(8, taskId.length()));
    }

    private static int volume(Bounds bounds) {
        int[] b = bounds.toArray();
        return Math.max(0, (b[3] - b[0] + 1)) * Math.max(0, (b[4] - b[1] + 1))
                * Math.max(0, (b[5] - b[2] + 1));
    }

    private static String describeBounds(Bounds bounds) {
        int[] b = bounds.toArray();
        return "[" + b[0] + "," + b[1] + "," + b[2] + " → " + b[3] + "," + b[4] + "," + b[5] + "]";
    }

    private static JsonObject error(String message) {
        JsonObject out = new JsonObject();
        out.addProperty("error", message == null ? "未知错误" : message);
        out.addProperty("isError", true);
        return out;
    }

    private static JsonObject rejected(String reason) {
        JsonObject out = new JsonObject();
        out.addProperty("approved", false);
        out.addProperty("reason", reason);
        out.addProperty("isError", true);
        return out;
    }

    // ------------------------------------------------------------------
    // 会话
    // ------------------------------------------------------------------

    private final class Session {
        final String taskId;
        Bounds bounds;
        VoxelWorld world;
        ToolExecutor executor;
        Map<Long, String> desired = new HashMap<>();
        final Map<Long, Change> applied = new HashMap<>();
        final Map<Long, Change> pending = new LinkedHashMap<>();
        final Queue<BuildMemory.CaptureLine> captures = new ConcurrentLinkedQueue<>();
        final Queue<String> capturePaths = new ConcurrentLinkedQueue<>();
        volatile boolean dirty;
        CompletableFuture<JsonObject> finishFuture;
        String finishSummary = "";
        String finishTitle = "";
        boolean finished;

        Session(String taskId, Bounds bounds, int version, List<Placement> previous) {
            this.taskId = taskId;
            this.bounds = bounds;
            bindWorld(bounds, version, previous);
        }

        void bindWorld(Bounds bounds, int version, List<Placement> previous) {
            this.world = new VoxelWorld(bounds);
            this.world.load(previous);
            this.applied.clear();
            this.pending.clear();
            Map<Long, String> snapshot = new HashMap<>();
            for (VoxelWorld.Voxel voxel : world.all()) {
                long key = VoxelWorld.key(voxel.x(), voxel.y(), voxel.z());
                Change change = new Change(voxel.x(), voxel.y(), voxel.z(), voxel.fullState());
                applied.put(key, change);
                snapshot.put(key, change.fullState());
            }
            this.desired = snapshot;
            this.executor = new ToolExecutor(taskId, bounds, world,
                    manager.captureDir(taskId), version);
        }

        /** 玩家批准新范围：把已有体素迁移到更大的 VoxelWorld，重建执行器。 */
        void rebindBounds(Bounds next) {
            List<Placement> existing = world.placements();
            BuildMemory memory = manager.memory(taskId);
            int version = memory == null ? 0 : memory.version;
            this.bounds = next;
            bindWorld(next, version, existing);
            builderLog(taskId, "范围已更新为 " + describeBounds(next) + "，已有 "
                    + existing.size() + " 格已迁移");
        }

        void submit(String name, JsonObject args, CompletableFuture<JsonObject> future) {
            TOOL_POOL.submit(() -> {
                try {
                    String argsJson = Json.GSON.toJson(args);
                    ToolExecutor.Outcome outcome = executor.execute(
                            new LlmClient.ToolCall("mcp_" + (++callSeq), name, argsJson));
                    for (BuildMemory.CaptureLine line : outcome.captures) {
                        if (line != null) {
                            captures.add(line);
                            if (line.path != null && !line.path.isBlank()) {
                                capturePaths.add(line.path);
                            }
                        }
                    }
                    dirty = true;
                    JsonElement parsed;
                    try {
                        parsed = Json.GSON.fromJson(outcome.result, JsonElement.class);
                    } catch (Exception e) {
                        parsed = Json.GSON.toJsonTree(outcome.result);
                    }
                    JsonObject out = new JsonObject();
                    out.add("result", parsed == null ? new JsonObject() : parsed);
                    if (!outcome.log.isBlank()) {
                        out.addProperty("log", outcome.log);
                    }
                    future.complete(out);
                } catch (Exception e) {
                    future.complete(error(name + " 执行异常：" + e));
                }
            });
        }

        void tick(MinecraftServer server) {
            if (finished) {
                return;
            }
            if (dirty) {
                syncDesired();
                dirty = false;
            }
            applyWorldChanges(server);
            if (finishFuture != null && pending.isEmpty()) {
                archive();
            }
        }

        private void syncDesired() {
            Map<Long, String> snapshot = new HashMap<>();
            Map<Long, int[]> coords = new HashMap<>();
            for (VoxelWorld.Voxel voxel : world.all()) {
                long key = VoxelWorld.key(voxel.x(), voxel.y(), voxel.z());
                snapshot.put(key, voxel.fullState());
                coords.put(key, new int[]{voxel.x(), voxel.y(), voxel.z()});
            }
            desired = snapshot;
            HashSet<Long> keys = new HashSet<>(desired.keySet());
            keys.addAll(applied.keySet());
            for (Long key : keys) {
                String want = desired.get(key);
                Change have = applied.get(key);
                boolean needWrite = want == null ? have != null
                        : (have == null || !want.equals(have.fullState()));
                if (!needWrite) {
                    pending.remove(key);
                    continue;
                }
                if (want != null) {
                    int[] coord = coords.get(key);
                    pending.put(key, new Change(coord[0], coord[1], coord[2], want));
                } else {
                    pending.put(key, new Change(have.x(), have.y(), have.z(), "minecraft:air"));
                }
            }
        }

        private void applyWorldChanges(MinecraftServer server) {
            if (pending.isEmpty()) {
                return;
            }
            ServerLevel level = resolveLevel(server, manager.memory(taskId) == null
                    ? "minecraft:overworld" : manager.memory(taskId).dimension);
            if (level == null) {
                return;
            }
            // 多个并行会话共享每 tick 的写入预算，避免总写入量翻倍卡服
            int share = Math.max(1, sessions.size());
            int budget = Math.max(16, BuilderConfig.BLOCKS_PER_TICK.getAsInt() / share);
            int processed = 0;
            List<Long> done = new ArrayList<>();
            for (Map.Entry<Long, Change> entry : pending.entrySet()) {
                if (processed >= budget) {
                    break;
                }
                if (!writeOne(level, entry.getValue())) {
                    continue;
                }
                if ("minecraft:air".equals(entry.getValue().fullState())) {
                    applied.remove(entry.getKey());
                } else {
                    applied.put(entry.getKey(), entry.getValue());
                }
                done.add(entry.getKey());
                processed++;
            }
            for (Long key : done) {
                pending.remove(key);
            }
        }

        private boolean writeOne(ServerLevel level, Change change) {
            BlockPos pos = new BlockPos(change.x(), change.y(), change.z());
            if (!level.isLoaded(pos)) {
                level.getChunk(change.x() >> 4, change.z() >> 4);
                if (!level.isLoaded(pos)) {
                    return false;
                }
            }
            BlockState state = BlockIndex.toState(change.fullState());
            if (state == null) {
                int bracket = change.fullState().indexOf('[');
                String base = bracket > 0 ? change.fullState().substring(0, bracket)
                        : change.fullState();
                state = BlockIndex.toState(base);
                if (state == null) {
                    return false;
                }
            }
            level.setBlock(pos, state, 2);
            return true;
        }

        private void archive() {
            int archivedVersion = 0;
            BuildMemory memory = manager.memory(taskId);
            if (memory != null) {
                List<Placement> placements = world.placements();
                memory.version++;
                archivedVersion = memory.version;
                manager.savePlacements(taskId, archivedVersion, placements);
                memory.blocksPlacedTotal = placements.size();
                memory.materials = world.materials();
                if (!finishSummary.isBlank()) {
                    memory.notes = finishSummary;
                    memory.addInstruction("MCP", finishTitle.isBlank() ? finishSummary
                            : finishTitle + "：" + finishSummary);
                }
                List<BuildMemory.CaptureLine> photos = new ArrayList<>(captures);
                if (!photos.isEmpty()) {
                    memory.captures.addAll(photos);
                }
                manager.setStatus(taskId, "DONE", "外部 Agent 建造完成");
                manager.saveMemory(memory);
                manager.markDirty(taskId);
                manager.log(taskId, "MCP", "INFO", "外部 Agent 归档 v" + archivedVersion + "："
                        + placements.size() + " 格方块，拍照 " + capturePaths.size()
                        + " 张，0 次模型调用");
            }
            JsonObject out = new JsonObject();
            out.addProperty("archived", true);
            out.addProperty("taskId", taskId);
            out.addProperty("version", archivedVersion);
            out.addProperty("totalBlocks", world.size());
            out.add("bounds", Json.GSON.toJsonTree(bounds.toArray()));
            JsonArray photos = new JsonArray();
            for (String path : capturePaths) {
                photos.add(path);
            }
            out.add("captures", photos);
            finished = true;
            CompletableFuture<JsonObject> future = finishFuture;
            finishFuture = null;
            if (future != null) {
                future.complete(out);
            }
        }
    }

    private static ServerLevel resolveLevel(MinecraftServer server, String dimension) {
        try {
            ResourceKey<Level> key = ResourceKey.create(Registries.DIMENSION,
                    ResourceLocation.parse(dimension));
            ServerLevel level = server.getLevel(key);
            return level == null ? server.overworld() : level;
        } catch (Exception e) {
            return server.overworld();
        }
    }
}
