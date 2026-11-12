package baocup.builder.agent;

import java.util.HashSet;
import java.util.Set;

/**
 * 事实层 / 语义层双写互校器。
 *
 * 规则：事实层是物理准绳，语义层只补用途、名称、氛围与关系，不得推翻事实层。
 * 差异分三类（事实有语义无 / 语义有事实无 / 属性冲突），只记录与展示，不自动改建筑。
 */
public final class CrossCheck {
    private CrossCheck() {
    }

    public static CrossCheckResult compare(SceneFact fact, SceneSemantic semantic, int version) {
        CrossCheckResult result = new CrossCheckResult();
        result.version = version;
        result.checkedAt = System.currentTimeMillis();
        result.ensureCollections();
        if (fact == null || fact.rooms == null) {
            result.verdict = "事实层缺失";
            result.issues.add("尚未抽取事实层，语义层仅供参考");
            result.buildSummary();
            return result;
        }
        if (semantic == null) {
            result.verdict = "语义层缺失";
            result.issues.add("离线模式或模型未返回语义层，事实层仍完整可用");
            result.buildSummary();
            return result;
        }

        Set<String> factRooms = new HashSet<>();
        for (SceneFact.Room room : fact.rooms) {
            factRooms.add(room.id);
        }
        Set<String> taggedRooms = new HashSet<>();
        for (SceneSemantic.RoomTag tag : semantic.rooms) {
            if (tag.roomId == null || tag.roomId.isBlank()) {
                result.factOnly++;
                result.issues.add("语义层有一条房间标注缺少 roomId，已忽略");
                continue;
            }
            taggedRooms.add(tag.roomId);
        }
        for (String id : factRooms) {
            if (!taggedRooms.contains(id)) {
                result.factOnly++;
                result.issues.add("事实层区域 " + id + " 在语义层没有用途标注");
            }
        }
        for (String id : taggedRooms) {
            if (!factRooms.contains(id)) {
                result.semanticOnly++;
                result.issues.add("语义层引用了不存在的区域 " + id + "（已按事实层忽略）");
            }
        }
        result.verdict = (result.factOnly == 0 && result.semanticOnly == 0 && result.conflicts == 0)
                ? "一致" : "有差异";
        result.buildSummary();
        return result;
    }

    /** 语义层只回填「名称/用途」这类标签，不动任何物理字段。 */
    public static void applyRoomTags(SceneFact fact, SceneSemantic semantic) {
        if (fact == null || semantic == null || fact.rooms == null || semantic.rooms == null) {
            return;
        }
        for (SceneSemantic.RoomTag tag : semantic.rooms) {
            if (tag.roomId == null || tag.roomId.isBlank()) {
                continue;
            }
            boolean matched = false;
            for (SceneFact.Room room : fact.rooms) {
                if (tag.roomId.equals(room.id)) {
                    if (!tag.name.isBlank()) room.name = tag.name;
                    if (!tag.purpose.isBlank()) room.purpose = tag.purpose;
                    matched = true;
                    break;
                }
            }
            if (!matched) {
                continue;
            }
        }
        fact.buildSummary();
        fact.rebuildIndex();
    }
}
