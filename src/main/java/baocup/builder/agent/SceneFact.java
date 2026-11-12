package baocup.builder.agent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import baocup.builder.util.Bounds;

/**
 * 结构化建筑「事实层」：从编译后的方块图与累计 zone 确定性抽取，0 额外 token。
 * 描述「现在这栋楼实际有什么」，与意图层 currentPlanJson 和物理方块图并列，互不替代。
 *
 * 参考 I-Design 的场景图中间表示（分离语义与几何）以及 LayoutGPT 的参数化布局，
 * 但不引入任何外部格式或依赖。
 */
public final class SceneFact {
    public int version;
    public long generatedAt;
    public Bounds bounds;
    /** 按 y 聚类的楼层。 */
    public List<Level> levels = new ArrayList<>();
    /** 由地板 zone 推导的区域，用途/名称由语义层补充。 */
    public List<Room> rooms = new ArrayList<>();
    /** 墙体 / 屋顶 / 地板 / 门 / 床 / 开口 / 家具 / 实体等构件。 */
    public List<Feature> features = new ArrayList<>();
    /** 材质用量 top 列表，避免把完整 counts 表塞进提示词。 */
    public Map<String, Integer> materials = new LinkedHashMap<>();
    /** 可读摘要，直接喂给下一阶段与修改指令的提示词。 */
    public String summary = "";

    /** 坐标 → 构件 id 的运行时索引；不序列化，重启后由 rebuildIndex 重建。 */
    public transient Map<Long, String> index = new HashMap<>();

    public static final class Level {
        public int index;
        public int yMin;
        public int yMax;
        public int blocks;
        public String dominantMaterial = "";
    }

    public static final class Room {
        public String id = "";
        public String name = "";
        public String purpose = "";
        public int[] box;
        public int floorCount;
        public int wallCount;
    }

    public static final class Feature {
        public String id = "";
        public String kind = "";
        public String name = "";
        public String material = "";
        public String state = "";
        public String facing = "";
        public int[] box;
        public String sourceZone = "";
        public int blocks;
    }

    public void ensureCollections() {
        if (levels == null) levels = new ArrayList<>();
        if (rooms == null) rooms = new ArrayList<>();
        if (features == null) features = new ArrayList<>();
        if (materials == null) materials = new LinkedHashMap<>();
        if (summary == null) summary = "";
        if (bounds == null) bounds = new Bounds(0, 0, 0, 0, 0, 0);
        if (index == null) index = new HashMap<>();
    }

    /** 重建坐标 → 构件索引，供「这个坐标上是什么」的 O(1) 查询。 */
    public void rebuildIndex() {
        ensureCollections();
        index = new HashMap<>();
        for (Feature feature : features) {
            int[] box = feature.box;
            if (box == null || box.length != 6) {
                continue;
            }
            for (int x = box[0]; x <= box[3]; x++) {
                for (int y = box[1]; y <= box[4]; y++) {
                    for (int z = box[2]; z <= box[5]; z++) {
                        index.put(pack(x, y, z), feature.id);
                    }
                }
            }
        }
    }

    public String featureAt(int x, int y, int z) {
        if (index == null || index.isEmpty()) {
            rebuildIndex();
        }
        return index.get(pack(x, y, z));
    }

    public Feature findFeature(String id) {
        if (id == null) {
            return null;
        }
        for (Feature feature : features) {
            if (id.equals(feature.id)) {
                return feature;
            }
        }
        return null;
    }

    /** 生成几百 token 的可读摘要，供 memoryDigest / stageUser 使用。 */
    public String buildSummary() {
        ensureCollections();
        StringBuilder sb = new StringBuilder();
        sb.append("事实层 v").append(version).append("：").append(levels.size()).append(" 层 / ")
                .append(rooms.size()).append(" 个区域 / ").append(features.size()).append(" 个构件");
        sb.append('\n');
        sb.append("全部材质：");
        for (Map.Entry<String, Integer> entry : materials.entrySet()) {
            sb.append(entry.getKey().replace("minecraft:", "")).append('×')
                    .append(entry.getValue()).append(' ');
        }
        sb.append('\n');
        for (Level level : levels) {
            sb.append("第 ").append(level.index + 1).append(" 层 y=").append(level.yMin)
                    .append("~").append(level.yMax).append("，").append(level.blocks)
                    .append(" 格，主材 ").append(level.dominantMaterial).append('\n');
        }
        for (Room room : rooms) {
            sb.append("区域 ").append(room.id).append(' ')
                    .append(room.name.isEmpty() ? "" : "「" + room.name + "」")
                    .append(room.purpose.isEmpty() ? "" : " 用途=" + room.purpose)
                    .append(" 地板 ").append(room.floorCount).append(" 格 / 墙 ")
                    .append(room.wallCount).append(" 格\n");
        }
        sb.append("构件：");
        Map<String, Integer> kinds = new LinkedHashMap<>();
        for (Feature feature : features) {
            kinds.merge(feature.kind, 1, Integer::sum);
        }
        for (Map.Entry<String, Integer> entry : kinds.entrySet()) {
            sb.append(entry.getKey()).append('×').append(entry.getValue()).append(' ');
        }
        sb.append('\n');
        for (Feature feature : features) {
            if (!"门".equals(feature.kind) && !"床".equals(feature.kind)
                    && !"实体".equals(feature.kind)) {
                continue;
            }
            sb.append("· ").append(feature.kind).append(' ').append(feature.name)
                    .append(" 材质=").append(feature.material.replace("minecraft:", ""))
                    .append(" 朝向=").append(feature.facing)
                    .append(" 状态=").append(feature.state)
                    .append(" 范围=").append(feature.box[0]).append(',').append(feature.box[1])
                    .append(',').append(feature.box[2]).append("~")
                    .append(feature.box[3]).append(',').append(feature.box[4])
                    .append(',').append(feature.box[5]).append('\n');
        }
        summary = sb.toString();
        return summary;
    }

    private static long pack(int x, int y, int z) {
        return (((long) x & 0x3FFFFFF) << 38) | (((long) z & 0x3FFFFFF) << 12)
                | ((long) (y + 64) & 0xFFF);
    }
}
