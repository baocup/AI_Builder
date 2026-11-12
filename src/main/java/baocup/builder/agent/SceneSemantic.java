package baocup.builder.agent;

import java.util.ArrayList;
import java.util.List;

import baocup.builder.util.Json;

/**
 * 结构化建筑「语义层」：由 LLM 产出房间用途、空间关系与设计意图，只补充标签，不覆盖物理事实。
 * 借鉴 I-Design 的场景图中间表示（语义与几何分离），但不引入外部格式。
 */
public final class SceneSemantic {
    public int version;
    public long generatedAt;
    public String designIntent = "";
    public List<RoomTag> rooms = new ArrayList<>();
    public List<Relation> relations = new ArrayList<>();
    public String summary = "";

    public static final class RoomTag {
        /** 对应事实层 Room.id，例如 room1；留空表示无法对应。 */
        public String roomId = "";
        public String name = "";
        public String purpose = "";
        public String mood = "";
    }

    public static final class Relation {
        public String subject = "";
        /** 位于 / 面向 / 连接 / 相邻 / 包含 等自然语言关系。 */
        public String predicate = "";
        public String object = "";
    }

    public void ensureCollections() {
        if (rooms == null) rooms = new ArrayList<>();
        if (relations == null) relations = new ArrayList<>();
        if (designIntent == null) designIntent = "";
        if (summary == null) summary = "";
    }

    public static SceneSemantic parse(String raw) {
        String json = LlmClient.extractJson(raw);
        if (json == null) {
            throw new IllegalArgumentException("语义层回复里没有 JSON 对象");
        }
        SceneSemantic semantic = Json.GSON.fromJson(json, SceneSemantic.class);
        if (semantic == null) {
            throw new IllegalArgumentException("语义层 JSON 解析为空");
        }
        semantic.ensureCollections();
        semantic.buildSummary();
        return semantic;
    }

    /** 生成给提示词/GUI 用的可读摘要。 */
    public String buildSummary() {
        ensureCollections();
        StringBuilder sb = new StringBuilder();
        sb.append("语义层：")
                .append(designIntent.isEmpty() ? "无设计意图描述" : designIntent).append('\n');
        for (RoomTag room : rooms) {
            sb.append("· ").append(room.roomId.isEmpty() ? "?" : room.roomId)
                    .append(' ').append(room.name)
                    .append(room.purpose.isEmpty() ? "" : " 用途=" + room.purpose)
                    .append(room.mood.isEmpty() ? "" : " 氛围=" + room.mood).append('\n');
        }
        for (Relation relation : relations) {
            sb.append("· ").append(relation.subject).append(' ').append(relation.predicate)
                    .append(' ').append(relation.object).append('\n');
        }
        summary = sb.toString();
        return summary;
    }

    public String toJson() {
        return Json.GSON.toJson(this);
    }
}
