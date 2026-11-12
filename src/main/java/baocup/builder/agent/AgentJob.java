package baocup.builder.agent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import baocup.builder.BuilderConfig;
import baocup.builder.memory.BuildMemory;
import baocup.builder.util.Bounds;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;

/**
 * 一次建造的 Agent 长链任务，按阶段逐步施工而不是一键生成：
 *
 * 阶段循环（每阶段一次模型调用，共 3~4 次）：
 *   阶段说明/检索(本地) -> 本阶段规划(模型) -> 校验(本地) -> 合并进累计方案
 *   -> 编译几何(本地,0 token) -> 逐 tick 放置(本地,0 token) -> 进入下一阶段
 * 全部阶段结束才归档版本。玩家能在游戏里看到建筑从地基、结构、门窗到细节一层层长出来。
 */
public final class AgentJob {
    public enum Phase { WAIT_LLM, COMPILE, EXECUTE, INSPECT, DONE, FAILED }

    public final String taskId;
    public final UUID owner;
    public final String brief;
    public final boolean modify;

    private final List<Prompts.Stage> stages;
    private final boolean offline;
    private final Bounds bounds;
    private final List<BlockIndex.BlockInfo> palette;
    private final List<BuildPlan> templateStages;
    private final int startModelCalls;

    private BuildPlan cumulative = new BuildPlan();
    private BuildPlan stagePlan;
    private List<Placement> working = new ArrayList<>();
    private List<Placement> pending = new ArrayList<>();
    private List<Placement> pendingEntities = new ArrayList<>();
    private List<Placement> workingEntities = new ArrayList<>();
    private List<String> entityRemovals = new ArrayList<>();
    private List<Placement> executionList = new ArrayList<>();
    private Set<Long> plannedNonAir = new HashSet<>();
    private int[] groupEnd = new int[0];
    private final Map<Long, Placement> extraSupports = new HashMap<>();
    private int nbtApplied;
    private int entitiesPlaced;
    private int entitiesRemoved;
    private int supportsAdded;
    private int unsupported;
    private int stageIndex;
    private int cursor;
    private int placed;
    private int skipped;
    private int totalPlaced;
    private int lastPercent = -1;
    private Phase phase = Phase.WAIT_LLM;
    private CompletableFuture<LlmClient.Result> future;
    private CompletableFuture<AgentManager.StageInspection> inspectionFuture;
    /** 模型在本轮输出里是否申请了本阶段结束后的拍照复查。 */
    private boolean inspectAfterStage;
    private int stageInspectionsDone;
    private int pendingInspectionStage;
    private boolean finished;
    private final long startedAt = System.currentTimeMillis();

    public AgentJob(String taskId, UUID owner, String brief, boolean modify, Bounds bounds,
                    List<BlockIndex.BlockInfo> palette, boolean offline, BuildMemory memory,
                    List<Placement> previousPlacements, List<Placement> previousEntities) {
        this.taskId = taskId;
        this.owner = owner;
        this.brief = brief;
        this.modify = modify;
        this.stages = Prompts.stages(modify);
        this.offline = offline;
        this.bounds = bounds;
        this.palette = palette;
        this.templateStages = BuildPlan.fallbackStages(bounds, brief, stages.size());
        this.working = new ArrayList<>(previousPlacements);
        this.workingEntities = new ArrayList<>(previousEntities);
        this.startModelCalls = memory.modelCalls;
        if (modify && !memory.currentPlanJson.isBlank()) {
            try {
                cumulative = BuildPlan.parse(memory.currentPlanJson);
            } catch (Exception ignored) {
                cumulative = new BuildPlan();
            }
        }
    }

    public boolean finished() {
        return finished;
    }

    public String phaseName() {
        String stage = stages.get(Math.min(stageIndex, stages.size() - 1)).name();
        String work = switch (phase) {
            case WAIT_LLM -> "模型规划";
            case COMPILE -> "编译几何";
            case EXECUTE -> "放置方块";
            case INSPECT -> "拍照检查";
            case DONE -> "完成";
            case FAILED -> "失败";
        };
        return "第 " + Math.min(stageIndex + 1, stages.size()) + "/" + stages.size()
                + " 阶段「" + stage + "」· " + work;
    }

    public void cancel(AgentManager manager) {
        if (future != null) {
            future.cancel(true);
        }
        if (inspectionFuture != null) {
            inspectionFuture.cancel(true);
        }
        manager.log(taskId, "中止", "WARN", "在第 " + (stageIndex + 1) + "/" + stages.size()
                + " 阶段被手动中止，累计已放置 " + totalPlaced + " 个方块");
        manager.setStatus(taskId, "IDLE", "已中止");
        finished = true;
    }

    public void tick(MinecraftServer server, AgentManager manager) {
        BuildMemory memory = manager.memory(taskId);
        if (memory == null) {
            finished = true;
            return;
        }
        try {
            switch (phase) {
                case WAIT_LLM -> awaitStage(manager, memory);
                case COMPILE -> compileStage(manager, memory);
                case EXECUTE -> execute(server, manager, memory);
                case INSPECT -> inspectStage(manager, memory);
                default -> {
                }
            }
        } catch (Exception e) {
            manager.log(taskId, "异常", "ERROR", "Agent 第 " + (stageIndex + 1) + " 阶段出错：" + e.getMessage());
            manager.setStatus(taskId, "FAILED", "异常终止");
            finished = true;
        }
    }

    /** 每个阶段单独向模型要一次「本阶段该盖什么」，并从同一轮输出里读取是否申请中途拍照检查。 */
    private void awaitStage(AgentManager manager, BuildMemory memory) {
        Prompts.Stage stage = stages.get(stageIndex);
        String label = "第 " + (stageIndex + 1) + "/" + stages.size() + " 阶段「" + stage.name() + "」";
        if (offline) {
            inspectAfterStage = false;
            stagePlan = templateStages.get(stageIndex);
            manager.log(taskId, "阶段", "INFO", label + "：离线模板，0 次模型调用");
            phase = Phase.COMPILE;
            return;
        }
        if (future == null) {
            manager.log(taskId, "阶段", "INFO", label + " 开始：" + stage.hint());
            String system = Prompts.PLAN_SYSTEM;
            String user = Prompts.stageUser(brief, bounds, palette, memory, cumulative,
                    stage, stageIndex, stages.size(), modify);
            future = manager.submitStage(taskId, stage.name(), system, user);
            return;
        }
        if (!future.isDone()) {
            return;
        }
        LlmClient.Result result;
        try {
            result = future.get();
        } catch (Exception e) {
            result = LlmClient.Result.fail("模型任务异常：" + e.getMessage());
        }
        future = null;
        manager.recordTokens(taskId, result);
        manager.logReasoning(taskId, stage.name(), result.reasoning());
        if (!result.ok()) {
            manager.log(taskId, "规划", "WARN", label + " 模型调用失败（" + result.error()
                    + "），改用本阶段的离线模板继续施工");
            stagePlan = templateStages.get(stageIndex);
            inspectAfterStage = false;
        } else {
            manager.log(taskId, "规划", "INFO", label + " 模型返回：输入 " + result.promptTokens()
                    + " / 输出 " + result.completionTokens() + " token，耗时 " + result.elapsedMs() + "ms");
            try {
                stagePlan = BuildPlan.parse(result.content());
                inspectAfterStage = stagePlan.inspectAfterStage;
                if (inspectAfterStage) {
                    manager.log(taskId, "视觉检查", "INFO", "模型在 " + label
                            + " 主动申请施工完成后拍照复查（由它自己决定，系统不强制）");
                }
            } catch (Exception e) {
                manager.log(taskId, "规划", "WARN", label + " 方案 JSON 无法解析（" + e.getMessage()
                        + "），改用本阶段的离线模板");
                stagePlan = templateStages.get(stageIndex);
                inspectAfterStage = false;
            }
        }
        phase = Phase.COMPILE;
    }

    private void compileStage(AgentManager manager, BuildMemory memory) {
        if (bounds == null) {
            fail(manager, "尚未选择坐标范围");
            return;
        }
        List<String> issues = new ArrayList<>();
        // 阶段前缀由本地补齐，模型不必为了命名规则牺牲表达
        stagePlan.normalizeZoneIds("s" + (stageIndex + 1) + "_");
        stagePlan.validate(bounds, BlockIndex.get(), issues);
        for (String issue : issues) {
            manager.log(taskId, "校验", "WARN", issue);
        }
        cumulative = mergeStage(cumulative, stagePlan);
        memory.currentPlanJson = cumulative.toJson();
        // 方案层已不再携带风格字段
        if (!cumulative.notes.isBlank()) {
            memory.notes = cumulative.notes;
        }
        memory.zones = zoneLines(cumulative);
        manager.setStatus(taskId, "BUILDING", phaseName());

        PlanCompiler.Compilation compilation = PlanCompiler.compile(cumulative, bounds,
                BuilderConfig.MAX_BLOCKS_PER_TASK.getAsInt());
        List<Placement> full = new ArrayList<>(compilation.blocks);
        this.pendingEntities = new ArrayList<>(compilation.entities);
        memory.entities = new ArrayList<>(pendingEntities);

        // 把之前自动补出的支撑方块并回方块图，避免下一阶段把它们当成「消失」而反复清空
        Set<Long> fullKeys = new HashSet<>();
        for (Placement p : full) {
            fullKeys.add(p.key());
        }
        for (Placement support : extraSupports.values()) {
            if (!fullKeys.contains(support.key())) {
                full.add(support);
                fullKeys.add(support.key());
            }
        }
        // 上一阶段存在、但新累计方案不再覆盖的方块，需要显式变回空气
        if (!working.isEmpty()) {
            for (Placement p : working) {
                if (!fullKeys.contains(p.key())) {
                    full.add(new Placement(p.x, p.y, p.z, "minecraft:air"));
                }
            }
        }
        pending = PlanCompiler.diff(full, working);
        PlanCompiler.Batch batch = PlanCompiler.orderForExecution(pending);
        executionList = batch.ordered;
        groupEnd = batch.groupEnd;
        // 本阶段会被填成实体的坐标，用于判断重力方块下方是否已有计划中的支撑
        plannedNonAir = new HashSet<>();
        for (Placement p : executionList) {
            if (!"minecraft:air".equals(p.block)) {
                plannedNonAir.add(p.key());
            }
        }
        working = full;

        // 实体走独立通道：只记录需要移除的稳定 UUID，实体本身在阶段结束时幂等重建
        entityRemovals = new ArrayList<>();
        for (Placement marker : PlanCompiler.removedEntities(workingEntities, pendingEntities)) {
            entityRemovals.add(marker.entityRemove);
        }
        workingEntities = new ArrayList<>(pendingEntities);

        cursor = 0;
        placed = 0;
        skipped = 0;
        lastPercent = -1;
        manager.log(taskId, "编译", "INFO", "累计 " + cumulative.zones.size()
                + " 个区域，展开 " + full.size() + " 格；本阶段需改动 " + pending.size()
                + " 格 / " + pendingEntities.size() + " 个实体（本地展开，0 次模型调用）");
        manager.markDirty(taskId);
        phase = Phase.EXECUTE;
    }

    /** 把本阶段的区域合并进累计方案；remove 会清掉所有与之重叠的旧区域。 */
    private static BuildPlan mergeStage(BuildPlan cumulative, BuildPlan patch) {
        BuildPlan merged = new BuildPlan();
        merged.title = patch.title.isBlank() ? cumulative.title : patch.title;
        merged.notes = patch.notes.isBlank() ? cumulative.notes : patch.notes;
        merged.design = appendText(cumulative.design, patch.design);
        merged.extra = new JsonObject();
        if (cumulative.extra != null) {
            for (Map.Entry<String, JsonElement> e : cumulative.extra.entrySet()) {
                merged.extra.add(e.getKey(), e.getValue());
            }
        }
        if (patch.extra != null) {
            for (Map.Entry<String, JsonElement> e : patch.extra.entrySet()) {
                merged.extra.add(e.getKey(), e.getValue());
            }
        }
        merged.palette.putAll(cumulative.palette);
        for (Map.Entry<String, String> e : patch.palette.entrySet()) {
            merged.palette.putIfAbsent(e.getKey(), e.getValue());
        }
        Map<String, BuildPlan.Zone> byId = new LinkedHashMap<>();
        for (BuildPlan.Zone z : cumulative.zones) {
            byId.put(z.id, z);
        }
        for (BuildPlan.Zone z : patch.zones) {
            if ("remove".equals(z.op)) {
                byId.values().removeIf(old -> overlaps(old.box, z.box));
                z.shape = "box";
                z.material = "minecraft:air";
            }
            byId.put(z.id, z);
        }
        merged.zones.addAll(byId.values());
        return merged;
    }

    private static String appendText(String previous, String addition) {
        if (addition == null || addition.isBlank()) {
            return previous == null ? "" : previous;
        }
        if (previous == null || previous.isBlank()) {
            return addition;
        }
        return previous + "\n\n" + addition;
    }

    private static boolean overlaps(int[] a, int[] b) {
        return Math.max(Math.min(a[0], a[3]), Math.min(b[0], b[3]))
                <= Math.min(Math.max(a[0], a[3]), Math.max(b[0], b[3]))
                && Math.max(Math.min(a[1], a[4]), Math.min(b[1], b[4]))
                <= Math.min(Math.max(a[1], a[4]), Math.max(b[1], b[4]))
                && Math.max(Math.min(a[2], a[5]), Math.min(b[2], b[5]))
                <= Math.min(Math.max(a[2], a[5]), Math.max(b[2], b[5]));
    }

    private static List<BuildMemory.ZoneLine> zoneLines(BuildPlan plan) {
        List<BuildMemory.ZoneLine> out = new ArrayList<>();
        for (BuildPlan.Zone z : plan.zones) {
            BuildMemory.ZoneLine line = new BuildMemory.ZoneLine();
            line.id = z.id;
            line.op = z.op;
            line.name = z.name;
            line.shape = z.shape;
            line.box = "(" + z.box[0] + "," + z.box[1] + "," + z.box[2] + ")~("
                    + z.box[3] + "," + z.box[4] + "," + z.box[5] + ")";
            line.material = z.material;
            out.add(line);
        }
        return out;
    }

    private void execute(MinecraftServer server, AgentManager manager, BuildMemory memory) {
        ServerLevel level = resolveLevel(server, memory.dimension);
        if (level == null) {
            fail(manager, "找不到维度 " + memory.dimension);
            return;
        }
        int cap = BuilderConfig.BLOCKS_PER_TICK.getAsInt();
        int budget = cap;
        int total = executionList.size();
        while (cursor < total && budget > 0) {
            int end = cursor < groupEnd.length ? groupEnd[cursor] : cursor;
            int need = end - cursor + 1;
            // 组内不拆断：本 tick 放不下一整组门/床就整体推迟，避免只放下半扇门
            if (budget < need && need <= cap) {
                break;
            }
            int index = cursor;
            for (; index <= end && budget > 0; index++) {
                placeOne(level, executionList.get(index));
                budget--;
            }
            cursor = index;
        }
        double stageProgress = total == 0 ? 1.0 : (double) cursor / total;
        int percent = (int) (stageProgress * 100);
        if (percent / 10 != lastPercent / 10 || cursor >= total) {
            lastPercent = percent;
            manager.setStatus(taskId, "BUILDING", phaseName() + " " + percent + "%");
            manager.log(taskId, "执行", "INFO", "第 " + (stageIndex + 1) + "/" + stages.size()
                    + " 阶段进度 " + percent + "%（" + cursor + "/" + total + "），本阶段已放置 "
                    + placed + " 格，跳过 " + skipped + " 格，补支撑 " + supportsAdded + " 格");
        }
        if (cursor < total) {
            return;
        }
        // 阶段收尾：实体走独立幂等通道，不进方块图 diff
        for (String uuid : entityRemovals) {
            if (NbtEntityOps.removeEntity(level, uuid)) {
                entitiesRemoved++;
            }
        }
        for (Placement entity : pendingEntities) {
            if (NbtEntityOps.applyEntity(level, entity)) {
                entitiesPlaced++;
            }
        }
        totalPlaced += placed;
        // 每阶段结束确定性抽取结构化事实层（0 token），供下一阶段提示词与 GUI 使用
        memory.fact = FactExtractor.extract(memory.version, bounds,
                working, cumulative, workingEntities);
        manager.log(taskId, "事实层", "INFO", "已抽取 " + memory.fact.levels.size() + " 层 / "
                + memory.fact.rooms.size() + " 个区域 / " + memory.fact.features.size()
                + " 个构件，空间索引 " + memory.fact.index.size() + " 格");
        String stageName = stages.get(stageIndex).name();
        manager.log(taskId, "阶段完成", "INFO", "「" + stageName + "」施工完毕，改动 " + placed
                + " 格；NBT " + nbtApplied + " 处，实体 " + entitiesPlaced + " 个，补支撑 "
                + supportsAdded + " 格；当前累计方块图 " + working.size() + " 格");
        stageIndex++;
        if (stageIndex < stages.size()) {
            boolean allowed = inspectAfterStage && !offline
                    && BuilderConfig.VISUAL_ENABLED.getAsBoolean()
                    && stageInspectionsDone < BuilderConfig.visionMaxStageInspections();
            if (allowed) {
                pendingInspectionStage = stageIndex - 1;
                inspectionFuture = null;
                phase = Phase.INSPECT;
                manager.log(taskId, "视觉检查", "INFO", "「" + stageName + "」施工完毕，按模型请求做第 "
                        + (stageInspectionsDone + 1) + " 次中途拍照复查；检查结论会注入下一阶段");
            } else {
                if (inspectAfterStage) {
                    manager.log(taskId, "视觉检查", "INFO", "「" + stageName
                            + "」的中途复查请求已跳过（次数上限 / 视觉检查关闭 / 离线模式）；"
                            + "建造完成后仍会强制检查一遍");
                }
                advanceToNextStage(manager);
            }
        } else {
            verify(manager, memory);
            phase = Phase.DONE;
            finished = true;
        }
    }

    /** 模型主动请求的中途检查：等拍照 + 多模态审查完成，结论写回记忆后才继续下一阶段。 */
    private void inspectStage(AgentManager manager, BuildMemory memory) {
        if (inspectionFuture == null) {
            String stageName = stages.get(pendingInspectionStage).name();
            inspectionFuture = manager.submitStageInspection(taskId, stageName, memory,
                    new ArrayList<>(working), pendingInspectionStage);
            return;
        }
        if (!inspectionFuture.isDone()) {
            return;
        }
        AgentManager.StageInspection inspection;
        try {
            inspection = inspectionFuture.get();
        } catch (Exception e) {
            manager.log(taskId, "视觉检查", "WARN", "中途检查异常（" + e.getMessage()
                    + "），跳过继续施工；建造完成后仍会强制检查一遍");
            inspectionFuture = null;
            advanceToNextStage(manager);
            return;
        }
        inspectionFuture = null;
        stageInspectionsDone++;
        manager.applyStageInspection(taskId, memory,
                stages.get(pendingInspectionStage).name(), inspection);
        manager.log(taskId, "视觉检查", "INFO", "第 " + stageInspectionsDone
                + " 次中途检查完成：照片 " + inspection.batch().lines.size()
                + " 张，审查结论已写进结构化记忆");
        advanceToNextStage(manager);
    }

    private void advanceToNextStage(AgentManager manager) {
        inspectAfterStage = false;
        phase = Phase.WAIT_LLM;
        future = null;
        manager.log(taskId, "阶段", "INFO", "进入第 " + (stageIndex + 1) + "/"
                + stages.size() + " 阶段「" + stages.get(stageIndex).name() + "」");
    }

    /** 单个放置单元：方块（带完整状态）、BlockEntity NBT、实体、实体删除、支撑校验。 */
    private void placeOne(ServerLevel level, Placement placement) {
        placement.normalize();
        if (placement.isEntityRemoval()) {
            if (NbtEntityOps.removeEntity(level, placement.entityRemove)) {
                entitiesRemoved++;
            }
            return;
        }
        if (placement.isEntity()) {
            if (NbtEntityOps.applyEntity(level, placement)) {
                entitiesPlaced++;
            } else {
                skipped++;
            }
            return;
        }
        BlockPos pos = new BlockPos(placement.x, placement.y, placement.z);
        if (!level.isLoaded(pos)) {
            level.getChunk(placement.x >> 4, placement.z >> 4);
            if (!level.isLoaded(pos)) {
                skipped++;
                return;
            }
        }
        BlockState state = BlockIndex.toState(placement.fullState());
        if (state == null) {
            skipped++;
            return;
        }
        if (BuilderConfig.AUTO_SUPPORT.getAsBoolean() && needsSupport(placement)) {
            BlockPos below = pos.below();
            boolean plannedBelow = plannedNonAir.contains(
                    PlanCompiler.packedKey(below.getX(), below.getY(), below.getZ()));
            if (bounds.contains(below.getX(), below.getY(), below.getZ())
                    && level.getBlockState(below).isAir() && !plannedBelow) {
                BlockState support = BlockIndex.toState("minecraft:stone_bricks");
                if (support != null) {
                    level.setBlock(below, support, 2);
                    Placement added = new Placement(below.getX(), below.getY(), below.getZ(),
                            "minecraft:stone_bricks");
                    added.normalize();
                    extraSupports.put(added.key(), added);
                    supportsAdded++;
                } else {
                    unsupported++;
                }
            }
        }
        level.setBlock(pos, state, 2);
        placed++;
        if (!placement.nbt.isBlank() && NbtEntityOps.applyBlockEntity(level, pos, placement.nbt)) {
            nbtApplied++;
        }
    }

    /** 门/床等成组构件的主块，以及沙子/沙砾/混凝土粉末等重力方块，都必须有下方实心支撑。 */
    private static boolean needsSupport(Placement placement) {
        if (BlockIndex.needsSupport(placement.block)) {
            return true;
        }
        return placement.order == 1 && !placement.groupId.isBlank()
                && BlockComponents.find(placement.block).requiresSupport();
    }

    private void verify(AgentManager manager, BuildMemory memory) {
        memory.version++;
        memory.materials = PlanCompiler.digest(working);
        long nonAir = working.stream().filter(p -> !"minecraft:air".equals(p.block)).count();
        memory.blocksPlacedTotal = nonAir;
        memory.status = "DONE";
        memory.stage = "完成";

        BuildMemory.VersionLine line = new BuildMemory.VersionLine();
        line.version = memory.version;
        line.ts = System.currentTimeMillis();
        line.instruction = brief;
        line.blocks = (int) nonAir;
        line.digest = memory.digestText();
        memory.versions.add(line);
        if (!memory.instructions.isEmpty()) {
            BuildMemory.Instruction last = memory.instructions.get(memory.instructions.size() - 1);
            last.version = memory.version;
            last.blocks = totalPlaced;
            last.modelCalls = memory.modelCalls - startModelCalls;
        }
        memory.entities = new ArrayList<>(workingEntities);
        memory.fact = FactExtractor.extract(memory.version, bounds,
                working, cumulative, workingEntities);
        manager.savePlacements(taskId, memory.version, working);
        manager.saveMemory(memory);
        manager.requestSemantic(taskId, memory.fact, memory.planDocument);
        manager.scheduleFinalInspection(taskId);
        manager.updateItemPointer(memory);
        manager.log(taskId, "归档", "INFO", "v" + memory.version
                + " 已写入本地记忆（游戏/服务器重启后自动恢复）");
        manager.log(taskId, "完成", "INFO", stages.size() + " 个阶段全部完成：方块 " + nonAir
                + " 格，本次改动 " + totalPlaced + " 格；NBT " + nbtApplied + " 处 / 实体 "
                + entitiesPlaced + " 个（移除 " + entitiesRemoved + " 个）/ 补支撑 " + supportsAdded
                + " 格 / 缺支撑 " + unsupported + " 格；本次模型调用 "
                + (memory.modelCalls - startModelCalls) + " 次 / 累计 "
                + (memory.promptTokens + memory.completionTokens) + " token；总耗时 "
                + (System.currentTimeMillis() - startedAt) / 1000 + "s");
        manager.markDirty(taskId);
    }

    private static ServerLevel resolveLevel(MinecraftServer server, String dimension) {
        try {
            ResourceKey<Level> key = ResourceKey.create(net.minecraft.core.registries.Registries.DIMENSION,
                    ResourceLocation.parse(dimension));
            ServerLevel level = server.getLevel(key);
            return level == null ? server.overworld() : level;
        } catch (Exception e) {
            return server.overworld();
        }
    }

    private void fail(AgentManager manager, String reason) {
        manager.log(taskId, "异常", "ERROR", reason);
        manager.setStatus(taskId, "FAILED", reason);
        phase = Phase.FAILED;
        finished = true;
    }
}
