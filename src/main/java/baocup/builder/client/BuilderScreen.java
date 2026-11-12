package baocup.builder.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import baocup.builder.net.ModPackets;
import baocup.builder.util.Json;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.network.PacketDistributor;

/** 建造指令物品的完整 GUI：坐标、指令、Agent 输出、Plan 对话、结构化记忆、视觉检查。 */
public final class BuilderScreen extends Screen {
    public final String taskId;

    private static final int BG = 0xF0121722;
    private static final int PANEL = 0xE0202836;
    private static final int BORDER = 0xFF4A637E;
    private static final int TEXT = 0xFFE6ECF2;
    private static final int MUTED = 0xFF9BA9B8;
    private static final int ACCENT = 0xFF63C6E8;
    private static final int LINE_H = 11;

    private ClientState state;
    private int tab;

    private int left, top, widthPx, heightPx;
    private int leftX, leftW, rightX, rightW, contentTop, bottom;
    private int panelX, panelY, panelW, panelH;

    private final String[] coordText = { "", "", "", "", "", "" };
    private final EditBox[] coordBoxes = new EditBox[6];
    private boolean coordsEdited;
    private boolean suppressResponder;
    private String commandText = "";
    private String chatText = "";

    private EditBox commandBox;
    private EditBox chatBox;
    private EditBox poseBox;
    private Button buildButton, planButton, stopButton;
    private Button sendButton, confirmButton, planEnterButton, planExitButton;
    private final Button[] tabButtons = new Button[5];

    private int logScroll, chatScroll, infoScroll, planScroll, visualScroll;
    private boolean followLog = true, followChat = true, followVisual = true;
    private String poseText = "";
    private final List<Line> logLines = new ArrayList<>();
    private final List<Line> chatLines = new ArrayList<>();
    private final List<Line> infoLines = new ArrayList<>();
    private final List<Line> planLines = new ArrayList<>();
    private final List<Line> visualLines = new ArrayList<>();
    private boolean linesDirty = true;

    public BuilderScreen(ClientState state) {
        super(Component.literal("建造指令 · Agent"));
        this.taskId = state.taskId == null ? "" : state.taskId;
        this.state = state;
        for (int i = 0; i < 6; i++) {
            coordText[i] = state.bounds != null && state.bounds.length == 6
                    ? String.valueOf(state.bounds[i]) : "0";
        }
        commandText = "";
        send("sync", "{}");
    }

    private record Line(String text, int color) {
    }

    @Override
    public boolean isPauseScreen() {
        // 不能暂停：暂停会让单机的服务端 tick 停下来，Agent 就无法继续放置方块
        return false;
    }

    /** 外部 Agent 物品只展示 MCP 动作日志，不提供游戏内模型的建造入口。 */
    private boolean externalMode() {
        return state != null && "external".equals(state.mode);
    }

    @Override
    protected void init() {
        if (externalMode()) {
            // 外部物品只保留「Agent 输出」：建造页不再构建，左侧坐标框仍可手动改范围
            tab = 1;
        }
        widthPx = Math.min(this.width - 16, 820);
        heightPx = Math.min(this.height - 16, 480);
        left = (this.width - widthPx) / 2;
        top = (this.height - heightPx) / 2;
        leftX = left + 8;
        leftW = 196;
        rightX = leftX + leftW + 8;
        rightW = widthPx - (rightX - left) - 8;
        contentTop = top + 52;
        bottom = top + heightPx - 8;

        buildLeftPanel();
        buildTabs();
        buildTabContent();
        refreshButtons();
        if (externalMode()) {
            // 外部任务的决策者是外部 Agent：坐标仍可手动输入，但游戏内模型的入口没有意义
            if (planButton != null) {
                planButton.visible = false;
                planButton.active = false;
            }
            if (buildButton != null) {
                buildButton.active = false;
            }
        }
        rebuildLines();
    }

    private void buildLeftPanel() {
        int y = top + 30;
        int boxW = (leftW - 12) / 3;
        for (int row = 0; row < 2; row++) {
            for (int col = 0; col < 3; col++) {
                int index = row * 3 + col;
                coordBoxes[index] = addEdit(leftX + col * (boxW + 4), y + 22 + row * 36, boxW, 18,
                        "XYZ".substring(col, col + 1), 10, coordText[index], value -> {
                            if (!suppressResponder) {
                                coordText[index] = value;
                                coordsEdited = true;
                            }
                        });
            }
        }
        addButton("应用坐标", leftX, y + 96, leftW, 20, this::applyBounds);
        // 外部 Agent 由客户端 MCP 驱动，没有"进行中的游戏内任务"，不需要中止按钮
        if (externalMode()) {
            addButton("刷新状态", leftX, bottom - 42, leftW, 20, () -> send("sync", "{}"));
        } else {
            addButton("刷新状态", leftX, bottom - 42, (leftW - 4) / 2, 20, () -> send("sync", "{}"));
            addButton("中止任务", leftX + (leftW + 4) / 2, bottom - 42, (leftW - 4) / 2, 20,
                    () -> send("stop", "{}"));
        }
    }

    private void buildTabs() {
        String[] labels = { "建造", "Agent 输出", "Plan 对话", "记忆", "视觉检查" };
        int gap = 4;
        int w = (rightW - gap * 4) / 5;
        for (int i = 0; i < tabButtons.length; i++) {
            final int target = i;
            tabButtons[i] = addButton(labels[i], rightX + i * (w + gap), top + 28, w, 20, () -> {
                tab = target;
                rebuildWidgets();
            });
            // 外部 Agent 物品只有「Agent 输出」一页，切换按钮全部隐藏
            if (externalMode()) {
                tabButtons[i].visible = false;
                tabButtons[i].active = false;
            }
        }
    }

    private void buildTabContent() {
        panelX = rightX + 2;
        panelW = rightW - 4;
        switch (tab) {
            case 0 -> {
                commandBox = addEdit(rightX, contentTop + 14, rightW, 20, "建造指令", 512, commandText,
                        value -> commandText = value);
                int gap = 4;
                int bw = (rightW - gap * 2) / 3;
                buildButton = addButton("开始建造", rightX, contentTop + 40, bw, 20, this::build);
                planButton = addButton("进入 Plan 模式", rightX + (bw + gap), contentTop + 40, bw, 20,
                        () -> {
                            send("plan_open", "{}");
                            tab = 2;
                            rebuildWidgets();
                        });
                stopButton = addButton("中止", rightX + 2 * (bw + gap), contentTop + 40, bw, 20,
                        () -> send("stop", "{}"));
                panelY = contentTop + 78;
                panelH = bottom - panelY - 4;
            }
            case 1 -> {
                panelY = contentTop + 14;
                panelH = bottom - panelY - 28;
                addButton("跟随最新", rightX, bottom - 20, (rightW - 8) / 2, 20, () -> {
                    followLog = true;
                    logScroll = maxScroll(logLines.size(), panelH);
                });
                addButton("回到顶部", rightX + (rightW - 8) / 2 + 8, bottom - 20, (rightW - 8) / 2, 20,
                        () -> {
                            followLog = false;
                            logScroll = 0;
                        });
            }
            case 2 -> {
                panelY = contentTop + 14;
                int chatY = bottom - 64;
                panelH = chatY - panelY - 6;
                chatBox = addEdit(rightX, chatY, rightW - 96, 20, "Plan 对话", 512, chatText,
                        value -> chatText = value);
                sendButton = addButton("发送", rightX + rightW - 90, chatY, 90, 20, this::sendChat);
                int gap = 4;
                int bw = (rightW - gap * 2) / 3;
                confirmButton = addButton("按计划建造", rightX, chatY + 24, bw, 20, this::confirmPlan);
                planExitButton = addButton("退出 Plan", rightX + bw + gap, chatY + 24, bw, 20,
                        () -> send("plan_exit", "{}"));
                planEnterButton = addButton("进入 Plan", rightX + 2 * (bw + gap), chatY + 24, bw, 20,
                        () -> send("plan_open", "{}"));
            }
            case 3 -> {
                panelY = contentTop + 14;
                panelH = bottom - panelY - 28;
                addButton("回到建造页", rightX, bottom - 20, rightW, 20, () -> {
                    tab = 0;
                    rebuildWidgets();
                });
            }
            case 4 -> {
                panelY = contentTop + 14;
                int poseY = bottom - 68;
                panelH = poseY - panelY - 6;
                poseBox = addEdit(rightX, poseY, rightW - 128, 20,
                        "自定义视角 x,y,z,yaw,pitch", 160, poseText, value -> poseText = value);
                addButton("自定义视角", rightX + rightW - 122, poseY, 122, 20, this::sendCustomPose);
                int gap = 4;
                int bw = (rightW - gap * 2) / 3;
                addButton("拍照检查", rightX, poseY + 24, bw, 20, () -> send("visual_capture", "{}"));
                addButton("AI 自由查看", rightX + bw + gap, poseY + 24, bw, 20,
                        () -> send("visual_analyze", "{}"));
                addButton("回到建造页", rightX + 2 * (bw + gap), poseY + 24, bw, 20, () -> {
                    tab = 0;
                    rebuildWidgets();
                });
            }
            default -> {
                panelY = contentTop + 14;
                panelH = bottom - panelY - 28;
            }
        }
    }

    private EditBox addEdit(int x, int y, int w, int h, String label, int maxLength,
                            String value, Consumer<String> responder) {
        EditBox box = new EditBox(this.font, x, y, w, h, Component.literal(label));
        box.setMaxLength(maxLength);
        box.setValue(value == null ? "" : value);
        box.setResponder(responder);
        box.setHint(Component.literal(label));
        this.addRenderableWidget(box);
        return box;
    }

    private Button addButton(String label, int x, int y, int w, int h, Runnable action) {
        Button button = Button.builder(Component.literal(label), b -> action.run()).bounds(x, y, w, h).build();
        this.addRenderableWidget(button);
        return button;
    }

    // ------------------------------------------------------------------
    // 状态同步
    // ------------------------------------------------------------------

    public void applyState(ClientState snapshot) {
        this.state = snapshot;
        if (!coordsEdited && snapshot.bounds != null && snapshot.bounds.length == 6) {
            for (int i = 0; i < 6; i++) {
                coordText[i] = String.valueOf(snapshot.bounds[i]);
                if (coordBoxes[i] != null) {
                    suppressResponder = true;
                    coordBoxes[i].setValue(coordText[i]);
                    suppressResponder = false;
                }
            }
        }
        linesDirty = true;
        rebuildLines();
        refreshButtons();
    }

    private void refreshButtons() {
        for (int i = 0; i < tabButtons.length; i++) {
            if (tabButtons[i] != null) {
                tabButtons[i].active = i != tab;
            }
        }
        boolean busy = state != null && "BUILDING".equals(state.status);
        boolean built = state != null && state.hasBuilt;
        if (buildButton != null) {
            buildButton.active = !busy;
            buildButton.setMessage(Component.literal(built ? "修改建筑" : "开始建造"));
        }
        if (stopButton != null) stopButton.active = busy;
        if (planButton != null) planButton.active = !busy;
        if (confirmButton != null) confirmButton.active = !busy && state != null && state.planReady;
        if (planExitButton != null) planExitButton.active = state != null && state.planMode;
        if (planEnterButton != null) planEnterButton.active = state == null || !state.planMode;
    }

    private void rebuildLines() {
        if (this.font == null) {
            linesDirty = true;
            return;
        }
        linesDirty = false;
        logLines.clear();
        chatLines.clear();
        infoLines.clear();
        planLines.clear();
        visualLines.clear();
        if (state == null) {
            return;
        }
        int wrapWidth = Math.max(60, panelW - 14);
        // 外部 Agent 的物品只看本地 MCP 的动作日志（服务端不再驱动这条链路）
        java.util.List<ClientState.LogItem> source = externalMode()
                ? ClientBuildHost.INSTANCE.snapshotLogs(state.taskId) : state.safeLogs();
        for (ClientState.LogItem item : source) {
            int color = switch (state.safe(item.level)) {
                case "WARN" -> 0xFFFFCC66;
                case "ERROR" -> 0xFFFF7777;
                default -> "思考".equals(item.stage) ? 0xFFC3A6F0 : TEXT;
            };
            String prefix = "[" + state.safe(item.stage) + "] ";
            for (String s : wrap(prefix + state.safe(item.text), wrapWidth)) {
                logLines.add(new Line(s, color));
            }
        }
        for (ClientState.ChatItem item : state.safeChat()) {
            boolean user = "user".equals(item.role);
            String prefix = user ? "你： " : "Agent： ";
            int color = user ? ACCENT : TEXT;
            for (String s : wrap(prefix + state.safe(item.text), wrapWidth)) {
                chatLines.add(new Line(s, color));
            }
        }
        for (String s : wrap(state.safe(state.planText), wrapWidth)) {
            planLines.add(new Line(s, TEXT));
        }
        int warnColor = 0xFFFFCC66;
        int okColor = 0xFF8FD6A0;
        infoLines.add(new Line("任务指针 " + state.safe(state.taskId), MUTED));
        infoLines.add(new Line("拥有者 " + state.safe(state.owner) + "   维度 " + state.safe(state.dimension), MUTED));
        infoLines.add(new Line("最近目标 " + state.safe(state.userBrief), TEXT));
        infoLines.add(new Line("—— 结构化事实层（AI 修改建筑时以此为准）——", ACCENT));
        String factSummary = state.safe(state.factSummary);
        if (factSummary.isBlank()) {
            infoLines.add(new Line("  尚未抽取，完成任一施工阶段后自动生成（0 额外 token）", MUTED));
        } else {
            for (String line : wrap(factSummary, wrapWidth)) {
                infoLines.add(new Line("  " + line, TEXT));
            }
        }
        infoLines.add(new Line("  楼层 " + state.safeFactLevels().size() + " / 区域 "
                + state.safeFactRooms().size() + " / 构件 " + state.safeFactFeatures().size()
                + "，坐标索引 " + state.factIndexSize + " 格，实体 " + state.entityCount + " 个", MUTED));
        infoLines.add(new Line("—— 楼层 ——", ACCENT));
        for (ClientState.FactLevel level : state.safeFactLevels()) {
            infoLines.add(new Line("  第 " + (level.index + 1) + " 层 y=" + level.yMin + "~"
                    + level.yMax + "，" + level.blocks + " 格，主材 "
                    + state.safe(level.dominantMaterial), MUTED));
        }
        infoLines.add(new Line("—— 房间 ——", ACCENT));
        if (state.safeFactRooms().isEmpty()) {
            infoLines.add(new Line("  （无）", MUTED));
        }
        for (ClientState.FactRoom room : state.safeFactRooms()) {
            infoLines.add(new Line("  " + room.id + " 「" + state.safe(room.name) + "」 用途="
                    + state.safe(room.purpose) + " 地板=" + room.floorCount
                    + " 墙=" + room.wallCount + " " + state.safe(room.box), TEXT));
        }
        infoLines.add(new Line("—— 构件树 ——", ACCENT));
        for (ClientState.FactFeature feature : state.safeFactFeatures()) {
            infoLines.add(new Line("  " + feature.id + " [" + feature.kind + "] "
                    + state.safe(feature.name) + " " + state.safe(feature.material).replace("minecraft:", "")
                    + " 朝向=" + state.safe(feature.facing) + " " + state.safe(feature.state), MUTED));
        }
        infoLines.add(new Line("—— LLM 语义层 ——", ACCENT));
        String semanticSummary = state.safe(state.semanticSummary);
        if (semanticSummary.isBlank()) {
            infoLines.add(new Line("  （离线模式或尚未生成）", MUTED));
        } else {
            for (String line : wrap(semanticSummary, wrapWidth)) {
                infoLines.add(new Line("  " + line, TEXT));
            }
        }
        for (ClientState.SemanticRoom room : state.safeSemanticRooms()) {
            infoLines.add(new Line("  " + room.roomId + " 「" + state.safe(room.name) + "」 用途="
                    + state.safe(room.purpose) + " 氛围=" + state.safe(room.mood), MUTED));
        }
        infoLines.add(new Line("—— 双写互校（事实层为准）——", ACCENT));
        boolean hasDifference = state.crossFactOnly > 0 || state.crossSemanticOnly > 0
                || state.crossConflicts > 0;
        if (state.safe(state.crossVerdict).isBlank()) {
            infoLines.add(new Line("  （尚未互校）", MUTED));
        } else {
            infoLines.add(new Line("  " + state.crossVerdict + "：事实独有 " + state.crossFactOnly
                    + " / 语义独有 " + state.crossSemanticOnly + " / 属性冲突 "
                    + state.crossConflicts, hasDifference ? warnColor : okColor));
        }
        for (String issue : state.safeCrossIssues()) {
            infoLines.add(new Line("  · " + issue, warnColor));
        }
        infoLines.add(new Line("—— 版本历史 ——", ACCENT));
        for (ClientState.VersionItem v : state.safeVersions()) {
            infoLines.add(new Line("v" + v.version + "  " + state.safe(v.instruction)
                    + "  " + v.blocks + " 方块", TEXT));
        }
        infoLines.add(new Line("—— 意图层区域（zone） ——", ACCENT));
        for (ClientState.ZoneItem z : state.safeZones()) {
            infoLines.add(new Line(z.id + " [" + z.op + "] " + z.name + " " + z.shape
                    + " " + z.box + " " + z.material, MUTED));
        }
        infoLines.add(new Line("—— 材质用量 ——", ACCENT));
        for (Map.Entry<String, Integer> e : state.safeMaterials().entrySet()) {
            infoLines.add(new Line(e.getKey() + " × " + e.getValue(), MUTED));
        }
        // 视觉检查页：相机清单、照片、AI 审查报告与发现的问题
        visualLines.add(new Line("独立虚拟相机（非玩家截图）· 离屏光线投射渲染", ACCENT));
        visualLines.add(new Line("世界中相机标记 " + state.cameraCount + " 个 · 照片 "
                + state.safeCaptures().size() + " 张", MUTED));
        visualLines.add(new Line("—— 照片清单 ——", ACCENT));
        if (state.safeCaptures().isEmpty()) {
            visualLines.add(new Line("  还没有照片，点击「拍照检查」或「AI 自由查看」", MUTED));
        }
        for (ClientState.CaptureItem capture : state.safeCaptures()) {
            visualLines.add(new Line("  " + capture.name + " [" + capture.id + "] "
                    + capture.width + "×" + capture.height
                    + " 建筑像素=" + capture.blockPixels, TEXT));
            for (String line : wrap("  机位 " + state.safe(capture.pose), wrapWidth)) {
                visualLines.add(new Line(line, MUTED));
            }
            for (String line : wrap("  命中材质 " + state.safe(capture.materials), wrapWidth)) {
                visualLines.add(new Line(line, MUTED));
            }
            for (String line : wrap("  文件 " + state.safe(capture.path), wrapWidth)) {
                visualLines.add(new Line(line, MUTED));
            }
        }
        visualLines.add(new Line("—— 视觉模型审查报告 ——", ACCENT));
        String report = state.safe(state.visualReport);
        if (report.isBlank()) {
            visualLines.add(new Line("  （尚未分析，点击「AI 自由查看」把照片交给视觉模型）", MUTED));
        } else {
            for (String line : wrap(report, wrapWidth)) {
                visualLines.add(new Line(line, TEXT));
            }
        }
        if (!state.safeVisualIssues().isEmpty()) {
            visualLines.add(new Line("—— 发现的问题 ——", ACCENT));
            for (String issue : state.safeVisualIssues()) {
                for (String line : wrap("  · " + issue, wrapWidth)) {
                    visualLines.add(new Line(line, 0xFFFFCC66));
                }
            }
        }

        if (followLog) logScroll = maxScroll(logLines.size(), panelH);
        if (followChat) chatScroll = maxScroll(chatLines.size(), panelH);
        planScroll = Math.min(planScroll, maxScroll(planLines.size(), panelH));
        infoScroll = Math.min(infoScroll, maxScroll(infoLines.size(), panelH));
        if (followVisual) visualScroll = maxScroll(visualLines.size(), panelH);
    }

    private List<String> wrap(String text, int maxWidth) {
        List<String> out = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            out.add("");
            return out;
        }
        StringBuilder current = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\n') {
                out.add(current.toString());
                current.setLength(0);
                continue;
            }
            if (this.font.width(current.toString() + c) > maxWidth && current.length() > 0) {
                out.add(current.toString());
                current.setLength(0);
            }
            current.append(c);
        }
        out.add(current.toString());
        return out;
    }

    // ------------------------------------------------------------------
    // 绘制
    // ------------------------------------------------------------------

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.renderBackground(g, mouseX, mouseY, partialTick);
        if (linesDirty) {
            rebuildLines();
        }
        g.fill(left, top, left + widthPx, top + heightPx, BG);
        frame(g, left, top, widthPx, heightPx);
        g.drawString(this.font, "建造指令 · Agent 自主建造", left + 10, top + 10, ACCENT, false);
        String header = state == null ? "" : "任务 " + shortId() + "   模型调用 "
                + state.modelCalls + " 次   " + (state.promptTokens + state.completionTokens) + " token";
        g.drawString(this.font, header, left + widthPx - this.font.width(header) - 10, top + 10, MUTED, false);

        g.fill(leftX, top + 26, leftX + leftW, bottom, PANEL);
        frame(g, leftX, top + 26, leftW, bottom - top - 26);
        drawLeftLabels(g);

        g.fill(rightX - 4, top + 26, rightX + rightW + 4, bottom, PANEL);
        frame(g, rightX - 4, top + 26, rightW + 8, bottom - top - 26);
        drawTabLabels(g);
        drawPanel(g);
    }

    private void drawLeftLabels(GuiGraphics g) {
        int y = top + 30;
        g.drawString(this.font, "建造范围（左键两次 / 手动填）", leftX, y, ACCENT, false);
        g.drawString(this.font, "起点  X / Y / Z", leftX, y + 12, MUTED, false);
        g.drawString(this.font, "终点  X / Y / Z", leftX, y + 48, MUTED, false);
        g.fill(leftX, y + 122, leftX + leftW, y + 123, BORDER);

        int sy = y + 130;
        String status = state == null ? "—" : state.safe(state.status) + " / " + state.safe(state.stage);
        boolean external = state != null && "external".equals(state.mode);
        line(g, leftX, sy, "链路 " + (external ? "外部 Agent（MCP）" : "游戏内 Agent"), ACCENT);
        line(g, leftX, sy + 14, "状态 " + status, TEXT);
        line(g, leftX, sy + 28, "模型调用 " + (state == null ? 0 : state.modelCalls) + " 次",
                external ? MUTED : ACCENT);
        line(g, leftX, sy + 42, "Token  in " + (state == null ? 0 : state.promptTokens)
                + " / out " + (state == null ? 0 : state.completionTokens), MUTED);
        line(g, leftX, sy + 56, "记忆版本 v" + (state == null ? 0 : state.version), TEXT);
        line(g, leftX, sy + 70, "已放置 " + (state == null ? 0 : state.blocksPlacedTotal) + " 方块", TEXT);
        line(g, leftX, sy + 84, "范围 " + (state == null ? "无" : boundsText()), MUTED);
        if (external) {
            line(g, leftX, sy + 98, "由外部 Agent 通过 MCP 驱动", MUTED);
        } else {
            line(g, leftX, sy + 98, "Plan 模式 " + (state != null && state.planMode ? "开启" : "关闭"), MUTED);
        }
        g.drawString(this.font, "物品与记忆一一绑定，不可更换", leftX, bottom - 66, 0xFF7E8C9C, false);
    }

    private void line(GuiGraphics g, int x, int y, String text, int color) {
        String clipped = text;
        while (this.font.width(clipped) > leftW - 4 && clipped.length() > 1) {
            clipped = clipped.substring(0, clipped.length() - 2) + "…";
        }
        g.drawString(this.font, clipped, x, y, color, false);
    }

    private void drawTabLabels(GuiGraphics g) {
        switch (tab) {
            case 0 -> {
                g.drawString(this.font, "想要一个什么样的大概样子？一句话即可，Agent 会分阶段补全并施工",
                        rightX, contentTop, MUTED, false);
                g.drawString(this.font, "可读建造计划 / 当前建筑状态", rightX, contentTop + 64, ACCENT, false);
            }
            case 1 -> g.drawString(this.font, externalMode()
                            ? "外部 Agent 动作（MCP 工具调用 / 范围设定 / 提交记录）"
                            : "Agent 全过程输出（阶段规划 / 深度思考 / 编译 / 逐阶段放置）",
                    rightX, contentTop, MUTED, false);
            case 2 -> g.drawString(this.font, "Plan 对话只写可读《建造计划》，不生成建造指令；确认后才分阶段施工",
                    rightX, contentTop, MUTED, false);
            case 3 -> g.drawString(this.font, "结构化记忆：事实层 / 语义层 / 双写互校 / 构件树 / 版本",
                    rightX, contentTop, MUTED, false);
            case 4 -> g.drawString(this.font, "建筑周围的独立相机：拍照检查、AI 自由查看、视觉审查报告",
                    rightX, contentTop, MUTED, false);
            default -> {
            }
        }
    }

    private void drawPanel(GuiGraphics g) {
        List<Line> lines = switch (tab) {
            case 0 -> planLines;
            case 1 -> logLines;
            case 2 -> chatLines;
            case 3 -> infoLines;
            default -> visualLines;
        };
        int scroll = switch (tab) {
            case 0 -> planScroll;
            case 1 -> logScroll;
            case 2 -> chatScroll;
            case 3 -> infoScroll;
            default -> visualScroll;
        };
        int x = panelX, y = panelY, w = panelW, h = Math.max(20, panelH);
        g.fill(x, y, x + w, y + h, 0xFF0C1119);
        g.fill(x, y, x + w, y + 1, BORDER);
        g.fill(x, y + h - 1, x + w, y + h, BORDER);
        g.fill(x, y, x + 1, y + h, BORDER);
        g.fill(x + w - 1, y, x + w, y + h, BORDER);
        g.enableScissor(x + 2, y + 2, x + w - 2, y + h - 2);
        int first = Math.max(0, scroll / LINE_H);
        for (int i = first; i < lines.size(); i++) {
            int yy = y + 3 + i * LINE_H - scroll;
            if (yy > y + h) break;
            if (yy + LINE_H < y) continue;
            g.drawString(this.font, lines.get(i).text(), x + 4, yy, lines.get(i).color(), false);
        }
        g.disableScissor();
        int content = lines.size() * LINE_H;
        if (content > h - 4) {
            int trackH = h - 6;
            int thumbH = Math.max(12, trackH * (h - 4) / content);
            int thumbY = y + 3 + (trackH - thumbH) * scroll / Math.max(1, content - h + 4);
            g.fill(x + w - 5, y + 3, x + w - 2, y + h - 3, 0xFF182230);
            g.fill(x + w - 5, thumbY, x + w - 2, thumbY + thumbH, 0xFF6AA9C9);
        }
    }

    private void frame(GuiGraphics g, int x, int y, int w, int h) {
        g.fill(x, y, x + w, y + 1, BORDER);
        g.fill(x, y + h - 1, x + w, y + h, BORDER);
        g.fill(x, y, x + 1, y + h, BORDER);
        g.fill(x + w - 1, y, x + w, y + h, BORDER);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (mouseX >= panelX && mouseX <= panelX + panelW && mouseY >= panelY && mouseY <= panelY + panelH) {
            int delta = (int) (-scrollY * LINE_H * 3);
            switch (tab) {
                case 0 -> planScroll = clampScroll(planScroll + delta, planLines.size());
                case 1 -> {
                    logScroll = clampScroll(logScroll + delta, logLines.size());
                    followLog = logScroll >= maxScroll(logLines.size(), panelH);
                }
                case 2 -> {
                    chatScroll = clampScroll(chatScroll + delta, chatLines.size());
                    followChat = chatScroll >= maxScroll(chatLines.size(), panelH);
                }
                case 3 -> infoScroll = clampScroll(infoScroll + delta, infoLines.size());
                default -> {
                    visualScroll = clampScroll(visualScroll + delta, visualLines.size());
                    followVisual = visualScroll >= maxScroll(visualLines.size(), panelH);
                }
            }
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    private int clampScroll(int value, int lineCount) {
        return Math.max(0, Math.min(maxScroll(lineCount, panelH), value));
    }

    private int maxScroll(int lineCount, int h) {
        return Math.max(0, lineCount * LINE_H - Math.max(20, h) + 8);
    }

    // ------------------------------------------------------------------
    // 动作
    // ------------------------------------------------------------------

    private void send(String action, String data) {
        PacketDistributor.sendToServer(new ModPackets.C2SAction(taskId, action, data));
    }

    private void applyBounds() {
        JsonObject o = new JsonObject();
        JsonArray array = new JsonArray();
        for (String value : coordText) {
            try {
                array.add(Integer.parseInt(value.trim()));
            } catch (Exception e) {
                array.add(0);
            }
        }
        o.add("bounds", array);
        send("bounds", Json.GSON.toJson(o));
        coordsEdited = false;
    }

    /** 一个按钮：服务端根据任务记忆自动判定是新建还是修改，客户端不再传 modify。 */
    private void build() {
        if (commandText == null || commandText.isBlank()) {
            return;
        }
        JsonObject o = new JsonObject();
        o.addProperty("text", commandText);
        send("build", Json.GSON.toJson(o));
        tab = 1;
        rebuildWidgets();
    }

    private void sendChat() {
        if (chatText == null || chatText.isBlank()) {
            return;
        }
        JsonObject o = new JsonObject();
        o.addProperty("text", chatText);
        send("plan_send", Json.GSON.toJson(o));
        chatText = "";
        if (chatBox != null) {
            chatBox.setValue("");
        }
    }

    private void confirmPlan() {
        JsonObject o = new JsonObject();
        o.addProperty("usePlan", true);
        send("plan_build", Json.GSON.toJson(o));
        tab = 1;
        rebuildWidgets();
    }

    /** 手动指定一台相机：x,y,z,yaw,pitch，拍照并交给视觉模型审查。 */
    private void sendCustomPose() {
        if (poseText == null || poseText.isBlank()) {
            return;
        }
        JsonObject o = new JsonObject();
        o.addProperty("pose", poseText);
        send("visual_custom", Json.GSON.toJson(o));
    }

    @Override
    public void onClose() {
        send("close", "{}");
        super.onClose();
    }

    private String boundsText() {
        if (state == null || !state.hasBounds || state.bounds == null || state.bounds.length != 6) {
            return "未选择";
        }
        int[] b = state.bounds;
        return b[0] + "," + b[1] + "," + b[2] + "→" + b[3] + "," + b[4] + "," + b[5];
    }

    private String shortId() {
        return taskId.length() > 8 ? taskId.substring(0, 8) : taskId;
    }
}
