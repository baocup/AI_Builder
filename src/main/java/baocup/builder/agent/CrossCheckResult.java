package baocup.builder.agent;

import java.util.ArrayList;
import java.util.List;

/** 事实层与语义层的互校结果；事实层为准，语义层只做标签补充。 */
public final class CrossCheckResult {
    public int version;
    public long checkedAt;
    /** 一致 / 有差异 / 语义层缺失 / 事实层缺失。 */
    public String verdict = "";
    /** 事实层有、语义层没提的房间数。 */
    public int factOnly;
    /** 语义层提到、事实层里找不到的房间数。 */
    public int semanticOnly;
    /** 属性冲突数。 */
    public int conflicts;
    public List<String> issues = new ArrayList<>();
    public String summary = "";

    public void ensureCollections() {
        if (issues == null) issues = new ArrayList<>();
        if (verdict == null) verdict = "";
        if (summary == null) summary = "";
    }

    public String buildSummary() {
        ensureCollections();
        StringBuilder sb = new StringBuilder();
        sb.append("互校结论：").append(verdict).append("（事实独有 ").append(factOnly)
                .append(" / 语义独有 ").append(semanticOnly)
                .append(" / 属性冲突 ").append(conflicts).append("）\n");
        for (String issue : issues) {
            sb.append("· ").append(issue).append('\n');
        }
        summary = sb.toString();
        return summary;
    }
}
