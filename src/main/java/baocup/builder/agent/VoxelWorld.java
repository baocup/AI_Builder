package baocup.builder.agent;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import baocup.builder.util.Bounds;

/**
 * 建造过程的唯一权威状态：一个稀疏体素表，键是绝对世界整数坐标。
 *
 * 借鉴 MineBench / 3D-GPT 的做法，模型只负责「哪些方块放在哪里」，本地只做编译与执行。
 * 所有工具（放置 / 形状 / 删除 / 读取 / 渲染）都读写这一份状态，避免「模型以为的」与
 * 「世界里真实的」两套坐标打架。任何写入都做硬校验，并把实际落地范围与被拒条目回执给模型。
 */
public final class VoxelWorld {
    /** 与 Placement.key() / PlanCompiler.packedKey() 完全一致的坐标打包方式，渲染器可直接命中。 */
    public static long key(int x, int y, int z) {
        return (((long) x & 0x3FFFFFF) << 38)
                | (((long) z & 0x3FFFFFF) << 12)
                | ((long) (y + 64) & 0xFFF);
    }

    public record Voxel(int x, int y, int z, String block, String state) {
        public String fullState() {
            return state == null || state.isBlank() ? block : block + "[" + state + "]";
        }
    }

    public record Rejection(int x, int y, int z, String reason) {
    }

    public static final class WriteResult {
        public int placed;
        public int removed;
        public final List<Rejection> rejections = new ArrayList<>();
        public int minX = Integer.MAX_VALUE;
        public int minY = Integer.MAX_VALUE;
        public int minZ = Integer.MAX_VALUE;
        public int maxX = Integer.MIN_VALUE;
        public int maxY = Integer.MIN_VALUE;
        public int maxZ = Integer.MIN_VALUE;

        void touched(int x, int y, int z) {
            minX = Math.min(minX, x);
            minY = Math.min(minY, y);
            minZ = Math.min(minZ, z);
            maxX = Math.max(maxX, x);
            maxY = Math.max(maxY, y);
            maxZ = Math.max(maxZ, z);
        }

        public JsonObject actualBounds() {
            JsonObject o = new JsonObject();
            if (placed + removed == 0) {
                o.add("min", new JsonArray());
                o.add("max", new JsonArray());
                return o;
            }
            JsonArray min = new JsonArray();
            min.add(minX);
            min.add(minY);
            min.add(minZ);
            JsonArray max = new JsonArray();
            max.add(maxX);
            max.add(maxY);
            max.add(maxZ);
            o.add("min", min);
            o.add("max", max);
            return o;
        }

        public JsonArray rejectionJson() {
            JsonArray array = new JsonArray();
            for (Rejection rejection : rejections) {
                JsonObject item = new JsonObject();
                item.addProperty("x", rejection.x());
                item.addProperty("y", rejection.y());
                item.addProperty("z", rejection.z());
                item.addProperty("reason", rejection.reason());
                array.add(item);
            }
            return array;
        }
    }

    private final ConcurrentHashMap<Long, Voxel> voxels = new ConcurrentHashMap<>();
    private final Bounds selection;

    public VoxelWorld(Bounds selection) {
        this.selection = selection;
    }

    /** 从上一版本的完整方块图恢复，用于「修改建筑」。 */
    public void load(List<Placement> previous) {
        for (Placement placement : previous) {
            placement.normalize();
            if ("minecraft:air".equals(placement.block)) {
                continue;
            }
            voxels.put(key(placement.x, placement.y, placement.z),
                    new Voxel(placement.x, placement.y, placement.z,
                            placement.block, placement.state));
        }
    }

    public int size() {
        return voxels.size();
    }

    public Bounds selection() {
        return selection;
    }

    public Voxel get(int x, int y, int z) {
        return voxels.get(key(x, y, z));
    }

    /** 基础 id 校验；状态串留给 BlockIndex.toState 过滤，避免把合法属性误判成非法方块。 */
    private String validate(int x, int y, int z, String block) {
        if (selection != null && !selection.contains(x, y, z)) {
            return "坐标落在选区外";
        }
        if (y < -64 || y > 319) {
            return "Y 超出世界高度范围";
        }
        if (block == null || block.isBlank()) {
            return "方块 id 为空";
        }
        String base = block;
        int bracket = base.indexOf('[');
        if (bracket >= 0) {
            base = base.substring(0, bracket);
        }
        if (!base.contains(":")) {
            base = "minecraft:" + base;
        }
        if (!BlockIndex.get().isValid(base)) {
            return "方块 id 不存在：" + base;
        }
        return null;
    }

    public WriteResult write(WriteResult result, int x, int y, int z,
                             String block, String state, boolean addOnly) {
        String reason = validate(x, y, z, block);
        if (reason != null) {
            result.rejections.add(new Rejection(x, y, z, reason));
            return result;
        }
        if (addOnly && voxels.containsKey(key(x, y, z))) {
            return result;
        }
        String normalized = block.contains(":") ? block : "minecraft:" + block;
        voxels.put(key(x, y, z), new Voxel(x, y, z, normalized, state == null ? "" : state));
        result.placed++;
        result.touched(x, y, z);
        return result;
    }

    public WriteResult erase(WriteResult result, int x, int y, int z) {
        if (selection != null && !selection.contains(x, y, z)) {
            result.rejections.add(new Rejection(x, y, z, "坐标落在选区外"));
            return result;
        }
        if (voxels.remove(key(x, y, z)) != null) {
            result.removed++;
            result.touched(x, y, z);
        }
        return result;
    }

    public List<Voxel> all() {
        List<Voxel> out = new ArrayList<>(voxels.values());
        out.sort(Comparator.comparingInt((Voxel v) -> v.y())
                .thenComparingInt(Voxel::x).thenComparingInt(Voxel::z));
        return out;
    }

    public List<Voxel> inRegion(int[] box) {
        List<Voxel> out = new ArrayList<>();
        for (Voxel voxel : voxels.values()) {
            if (inside(voxel, box)) {
                out.add(voxel);
            }
        }
        out.sort(Comparator.comparingInt((Voxel v) -> v.y())
                .thenComparingInt(Voxel::x).thenComparingInt(Voxel::z));
        return out;
    }

    private static boolean inside(Voxel v, int[] box) {
        return v.x() >= box[0] && v.x() <= box[3]
                && v.y() >= box[1] && v.y() <= box[4]
                && v.z() >= box[2] && v.z() <= box[5];
    }

    /** 当前真实占用的坐标范围；坐标一律以它为准，模型不必凭记忆推测。 */
    public int[] occupiedBounds() {
        if (voxels.isEmpty()) {
            return null;
        }
        int[] b = {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE,
                Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE};
        for (Voxel v : voxels.values()) {
            b[0] = Math.min(b[0], v.x());
            b[1] = Math.min(b[1], v.y());
            b[2] = Math.min(b[2], v.z());
            b[3] = Math.max(b[3], v.x());
            b[4] = Math.max(b[4], v.y());
            b[5] = Math.max(b[5], v.z());
        }
        return b;
    }

    public Map<String, Integer> materials() {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Voxel v : voxels.values()) {
            counts.merge(v.block(), 1, Integer::sum);
        }
        return counts;
    }

    /** 每层的格数、材质与坐标范围，帮助模型在体素很多时定位。 */
    public JsonArray layerSummary() {
        Map<Integer, List<Voxel>> byY = new LinkedHashMap<>();
        for (Voxel v : voxels.values()) {
            byY.computeIfAbsent(v.y(), k -> new ArrayList<>()).add(v);
        }
        JsonArray layers = new JsonArray();
        for (Map.Entry<Integer, List<Voxel>> e : byY.entrySet()) {
            JsonObject item = new JsonObject();
            item.addProperty("y", e.getKey());
            item.addProperty("blocks", e.getValue().size());
            Map<String, Integer> mats = new LinkedHashMap<>();
            int minX = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE;
            int minZ = Integer.MAX_VALUE, maxZ = Integer.MIN_VALUE;
            for (Voxel v : e.getValue()) {
                mats.merge(v.block(), 1, Integer::sum);
                minX = Math.min(minX, v.x());
                maxX = Math.max(maxX, v.x());
                minZ = Math.min(minZ, v.z());
                maxZ = Math.max(maxZ, v.z());
            }
            JsonObject box = new JsonObject();
            box.addProperty("minX", minX);
            box.addProperty("maxX", maxX);
            box.addProperty("minZ", minZ);
            box.addProperty("maxZ", maxZ);
            item.add("span", box);
            JsonObject top = new JsonObject();
            mats.entrySet().stream()
                    .sorted((a, b) -> Integer.compare(b.getValue(), a.getValue()))
                    .limit(5)
                    .forEach(entry -> top.addProperty(entry.getKey(), entry.getValue()));
            item.add("materials", top);
            layers.add(item);
        }
        return layers;
    }

    /** 导出成 Placement，供 gzip 存档、差量修改与视觉渲染复用。 */
    public List<Placement> placements() {
        List<Placement> out = new ArrayList<>(voxels.size());
        for (Voxel v : voxels.values()) {
            out.add(new Placement(v.x(), v.y(), v.z(), v.block(), v.state()));
        }
        return out;
    }
}
