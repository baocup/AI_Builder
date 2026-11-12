package baocup.builder.agent;

import java.util.Locale;

import baocup.builder.util.Bounds;

/**
 * 本地朝向推导：把「朝院内 / north / 沿 X」这类语义或位置信息解析成具体 BlockState 属性值。
 * 默认不依赖 LLM —— 没有任何 hint 时按方块相对选区中心的位置推导「朝内」方向。
 */
public final class OrientationResolver {
    private OrientationResolver() {
    }

    /** 返回 north / south / east / west。hint 支持方位词、中文方位、朝内/朝外、inward/outward。 */
    public static String horizontalFacing(String hint, int x, int z, Bounds bounds) {
        String normalized = normalize(hint);
        switch (normalized) {
            case "north", "n", "北", "朝北", "北向" -> {
                return "north";
            }
            case "south", "s", "南", "朝南", "南向" -> {
                return "south";
            }
            case "east", "e", "东", "朝东", "东向" -> {
                return "east";
            }
            case "west", "w", "西", "朝西", "西向" -> {
                return "west";
            }
            case "outward", "朝外", "朝院外", "向外" -> {
                return opposite(inward(x, z, bounds));
            }
            default -> {
                return inward(x, z, bounds);
            }
        }
    }

    /** 返回 x / y / z。无 hint 时按 zone 盒子的最长轴推断。 */
    public static String axis(String hint, BuildPlan.Zone zone) {
        String normalized = normalize(hint);
        switch (normalized) {
            case "x", "沿x", "沿x轴" -> {
                return "x";
            }
            case "y", "沿y", "沿y轴", "竖直" -> {
                return "y";
            }
            case "z", "沿z", "沿z轴" -> {
                return "z";
            }
            default -> {
                int[] box = zone.box;
                int sx = Math.abs(box[3] - box[0]) + 1;
                int sy = Math.abs(box[4] - box[1]) + 1;
                int sz = Math.abs(box[5] - box[2]) + 1;
                if (sx > 1 && sx >= sz && sx >= sy) return "x";
                if (sz > 1 && sz >= sx && sz >= sy) return "z";
                return "y";
            }
        }
    }

    /** 该朝向的水平单位位移，用于床的 head、门的上半等的偏移计算。 */
    public static int[] offset(String facing) {
        return switch (facing) {
            case "north" -> new int[] { 0, -1 };
            case "south" -> new int[] { 0, 1 };
            case "east" -> new int[] { 1, 0 };
            case "west" -> new int[] { -1, 0 };
            default -> new int[] { 0, 1 };
        };
    }

    public static String opposite(String facing) {
        return switch (facing) {
            case "north" -> "south";
            case "south" -> "north";
            case "east" -> "west";
            case "west" -> "east";
            default -> "north";
        };
    }

    /** 该朝向所属的水平轴，供连接类方块的邻接判断。 */
    public static String axisOf(String facing) {
        return "east".equals(facing) || "west".equals(facing) ? "x" : "z";
    }

    private static String inward(int x, int z, Bounds bounds) {
        if (bounds == null) {
            return "north";
        }
        int cx = (bounds.minX() + bounds.maxX()) / 2;
        int cz = (bounds.minZ() + bounds.maxZ()) / 2;
        int dx = cx - x;
        int dz = cz - z;
        if (Math.abs(dx) >= Math.abs(dz)) {
            return dx >= 0 ? "east" : "west";
        }
        return dz >= 0 ? "south" : "north";
    }

    private static String normalize(String hint) {
        return hint == null ? "" : hint.trim().toLowerCase(Locale.ROOT);
    }
}
