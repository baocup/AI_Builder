package baocup.builder.agent;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import baocup.builder.BuilderConfig;
import baocup.builder.util.Bounds;
import baocup.builder.util.Json;

/**
 * 本地「计划编译器」：把十几个参数化区域展开成数万个方块。
 * 这一步完全不调用模型，是最主要的省 token 手段：模型只写几何意图，方块由确定性代码生成。
 */
public final class PlanCompiler {
    private PlanCompiler() {
    }

    /** 一次编译的产物：方块图 + 独立的实体列表。实体不进方块图，也不参与 diff。 */
    public static final class Compilation {
        public final List<Placement> blocks;
        public final List<Placement> entities;

        public Compilation(List<Placement> blocks, List<Placement> entities) {
            this.blocks = blocks;
            this.entities = entities;
        }
    }

    /** 按组排好序的执行批次，groupEnd[i] 是第 i 格所属组的最后一格下标，保证一个 tick 不拆断门/床。 */
    public static final class Batch {
        public final List<Placement> ordered;
        public final int[] groupEnd;

        public Batch(List<Placement> ordered, int[] groupEnd) {
            this.ordered = ordered;
            this.groupEnd = groupEnd;
        }
    }

    public static Compilation compile(BuildPlan plan, Bounds bounds, int maxBlocks) {
        Map<Long, Placement> result = new HashMap<>();
        List<Placement> entities = new ArrayList<>();
        // 构件类 zone 延后处理：先铺结构/清空，再放门、床、楼梯等，避免被后续几何覆盖掉半个门
        for (BuildPlan.Zone zone : orderZones(plan.zones)) {
            String material = zone.op.equals("remove") ? "minecraft:air" : zone.material;
            if (material == null || material.isBlank()) {
                continue;
            }
            if (material.startsWith("entity:")) {
                emitEntities(entities, bounds, zone, material);
                continue;
            }
            Map<Long, Placement> zoneMap = new HashMap<>();
            emit(zoneMap, bounds, maxBlocks, zone, material);
            boolean multiBlock = isMultiBlock(material);
            int[] box = zone.box;
            for (Placement raw : zoneMap.values()) {
                // 多格构件只在 zone 锚点生成一次，否则逐格展开会互相覆盖 head/upper
                if (multiBlock && (raw.x != box[0] || raw.y != box[1] || raw.z != box[2])) {
                    continue;
                }
                ComponentRequest request = componentRequest(zone, material, raw.x, raw.y, raw.z, bounds);
                for (Placement placement : BlockComponents.expand(request)) {
                    if (placement.isEntity()
                            || !bounds.contains(placement.x, placement.y, placement.z)) {
                        continue;
                    }
                    long key = placement.key();
                    if (!result.containsKey(key) && result.size() >= maxBlocks) {
                        continue;
                    }
                    result.put(key, placement);
                }
            }
        }
        resolveConnections(result);
        return new Compilation(new ArrayList<>(result.values()), entities);
    }

    private static List<BuildPlan.Zone> orderZones(List<BuildPlan.Zone> zones) {
        List<BuildPlan.Zone> plain = new ArrayList<>();
        List<BuildPlan.Zone> components = new ArrayList<>();
        for (BuildPlan.Zone zone : zones) {
            String material = zone.material == null ? "" : zone.material;
            if (material.startsWith("entity:") || BlockComponents.isComponentZone(material)) {
                components.add(zone);
            } else {
                plain.add(zone);
            }
        }
        plain.addAll(components);
        return plain;
    }

    private static boolean isMultiBlock(String material) {
        int bracket = material.indexOf('[');
        String base = bracket >= 0 ? material.substring(0, bracket) : material;
        return BlockComponents.find(base).placementOrder() >= 9;
    }

    /** 把 zone 的 material / params 组装成构件展开请求；朝向和轴全部本地推导。 */
    private static ComponentRequest componentRequest(BuildPlan.Zone zone, String material,
                                                      int x, int y, int z, Bounds bounds) {
        int bracket = material.indexOf('[');
        String base = (bracket >= 0 ? material.substring(0, bracket) : material).trim();
        String explicit = (bracket >= 0 && material.endsWith("]"))
                ? material.substring(bracket + 1, material.length() - 1) : "";
        Map.Entry<String, Map<String, String>> entity = BlockComponents.parseEntityMaterial(material);
        String blockId = entity.getKey().isBlank() ? base : "minecraft:air";

        String cleanParams = BlockComponents.sanitizeParameters(
                zone.params == null ? "" : zone.params.toString());
        Map<String, String> props = new LinkedHashMap<>();
        if (!cleanParams.isBlank()) {
            JsonObject parsed = Json.GSON.fromJson(cleanParams, JsonObject.class);
            if (parsed != null) {
                for (Map.Entry<String, JsonElement> entry : parsed.entrySet()) {
                    if (entry.getValue().isJsonPrimitive()) {
                        props.put(entry.getKey(), entry.getValue().getAsString());
                    }
                }
            }
        }
        props.putAll(entity.getValue());

        String groupId = "g:" + zone.id + ":" + x + ":" + y + ":" + z;
        String facing = OrientationResolver.horizontalFacing(zone.facingHint(), x, z, bounds);
        String axis = OrientationResolver.axis(zone.axisHint(), zone);
        return new ComponentRequest(x, y, z, blockId, explicit, facing, axis,
                groupId, cleanParams, entity.getKey(), props, bounds);
    }

    /** 实体 zone：默认只放锚点一个实体，params.step>0 时按步长铺开。 */
    private static void emitEntities(List<Placement> out, Bounds bounds,
                                     BuildPlan.Zone zone, String material) {
        int[] box = zone.box;
        int step = zone.paramInt("step", 0);
        if (step <= 0) {
            addEntity(out, bounds, zone, material, box[0], box[1], box[2]);
            return;
        }
        for (int x = box[0]; x <= box[3]; x += step) {
            for (int z = box[2]; z <= box[5]; z += step) {
                addEntity(out, bounds, zone, material, x, box[1], z);
            }
        }
    }

    private static void addEntity(List<Placement> out, Bounds bounds, BuildPlan.Zone zone,
                                  String material, int x, int y, int z) {
        if (!bounds.contains(x, y, z)) {
            return;
        }
        ComponentRequest request = componentRequest(zone, material, x, y, z, bounds);
        for (Placement placement : BlockComponents.expand(request)) {
            if (placement.isEntity()) {
                out.add(placement);
            }
        }
    }

    /** 后处理：栅栏 / 玻璃板 / 围墙按最终方块图补齐四向连接，避免出现孤立的一格栅栏。 */
    private static void resolveConnections(Map<Long, Placement> map) {
        String[][] directions = {
                { "north", "0", "-1" }, { "south", "0", "1" },
                { "east", "1", "0" }, { "west", "-1", "0" }
        };
        for (Placement placement : new ArrayList<>(map.values())) {
            String category = BlockComponents.categoryOf(placement.block);
            boolean cross = "栅栏/玻璃板".equals(category);
            boolean wall = "围墙".equals(category);
            if (!cross && !wall) {
                continue;
            }
            Map<String, String> props = parseState(placement.state);
            for (String[] direction : directions) {
                int dx = Integer.parseInt(direction[1]);
                int dz = Integer.parseInt(direction[2]);
                Placement neighbor = map.get(packedKey(placement.x + dx, placement.y, placement.z + dz));
                boolean connects = neighbor != null && !"minecraft:air".equals(neighbor.block);
                props.put(direction[0], wall ? (connects ? "low" : "none")
                        : (connects ? "true" : "false"));
            }
            if (wall) {
                Placement above = map.get(packedKey(placement.x, placement.y + 1, placement.z));
                props.put("up", above != null && !"minecraft:air".equals(above.block) ? "true" : "false");
            }
            placement.state = BlockIndex.sanitizeState(placement.block, toStateString(props));
        }
    }

    public static long packedKey(int x, int y, int z) {
        return (((long) x & 0x3FFFFFF) << 38) | (((long) z & 0x3FFFFFF) << 12)
                | ((long) (y + 64) & 0xFFF);
    }

    private static Map<String, String> parseState(String state) {
        Map<String, String> out = new LinkedHashMap<>();
        if (state == null || state.isBlank()) {
            return out;
        }
        for (String pair : state.split(",")) {
            String[] kv = pair.split("=", 2);
            if (kv.length == 2) {
                out.put(kv[0].trim(), kv[1].trim());
            }
        }
        return out;
    }

    private static String toStateString(Map<String, String> props) {
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, String> entry : props.entrySet()) {
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(entry.getKey()).append('=').append(entry.getValue());
        }
        return sb.toString();
    }

    private static void emit(Map<Long, Placement> out, Bounds bounds, int maxBlocks,
                             BuildPlan.Zone zone, String material) {
        int[] b = zone.box;
        int x1 = b[0], y1 = b[1], z1 = b[2], x2 = b[3], y2 = b[4], z2 = b[5];
        int thickness = Math.max(1, zone.paramInt("thickness", 1));
        String axis = zone.paramStr("axis", "x");
        int step = Math.max(1, zone.paramInt("step", 1));

        switch (zone.shape) {
            case "box", "clear" -> {
                for (int x = x1; x <= x2; x++)
                    for (int y = y1; y <= y2; y++)
                        for (int z = z1; z <= z2; z++) put(out, bounds, maxBlocks, x, y, z, material);
            }
            case "hollow" -> {
                for (int x = x1; x <= x2; x++)
                    for (int y = y1; y <= y2; y++)
                        for (int z = z1; z <= z2; z++) {
                            boolean shell = near(x, x1, x2, thickness) || near(y, y1, y2, thickness)
                                    || near(z, z1, z2, thickness);
                            if (shell) put(out, bounds, maxBlocks, x, y, z, material);
                        }
            }
            case "frame" -> {
                for (int x = x1; x <= x2; x++)
                    for (int y = y1; y <= y2; y++)
                        for (int z = z1; z <= z2; z++) {
                            int edges = (near(x, x1, x2, thickness) ? 1 : 0)
                                    + (near(y, y1, y2, thickness) ? 1 : 0)
                                    + (near(z, z1, z2, thickness) ? 1 : 0);
                            if (edges >= 2) put(out, bounds, maxBlocks, x, y, z, material);
                        }
            }
            case "floor" -> {
                int top = Math.min(y2, y1 + thickness - 1);
                for (int x = x1; x <= x2; x++)
                    for (int y = y1; y <= top; y++)
                        for (int z = z1; z <= z2; z++) put(out, bounds, maxBlocks, x, y, z, material);
            }
            case "ceiling" -> {
                int bottom = Math.max(y1, y2 - thickness + 1);
                for (int x = x1; x <= x2; x++)
                    for (int y = bottom; y <= y2; y++)
                        for (int z = z1; z <= z2; z++) put(out, bounds, maxBlocks, x, y, z, material);
            }
            case "walls", "walls_open" -> {
                int[] opening = readOpening(zone);
                for (int x = x1; x <= x2; x++)
                    for (int y = y1; y <= y2; y++)
                        for (int z = z1; z <= z2; z++) {
                            if (!isSide(x, z, x1, x2, z1, z2, thickness)) continue;
                            if (opening != null && inside(x, y, z, opening)) continue;
                            put(out, bounds, maxBlocks, x, y, z, material);
                        }
            }
            case "roof_gable" -> {
                int height = y2 - y1 + 1;
                boolean alongX = !axis.equalsIgnoreCase("z");
                for (int layer = 0; layer < height; layer++) {
                    int y = y2 - layer;
                    if (alongX) {
                        int za = z1 + layer, zb = z2 - layer;
                        if (za > zb) break;
                        for (int x = x1; x <= x2; x++) {
                            put(out, bounds, maxBlocks, x, y, za, material);
                            if (zb != za) put(out, bounds, maxBlocks, x, y, zb, material);
                        }
                    } else {
                        int xa = x1 + layer, xb = x2 - layer;
                        if (xa > xb) break;
                        for (int z = z1; z <= z2; z++) {
                            put(out, bounds, maxBlocks, xa, y, z, material);
                            if (xb != xa) put(out, bounds, maxBlocks, xb, y, z, material);
                        }
                    }
                }
            }
            case "pyramid" -> {
                int height = y2 - y1 + 1;
                for (int layer = 0; layer < height; layer++) {
                    int y = y2 - layer;
                    int xa = x1 + layer, xb = x2 - layer, za = z1 + layer, zb = z2 - layer;
                    if (xa > xb || za > zb) break;
                    for (int x = xa; x <= xb; x++)
                        for (int z = za; z <= zb; z++) {
                            if (x == xa || x == xb || z == za || z == zb) {
                                put(out, bounds, maxBlocks, x, y, z, material);
                            }
                        }
                }
            }
            case "cylinder", "ring" -> {
                double cx = (x1 + x2) / 2.0, cz = (z1 + z2) / 2.0;
                double r = Math.max(1, zone.paramInt("radius",
                        Math.max(1, Math.min(x2 - x1 + 1, z2 - z1 + 1) / 2)));
                boolean hollow = zone.shape.equals("ring");
                for (int x = x1; x <= x2; x++)
                    for (int z = z1; z <= z2; z++) {
                        double d = Math.hypot(x - cx, z - cz);
                        if (d > r || (hollow && d < r - thickness)) continue;
                        for (int y = y1; y <= y2; y++) put(out, bounds, maxBlocks, x, y, z, material);
                    }
            }
            case "sphere" -> {
                double cx = (x1 + x2) / 2.0, cy = (y1 + y2) / 2.0, cz = (z1 + z2) / 2.0;
                double rx = Math.max(1, (x2 - x1 + 1) / 2.0), ry = Math.max(1, (y2 - y1 + 1) / 2.0),
                        rz = Math.max(1, (z2 - z1 + 1) / 2.0);
                for (int x = x1; x <= x2; x++)
                    for (int y = y1; y <= y2; y++)
                        for (int z = z1; z <= z2; z++) {
                            double d = Math.pow((x - cx) / rx, 2) + Math.pow((y - cy) / ry, 2)
                                    + Math.pow((z - cz) / rz, 2);
                            if (d <= 1.0) put(out, bounds, maxBlocks, x, y, z, material);
                        }
            }
            case "arch" -> {
                double cx = (x1 + x2) / 2.0;
                double r = Math.max(1, (x2 - x1 + 1) / 2.0);
                double ry = Math.max(1, y2 - y1 + 1);
                for (int x = x1; x <= x2; x++)
                    for (int y = y1; y <= y2; y++) {
                        double d = Math.pow((x - cx) / r, 2) + Math.pow((y - y2) / ry, 2);
                        if (d <= 1.0 && d >= 0.45) {
                            for (int z = z1; z <= z2; z++) put(out, bounds, maxBlocks, x, y, z, material);
                        }
                    }
            }
            case "stairs" -> {
                boolean alongX = !axis.equalsIgnoreCase("z");
                int length = alongX ? x2 - x1 + 1 : z2 - z1 + 1;
                for (int i = 0; i < length; i += step) {
                    int rise = Math.min(y2 - y1 + 1, 1 + i / Math.max(1, step));
                    for (int k = 0; k < step && i + k < length; k++) {
                        for (int y = y1; y < y1 + rise; y++) {
                            if (alongX) {
                                for (int z = z1; z <= z2; z++) put(out, bounds, maxBlocks, x1 + i + k, y, z, material);
                            } else {
                                for (int x = x1; x <= x2; x++) put(out, bounds, maxBlocks, x, y, z1 + i + k, material);
                            }
                        }
                    }
                }
            }
            case "pillar" -> {
                int[] xs = { x1, x2 }, zs = { z1, z2 };
                if (zone.paramInt("center", 0) == 1) {
                    xs = new int[] { (x1 + x2) / 2 };
                    zs = new int[] { (z1 + z2) / 2 };
                }
                for (int cx : xs)
                    for (int cz : zs)
                        for (int dx = 0; dx < thickness; dx++)
                            for (int dz = 0; dz < thickness; dz++)
                                for (int y = y1; y <= y2; y++)
                                    put(out, bounds, maxBlocks, cx + dx, y, cz + dz, material);
            }
            case "line" -> {
                boolean alongX = !axis.equalsIgnoreCase("z");
                int y = zone.paramInt("y", y1);
                if (alongX) {
                    for (int x = x1; x <= x2; x++) put(out, bounds, maxBlocks, x, y, z1, material);
                } else {
                    for (int z = z1; z <= z2; z++) put(out, bounds, maxBlocks, x1, y, z, material);
                }
            }
            case "checker" -> {
                for (int x = x1; x <= x2; x++)
                    for (int y = y1; y <= y2; y++)
                        for (int z = z1; z <= z2; z++) {
                            if (((x - x1) / step + (z - z1) / step + (y - y1) / step) % 2 == 0) {
                                put(out, bounds, maxBlocks, x, y, z, material);
                            }
                        }
            }
            default -> {
                for (int x = x1; x <= x2; x++)
                    for (int y = y1; y <= y2; y++)
                        for (int z = z1; z <= z2; z++) put(out, bounds, maxBlocks, x, y, z, material);
            }
        }
    }

    private static int[] readOpening(BuildPlan.Zone zone) {
        JsonElement element = zone.params.get("opening");
        if (element == null || !element.isJsonArray()) {
            return null;
        }
        JsonArray array = element.getAsJsonArray();
        if (array.size() != 6) {
            return null;
        }
        int[] out = new int[6];
        for (int i = 0; i < 6; i++) {
            try {
                out[i] = array.get(i).getAsInt();
            } catch (Exception e) {
                return null;
            }
        }
        return out;
    }

    private static boolean inside(int x, int y, int z, int[] b) {
        return x >= Math.min(b[0], b[3]) && x <= Math.max(b[0], b[3])
                && y >= Math.min(b[1], b[4]) && y <= Math.max(b[1], b[4])
                && z >= Math.min(b[2], b[5]) && z <= Math.max(b[2], b[5]);
    }

    private static boolean isSide(int x, int z, int x1, int x2, int z1, int z2, int thickness) {
        return near(x, x1, x2, thickness) || near(z, z1, z2, thickness);
    }

    private static boolean near(int v, int lo, int hi, int thickness) {
        return v - lo < thickness || hi - v < thickness;
    }

    private static void put(Map<Long, Placement> out, Bounds bounds, int maxBlocks,
                            int x, int y, int z, String material) {
        if (!bounds.contains(x, y, z)) {
            return;
        }
        Placement placement = new Placement(x, y, z, material);
        long key = placement.key();
        if (!out.containsKey(key) && out.size() >= maxBlocks) {
            return;
        }
        out.put(key, placement);
    }

    public static Map<String, Integer> digest(List<Placement> placements) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Placement p : placements) {
            counts.merge(p.block, 1, Integer::sum);
        }
        return counts;
    }

    /** 与上一版本对比，只保留真正发生变化的方块；比较完整签名，只改朝向/状态也会被捕获。 */
    public static List<Placement> diff(List<Placement> next, List<Placement> previous) {
        if (previous == null || previous.isEmpty()) {
            return next;
        }
        Map<Long, String> old = new HashMap<>();
        for (Placement p : previous) {
            old.put(p.key(), p.signature());
        }
        List<Placement> changed = new ArrayList<>();
        for (Placement p : next) {
            String before = old.get(p.key());
            if (before == null || !before.equals(p.signature())) {
                changed.add(p);
            }
        }
        return changed;
    }

    /** 把待放置列表按构件组排序：普通方块先放（提供支撑），门/床等成组构件后放且组内不拆断。 */
    public static Batch orderForExecution(List<Placement> pending) {
        Map<String, List<Placement>> groups = new LinkedHashMap<>();
        for (Placement placement : pending) {
            String key = placement.groupId == null || placement.groupId.isBlank()
                    ? "single:" + placement.key() : placement.groupId;
            groups.computeIfAbsent(key, k -> new ArrayList<>()).add(placement);
        }
        List<List<Placement>> orderedGroups = new ArrayList<>(groups.values());
        orderedGroups.sort(Comparator.comparingInt(
                group -> group.stream().mapToInt(placement -> placement.order).min().orElse(0)));
        List<Placement> result = new ArrayList<>();
        int[] groupEnd = new int[pending.size()];
        int index = 0;
        for (List<Placement> group : orderedGroups) {
            group.sort(Comparator.comparingInt(placement -> placement.order));
            int end = index + group.size() - 1;
            for (Placement placement : group) {
                result.add(placement);
                groupEnd[index++] = end;
            }
        }
        return new Batch(result, groupEnd);
    }

    /** 上一版有实体、这一版没有的坐标，生成幂等移除标记。 */
    public static List<Placement> removedEntities(List<Placement> previousEntities,
                                                   List<Placement> currentEntities) {
        if (previousEntities == null || previousEntities.isEmpty()) {
            return new ArrayList<>();
        }
        Map<String, Placement> current = new HashMap<>();
        for (Placement placement : currentEntities) {
            current.put(NbtEntityOps.entityId(placement), placement);
        }
        List<Placement> removed = new ArrayList<>();
        for (Placement placement : previousEntities) {
            if (!current.containsKey(NbtEntityOps.entityId(placement))) {
                Placement marker = new Placement(placement.x, placement.y, placement.z,
                        "minecraft:air", "");
                marker.entityRemove = NbtEntityOps.entityId(placement);
                marker.normalize();
                removed.add(marker);
            }
        }
        return removed;
    }
}
