package baocup.builder.agent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.google.gson.JsonArray;

import baocup.builder.BuilderConfig;
import baocup.builder.BuilderMod;
import baocup.builder.memory.BuildMemory;
import baocup.builder.util.Bounds;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 真正的 Agent：把建造的决策权完全交给模型。
 *
 * 模型通过 tool calling 自己决定：什么时候读状态、用什么形状、放哪些方块、何时拍照、
 * 看到照片后要不要修正、什么时候结束。本地只做三件事：把几何编译成体素、维护权威坐标状态、
 * 把工具结果（含照片）结构化回执。借鉴 3D-GPT / LL3M 的「写几何程序 → 执行 → 渲染 → 修正」闭环。
 */
public final class BuildAgent {
    private static final ExecutorService TOOL_POOL = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "builder-agent-tools");
        thread.setDaemon(true);
        return thread;
    });

    public enum Phase { REQUEST, TOOLS, FLUSH, DONE, FAILED }

    private record Change(int x, int y, int z, String fullState) {
    }

    public final String taskId;
    private final String brief;
    private final Bounds bounds;
    private final AgentManager manager;
    private final VoxelWorld world;
    private final ToolExecutor executor;
    private final List<LlmClient.AgentMessage> messages = new ArrayList<>();
    private final JsonArray tools = AgentTools.definitions(BuilderConfig.toolsSchemaCompat());
    /**
     * desired = VoxelWorld 的目标状态；applied = 真正成功写进 ServerLevel 的状态。
     * pendingChanges 是两者差量：只有 setBlock 成功才更新 applied，失败会留着下 tick 重试，
     * 不会出现「提前记账后丢写入」或「存档里有、世界里没有」。
     */
    private Map<Long, String> desired = new HashMap<>();
    private final Map<Long, Change> applied = new HashMap<>();
    private final Map<Long, Change> pendingChanges = new LinkedHashMap<>();

    private Phase phase = Phase.REQUEST;
    private CompletableFuture<LlmClient.AgentReply> requestFuture;
    /** 当前轮的流式思考片段队列；主线程每 tick 取走写进 Agent 输出。 */
    private LlmClient.StreamProgress streamProgress;
    private boolean thinkingStarted;
    private CompletableFuture<List<ToolExecutor.Outcome>> toolFuture;
    private List<LlmClient.ToolCall> currentCalls = List.of();
    private int round;
    private int idleRounds;
    private int lastImageIndex = -1;
    private String pendingFinishSummary = "";
    private String pendingFinishTitle = "";
    private int flushTicks;
    private boolean finished;
    private final long startedAt = System.currentTimeMillis();

    public BuildAgent(String taskId, String brief, Bounds bounds, BuildMemory memory,
                      List<Placement> previous, AgentManager manager) {
        this.taskId = taskId;
        this.brief = brief;
        this.bounds = bounds;
        this.manager = manager;
        this.world = new VoxelWorld(bounds);
        this.world.load(previous);
        for (VoxelWorld.Voxel v : this.world.all()) {
            long key = VoxelWorld.key(v.x(), v.y(), v.z());
            Change change = new Change(v.x(), v.y(), v.z(), v.fullState());
            applied.put(key, change);
            desired.put(key, change.fullState());
        }
        this.executor = new ToolExecutor(taskId, bounds, world,
                manager.captureDir(taskId), memory.version);
        messages.add(LlmClient.AgentMessage.system(Prompts.AGENT_SYSTEM));
        messages.add(LlmClient.AgentMessage.userText(
                Prompts.agentUser(brief, bounds, memory, world.size())));
    }

    public boolean finished() {
        return finished;
    }

    public String phaseName() {
        return switch (phase) {
            case REQUEST -> "Agent 决策第 " + (round + 1) + " 轮";
            case TOOLS -> "执行工具调用";
            case FLUSH -> "同步世界（归档前）";
            case DONE -> "完成";
            case FAILED -> "失败";
        };
    }

    public void cancel() {
        if (requestFuture != null) {
            requestFuture.cancel(true);
        }
        if (toolFuture != null) {
            toolFuture.cancel(true);
        }
        manager.log(taskId, "Agent", "WARN", "任务在第 " + (round + 1) + " 轮被手动中止；"
                + "已写入世界的方块保留，未同步的 " + pendingChanges.size()
                + " 格差量丢弃，磁盘版本保持不变");
        manager.setStatus(taskId, "IDLE", "已中止");
        finished = true;
    }

    public void tick(MinecraftServer server, AgentManager agentManager) {
        BuildMemory memory = agentManager.memory(taskId);
        if (memory == null) {
            finished = true;
            return;
        }
        try {
            applyWorldChanges(server, memory);
            drainThinking();
            switch (phase) {
                case REQUEST -> doRequest(memory);
                case TOOLS -> doTools(memory);
                case FLUSH -> doFlush(memory);
                default -> {
                }
            }
        } catch (Exception e) {
            manager.log(taskId, "Agent", "ERROR", "第 " + (round + 1) + " 轮异常：" + e.getMessage());
            BuilderMod.LOGGER.warn("[建造] Agent 异常", e);
            manager.setStatus(taskId, "FAILED", "异常终止");
            phase = Phase.FAILED;
            finished = true;
        }
    }

    private void doRequest(BuildMemory memory) {
        if (requestFuture == null) {
            manager.setStatus(taskId, "BUILDING", phaseName());
            manager.log(taskId, "Agent", "INFO", "第 " + (round + 1) + " 轮：把工具清单交给模型，由它决定下一步");
            LlmClient.AgentRequest request = manager.agentChat(new ArrayList<>(messages), tools);
            requestFuture = request.future();
            streamProgress = request.progress();
            thinkingStarted = false;
            return;
        }
        if (!requestFuture.isDone()) {
            return;
        }
        LlmClient.AgentReply reply;
        try {
            reply = requestFuture.get();
        } catch (Exception e) {
            reply = LlmClient.AgentReply.fail("请求异常：" + e.getMessage());
        }
        requestFuture = null;
        // 取走响应结束前最后一段思考；若整轮都没流式输出过，回退到一次性完整写入
        drainThinking();
        boolean streamedThinking = streamProgress != null && streamProgress.hasEmitted();
        streamProgress = null;
        thinkingStarted = false;
        if (!reply.ok()) {
            manager.log(taskId, "Agent", "WARN", "模型调用失败：" + reply.error());
            phase = Phase.FAILED;
            manager.setStatus(taskId, "FAILED", "模型调用失败");
            finished = true;
            return;
        }
        memory.modelCalls++;
        memory.promptTokens += Math.max(0, reply.promptTokens());
        memory.completionTokens += Math.max(0, reply.completionTokens());
        manager.markDirty(taskId);
        if (streamedThinking) {
            manager.logThinkingChunk(taskId, "思考", "【深度思考结束】共 "
                    + reply.reasoning().length() + " 字，已完整实时输出");
        } else {
            manager.logReasoning(taskId, "Agent 第 " + (round + 1) + " 轮", reply.reasoning());
        }
        if (!reply.content().isBlank()) {
            manager.log(taskId, "Agent", "INFO", "模型说明：" + reply.content());
        }
        // reasoning_content 不回传，避免部分端点拒收 assistant 消息里的未知字段
        messages.add(LlmClient.AgentMessage.assistant(
                reply.content(), "", reply.toolCalls()));
        if (reply.toolCalls().isEmpty()) {
            idleRounds++;
            manager.log(taskId, "Agent", "WARN", "本轮没有工具调用；提醒模型继续用工具施工或调用 finish_build");
            messages.add(LlmClient.AgentMessage.userText(
                    "请继续通过工具调用施工。若已完成，请先 capture 看图确认，再调用 finish_build；"
                            + "不要在正文里描述建造方案。"));
            if (idleRounds >= 3 || round >= BuilderConfig.agentMaxRounds()) {
                forceFinish(memory, "达到无工具调用 / 轮数上限，自动归档");
                return;
            }
            nextRound();
            return;
        }
        idleRounds = 0;
        currentCalls = reply.toolCalls();
        phase = Phase.TOOLS;
        toolFuture = null;
    }

    private void doTools(BuildMemory memory) {
        if (toolFuture == null) {
            List<LlmClient.ToolCall> calls = new ArrayList<>(currentCalls);
            toolFuture = CompletableFuture.supplyAsync(() -> {
                List<ToolExecutor.Outcome> outcomes = new ArrayList<>(calls.size());
                for (LlmClient.ToolCall call : calls) {
                    outcomes.add(executor.execute(call));
                }
                return outcomes;
            }, TOOL_POOL);
            return;
        }
        if (!toolFuture.isDone()) {
            return;
        }
        List<ToolExecutor.Outcome> outcomes;
        try {
            outcomes = toolFuture.get();
        } catch (Exception e) {
            manager.log(taskId, "Agent", "WARN", "工具执行异常：" + e.getMessage());
            outcomes = List.of();
        }
        toolFuture = null;
        List<byte[]> photos = new ArrayList<>();
        String finishSummary = null;
        String finishTitle = "";
        for (int i = 0; i < currentCalls.size(); i++) {
            LlmClient.ToolCall call = currentCalls.get(i);
            ToolExecutor.Outcome outcome = i < outcomes.size()
                    ? outcomes.get(i) : new ToolExecutor.Outcome();
            messages.add(LlmClient.AgentMessage.tool(call.id(), outcome.result));
            manager.log(taskId, "工具", "INFO", call.name() + " → " + outcome.log);
            photos.addAll(outcome.images);
            if (!outcome.captures.isEmpty()) {
                memory.captures.addAll(outcome.captures);
                manager.saveMemory(memory);
            }
            if (outcome.finish) {
                finishSummary = outcome.summary;
                finishTitle = outcome.title;
            }
        }
        syncDesired();
        if (!photos.isEmpty()) {
            // 只保留最近一次拍照的图片，避免历史里越积越多
            if (lastImageIndex >= 0 && lastImageIndex < messages.size()) {
                messages.set(lastImageIndex, LlmClient.AgentMessage.userText(
                        "[此前拍照的照片已从上下文省略，如需再看请重新调用 capture]"));
            }
            messages.add(LlmClient.AgentMessage.userWithImages(
                    "这是刚才 capture 工具拍的照片。照片只含本次建造的方块，"
                            + "天空与绿色草地是占位背景，不代表真实周边环境；"
                            + "请据此核对造型、比例、遮挡与细节，然后用工具修正或调用 finish_build。",
                    photos));
            lastImageIndex = messages.size() - 1;
        }
        if (finishSummary != null) {
            beginFlush(finishSummary, finishTitle);
            return;
        }
        if (round >= BuilderConfig.agentMaxRounds()) {
            forceFinish(memory, "达到最大工具轮数，自动归档");
            return;
        }
        nextRound();
    }

    /** 主线程每 tick 取走后台 IO 线程收到的思考片段，按 240 字切块写进 Agent 输出。 */
    private void drainThinking() {
        if (streamProgress == null) {
            return;
        }
        String chunk = streamProgress.drain();
        if (chunk.isEmpty()) {
            return;
        }
        if (!thinkingStarted) {
            manager.logThinkingChunk(taskId, "思考",
                    "【深度思考 · Agent 第 " + (round + 1) + " 轮 · 流式实时输出】");
            thinkingStarted = true;
        }
        // 与 logReasoning 一致：纯渲染分块，避免 GUI 逐字符折行 O(n²)，内容不丢
        for (int i = 0; i < chunk.length(); i += 240) {
            manager.logThinkingChunk(taskId, "思考",
                    chunk.substring(i, Math.min(chunk.length(), i + 240)));
        }
    }

    private void nextRound() {
        round++;
        phase = Phase.REQUEST;
        requestFuture = null;
    }

    /** 取 VoxelWorld 最新快照作为目标，重算与「真正已写入世界」之间的差量；未完成的差量保留重试。 */
    private void syncDesired() {
        Map<Long, String> snapshot = new HashMap<>();
        Map<Long, int[]> coords = new HashMap<>();
        for (VoxelWorld.Voxel v : world.all()) {
            long key = VoxelWorld.key(v.x(), v.y(), v.z());
            snapshot.put(key, v.fullState());
            coords.put(key, new int[]{v.x(), v.y(), v.z()});
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
                pendingChanges.remove(key);
                continue;
            }
            if (want != null) {
                int[] c = coords.get(key);
                pendingChanges.put(key, new Change(c[0], c[1], c[2], want));
            } else {
                pendingChanges.put(key, new Change(have.x(), have.y(), have.z(),
                        "minecraft:air"));
            }
        }
    }

    /** 逐 tick 同步：只有 setBlock 成功才记账；失败的差量留着下 tick 重试，绝不静默丢弃。 */
    private void applyWorldChanges(MinecraftServer server, BuildMemory memory) {
        if (pendingChanges.isEmpty() || server == null) {
            return;
        }
        ServerLevel level = resolveLevel(server, memory.dimension);
        if (level == null) {
            return;
        }
        int budget = Math.max(16, BuilderConfig.BLOCKS_PER_TICK.getAsInt());
        int processed = 0;
        List<Long> done = new ArrayList<>();
        for (Map.Entry<Long, Change> entry : pendingChanges.entrySet()) {
            if (processed >= budget) {
                break;
            }
            Change change = entry.getValue();
            if (!writeOne(level, change)) {
                continue;
            }
            if ("minecraft:air".equals(change.fullState())) {
                applied.remove(entry.getKey());
            } else {
                applied.put(entry.getKey(), change);
            }
            done.add(entry.getKey());
            processed++;
        }
        for (Long key : done) {
            pendingChanges.remove(key);
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
            // 状态串非法时退回基础方块，避免这一格永远卡在差量里
            int bracket = change.fullState().indexOf('[');
            String base = bracket > 0
                    ? change.fullState().substring(0, bracket) : change.fullState();
            state = BlockIndex.toState(base);
            if (state == null) {
                return false;
            }
        }
        level.setBlock(pos, state, 2);
        return true;
    }

    /** 归档前必须把差量全部写进世界，保证存档、照片与游戏世界三者一致。 */
    private void beginFlush(String summary, String title) {
        pendingFinishSummary = summary;
        pendingFinishTitle = title;
        phase = Phase.FLUSH;
        flushTicks = 0;
        manager.setStatus(taskId, "BUILDING", "同步世界（归档前）");
        manager.log(taskId, "Agent", "INFO", "等待 " + pendingChanges.size()
                + " 格差量写入世界后再归档，避免存档与游戏世界不一致");
    }

    private void doFlush(BuildMemory memory) {
        flushTicks++;
        if (!pendingChanges.isEmpty() && flushTicks < 400) {
            return;
        }
        if (!pendingChanges.isEmpty()) {
            manager.log(taskId, "Agent", "WARN", "仍有 " + pendingChanges.size()
                    + " 格差量未能写入世界，按超时归档；存档只记录真正写入的方块，下次修改会自动补齐");
        }
        archive(memory, pendingFinishSummary, pendingFinishTitle);
    }

    private void forceFinish(BuildMemory memory, String reason) {
        manager.log(taskId, "Agent", "WARN", reason + "；当前状态仍会完整归档");
        beginFlush(reason, "");
    }

    /** 超时兜底：存档以真正写入世界的 applied 为准，避免下次修改把没写入的方块当成已存在。 */
    private List<Placement> placementsFromApplied() {
        List<Placement> out = new ArrayList<>();
        for (Change change : applied.values()) {
            String full = change.fullState();
            int bracket = full.indexOf('[');
            if (bracket > 0 && full.endsWith("]")) {
                out.add(new Placement(change.x(), change.y(), change.z(),
                        full.substring(0, bracket),
                        full.substring(bracket + 1, full.length() - 1)));
            } else {
                out.add(new Placement(change.x(), change.y(), change.z(), full));
            }
        }
        return out;
    }

    private void archive(BuildMemory memory, String summary, String title) {
        List<Placement> placements = pendingChanges.isEmpty()
                ? world.placements() : placementsFromApplied();
        memory.version++;
        manager.savePlacements(taskId, memory.version, placements);
        memory.blocksPlacedTotal = placements.size();
        memory.materials = world.materials();
        memory.notes = title.isBlank() ? summary : title + "：" + summary;
        memory.status = "DONE";
        memory.stage = "完成";
        manager.markDirty(taskId);
        BuildMemory.VersionLine line = new BuildMemory.VersionLine();
        line.version = memory.version;
        line.ts = System.currentTimeMillis();
        line.instruction = brief;
        line.blocks = placements.size();
        line.digest = summary;
        memory.versions.add(line);
        manager.saveMemory(memory);
        manager.setStatus(taskId, "DONE", "完成");
        manager.log(taskId, "Agent", "INFO", "v" + memory.version + " 归档：" + world.size()
                + " 格方块，模型调用 " + memory.modelCalls + " 次，工具轮 " + round
                + "，拍照 " + (executor.hasCaptured() ? "已" : "未")
                + "完成，总耗时 " + (System.currentTimeMillis() - startedAt) / 1000 + "s；" + summary);
        manager.markDirty(taskId);
        phase = Phase.DONE;
        finished = true;
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
