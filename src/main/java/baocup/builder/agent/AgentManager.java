package baocup.builder.agent;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import baocup.builder.BuilderConfig;
import baocup.builder.BuilderMod;
import baocup.builder.item.BuildCommanderItem;
import baocup.builder.item.ExternalBuildWandItem;
import baocup.builder.item.ItemData;
import baocup.builder.memory.BuildMemory;
import baocup.builder.memory.MemoryStore;
import baocup.builder.memory.SelectionStore;
import baocup.builder.net.ModPackets;
import baocup.builder.util.Bounds;
import baocup.builder.util.Json;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * 服务端唯一的 Agent 调度中心：记忆、任务队列、Plan 对话、状态推送、磁盘持久化。
 * 每个建造物品只带一个 taskId，所有历史都挂在这个 id 对应的 BuildMemory 上。
 */
public final class AgentManager {
    private static final AgentManager INSTANCE = new AgentManager();
    /** 阶段规划要附带的照片在后台线程读盘 / 渲染，绝不阻塞服务端 tick。 */
    private static final ExecutorService IMAGE_POOL = Executors.newFixedThreadPool(2, runnable -> {
        Thread thread = new Thread(runnable, "builder-vision-images");
        thread.setDaemon(true);
        return thread;
    });

    private final Map<String, BuildMemory> memories = new LinkedHashMap<>();
    /** 客户端提交的方块批次队列（外部 Agent 链路）。 */
    private final Map<String, CommitJob> commitJobs = new LinkedHashMap<>();
    private final Map<String, AgentJob> jobs = new LinkedHashMap<>();
    /** 真正的 tool calling Agent；关闭工具模式时才回退到 jobs 里的旧固定阶段链路。 */
    private final Map<String, BuildAgent> buildAgents = new LinkedHashMap<>();
    private final Map<String, CompletableFuture<LlmClient.Result>> planFutures = new LinkedHashMap<>();
    private final Map<String, CompletableFuture<LlmClient.Result>> semanticFutures = new LinkedHashMap<>();
    private final Map<String, VisualJob> visualJobs = new LinkedHashMap<>();
    private final Set<String> semanticCorrected = new HashSet<>();
    private final Map<String, Set<UUID>> watchers = new LinkedHashMap<>();
    private final Map<String, Long> lastOpen = new HashMap<>();
    private final Map<UUID, Long> lastSelect = new HashMap<>();
    private final Set<String> dirtyStates = new LinkedHashSet<>();
    private final SelectionStore selections = new SelectionStore();
    private final LlmClient llm = new LlmClient();
    /** MCP 桥：外部 Agent 通过 tool calling 直接建造，不经过 LlmClient。 */
    private final McpBridge mcpBridge = new McpBridge(this);

    private MinecraftServer server;
    private MemoryStore store;
    private Path gameDir;
    private int tickCounter;

    private AgentManager() {
    }

    public static AgentManager get() {
        return INSTANCE;
    }

    public static void init(MinecraftServer minecraftServer) {
        AgentManager m = INSTANCE;
        m.server = minecraftServer;
        m.gameDir = minecraftServer.getServerDirectory().toAbsolutePath();
        m.store = new MemoryStore(m.gameDir);
        m.store.init();
        m.memories.clear();
        m.memories.putAll(m.store.loadAll());
        m.jobs.clear();
        m.buildAgents.clear();
        m.planFutures.clear();
        m.semanticFutures.clear();
        m.visualJobs.clear();
        m.semanticCorrected.clear();
        m.watchers.clear();
        m.dirtyStates.clear();
        m.selections.load(m.store.loadSelections());
        BlockIndex.get().loadExternal(m.gameDir);
        BuilderMod.LOGGER.info("[建造] 记忆已从 {} 恢复，共 {} 个建造任务",
                m.store.root(), m.memories.size());
        BuilderMod.LOGGER.info("[建造] LLM 限额：输出 {}，输入预算 {} token，深度思考强制开启（强度 {}）",
                BuilderConfig.outputTokenLimit() == 0 ? "不设限" : BuilderConfig.outputTokenLimit() + " token",
                BuilderConfig.maxInputTokens(),
                BuilderConfig.reasoningEffort().isBlank() ? "默认" : BuilderConfig.reasoningEffort());
        selfCheck();
        // 外部 Agent 的 MCP 接口已下沉到客户端（ClientBuildHost）：每个客户端独立端口，
        // 几何编译 / VoxelWorld / 拍照渲染都在本地，服务端只负责接收方块提交。
        BuilderMod.LOGGER.info("[建造] 外部 Agent 接口在客户端开放，服务端只接收 C2SCommit 方块提交");
    }

    /** 启动自检：方案 JSON 往返、离线多阶段展开、构件状态/成组、实体幂等标识、执行排序。 */
    private static void selfCheck() {
        try {
            Bounds probe = new Bounds(0, 64, 0, 15, 72, 15);
            BuildPlan plan = BuildPlan.fallback(probe, "启动自检");
            BuildPlan roundTrip = BuildPlan.parse(plan.toJson());
            int blocks = PlanCompiler.compile(roundTrip, probe, 120000).blocks.size();
            BuilderMod.LOGGER.info("[建造] 几何编译器自检通过：{} 个区域展开 {} 个方块（0 次模型调用）",
                    roundTrip.zones.size(), blocks);

            BuildPlan cumulative = new BuildPlan();
            PlanCompiler.Compilation compilation = null;
            List<BuildPlan> stages = BuildPlan.fallbackStages(probe, "启动自检", Prompts.stages(false).size());
            for (BuildPlan stage : stages) {
                stage.validate(probe, BlockIndex.get(), new ArrayList<>());
                cumulative.palette.putAll(stage.palette);
                cumulative.zones.addAll(stage.zones);
                compilation = PlanCompiler.compile(cumulative, probe, 120000);
            }
            int withState = 0;
            int resolvable = 0;
            int doorHalves = 0;
            int bedHalves = 0;
            for (Placement placement : compilation.blocks) {
                if (!placement.state.isBlank()) {
                    withState++;
                    if (BlockIndex.toState(placement.fullState()) != null) resolvable++;
                }
                if (placement.block.endsWith("_door") && placement.state.contains("half=")) doorHalves++;
                if (placement.block.endsWith("_bed") && placement.state.contains("part=")) bedHalves++;
            }
            boolean entityOk = !compilation.entities.isEmpty()
                    && !NbtEntityOps.entityId(compilation.entities.get(0)).isBlank();
            PlanCompiler.Batch batch = PlanCompiler.orderForExecution(compilation.blocks);
            SceneFact probeFact = FactExtractor.extract(0, probe,
                    compilation.blocks, cumulative, compilation.entities);
            BuilderMod.LOGGER.info("[建造] 离线多阶段自检通过：{} 个阶段，累计 {} 个区域，最终 {} 格",
                    stages.size(), cumulative.zones.size(), compilation.blocks.size());
            BuilderMod.LOGGER.info("[建造] 构件自检：带状态 {} 格（可解析 {} 格），门 {} 半 / 床 {} 半，"
                            + "实体 {} 个（幂等标识{}），成组排序 {} 格",
                    withState, resolvable, doorHalves, bedHalves, compilation.entities.size(),
                    entityOk ? "正常" : "异常", batch.ordered.size());
            BuilderMod.LOGGER.info("[建造] 事实层自检：{} 层 / {} 个区域 / {} 个构件，坐标索引 {} 格",
                    probeFact.levels.size(), probeFact.rooms.size(), probeFact.features.size(),
                    probeFact.index.size());

            // 重力方块描述自检
            boolean sandNoted = !BlockIndex.physicsOf("minecraft:sand").isEmpty();
            boolean sandstoneClean = BlockIndex.physicsOf("minecraft:sandstone").isEmpty();
            String sandLine = BlockIndex.compactLine(BlockIndex.get().get("minecraft:sand"));
            BuilderMod.LOGGER.info("[建造] 重力方块自检：沙子有说明={} / 砂岩无误标={} / 候选说明含物理={}",
                    sandNoted, sandstoneClean, sandLine.contains("受重力影响"));

            // 独立相机离屏渲染自检（专用服务器也能跑，验证不依赖玩家屏幕）
            Map<Long, Placement> probeVoxels = new HashMap<>();
            for (Placement placement : compilation.blocks) {
                probeVoxels.put(placement.key(), placement);
            }
            List<CameraPose> probeCameras = CameraRig.around(probe, 4);
            VisualRenderer.Frame frame = VisualRenderer.render(
                    probeCameras.get(0), probeVoxels, probe, 96, 64);
            BuilderMod.LOGGER.info("[建造] 离屏相机自检：{} 台机位，PNG {} 字节，建筑像素 {} / 天空像素 {}",
                    probeCameras.size(), frame.png.length, frame.blockPixels, frame.skyPixels);

            // 完整拍照链路自检：写盘 PNG + 生成报告，用完立即清理临时目录
            Path temp = Files.createTempDirectory("builder-visual-check");
            try {
                VisualInspection.Batch visualBatch = VisualInspection.capture("_selftest", 0, probe,
                        compilation.blocks, probeCameras.subList(0, 2), temp, 64, 40);
                BuilderMod.LOGGER.info("[建造] 视觉检查自检：写盘 {} 张 PNG，报告 {} 字",
                        visualBatch.images.size(), visualBatch.localReport.length());
            } finally {
                try (var paths = Files.list(temp)) {
                    for (Path file : paths.toList()) {
                        Files.deleteIfExists(file);
                    }
                } catch (Exception ignored) {
                    // 临时文件清理失败不影响启动
                }
                Files.deleteIfExists(temp);
            }
        } catch (Exception e) {
            BuilderMod.LOGGER.warn("[建造] 几何编译器自检失败：{}", e.toString());
        }
    }

    public static void shutdown() {
        AgentManager m = INSTANCE;
        if (m.store != null) {
            for (BuildMemory memory : m.memories.values()) {
                m.store.save(memory);
            }
            m.store.saveSelections(m.selections.toJson());
            BuilderMod.LOGGER.info("[建造] 已保存 {} 份记忆与玩家选区", m.memories.size());
        }
        for (CompletableFuture<LlmClient.Result> future : m.planFutures.values()) {
            future.cancel(true);
        }
        for (CompletableFuture<LlmClient.Result> future : m.semanticFutures.values()) {
            future.cancel(true);
        }
        m.planFutures.clear();
        m.semanticFutures.clear();
        m.visualJobs.clear();
        m.semanticCorrected.clear();
        m.buildAgents.clear();
        m.jobs.clear();
        m.commitJobs.clear();
        m.dirtyStates.clear();
        m.mcpBridge.shutdown();
        m.server = null;
    }

    // ------------------------------------------------------------------
    // 记忆与选区
    // ------------------------------------------------------------------

    public BuildMemory memory(String taskId) {
        return taskId == null ? null : memories.get(taskId);
    }

    public BuildMemory peekMemory(String taskId) {
        return memory(taskId);
    }

    /** 技能指令未指定 taskId 时，选最近更新且有选区的任务。 */
    public BuildMemory latestBuildableMemory() {
        BuildMemory best = null;
        for (BuildMemory m : memories.values()) {
            if (m.hasBounds && m.bounds != null
                    && (best == null || m.updatedAt > best.updatedAt)) {
                best = m;
            }
        }
        return best;
    }

    /** 三条建造链路互斥，避免同一个任务被两个执行器同时写世界。 */
    public boolean hasActiveBuild(String taskId) {
        return jobs.containsKey(taskId) || buildAgents.containsKey(taskId)
                || mcpBridge.isActive(taskId);
    }

    /** 全部任务记忆（按创建顺序），供 MCP 的 list_tasks 使用。 */
    public java.util.Collection<BuildMemory> allMemories() {
        return memories.values();
    }

    public MinecraftServer serverOrNull() {
        return server;
    }

    /** /builder approve [taskId]：批准待处理的坐标请求。 */
    public String approveBounds(ServerPlayer player, String taskId) {
        return mcpBridge.approve(player, taskId);
    }

    /** /builder deny [taskId]：拒绝待处理的坐标请求。 */
    public String denyBounds(ServerPlayer player, String taskId) {
        return mcpBridge.deny(player, taskId);
    }

    public String mcpStatus() {
        return mcpBridge.status();
    }

    public void setSelection(Player player, Bounds bounds, String dimension) {
        selections.set(player.getUUID(), bounds, dimension);
        if (store != null) {
            store.saveSelections(selections.toJson());
        }
    }

    private BuildMemory createMemory(ServerPlayer player, String taskId, String mode) {
        BuildMemory existing = memories.get(taskId);
        if (existing != null) {
            return existing;
        }
        BuildMemory m = new BuildMemory();
        m.taskId = taskId;
        m.linkMode = mode == null || mode.isBlank() ? "internal" : mode;
        m.ownerUuid = player.getUUID().toString();
        m.ownerName = player.getGameProfile().getName();
        m.dimension = player.serverLevel().dimension().location().toString();
        SelectionStore.Entry selection = selections.get(player.getUUID());
        if (selection != null && selection.bounds != null && selection.bounds.length == 6) {
            m.bounds = Bounds.fromArray(selection.bounds);
            m.dimension = selection.dimension == null ? m.dimension : selection.dimension;
            m.hasBounds = true;
        }
        memories.put(taskId, m);
        log(taskId, "记忆", "INFO", "已为本物品创建独立记忆 " + taskId
                + (m.hasBounds ? "，自动载入已选范围 " + m.bounds.display() : "，尚未选择范围"));
        saveMemory(m);
        return m;
    }

    public void saveMemory(BuildMemory m) {
        if (m != null && store != null) {
            store.save(m);
        }
    }

    /**
     * 把任务摘要回写到建造指令物品上。物品只携带 UUID 指针，摘要纯粹用于客户端 tooltip，
     * 因此即使连到别人的专用服务器，玩家也能看到当前版本 / 模型调用次数。
     */
    public void updateItemPointer(BuildMemory m) {
        if (server == null || m == null || m.ownerUuid.isBlank()) {
            return;
        }
        ServerPlayer player;
        try {
            player = server.getPlayerList().getPlayer(UUID.fromString(m.ownerUuid));
        } catch (Exception e) {
            return;
        }
        if (player == null) {
            return;
        }
        String brief = m.userBrief == null ? "" : m.userBrief;
        if (brief.length() > 60) {
            brief = brief.substring(0, 60) + "…";
        }
        final String shortBrief = brief;
        for (int i = 0; i < player.getInventory().getContainerSize(); i++) {
            ItemStack stack = player.getInventory().getItem(i);
            if (!(stack.getItem() instanceof BuildCommanderItem) || !m.taskId.equals(ItemData.taskId(stack))) {
                continue;
            }
            ItemData.update(stack, tag -> {
                tag.putString("ptr_status", m.status == null ? "IDLE" : m.status);
                tag.putInt("ptr_version", m.version);
                tag.putInt("ptr_calls", m.modelCalls);
                tag.putLong("ptr_blocks", m.blocksPlacedTotal);
                tag.putString("ptr_brief", shortBrief);
            });
            return;
        }
    }

    public void savePlacements(String taskId, int version, List<Placement> placements) {
        if (store != null && BuilderConfig.SAVE_PLACEMENTS.getAsBoolean()) {
            store.savePlacements(taskId, version, placements);
        }
    }

    public List<Placement> loadPlacements(String taskId, int version) {
        return store == null ? new ArrayList<>() : store.loadPlacements(taskId, version);
    }

    // ------------------------------------------------------------------
    // 日志 / 状态 / 统计
    // ------------------------------------------------------------------

    public void log(String taskId, String stage, String level, String text) {
        appendLog(taskId, stage, level, text, true);
    }

    private void appendLog(String taskId, String stage, String level, String text, boolean echo) {
        BuildMemory m = memory(taskId);
        if (m == null) {
            return;
        }
        m.addLog(stage, level, text);
        m.touch();
        markDirty(taskId);
        BuilderMod.LOGGER.info("[建造][{}][{}] {}", taskId.substring(0, Math.min(8, taskId.length())), stage, text);
        if (echo && BuilderConfig.ECHO_TO_CHAT.getAsBoolean() && server != null && !m.ownerUuid.isBlank()) {
            try {
                ServerPlayer player = server.getPlayerList().getPlayer(UUID.fromString(m.ownerUuid));
                if (player != null) {
                    player.sendSystemMessage(Component.literal("§b[Agent·" + stage + "] §f" + text));
                }
            } catch (Exception ignored) {
                // 玩家离线
            }
        }
    }

    /**
     * 把深度思考内容完整写进 Agent 过程日志，不设字符上限、不丢弃任何一段。
     * 只按 240 字切成显示块（纯渲染分块，内容 100% 完整，否则 GUI 逐字符折行会 O(n²) 卡住）。
     */
    public void logReasoning(String taskId, String stage, String reasoning) {
        if (reasoning == null || reasoning.isBlank()) {
            return;
        }
        appendLog(taskId, "思考", "INFO",
                "【深度思考 · " + stage + "】共 " + reasoning.length() + " 字，完整记录", false);
        int chunk = 240;
        for (int i = 0; i < reasoning.length(); i += chunk) {
            appendLog(taskId, "思考", "INFO",
                    reasoning.substring(i, Math.min(reasoning.length(), i + chunk)), false);
        }
        appendLog(taskId, "思考", "INFO", "【深度思考结束】", false);
    }

    public void setStatus(String taskId, String status, String stage) {
        BuildMemory m = memory(taskId);
        if (m == null) {
            return;
        }
        m.status = status;
        m.stage = stage;
        markDirty(taskId);
    }

    public void markDirty(String taskId) {
        BuildMemory m = memory(taskId);
        if (m != null) {
            m.dirty = true;
        }
        dirtyStates.add(taskId);
    }

    public void recordTokens(String taskId, LlmClient.Result result) {
        BuildMemory m = memory(taskId);
        if (m == null || result == null) {
            return;
        }
        m.promptTokens += Math.max(0, result.promptTokens());
        m.completionTokens += Math.max(0, result.completionTokens());
        markDirty(taskId);
    }

    private void countModelCall(String taskId, String purpose) {
        BuildMemory m = memory(taskId);
        if (m == null) {
            return;
        }
        m.modelCalls++;
        log(taskId, "模型", "INFO", "第 " + m.modelCalls + " 次模型调用（" + purpose + "），模型 "
                + BuilderConfig.model());
        markDirty(taskId);
    }

    /**
     * 每个施工阶段单独提交一次模型调用。是否要在阶段结束后拍照复查，
     * 由模型在本轮 JSON 里用 inspectAfterStage 自己声明，系统不再固定给每轮塞照片。
     */
    public CompletableFuture<LlmClient.Result> submitStage(String taskId, String stageName,
                                                           String system, String user) {
        countModelCall(taskId, "阶段规划「" + stageName + "」");
        return llm.chat(system, user);
    }

    /** 整栋完成后发起 1 次语义场景图调用；离线模式跳过，但事实层照常存在。 */
    public void requestSemantic(String taskId, SceneFact fact, String planDocument) {
        if (!BuilderConfig.SEMANTIC_ENABLED.getAsBoolean()) {
            return;
        }
        if (!llm.isConfigured()) {
            log(taskId, "语义层", "INFO", "离线模式跳过语义场景图，事实层仍完整可用");
            return;
        }
        if (semanticFutures.containsKey(taskId)) {
            return;
        }
        log(taskId, "语义层", "INFO", "整栋完成，发起 1 次语义场景图调用（房间用途 / 关系 / 设计意图）");
        countModelCall(taskId, "语义场景图");
        semanticFutures.put(taskId, llm.chat(Prompts.SEMANTIC_SYSTEM,
                Prompts.semanticUser(fact, planDocument)));
    }

    private void finishSemantic(String taskId, LlmClient.Result result) {
        BuildMemory m = memory(taskId);
        if (m == null) {
            return;
        }
        recordTokens(taskId, result);
        logReasoning(taskId, "语义层", result.reasoning());
        if (!result.ok()) {
            log(taskId, "语义层", "WARN", "语义场景图调用失败（" + result.error()
                    + "），保留事实层，不触发修正");
            return;
        }
        try {
            SceneSemantic semantic = SceneSemantic.parse(result.content());
            semantic.version = m.version;
            semantic.generatedAt = System.currentTimeMillis();
            m.semantic = semantic;
            CrossCheckResult check = CrossCheck.compare(m.fact, semantic, m.version);
            m.crossCheck = check;
            CrossCheck.applyRoomTags(m.fact, semantic);
            saveMemory(m);
            log(taskId, "互校", "INFO", check.verdict + "：事实独有 " + check.factOnly
                    + " / 语义独有 " + check.semanticOnly + " / 属性冲突 " + check.conflicts
                    + "（以事实层为准，语义层只补标签）");
            for (String issue : check.issues) {
                log(taskId, "互校", "WARN", issue);
            }
            markDirty(taskId);
            // 最多 1 次修正型调用，且必须显式开启，绝不循环重试
            if (BuilderConfig.SEMANTIC_CORRECTION.getAsBoolean()
                    && (check.conflicts + check.semanticOnly) > 0
                    && semanticCorrected.add(taskId)) {
                log(taskId, "互校", "INFO", "按互校差异发起 1 次语义层修正调用");
                countModelCall(taskId, "语义层修正");
                semanticFutures.put(taskId, llm.chat(Prompts.SEMANTIC_SYSTEM,
                        Prompts.semanticUser(m.fact, m.planDocument)
                                + "\n\n上次互校差异（请据此修正，不要编造房间）：\n" + check.buildSummary()));
            }
        } catch (Exception e) {
            log(taskId, "语义层", "WARN", "语义层解析失败：" + e.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // 视觉检查：独立虚拟相机 / 离屏渲染 / 视觉模型 / 相机标记实体
    // ------------------------------------------------------------------

    /** 一次施工中途检查的产物：照片批次 + 模型看完照片后给出的审查结果。 */
    public record StageInspection(VisualInspection.Batch batch, LlmClient.Result result) {
    }

    /**
     * 模型在某一轮主动请求的中途检查：拍当前已放置建筑的高清照片，交给同一个模型审查。
     * 渲染在后台线程，服务端主线程只轮询 future；结论由 AgentJob 在主线程写回记忆，
     * 从而完整注入下一阶段的 structuredMemory。
     */
    public CompletableFuture<AgentManager.StageInspection> submitStageInspection(
            String taskId, String stageName, BuildMemory memory,
            List<Placement> current, int stageIndex) {
        int views = BuilderConfig.visionInspectionImages();
        Bounds bounds = memory.bounds == null
                ? new Bounds(0, 64, 0, 15, 72, 15) : memory.bounds.copy();
        List<CameraPose> poses = CameraRig.representative(bounds, views);
        int width = BuilderConfig.VISUAL_WIDTH.getAsInt();
        int height = BuilderConfig.VISUAL_HEIGHT.getAsInt();
        int version = memory.version;
        Path dir = captureDir(taskId);
        String prefix = "v" + (version + 1) + "-s" + (stageIndex + 1);
        String user = Prompts.stageInspectionUser(memory, stageName, stageIndex, poses);
        countModelCall(taskId, "过程视觉检查「" + stageName + "」");
        log(taskId, "视觉检查", "INFO", "模型主动请求在「" + stageName + "」后拍照复查，从 "
                + poses.size() + " 台机位渲染 " + width + "×" + height + " 高清照片");

        CompletableFuture<VisualInspection.Batch> capture = CompletableFuture
                .supplyAsync(() -> VisualInspection.capture(taskId, version + 1, bounds, current,
                        poses, dir, width, height, prefix), IMAGE_POOL)
                .exceptionally(e -> {
                    BuilderMod.LOGGER.warn("[建造] 过程拍照失败：{}", e.toString());
                    return new VisualInspection.Batch();
                });
        return capture.thenCompose(batch -> {
            if (batch.images.isEmpty()) {
                return CompletableFuture.completedFuture(new StageInspection(batch,
                        LlmClient.Result.fail("过程拍照没有可用照片")));
            }
            return llm.chatWithImages(Prompts.STAGE_INSPECTION_SYSTEM, user,
                            VisualInspection.pickImages(batch.images, BuilderConfig.visionMaxImages()))
                    .thenApply(result -> new StageInspection(batch, result));
        });
    }

    /** 主线程把中途检查的照片元数据与审查结论写回记忆，供下一阶段规划与 GUI 使用。 */
    public void applyStageInspection(String taskId, BuildMemory memory, String stageName,
                                     StageInspection inspection) {
        VisualInspection.Batch batch = inspection.batch();
        LlmClient.Result result = inspection.result();
        memory.captures.addAll(batch.lines);
        recordTokens(taskId, result);
        logReasoning(taskId, "过程视觉检查 · " + stageName, result.reasoning());
        if (!result.ok()) {
            log(taskId, "视觉检查", "WARN", "「" + stageName + "」中途检查调用失败（"
                    + result.error() + "）；照片与本地统计已保存");
            saveMemory(memory);
            markDirty(taskId);
            return;
        }
        String previous = memory.visualReport == null ? "" : memory.visualReport;
        StringBuilder text = new StringBuilder(previous);
        if (!previous.isBlank()) {
            text.append('\n');
        }
        text.append(batch.localReport);
        text.append("\n【过程视觉检查 · ").append(stageName).append(" 施工后】\n")
                .append(result.content()).append('\n');
        memory.visualReport = text.toString();
        log(taskId, "视觉检查", "INFO", "「" + stageName + "」中途检查报告（完整原文）：\n"
                + result.content());
        String json = LlmClient.extractJson(result.content());
        if (json != null) {
            try {
                JsonObject root = Json.GSON.fromJson(json, JsonObject.class);
                JsonArray issues = root == null ? null : root.getAsJsonArray("issues");
                if (issues != null) {
                    for (JsonElement element : issues) {
                        String issue = element.getAsString();
                        memory.visualIssues.add("[" + stageName + "] " + issue);
                        log(taskId, "视觉检查", "WARN", "中途检查发现问题：" + issue);
                    }
                }
            } catch (Exception ignored) {
                // 模型没按 schema 输出时，完整原文也已保存
            }
        }
        saveMemory(memory);
        markDirty(taskId);
    }

    /** 供离屏渲染用的方块图：优先当前版本保存的完整方块图，其次从累计方案重新编译。 */
    public List<Placement> visualVoxels(BuildMemory memory) {
        List<Placement> saved = loadPlacements(memory.taskId, memory.version);
        if (!saved.isEmpty()) {
            return saved;
        }
        if (memory.currentPlanJson != null && !memory.currentPlanJson.isBlank()) {
            try {
                BuildPlan plan = BuildPlan.parse(memory.currentPlanJson);
                Bounds bounds = memory.bounds == null
                        ? new Bounds(0, 64, 0, 15, 72, 15) : memory.bounds;
                return PlanCompiler.compile(plan, bounds,
                        BuilderConfig.MAX_BLOCKS_PER_TASK.getAsInt()).blocks;
            } catch (Exception ignored) {
                // 方案损坏时按空场景渲染
            }
        }
        return new ArrayList<>();
    }

    public java.nio.file.Path captureDir(String taskId) {
        return store == null
                ? java.nio.file.Path.of("config/builder/memory/captures", taskId)
                : store.captureDir(taskId);
    }

    public CompletableFuture<LlmClient.Result> submitVision(String taskId, String system,
                                                            String user, List<byte[]> images) {
        List<byte[]> picked = VisualInspection.pickImages(images, BuilderConfig.visionMaxImages());
        countModelCall(taskId, "视觉审查（" + picked.size() + " 张高清照片）");
        return llm.chatWithImages(system, user, picked);
    }

    /** 在世界中放置隐形相机标记（带可见名称），用稳定 UUID 做幂等替换。 */
    public void refreshCameraMarkers(BuildMemory memory, List<CameraPose> poses) {
        ServerLevel level = serverLevel(memory.dimension);
        if (level == null) {
            return;
        }
        for (String saved : memory.cameraMarkers) {
            try {
                net.minecraft.world.entity.Entity old = level.getEntity(UUID.fromString(saved));
                if (old != null) {
                    old.discard();
                }
            } catch (Exception ignored) {
                // UUID 非法时忽略
            }
        }
        memory.cameraMarkers.clear();
        if (BuilderConfig.CAMERA_MARKERS.getAsBoolean()) {
            int count = 0;
            for (CameraPose pose : poses) {
                if (count++ >= 16) {
                    break;
                }
                try {
                    UUID uuid = UUID.nameUUIDFromBytes(
                            (memory.taskId + ":" + pose.id).getBytes(StandardCharsets.UTF_8));
                    net.minecraft.world.entity.Entity existing = level.getEntity(uuid);
                    if (existing != null) {
                        existing.discard();
                    }
                    ArmorStand camera = new ArmorStand(level, pose.x, pose.y, pose.z);
                    camera.setUUID(uuid);
                    camera.setInvisible(true);
                    camera.setNoGravity(true);
                    camera.setCustomName(Component.literal("§b[相机] " + pose.name));
                    camera.setCustomNameVisible(true);
                    level.addFreshEntity(camera);
                    memory.cameraMarkers.add(uuid.toString());
                } catch (Exception e) {
                    BuilderMod.LOGGER.warn("[建造] 相机标记 {} 放置失败：{}", pose.id, e.getMessage());
                }
            }
            log(memory.taskId, "视觉检查", "INFO", "已在建筑周围布置 " + memory.cameraMarkers.size()
                    + " 个隐形相机标记（可见名称，供玩家与 AI 定位视角）");
        }
        saveMemory(memory);
        markDirty(memory.taskId);
    }

    private ServerLevel serverLevel(String dimension) {
        if (server == null) {
            return null;
        }
        try {
            ResourceKey<Level> key = ResourceKey.create(Registries.DIMENSION,
                    ResourceLocation.parse(dimension));
            ServerLevel level = server.getLevel(key);
            return level == null ? server.overworld() : level;
        } catch (Exception e) {
            return server.overworld();
        }
    }

    /**
     * 建造完成后的强制视觉检查：只要视觉功能启用，必定拍一组高清照片并交给模型审查一遍。
     * 这一步不由模型决定，也不受中途检查次数影响；施工中途的检查结论会保留在报告前面。
     */
    public void scheduleFinalInspection(String taskId) {
        if (!BuilderConfig.VISUAL_ENABLED.getAsBoolean()
                || !BuilderConfig.visualFinalInspection()) {
            return;
        }
        BuildMemory memory = memory(taskId);
        if (memory == null || !memory.hasBounds || memory.bounds == null) {
            return;
        }
        if (visualJobs.containsKey(taskId)) {
            log(taskId, "视觉检查", "INFO", "已有手动视觉检查在执行，完成后的强制检查与之合并为同一次");
            return;
        }
        boolean analyze = llm.isConfigured();
        visualJobs.put(taskId, new VisualJob(taskId, analyze, false, null, memory));
        log(taskId, "视觉检查", "INFO", "建造完成，执行强制视觉检查：拍一组 "
                + BuilderConfig.VISUAL_WIDTH.getAsInt() + "×" + BuilderConfig.VISUAL_HEIGHT.getAsInt()
                + " 高清照片" + (analyze
                        ? "，并交给 " + BuilderConfig.model() + " 审查一遍"
                        : " 留档（离线模式无法调用模型）"));
    }

    private void startVisual(BuildMemory memory, boolean analyze, CameraPose customPose) {
        if (!BuilderConfig.VISUAL_ENABLED.getAsBoolean()) {
            log(memory.taskId, "视觉检查", "WARN", "视觉检查已在配置中关闭（visualEnabled=false）");
            return;
        }
        if (!memory.hasBounds || memory.bounds == null) {
            log(memory.taskId, "视觉检查", "WARN", "请先用建造物品左键两次选定范围，或手动填写坐标");
            return;
        }
        if (visualJobs.containsKey(memory.taskId)) {
            log(memory.taskId, "视觉检查", "WARN", "已有视觉检查任务在执行中");
            return;
        }
        memory.visualReport = "";
        memory.visualIssues.clear();
        visualJobs.put(memory.taskId,
                new VisualJob(memory.taskId, analyze, analyze, customPose, memory));
        log(memory.taskId, "视觉检查", "INFO", (analyze ? "拍照并交给视觉模型审查" : "仅拍照")
                + (customPose == null ? "（建筑周围环绕机位）" : "（自定义机位）"));
    }

    // ------------------------------------------------------------------
    // GUI / 网络
    // ------------------------------------------------------------------

    public void openGui(ServerPlayer player, String taskId, String mode) {
        if (taskId == null || taskId.isBlank()) {
            return;
        }
        // 一次右键在服务端可能同时触发 useOn 与 use，去重避免重复重建 GUI
        String key = player.getUUID() + ":" + taskId;
        long now = System.currentTimeMillis();
        if (now - lastOpen.getOrDefault(key, 0L) < 250L) {
            return;
        }
        lastOpen.put(key, now);
        BuildMemory m = createMemory(player, taskId, mode);
        watchers.computeIfAbsent(taskId, k -> new LinkedHashSet<>()).add(player.getUUID());
        log(taskId, "GUI", "INFO", "打开建造面板（任务指针 " + taskId + "，版本 v" + m.version + "）");
        pushState(m, true, player);
    }

    private void pushState(BuildMemory m, boolean open, ServerPlayer only) {
        if (m == null || server == null) {
            return;
        }
        String planText = planDisplay(m);
        String json = Json.GSON.toJson(Prompts.stateJson(m, planText));
        // 唯一保留的限制是 Minecraft 自定义包的物理上限。超限时按「整条」跳过最旧记录，
        // 绝不裁剪任何单条文本；服务端磁盘记忆始终保存全量，重启后可完整恢复。
        int logOffset = 0;
        int chatOffset = 0;
        int logStep = Math.max(1, m.logs.size() / 8);
        int chatStep = Math.max(1, m.chat.size() / 8);
        int guard = 0;
        while (json.getBytes(StandardCharsets.UTF_8).length > ModPackets.MAX_JSON && guard++ < 32) {
            if (logOffset < m.logs.size()) {
                logOffset = Math.min(m.logs.size(), logOffset + logStep);
            } else if (chatOffset < m.chat.size()) {
                chatOffset = Math.min(m.chat.size(), chatOffset + chatStep);
            } else {
                break;
            }
            json = Json.GSON.toJson(Prompts.stateJson(m, planText, logOffset, chatOffset));
        }
        if (logOffset > 0 || chatOffset > 0) {
            BuilderMod.LOGGER.warn("[建造] 状态快照超过协议包上限，按整条跳过最旧 {} 条日志 / {} 条对话；"
                            + "单条内容未截断，磁盘记忆仍是全量",
                    logOffset, chatOffset);
        }
        ModPackets.S2CState packet = new ModPackets.S2CState(m.taskId, open, json);
        if (only != null) {
            PacketDistributor.sendToPlayer(only, packet);
            return;
        }
        Set<UUID> targets = watchers.get(m.taskId);
        if (targets == null) {
            return;
        }
        for (UUID uuid : targets) {
            ServerPlayer player = server.getPlayerList().getPlayer(uuid);
            if (player != null) {
                PacketDistributor.sendToPlayer(player, packet);
            }
        }
    }

    /** 给 GUI 看的始终是中文可读文字，不把参数化 JSON / 坐标表摊给用户。 */
    private String planDisplay(BuildMemory m) {
        StringBuilder sb = new StringBuilder();
        if (!m.planDocument.isBlank()) {
            sb.append("【Plan 对话确认的《建造计划》】\n").append(m.planDocument).append('\n');
        }
        sb.append("\n【当前建筑状态】\n");
        sb.append("版本 v").append(m.version).append("，");
        sb.append(m.zones.isEmpty() ? "尚无施工区域" : "共 " + m.zones.size() + " 个施工区域");
        sb.append('\n');
        sb.append("材质用量：").append(m.materials.isEmpty() ? "（无）" : "");
        for (Map.Entry<String, Integer> e : m.materials.entrySet()) {
            sb.append(e.getKey().replace("minecraft:", "")).append('×').append(e.getValue()).append(' ');
        }
        sb.append('\n');
        if (!m.userBrief.isBlank()) {
            sb.append("最近目标：").append(m.userBrief).append('\n');
        }
        if (!m.notes.isBlank()) {
            sb.append("设计说明：").append(m.notes).append('\n');
        }
        if (m.planDocument.isBlank() && m.currentPlanJson.isBlank()) {
            sb.append("\n还没有计划。可以在「建造」页直接写一句需求开始分阶段施工，"
                    + "或进入 Plan 对话模式先让 Agent 写一份可读的《建造计划》。");
        }
        return sb.toString();
    }

    public void handleAction(Player player, ModPackets.C2SAction payload) {
        if (!(player instanceof ServerPlayer sp) || payload == null || server == null) {
            return;
        }
        String action = payload.action();
        String data = payload.data() == null ? "" : payload.data();
        // 左键选点：taskId 可能为空（客户端不生成 UUID），由服务端从手持物品权威解析
        if ("select_point".equals(action)) {
            selectPoint(sp);
            return;
        }
        String taskId = payload.taskId();
        if (taskId == null || taskId.isBlank() || taskId.length() > 100) {
            return;
        }
        BuildMemory m = memories.get(taskId);
        if ("sync".equals(action) || "close".equals(action)) {
            if (m == null) {
                m = createMemory(sp, taskId, heldLinkMode(sp));
            }
            if ("close".equals(action)) {
                Set<UUID> set = watchers.get(taskId);
                if (set != null) set.remove(sp.getUUID());
                return;
            }
            watchers.computeIfAbsent(taskId, k -> new LinkedHashSet<>()).add(sp.getUUID());
            pushState(m, false, sp);
            return;
        }
        if (!authorized(sp, taskId)) {
            sp.sendSystemMessage(Component.literal("§c[建造] 该物品没有绑定此任务，操作已拒绝"));
            return;
        }
        if (m == null) {
            m = createMemory(sp, taskId, heldLinkMode(sp));
        }
        try {
            switch (action) {
                case "bounds" -> applyBounds(sp, m, data);
                case "build" -> startBuild(sp, m, data);
                case "plan_open" -> {
                    m.planMode = true;
                    log(taskId, "Plan", "INFO", "进入对话 Plan 模式：只讨论方案，不会立即建造");
                    setStatus(taskId, m.status, "Plan 对话");
                }
                case "plan_exit" -> {
                    m.planMode = false;
                    log(taskId, "Plan", "INFO", "退出对话 Plan 模式，可读建造计划保留在记忆中");
                }
                case "plan_send" -> sendPlanChat(sp, m, data);
                case "plan_build" -> buildFromPlan(sp, m, data);
                case "visual_capture" -> startVisual(m, false, null);
                case "visual_analyze" -> startVisual(m, true, null);
                case "visual_custom" -> {
                    try {
                        String text = Json.str(parseObject(data), "pose", "");
                        startVisual(m, true, CameraRig.custom(text));
                    } catch (Exception e) {
                        log(taskId, "视觉检查", "WARN", "自定义视角无效：" + e.getMessage());
                    }
                }
                case "stop" -> stopJob(m);
                default -> BuilderMod.LOGGER.warn("[建造] 未知 GUI 动作 {}", action);
            }
        } catch (Exception e) {
            log(taskId, "GUI", "ERROR", "处理 " + action + " 失败：" + e.getMessage());
            BuilderMod.LOGGER.warn("[建造] GUI 动作处理异常", e);
        }
        BuildMemory current = memory(taskId);
        if (current != null) {
            current.touch();
            pushState(current, false, sp);
        }
    }

    /** 建造物品两次左键选点：用的是玩家自身所在坐标，不是准星指向的方块。 */
    private void selectPoint(ServerPlayer player) {
        ItemStack stack = null;
        for (InteractionHand hand : InteractionHand.values()) {
            ItemStack candidate = player.getItemInHand(hand);
            if (candidate.getItem() instanceof BuildCommanderItem) {
                stack = candidate;
                break;
            }
        }
        if (stack == null) {
            return;
        }
        long now = System.currentTimeMillis();
        Long previous = lastSelect.get(player.getUUID());
        if (previous != null && now - previous < 200L) {
            return;
        }
        lastSelect.put(player.getUUID(), now);

        String taskId = ItemData.ensureTaskId(stack);
        BuildMemory m = createMemory(player, taskId, heldLinkMode(player));
        CompoundTag tag = ItemData.read(stack);
        BlockPos pos = player.blockPosition();
        String dimension = player.serverLevel().dimension().location().toString();
        BlockPos first = ItemData.point(tag, ItemData.P1);
        BlockPos second = ItemData.point(tag, ItemData.P2);
        String shown = "(" + pos.getX() + ", " + pos.getY() + ", " + pos.getZ() + ")";

        if (first == null || second != null) {
            ItemData.update(stack, t -> {
                ItemData.putPoint(t, ItemData.P1, pos);
                t.remove(ItemData.P2);
                t.putString(ItemData.DIM, dimension);
            });
            player.sendSystemMessage(Component.literal("§b[选区] §f起点 = 你当前位置 " + shown
                    + "，请走到另一个位置再左键一次"));
            log(taskId, "选点", "INFO", "左键设置起点（玩家自身坐标）" + shown);
            return;
        }
        ItemData.update(stack, t -> {
            ItemData.putPoint(t, ItemData.P2, pos);
            t.putString(ItemData.DIM, dimension);
        });
        Bounds bounds = Bounds.of(first, pos);
        setSelection(player, bounds, dimension);
        m.bounds = bounds;
        m.hasBounds = true;
        m.dimension = dimension;
        player.sendSystemMessage(Component.literal("§a[选区] §f终点 = 你当前位置 " + shown
                + "，范围 " + bounds.display()));
        player.sendSystemMessage(Component.literal("§7[选区] §f右键打开建造 GUI 即可使用该范围"));
        log(taskId, "选点", "INFO", "左键设置终点（玩家自身坐标）" + shown
                + "，范围 " + bounds.display() + "（" + dimension + "）");
        markDirty(taskId);
        saveMemory(m);
    }

    /** 玩家手持外部 Agent 物品时返回 external，否则 internal。 */
    private String heldLinkMode(ServerPlayer player) {
        for (InteractionHand hand : InteractionHand.values()) {
            if (player.getItemInHand(hand).getItem() instanceof ExternalBuildWandItem) {
                return "external";
            }
        }
        return "internal";
    }

    private boolean authorized(ServerPlayer player, String taskId) {
        for (InteractionHand hand : InteractionHand.values()) {
            ItemStack stack = player.getItemInHand(hand);
            if (stack.getItem() instanceof BuildCommanderItem && taskId.equals(ItemData.taskId(stack))) {
                return true;
            }
        }
        BuildMemory m = memories.get(taskId);
        return m != null && m.ownerUuid.equals(player.getUUID().toString());
    }

    private void applyBounds(ServerPlayer player, BuildMemory m, String data) {
        JsonObject o;
        try {
            o = Json.GSON.fromJson(data, JsonObject.class);
        } catch (Exception e) {
            log(m.taskId, "坐标", "WARN", "坐标格式错误：" + e.getMessage());
            return;
        }
        if (o == null) {
            return;
        }
        JsonArray array = o.getAsJsonArray("bounds");
        if (array == null || array.size() != 6) {
            log(m.taskId, "坐标", "WARN", "坐标必须是 6 个整数");
            return;
        }
        int[] values = new int[6];
        try {
            for (int i = 0; i < 6; i++) {
                values[i] = array.get(i).getAsInt();
            }
        } catch (Exception e) {
            log(m.taskId, "坐标", "WARN", "坐标必须为整数");
            return;
        }
        Bounds bounds = Bounds.fromArray(values);
        bounds.y1 = Math.max(-64, Math.min(319, bounds.y1));
        bounds.y2 = Math.max(-64, Math.min(319, bounds.y2));
        bounds.normalize();
        if (bounds.volume() > BuilderConfig.MAX_VOLUME.getAsLong()) {
            log(m.taskId, "坐标", "WARN", "范围体积 " + bounds.volume() + " 超过上限 "
                    + BuilderConfig.MAX_VOLUME.getAsLong() + "，已拒绝");
            return;
        }
        m.bounds = bounds;
        m.hasBounds = true;
        m.dimension = player.serverLevel().dimension().location().toString();
        setSelection(player, bounds, m.dimension);
        // 关键：客户端 MCP 是从物品 NBT 读选区的，只改记忆的话外部 Agent 永远看不到手动输入的范围
        writeBoundsToHeldItem(player, m.taskId, bounds);
        log(m.taskId, "坐标", "INFO", "建造范围已更新为 " + bounds.display() + "（" + m.dimension + "）");
        markDirty(m.taskId);
    }

    /** 把范围写回与该任务绑定的手持物品（P1 / P2 / 维度），并触发客户端同步。 */
    private void writeBoundsToHeldItem(ServerPlayer player, String taskId, Bounds bounds) {
        for (InteractionHand hand : InteractionHand.values()) {
            ItemStack stack = player.getItemInHand(hand);
            if (!(stack.getItem() instanceof BuildCommanderItem)) {
                continue;
            }
            if (!taskId.equals(ItemData.taskId(stack))) {
                continue;
            }
            int[] b = bounds.toArray();
            String dimension = player.serverLevel().dimension().location().toString();
            BlockPos first = new BlockPos(b[0], b[1], b[2]);
            BlockPos second = new BlockPos(b[3], b[4], b[5]);
            ItemData.update(stack, tag -> {
                ItemData.putPoint(tag, ItemData.P1, first);
                ItemData.putPoint(tag, ItemData.P2, second);
                tag.putString(ItemData.DIM, dimension);
            });
            player.getInventory().setChanged();
            player.containerMenu.broadcastChanges();
            return;
        }
    }

    private void startBuild(ServerPlayer player, BuildMemory m, String data) {
        JsonObject o = parseObject(data);
        boolean fromPlan = Json.bool(o, "usePlan", false);
        String brief = fromPlan ? m.planDocument.trim() : Json.str(o, "text", "").trim();
        if (brief.isBlank()) {
            log(m.taskId, "建造", "WARN", fromPlan
                    ? "Plan 对话里还没有形成建造计划" : "请先用一句话描述你想要的建筑");
            return;
        }
        if ("external".equals(m.linkMode)) {
            log(m.taskId, "建造", "WARN", "该任务属于外部 Agent（MCP），游戏内模型不会参与");
            player.sendSystemMessage(Component.literal(
                    "§c[建造] 这个任务由外部建造手杖创建，请保持手持去外部 Agent 对话，"
                            + "游戏内模型不会重复施工。"));
            return;
        }
        if (!m.hasBounds || m.bounds == null) {
            log(m.taskId, "建造", "WARN", "请先用坐标选择物品确定范围，或在 GUI 里手动填写坐标");
            return;
        }
        if (hasActiveBuild(m.taskId)) {
            log(m.taskId, "建造", "WARN", "已有任务在执行中（含外部 Agent），请先中止");
            return;
        }
        // 是否属于「修改」由任务记忆决定，GUI 只提供一个按钮
        boolean modify = m.version > 0 || !m.currentPlanJson.isBlank();
        boolean offline = !llm.isConfigured();
        List<BlockIndex.BlockInfo> palette = palette(m, brief);
        log(m.taskId, "检索", "INFO", "本地索引检索出 " + palette.size() + " 个候选方块（"
                + BlockIndex.get().size() + " 条外观记录），未消耗 token"
                + (offline ? "；未配置 API Key，使用离线分阶段模板（0 次模型调用）" : ""));
        if (fromPlan) {
            log(m.taskId, "建造", "INFO", "以 Plan 对话确认的可读建造计划为施工依据（" + brief.length() + " 字）");
        }
        List<Placement> previous = loadPlacements(m.taskId, m.version);
        m.userBrief = brief;
        m.addInstruction(modify ? "修改" : "建造", brief);
        boolean toolsReady = BuilderConfig.supportsTools();
        if (BuilderConfig.agentToolsEnabled() && !toolsReady) {
            log(m.taskId, "Agent", "WARN", "模型 " + BuilderConfig.model()
                    + " 被标记为不支持 tools，自动回退到旧的固定阶段链路；"
                    + "若该模型其实支持 function calling，把 modelTools 设为 yes");
        }
        if (BuilderConfig.agentToolsEnabled() && toolsReady && !offline) {
            BuildAgent agent = new BuildAgent(m.taskId, brief, m.bounds.copy(), m, previous, this);
            buildAgents.put(m.taskId, agent);
            setStatus(m.taskId, "BUILDING", agent.phaseName());
            log(m.taskId, "Agent", "INFO", "启动工具调用 Agent（模型 " + BuilderConfig.model()
                    + "）：读状态 / 建模 / 拍照 / 修正 / 完成全由模型自主决定；"
                    + "VoxelWorld 是坐标唯一权威，已有 " + previous.size() + " 格基线");
            if (!BuilderConfig.supportsVision()) {
                log(m.taskId, "Agent", "INFO", "图片能力已关闭（modelVision=no）：capture 仍会拍照落盘，"
                        + "但照片以文本回执 / read_state 替代；视觉审查可另配 visionModel");
            }
            return;
        }
        AgentJob job = new AgentJob(m.taskId, player.getUUID(), brief, modify,
                m.bounds.copy(), palette, offline, m, previous,
                m.entities == null ? new ArrayList<>() : m.entities);
        jobs.put(m.taskId, job);
        setStatus(m.taskId, "BUILDING", job.phaseName());
        log(m.taskId, "建造", "INFO", "多阶段长链任务启动：" + (modify ? "修改" : "新建")
                + "「" + brief + "」，共 " + Prompts.stages(modify).size() + " 个施工阶段，逐阶段规划并放置");
    }

    /** 「按计划建造」：把可读文字计划作为需求交给多阶段施工链，不再复用任何 JSON 草案。 */
    private void buildFromPlan(ServerPlayer player, BuildMemory m, String data) {
        if (m.planDocument.isBlank()) {
            log(m.taskId, "Plan", "WARN", "对话里还没有形成《建造计划》，请继续与 Agent 确认");
            return;
        }
        JsonObject o = new JsonObject();
        o.addProperty("usePlan", true);
        startBuild(player, m, Json.GSON.toJson(o));
        if (jobs.containsKey(m.taskId) || buildAgents.containsKey(m.taskId)) {
            m.planMode = false;
            log(m.taskId, "Plan", "INFO", "计划已确认，开始施工");
        }
    }

    private void sendPlanChat(ServerPlayer player, BuildMemory m, String data) {
        String text = Json.str(parseObject(data), "text", "").trim();
        if (text.isBlank()) {
            return;
        }
        if (planFutures.containsKey(m.taskId)) {
            log(m.taskId, "Plan", "WARN", "上一条消息还在等待模型回复");
            return;
        }
        if (!llm.isConfigured()) {
            m.addChat("user", text);
            m.addChat("assistant", "（离线模式）当前未配置 API Key，Plan 对话不可用；"
                    + "可以直接在「建造」页输入需求，使用内置参数化模板建造。");
            log(m.taskId, "Plan", "WARN", "未配置 API Key，无法进行 Plan 对话");
            return;
        }
        m.addChat("user", text);
        Bounds bounds = m.bounds != null ? m.bounds : new Bounds(0, 0, 0, 15, 8, 15);
        List<LlmClient.Message> messages = Prompts.chatMessages(m, text, bounds, palette(m, text));
        countModelCall(m.taskId, "Plan 对话");
        planFutures.put(m.taskId, llm.chat(messages));
        m.planMode = true;
        setStatus(m.taskId, m.status, "Plan 对话中");
    }

    private void stopJob(BuildMemory m) {
        BuildAgent agent = buildAgents.remove(m.taskId);
        if (agent != null) {
            agent.cancel();
            return;
        }
        AgentJob job = jobs.remove(m.taskId);
        if (job == null) {
            log(m.taskId, "中止", "WARN", "当前没有正在执行的任务");
            return;
        }
        job.cancel(this);
    }

    /** 供 BuildAgent 发起一轮带 tools 的模型请求；同时交回流式思考片段队列。 */
    public LlmClient.AgentRequest agentChat(
            List<LlmClient.AgentMessage> messages, JsonArray tools) {
        return llm.agentChat(messages, tools);
    }

    /** 实时把一段深度思考写进 Agent 输出；不 echo 到聊天栏，避免刷屏。 */
    public void logThinkingChunk(String taskId, String stage, String text) {
        appendLog(taskId, "思考", "INFO", text, false);
    }

    private List<BlockIndex.BlockInfo> palette(BuildMemory m, String brief) {
        return BlockIndex.get().paletteFor(brief, "", 16);
    }

    private static JsonObject parseObject(String data) {
        try {
            JsonObject o = Json.GSON.fromJson(data == null || data.isBlank() ? "{}" : data, JsonObject.class);
            return o == null ? new JsonObject() : o;
        } catch (Exception e) {
            return new JsonObject();
        }
    }

    // ------------------------------------------------------------------
    // 服务端 tick
    // ------------------------------------------------------------------

    // ------------------------------------------------------------------
    // 客户端建模提交（外部 Agent：几何 / 渲染在客户端，服务端只写方块）
    // ------------------------------------------------------------------

    private record CommitBlock(int x, int y, int z, String fullState) {
    }

    /** 高风险方块（重力 / 需支撑 / 树叶）的完整状态，归档前用它校验是否被原版规则抹掉。 */
    private record PlacedBlock(int x, int y, int z,
                               net.minecraft.world.level.block.state.BlockState state) {
    }

    /** 按 y 优先排序的坐标 key：保证从下往上写，支撑方块先到位。 */
    private static long commitSortKey(int x, int y, int z) {
        return (((long) (y + 64) & 0xFFF) << 52)
                | (((long) x & 0x3FFFFFF) << 26)
                | ((long) z & 0x3FFFFFF);
    }

    /** 一个任务的分批提交队列；全部收到并写完才归档。 */
    private static final class CommitJob {
        final String taskId;
        final String dimension;
        /** TreeMap + y 优先 key：天然按「从下往上」的顺序写入。 */
        final Map<Long, CommitBlock> pending = new java.util.TreeMap<>();
        /** 高风险方块清单：归档前逐个校验有没有被原版规则抹掉，普通方块不记录以节省开销。 */
        final List<PlacedBlock> placed = new ArrayList<>();
        Bounds bounds;
        String summary = "";
        boolean last;
        int total;
        /** 实际写入格数 / 状态解析失败 / 区块未加载 / 写入后又消失，用于诊断。 */
        int written;
        int failedState;
        int failedLoaded;
        int vanished;
        String firstRaw = "";
        String sampleRaw = "";

        CommitJob(String taskId, String dimension) {
            this.taskId = taskId;
            this.dimension = dimension == null || dimension.isBlank()
                    ? "minecraft:overworld" : dimension;
        }
    }

    /** 接收客户端提交的方块批次；只校验任务归属，几何与渲染完全由客户端负责。 */
    public void applyClientCommit(Player player, ModPackets.C2SCommit payload) {
        if (server == null || payload == null || !(player instanceof ServerPlayer sp)) {
            return;
        }
        String taskId = payload.taskId();
        if (taskId == null || taskId.isBlank() || taskId.length() > 100) {
            return;
        }
        BuildMemory m = memories.get(taskId);
        if (m == null) {
            m = createMemory(sp, taskId, "external");
        }
        if (!m.ownerUuid.isBlank() && !m.ownerUuid.equals(sp.getUUID().toString())) {
            log(taskId, "提交", "WARN", "非任务所有者提交方块，已拒绝");
            return;
        }
        CommitJob job = commitJobs.computeIfAbsent(taskId,
                id -> new CommitJob(id, payload.dimension()));
        JsonObject root;
        try {
            root = Json.GSON.fromJson(payload.payload(), JsonObject.class);
        } catch (Exception e) {
            log(taskId, "提交", "WARN", "提交内容不是合法 JSON：" + e.getMessage());
            return;
        }
        if (root == null) {
            return;
        }
        if (job.bounds == null) {
            JsonArray box = root.getAsJsonArray("bounds");
            if (box != null && box.size() == 6) {
                int[] b = new int[6];
                for (int i = 0; i < 6; i++) {
                    b[i] = box.get(i).getAsInt();
                }
                job.bounds = new Bounds(b[0], b[1], b[2], b[3], b[4], b[5]);
            }
        }
        if (job.summary.isBlank()) {
            job.summary = Json.str(root, "summary", "").trim();
        }
        JsonArray blocks = root.getAsJsonArray("blocks");
        int received = 0;
        if (blocks != null) {
            for (JsonElement element : blocks) {
                try {
                    JsonArray one = element.getAsJsonArray();
                    if (one.size() < 4) {
                        continue;
                    }
                    int x = one.get(0).getAsInt();
                    int y = one.get(1).getAsInt();
                    int z = one.get(2).getAsInt();
                    String state = one.get(3).getAsString();
                    if (job.firstRaw.isBlank()) {
                        job.firstRaw = state;
                    }
                    job.pending.put(commitSortKey(x, y, z), new CommitBlock(x, y, z, state));
                    received++;
                } catch (Exception ignored) {
                    // 跳过坏条目
                }
            }
        }
        if (job.total == 0 && received > 0) {
            log(taskId, "提交", "INFO", "首个方块示例：" + job.firstRaw);
        }
        job.total += received;
        if (payload.last()) {
            job.last = true;
        }
        log(taskId, "提交", "INFO", "客户端提交 " + received + " 格（累计 " + job.total
                + "），服务端排队写入世界");
    }

    private void tickClientCommits(MinecraftServer currentServer) {
        if (commitJobs.isEmpty()) {
            return;
        }
        Iterator<Map.Entry<String, CommitJob>> iterator = commitJobs.entrySet().iterator();
        while (iterator.hasNext()) {
            CommitJob job = iterator.next().getValue();
            var level = resolveAnyLevel(currentServer, job.dimension);
            if (level == null) {
                continue;
            }
            int budget = Math.max(16, BuilderConfig.BLOCKS_PER_TICK.getAsInt());
            int processed = 0;
            List<Long> done = new ArrayList<>();
            for (Map.Entry<Long, CommitBlock> entry : job.pending.entrySet()) {
                if (processed >= budget) {
                    break;
                }
                CommitBlock block = entry.getValue();
                BlockPos pos = new BlockPos(block.x(), block.y(), block.z());
                var state = BlockIndex.toState(block.fullState());
                if (state == null) {
                    int bracket = block.fullState().indexOf('[');
                    String base = bracket > 0 ? block.fullState().substring(0, bracket)
                            : block.fullState();
                    state = BlockIndex.toState(base);
                }
                if (state == null) {
                    // 状态字符串解析不出来：记进统计，绝不能静默丢掉
                    job.failedState++;
                    if (job.sampleRaw.isBlank()) {
                        job.sampleRaw = block.fullState();
                    }
                    done.add(entry.getKey());
                    processed++;
                    continue;
                }
                if (pos.getY() < level.getMinBuildHeight() || pos.getY() >= level.getMaxBuildHeight()) {
                    job.failedState++;
                    if (job.sampleRaw.isBlank()) {
                        job.sampleRaw = block.fullState() + " @y=" + block.y() + "（超出世界高度）";
                    }
                    done.add(entry.getKey());
                    processed++;
                    continue;
                }
                if (!level.isLoaded(pos)) {
                    level.getChunk(block.x() >> 4, block.z() >> 4);
                }
                if (!level.isLoaded(pos)) {
                    // 区块仍未加载：留在队列里下一 tick 重试，不能算作已写入
                    job.failedLoaded++;
                    processed++;
                    continue;
                }
                // 放置修正：树叶锁成 persistent，否则会按到原木的距离随机衰减消失
                state = BlockIndex.forPlacement(state);
                // flag=2 只同步客户端、不触发邻居更新：避免悬空方块被原版物理规则当场破坏；
                // 重力方块再取消掉落调度，否则它下一 tick 就变成实体，世界与渲染不一致
                level.setBlock(pos, state, 2);
                // 只登记高风险方块：重力 / 当前位置撑不住 / 树叶 / 随机刻会自己变化
                // （冰与雪融化、珊瑚离水变死珊瑚、耕地退化、草方块蔓延……）
                boolean risky = BlockIndex.isGravity(state) || !state.canSurvive(level, pos)
                        || state.hasProperty(net.minecraft.world.level.block.LeavesBlock.PERSISTENT)
                        || state.isRandomlyTicking();
                if (risky) {
                    job.placed.add(new PlacedBlock(block.x(), block.y(), block.z(), state));
                }
                job.written++;
                done.add(entry.getKey());
                processed++;
            }
            for (Long key : done) {
                job.pending.remove(key);
            }
            if (job.last && job.pending.isEmpty()) {
                verifyPlacements(job, level);
                finishCommitJob(job);
                iterator.remove();
            }
        }
    }

    /**
     * 归档前校验：重力方块、撑不住的支撑件、树叶都可能被原版规则改掉。
     * 逐个核对，丢了就按原状态补写一次；仍然不在的计入 vanished 并写日志，
     * 这样「渲染里有、世界里没有」的差异会被发现，而不是静默存在。
     */
    private void verifyPlacements(CommitJob job,
                                  net.minecraft.server.level.ServerLevel level) {
        if (job.placed.isEmpty()) {
            return;
        }
        int restored = 0;
        List<String> missing = new ArrayList<>();
        Map<String, Integer> restoredByBlock = new java.util.HashMap<>();
        for (PlacedBlock entry : job.placed) {
            BlockPos pos = new BlockPos(entry.x(), entry.y(), entry.z());
            if (!level.isLoaded(pos)) {
                continue;
            }
            if (level.getBlockState(pos).getBlock() == entry.state().getBlock()) {
                continue;
            }
            String blockId = String.valueOf(net.minecraft.core.registries.BuiltInRegistries.BLOCK
                    .getKey(entry.state().getBlock()));
            level.setBlock(pos, entry.state(), 2);
            if (level.getBlockState(pos).getBlock() == entry.state().getBlock()) {
                restored++;
                restoredByBlock.merge(blockId, 1, Integer::sum);
            } else {
                job.vanished++;
                if (missing.size() < 5) {
                    missing.add("(" + entry.x() + "," + entry.y() + "," + entry.z() + ") "
                            + blockId);
                }
            }
        }
        if (restored > 0 || job.vanished > 0) {
            StringBuilder detail = new StringBuilder("归档前校验：补写 ")
                    .append(restored).append(" 格");
            if (!restoredByBlock.isEmpty()) {
                detail.append("，Top 方块=");
                restoredByBlock.entrySet().stream()
                        .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
                        .limit(5)
                        .forEach(e -> detail.append(e.getKey()).append('×')
                                .append(e.getValue()).append(' '));
            }
            detail.append("，仍缺失 ").append(job.vanished).append(" 格");
            if (!missing.isEmpty()) {
                detail.append("，示例 ").append(String.join("、", missing));
            }
            log(job.taskId, "提交", job.vanished > 0 ? "WARN" : "INFO", detail.toString());
        }
    }

    // ------------------------------------------------------------------
    // 客户端世界查询：read_world / get_build_status / resume
    // ------------------------------------------------------------------

    /** 客户端查询真实世界与提交状态；结果单独回给发起者，不广播。 */
    public void applyClientQuery(Player player, ModPackets.C2SQuery payload) {
        if (server == null || payload == null || !(player instanceof ServerPlayer sp)) {
            return;
        }
        String requestId = payload.requestId();
        String taskId = payload.taskId();
        JsonObject out = new JsonObject();
        out.addProperty("requestId", requestId);
        out.addProperty("query", payload.query());
        out.addProperty("taskId", taskId);
        try {
            switch (payload.query()) {
                case "read_world" -> queryReadWorld(sp, payload.payload(), out);
                case "get_build_status" -> queryBuildStatus(taskId, out);
                case "resume" -> queryResume(taskId, out);
                case "list_versions" -> queryListVersions(taskId, out);
                case "revert" -> queryRevert(taskId, payload.payload(), out);
                default -> out.addProperty("error", "未知查询：" + payload.query());
            }
        } catch (Exception e) {
            out.addProperty("error", e.getMessage() == null ? e.toString() : e.getMessage());
        }
        PacketDistributor.sendToPlayer(sp,
                new ModPackets.S2CQueryResult(requestId, Json.GSON.toJson(out)));
    }

    /** 按 region 采样服务端真实方块，带体积与条数上限，避免一次扫平整片地形。 */
    private void queryReadWorld(ServerPlayer player, String payload, JsonObject out) {
        JsonObject args = Json.GSON.fromJson(payload, JsonObject.class);
        JsonArray region = args == null ? null : args.getAsJsonArray("region");
        if (region == null || region.size() != 6) {
            out.addProperty("error", "region 需要 [x1,y1,z1,x2,y2,z2] 六个整数");
            return;
        }
        int[] b = new int[6];
        for (int i = 0; i < 6; i++) {
            b[i] = region.get(i).getAsInt();
        }
        for (int i = 0; i < 3; i++) {
            if (b[i] > b[i + 3]) {
                int t = b[i];
                b[i] = b[i + 3];
                b[i + 3] = t;
            }
        }
        long volume = (long) (b[3] - b[0] + 1) * (b[4] - b[1] + 1) * (b[5] - b[2] + 1);
        if (volume > 200000L) {
            out.addProperty("error", "region 体积 " + volume
                    + " 过大（上限 200000），请按 y 分层查询");
            return;
        }
        int limit = Math.max(1, Math.min(8192, Json.integer(args, "limit", 4096)));
        String dimension = player.serverLevel().dimension().location().toString();
        var level = resolveAnyLevel(server, dimension);
        JsonArray blocks = new JsonArray();
        int found = 0;
        boolean truncated = false;
        outer:
        for (int x = b[0]; x <= b[3]; x++) {
            for (int z = b[2]; z <= b[5]; z++) {
                for (int y = b[1]; y <= b[4]; y++) {
                    BlockPos pos = new BlockPos(x, y, z);
                    if (!level.isLoaded(pos)) {
                        continue;
                    }
                    var state = level.getBlockState(pos);
                    if (state.isAir()) {
                        continue;
                    }
                    if (found >= limit) {
                        truncated = true;
                        break outer;
                    }
                    found++;
                    JsonArray one = new JsonArray();
                    one.add(x);
                    one.add(y);
                    one.add(z);
                    one.add(String.valueOf(net.minecraft.core.registries.BuiltInRegistries.BLOCK
                            .getKey(state.getBlock())));
                    blocks.add(one);
                }
            }
        }
        out.add("blocks", blocks);
        out.addProperty("returned", blocks.size());
        out.addProperty("truncated", truncated);
        out.addProperty("dimension", dimension);
        out.add("region", Json.GSON.toJsonTree(b));
    }

    private void queryBuildStatus(String taskId, JsonObject out) {
        BuildMemory m = memories.get(taskId);
        if (m == null) {
            out.addProperty("error", "任务记忆不存在：" + taskId);
            return;
        }
        CommitJob job = commitJobs.get(taskId);
        int pending = job == null ? 0 : job.pending.size();
        out.addProperty("version", m.version);
        out.addProperty("status", m.status);
        out.addProperty("stage", m.stage);
        out.addProperty("blocksPlacedTotal", m.blocksPlacedTotal);
        out.addProperty("pendingWrites", pending);
        out.addProperty("writing", pending > 0);
        out.addProperty("done", "DONE".equals(m.status) && pending == 0);
        if (job != null) {
            out.addProperty("received", job.total);
            out.addProperty("written", job.written);
            out.addProperty("vanished", job.vanished);
        }
    }

    /** 回读归档版本，客户端重启后用它恢复本地 VoxelWorld。 */
    private void queryResume(String taskId, JsonObject out) {
        BuildMemory m = memories.get(taskId);
        if (m == null) {
            out.addProperty("error", "任务记忆不存在：" + taskId);
            return;
        }
        List<Placement> placements = loadPlacements(taskId, m.version);
        JsonArray blocks = new JsonArray();
        for (Placement p : placements) {
            JsonArray one = new JsonArray();
            one.add(p.x);
            one.add(p.y);
            one.add(p.z);
            one.add(p.state == null || p.state.isBlank()
                    ? p.block : p.block + "[" + p.state + "]");
            blocks.add(one);
        }
        out.add("blocks", blocks);
        out.addProperty("version", m.version);
        out.addProperty("count", blocks.size());
        if (m.hasBounds && m.bounds != null) {
            out.add("bounds", Json.GSON.toJsonTree(m.bounds.toArray()));
        }
    }

    /** 列出有归档方块图的版本，供回滚前选择。 */
    private void queryListVersions(String taskId, JsonObject out) {
        BuildMemory m = memories.get(taskId);
        if (m == null) {
            out.addProperty("error", "任务记忆不存在：" + taskId);
            return;
        }
        JsonArray versions = new JsonArray();
        for (int v = 0; v <= m.version; v++) {
            if (!loadPlacements(taskId, v).isEmpty()) {
                versions.add(v);
            }
        }
        out.add("versions", versions);
        out.addProperty("current", m.version);
    }

    /** 回滚：把指定版本的方块图重新排进写入队列，逐 tick 覆盖回去。 */
    private void queryRevert(String taskId, String payload, JsonObject out) {
        BuildMemory m = memories.get(taskId);
        if (m == null) {
            out.addProperty("error", "任务记忆不存在：" + taskId);
            return;
        }
        JsonObject args = Json.GSON.fromJson(payload, JsonObject.class);
        int target = args == null ? -1 : Json.integer(args, "version", -1);
        if (target < 0) {
            target = Math.max(0, m.version - 1);
        }
        List<Placement> placements = loadPlacements(taskId, target);
        if (placements.isEmpty()) {
            out.addProperty("error", "v" + target + " 没有可用的方块图，无法回滚");
            return;
        }
        CommitJob job = commitJobs.computeIfAbsent(taskId,
                id -> new CommitJob(id, m.dimension));
        for (Placement p : placements) {
            String full = p.state == null || p.state.isBlank()
                    ? p.block : p.block + "[" + p.state + "]";
            job.pending.put(commitSortKey(p.x, p.y, p.z),
                    new CommitBlock(p.x, p.y, p.z, full));
        }
        job.total += placements.size();
        job.last = true;
        job.summary = "回滚到 v" + target;
        m.version++;
        out.addProperty("revertedTo", target);
        out.addProperty("queued", placements.size());
        out.addProperty("note", "方块按 blocksPerTick 排队写入；用 get_build_status 确认是否完成");
    }

    private void finishCommitJob(CommitJob job) {
        BuildMemory m = memories.get(job.taskId);
        if (m == null) {
            return;
        }
        if (job.bounds != null) {
            m.bounds = job.bounds.copy();
            m.hasBounds = true;
        }
        m.dimension = job.dimension;
        m.version++;
        m.status = "DONE";
        m.stage = "外部 Agent 建造完成";
        m.blocksPlacedTotal = job.written;
        m.linkMode = "external";
        if (!job.summary.isBlank()) {
            m.notes = job.summary;
            m.addInstruction("外部 Agent", job.summary);
        }
        saveMemory(m);
        markDirty(job.taskId);
        StringBuilder report = new StringBuilder()
                .append("外部 Agent 建造完成 v").append(m.version).append("：实际写入 ")
                .append(job.written).append(" 格 / 收到 ").append(job.total).append(" 格");
        if (job.failedState > 0) {
            report.append("，状态解析失败 ").append(job.failedState).append(" 格");
            if (!job.sampleRaw.isBlank()) {
                report.append("（示例 ").append(job.sampleRaw).append("）");
            }
        }
        if (job.vanished > 0) {
            report.append("，写入后消失 ").append(job.vanished).append(" 格");
        }
        if (!job.pending.isEmpty()) {
            report.append("，仍有 ").append(job.pending.size()).append(" 格未写入");
        }
        report.append("（客户端建模与渲染，0 次模型调用）");
        log(job.taskId, "提交", "INFO", report.toString());
        if (job.written == 0 && job.total > 0) {
            log(job.taskId, "提交", "ERROR",
                    "收到 " + job.total + " 格但一格都没写进世界：检查上面那条日志里的"
                            + "「状态解析失败 / 未写入」与首个方块示例");
        }
        pushState(m, false, null);
    }

    private static net.minecraft.server.level.ServerLevel resolveAnyLevel(
            MinecraftServer server, String dimension) {
        try {
            var key = net.minecraft.resources.ResourceKey.create(
                    net.minecraft.core.registries.Registries.DIMENSION,
                    net.minecraft.resources.ResourceLocation.parse(dimension));
            var level = server.getLevel(key);
            return level == null ? server.overworld() : level;
        } catch (Exception e) {
            return server.overworld();
        }
    }

    public void tick(MinecraftServer currentServer) {
        if (server == null || currentServer == null) {
            return;
        }
        tickCounter++;
        mcpBridge.tick(currentServer);
        tickClientCommits(currentServer);
        Iterator<Map.Entry<String, AgentJob>> iterator = jobs.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, AgentJob> entry = iterator.next();
            entry.getValue().tick(currentServer, this);
            if (entry.getValue().finished()) {
                iterator.remove();
            }
        }
        Iterator<Map.Entry<String, BuildAgent>> agents = buildAgents.entrySet().iterator();
        while (agents.hasNext()) {
            Map.Entry<String, BuildAgent> entry = agents.next();
            entry.getValue().tick(currentServer, this);
            if (entry.getValue().finished()) {
                agents.remove();
            }
        }
        Iterator<Map.Entry<String, CompletableFuture<LlmClient.Result>>> chats = planFutures.entrySet().iterator();
        while (chats.hasNext()) {
            Map.Entry<String, CompletableFuture<LlmClient.Result>> entry = chats.next();
            if (!entry.getValue().isDone()) {
                continue;
            }
            LlmClient.Result result;
            try {
                result = entry.getValue().get();
            } catch (Exception e) {
                result = LlmClient.Result.fail("对话任务异常：" + e.getMessage());
            }
            finishPlanChat(entry.getKey(), result);
            chats.remove();
        }
        // 语义层异步结果：先收集已完成的，退出迭代后再处理，避免修正调用写回同一个 map 时 CME
        List<Map.Entry<String, LlmClient.Result>> semanticDone = new ArrayList<>();
        Iterator<Map.Entry<String, CompletableFuture<LlmClient.Result>>> semantics =
                semanticFutures.entrySet().iterator();
        while (semantics.hasNext()) {
            Map.Entry<String, CompletableFuture<LlmClient.Result>> entry = semantics.next();
            if (!entry.getValue().isDone()) {
                continue;
            }
            LlmClient.Result result;
            try {
                result = entry.getValue().get();
            } catch (Exception e) {
                result = LlmClient.Result.fail("语义层任务异常：" + e.getMessage());
            }
            semanticDone.add(Map.entry(entry.getKey(), result));
            semantics.remove();
        }
        for (Map.Entry<String, LlmClient.Result> entry : semanticDone) {
            finishSemantic(entry.getKey(), entry.getValue());
        }
        Iterator<Map.Entry<String, VisualJob>> visuals = visualJobs.entrySet().iterator();
        while (visuals.hasNext()) {
            Map.Entry<String, VisualJob> entry = visuals.next();
            entry.getValue().tick(this);
            if (entry.getValue().finished()) {
                visuals.remove();
            }
        }
        if (tickCounter % 5 == 0 && !dirtyStates.isEmpty()) {
            for (String taskId : dirtyStates) {
                BuildMemory m = memories.get(taskId);
                if (m != null) {
                    pushState(m, false, null);
                }
            }
            dirtyStates.clear();
        }
        if (tickCounter % 100 == 0) {
            for (BuildMemory m : memories.values()) {
                if (m.dirty) {
                    saveMemory(m);
                }
                updateItemPointer(m);
            }
        }
    }

    private void finishPlanChat(String taskId, LlmClient.Result result) {
        BuildMemory m = memory(taskId);
        if (m == null) {
            return;
        }
        recordTokens(taskId, result);
        if (!result.ok()) {
            String reply = "（模型调用失败：" + result.error() + "）";
            m.addChat("assistant", reply);
            log(taskId, "Plan", "WARN", reply);
            return;
        }
        String plan = result.content().trim();
        m.addChat("assistant", plan);
        log(taskId, "Plan", "INFO", "Agent 已回复（输入 " + result.promptTokens()
                + " / 输出 " + result.completionTokens() + " token，深度思考 "
                + result.reasoning().length() + " 字）");
        logReasoning(taskId, "Plan 对话", result.reasoning());
        // Plan 只写给人看的计划：整段文字直接存进记忆，不解析任何 JSON / 建造指令
        m.planDocument = plan;
        m.planMode = true;
        log(taskId, "Plan", "INFO", "《建造计划》已写入记忆（" + plan.length()
                + " 字），确认后点「按计划建造」才会开始施工");
        setStatus(taskId, m.status, "建造计划已就绪");
    }
}
