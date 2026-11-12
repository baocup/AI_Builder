package baocup.builder.agent;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import baocup.builder.BuilderConfig;
import baocup.builder.BuilderMod;
import baocup.builder.memory.BuildMemory;
import baocup.builder.util.Bounds;
import baocup.builder.util.Json;

/**
 * 视觉检查长链：拍照 → 视觉模型审查 → 按 AI 请求补拍额外视角 → 最终审查。
 *
 * 渲染与网络调用都在后台线程，服务端主线程只轮询 future；
 * 相机是包围盒周围的独立虚拟相机，不读取玩家屏幕、不改变玩家视角。
 */
public final class VisualJob {
    private static final ExecutorService RENDER_POOL = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "builder-visual-render");
        thread.setDaemon(true);
        return thread;
    });

    private enum Phase { CAPTURE, VISION_FIRST, EXTRA_CAPTURE, VISION_FINAL, DONE }

    private final String taskId;
    private final boolean analyze;
    /** 自动审查不补拍，手动「AI 自由查看」才允许模型追加机位。 */
    private final boolean allowExtraViews;
    private final CameraPose customPose;
    /** 施工中途检查已经积累的报告，完成检查的结论追加在后面，不覆盖。 */
    private final String historyPrefix;
    private Phase phase = Phase.CAPTURE;
    private Bounds bounds;
    private List<CameraPose> poses = new ArrayList<>();
    private final List<CameraPose> allPoses = new ArrayList<>();
    private List<byte[]> images = new ArrayList<>();
    private String localReport = "";
    private CompletableFuture<VisualInspection.Batch> captureFuture;
    private CompletableFuture<LlmClient.Result> visionFuture;

    public VisualJob(String taskId, boolean analyze, boolean allowExtraViews,
                     CameraPose customPose, BuildMemory memory) {
        this.taskId = taskId;
        this.analyze = analyze;
        this.allowExtraViews = allowExtraViews;
        this.customPose = customPose;
        this.historyPrefix = memory != null && memory.visualReport != null ? memory.visualReport : "";
        this.bounds = memory != null && memory.bounds != null ? memory.bounds.copy()
                : new Bounds(0, 64, 0, 15, 72, 15);
    }

    public boolean finished() {
        return phase == Phase.DONE;
    }

    public void tick(AgentManager manager) {
        BuildMemory memory = manager.memory(taskId);
        if (memory == null) {
            phase = Phase.DONE;
            return;
        }
        try {
            switch (phase) {
                case CAPTURE -> doCapture(manager, memory);
                case VISION_FIRST -> doFirstVision(manager, memory);
                case EXTRA_CAPTURE -> doExtraCapture(manager, memory);
                case VISION_FINAL -> doFinalVision(manager, memory);
                default -> {
                }
            }
        } catch (Exception e) {
            manager.log(taskId, "视觉检查", "ERROR", "阶段 " + phase + " 异常：" + e.getMessage());
            BuilderMod.LOGGER.warn("[建造] 视觉检查异常", e);
            phase = Phase.DONE;
        }
    }

    private void doCapture(AgentManager manager, BuildMemory memory) throws Exception {
        if (captureFuture == null) {
            poses = customPose == null
                    ? CameraRig.around(bounds, BuilderConfig.VISUAL_VIEWS.getAsInt())
                    : List.of(customPose);
            List<Placement> voxels = manager.visualVoxels(memory);
            int width = BuilderConfig.VISUAL_WIDTH.getAsInt();
            int height = BuilderConfig.VISUAL_HEIGHT.getAsInt();
            int version = memory.version;
            java.nio.file.Path dir = manager.captureDir(taskId);
            manager.log(taskId, "视觉检查", "INFO", "在选区周围布置 " + poses.size()
                    + " 台独立虚拟相机（非玩家截图），开始离屏渲染 " + width + "×" + height
                    + " 高清照片（含方块边缘细节）");
            captureFuture = CompletableFuture.supplyAsync(
                    () -> VisualInspection.capture(taskId, version, bounds, voxels, poses, dir, width, height),
                    RENDER_POOL);
            return;
        }
        if (!captureFuture.isDone()) {
            return;
        }
        VisualInspection.Batch batch = captureFuture.get();
        captureFuture = null;
        applyBatch(manager, memory, batch);
        manager.refreshCameraMarkers(memory, poses);
        if (analyze && !images.isEmpty() && BuilderConfig.VISUAL_ENABLED.getAsBoolean()) {
            phase = Phase.VISION_FIRST;
        } else {
            manager.log(taskId, "视觉检查", "INFO", "拍照完成，共 " + images.size()
                    + " 张 " + BuilderConfig.VISUAL_WIDTH.getAsInt() + "×"
                    + BuilderConfig.VISUAL_HEIGHT.getAsInt()
                    + " 照片；当前为离线模式或仅拍照，仅保存本地渲染统计");
            phase = Phase.DONE;
        }
    }

    private void doFirstVision(AgentManager manager, BuildMemory memory) throws Exception {
        if (visionFuture == null) {
            String user = Prompts.visionUser(memory, poses, localReport, false, allowExtraViews);
            manager.log(taskId, "视觉检查", "INFO", "把 " + images.size()
                    + " 张高清照片作为多模态输入交给 " + BuilderConfig.visionModel()
                    + " 审查" + (allowExtraViews ? "，允许它自由追加视角" : "（完成检查不补拍）"));
            visionFuture = manager.submitVision(taskId, Prompts.VISION_SYSTEM, user, images);
            return;
        }
        if (!visionFuture.isDone()) {
            return;
        }
        LlmClient.Result result = visionFuture.get();
        visionFuture = null;
        manager.recordTokens(taskId, result);
        manager.logReasoning(taskId, "视觉审查", result.reasoning());
        if (!result.ok()) {
            manager.log(taskId, "视觉检查", "WARN", "视觉模型调用失败（" + result.error()
                    + "）；照片与本地统计已完整保存");
            phase = Phase.DONE;
            return;
        }
        storeVisionText(manager, memory, result.content(), false);
        List<CameraPose> extra = allowExtraViews
                ? CameraRig.requestedViews(result.content(), bounds,
                        BuilderConfig.VISION_MAX_EXTRA_VIEWS.getAsInt())
                : List.of();
        if (extra.isEmpty()) {
            manager.log(taskId, "视觉检查", "INFO", allowExtraViews
                    ? "视觉模型未请求额外视角，审查完成" : "完成后的强制视觉审查完成，完整报告已写入结构化记忆");
            phase = Phase.DONE;
            return;
        }
        manager.log(taskId, "视觉检查", "INFO", "AI 自由查看：请求补拍 " + extra.size() + " 个额外视角");
        poses = extra;
        List<Placement> voxels = manager.visualVoxels(memory);
        int width = BuilderConfig.VISUAL_WIDTH.getAsInt();
        int height = BuilderConfig.VISUAL_HEIGHT.getAsInt();
        int version = memory.version;
        java.nio.file.Path dir = manager.captureDir(taskId);
        captureFuture = CompletableFuture.supplyAsync(
                () -> VisualInspection.capture(taskId, version, bounds, voxels, poses, dir, width, height),
                RENDER_POOL);
        phase = Phase.EXTRA_CAPTURE;
    }

    private void doExtraCapture(AgentManager manager, BuildMemory memory) throws Exception {
        if (captureFuture == null || !captureFuture.isDone()) {
            return;
        }
        VisualInspection.Batch batch = captureFuture.get();
        captureFuture = null;
        applyBatch(manager, memory, batch);
        manager.log(taskId, "视觉检查", "INFO", "已补拍 " + batch.images.size() + " 张，交给视觉模型做最终审查");
        phase = Phase.VISION_FINAL;
    }

    private void doFinalVision(AgentManager manager, BuildMemory memory) throws Exception {
        if (visionFuture == null) {
            String user = Prompts.visionUser(memory, allPoses, localReport, true);
            // 最终轮把第一批 + 补拍的照片都给模型
            visionFuture = manager.submitVision(taskId, Prompts.VISION_SYSTEM, user, images);
            return;
        }
        if (!visionFuture.isDone()) {
            return;
        }
        LlmClient.Result result = visionFuture.get();
        visionFuture = null;
        manager.recordTokens(taskId, result);
        manager.logReasoning(taskId, "视觉审查最终轮", result.reasoning());
        if (result.ok()) {
            storeVisionText(manager, memory, result.content(), true);
            manager.log(taskId, "视觉检查", "INFO", "视觉审查完成，完整报告已写入结构化记忆");
        } else {
            manager.log(taskId, "视觉检查", "WARN", "最终轮视觉模型调用失败（" + result.error() + "）");
        }
        phase = Phase.DONE;
    }

    private void applyBatch(AgentManager manager, BuildMemory memory, VisualInspection.Batch batch) {
        allPoses.addAll(poses);
        memory.captures.addAll(batch.lines);
        images.addAll(batch.images);
        localReport = localReport.isEmpty() ? batch.localReport : localReport + batch.localReport;
        memory.visualReport = historyPrefix.isBlank()
                ? localReport : historyPrefix + "\n" + localReport;
        for (BuildMemory.CaptureLine line : batch.lines) {
            manager.log(taskId, "视觉检查", "INFO", "照片 " + line.name + " → " + line.path
                    + "（建筑覆盖 " + line.blockPixels + " 像素）");
        }
        manager.saveMemory(memory);
        manager.markDirty(taskId);
    }

    private void storeVisionText(AgentManager manager, BuildMemory memory,
                                 String raw, boolean followUp) {
        String json = LlmClient.extractJson(raw);
        if (json == null) {
            // 模型没按 JSON 输出时，把完整原文当作观察报告保存，绝不丢弃
            memory.visualReport = (memory.visualReport.isBlank() ? localReport : memory.visualReport)
                    + "\n视觉模型原文：\n" + raw;
            manager.saveMemory(memory);
            return;
        }
        JsonObject root;
        try {
            root = Json.GSON.fromJson(json, JsonObject.class);
        } catch (Exception e) {
            memory.visualReport = (memory.visualReport.isBlank() ? localReport : memory.visualReport)
                    + "\n视觉模型原文：\n" + raw;
            manager.saveMemory(memory);
            return;
        }
        if (root == null) {
            return;
        }
        String observations = Json.str(root, "observations", "");
        String assessment = Json.str(root, "assessment", "");
        StringBuilder text = new StringBuilder();
        // 完整保留中途检查报告、本地统计与首轮观察，最终结论只往后追加
        text.append(memory.visualReport.isBlank() ? localReport : memory.visualReport).append('\n');
        text.append(followUp ? "\n【最终视觉审查结论】\n" : "\n【首轮视觉审查】\n");
        text.append(observations).append('\n');
        if (!assessment.isBlank()) {
            text.append("总体评价：").append(assessment).append('\n');
        }
        JsonArray issues = root.getAsJsonArray("issues");
        if (issues != null) {
            for (JsonElement element : issues) {
                try {
                    String issue = element.getAsString();
                    memory.visualIssues.add(issue);
                    manager.log(taskId, "视觉检查", "WARN", "发现问题：" + issue);
                    text.append("· 问题：").append(issue).append('\n');
                } catch (Exception ignored) {
                    // 跳过非法条目
                }
            }
        }
        memory.visualReport = text.toString();
        manager.saveMemory(memory);
        manager.markDirty(taskId);
    }
}
