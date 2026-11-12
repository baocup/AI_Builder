package baocup.builder.memory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import baocup.builder.agent.CrossCheckResult;
import baocup.builder.agent.Placement;
import baocup.builder.agent.SceneFact;
import baocup.builder.agent.SceneSemantic;
import baocup.builder.util.Bounds;

/**
 * 单个建造任务的完整记忆。任务结束后不删除：
 * - 内存里常驻（服务端生命周期内）
 * - 每次改动原子写入 config/builder/memory/tasks/&lt;uuid&gt;.json
 * - 每个完成版本另存 gzip 的完整方块图，用于修改时做差量对比
 * 游戏 / 服务器重启后由 MemoryStore 重新加载。
 */
public final class BuildMemory {
    public String taskId = "";
    public String ownerUuid = "";
    public String ownerName = "";
    public String dimension = "minecraft:overworld";
    public Bounds bounds;
    public boolean hasBounds;

    public String status = "IDLE";
    public String stage = "待命";
    /** 该任务由哪个物品开启：internal = 游戏内 LLM Agent，external = 外部 Agent（MCP）。 */
    public String linkMode = "internal";
    public String userBrief = "";
    public String notes = "";

    /** 结构化的当前方案（参数化 zone，不含逐方块数据，保证提示词很小）。 */
    public String currentPlanJson = "";
    /** Plan 对话模式产出的「可读文字建造计划」，不是建造指令 / JSON。 */
    public String planDocument = "";
    public boolean planMode;

    public int version;
    public int modelCalls;
    public long promptTokens;
    public long completionTokens;
    public long blocksPlacedTotal;
    public long createdAt = System.currentTimeMillis();
    public long updatedAt = System.currentTimeMillis();

    public List<Instruction> instructions = new ArrayList<>();
    public List<ChatLine> chat = new ArrayList<>();
    public List<LogLine> logs = new ArrayList<>();
    public List<VersionLine> versions = new ArrayList<>();
    public List<ZoneLine> zones = new ArrayList<>();
    public Map<String, Integer> materials = new LinkedHashMap<>();
    /** 当前版本的实体放置清单（不进方块图 diff），用于差量删除与重启恢复。 */
    public List<Placement> entities = new ArrayList<>();
    /** 独立虚拟相机拍摄的照片元数据（PNG 落在 captures/ 目录）。 */
    public List<CaptureLine> captures = new ArrayList<>();
    /** 视觉模型对照片的完整观察报告，不做截断。 */
    public String visualReport = "";
    /** 视觉模型发现的问题清单。 */
    public List<String> visualIssues = new ArrayList<>();
    /** 世界里已放置的相机标记实体 UUID，便于重拍时幂等移除。 */
    public List<String> cameraMarkers = new ArrayList<>();

    /** 结构化事实层：独立文件持久化，不重复写进 tasks/*.json。 */
    public transient SceneFact fact;
    /** LLM 语义场景图：独立文件持久化。 */
    public transient SceneSemantic semantic;
    /** 事实层与语义层的互校结果：独立文件持久化。 */
    public transient CrossCheckResult crossCheck;

    /** 不参与序列化：仅用于决定是否要把状态推送给客户端 / 落盘。 */
    public transient boolean dirty = true;

    public static final class Instruction {
        public long ts;
        public String mode = "";
        public String text = "";
        public int version;
        public int blocks;
        public int modelCalls;

        public Instruction() {
        }

        public Instruction(String mode, String text) {
            this.ts = System.currentTimeMillis();
            this.mode = mode;
            this.text = text;
        }
    }

    public static final class ChatLine {
        public long ts;
        public String role = "";
        public String text = "";

        public ChatLine() {
        }

        public ChatLine(String role, String text) {
            this.ts = System.currentTimeMillis();
            this.role = role;
            this.text = text;
        }
    }

    public static final class LogLine {
        public long ts;
        public String stage = "";
        public String level = "";
        public String text = "";

        public LogLine() {
        }

        public LogLine(String stage, String level, String text) {
            this.ts = System.currentTimeMillis();
            this.stage = stage;
            this.level = level;
            this.text = text;
        }
    }

    public static final class VersionLine {
        public int version;
        public long ts;
        public String instruction = "";
        public int blocks;
        public String digest = "";

        public VersionLine() {
        }
    }

    public static final class CaptureLine {
        public String id = "";
        public String name = "";
        public String path = "";
        public int width;
        public int height;
        public int blockPixels;
        public int skyPixels;
        public String materials = "";
        public String pose = "";
        public long ts;

        public CaptureLine() {
        }
    }

    public static final class ZoneLine {
        public String id = "";
        public String op = "";
        public String name = "";
        public String shape = "";
        public String box = "";
        public String material = "";

        public ZoneLine() {
        }
    }

    public void touch() {
        updatedAt = System.currentTimeMillis();
        dirty = true;
    }

    /** 过程输出全量保留：深度思考、阶段日志、互校差异都不按字符数或条数淘汰。 */
    public void addLog(String stage, String level, String text) {
        logs.add(new LogLine(stage, level, text));
        this.stage = stage;
        dirty = true;
    }

    /** 对话历史全量保留，模型每一轮回复都不丢。 */
    public void addChat(String role, String text) {
        chat.add(new ChatLine(role, text));
        dirty = true;
    }

    public void addInstruction(String mode, String text) {
        instructions.add(new Instruction(mode, text));
        dirty = true;
    }

    public String digestText() {
        StringBuilder sb = new StringBuilder();
        sb.append("版本 v").append(version).append("，已放置 ").append(blocksPlacedTotal).append(" 方块");
        for (Map.Entry<String, Integer> e : materials.entrySet()) {
            sb.append("，").append(e.getKey()).append("×").append(e.getValue());
        }
        return sb.toString();
    }
}
