package baocup.builder.agent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import baocup.builder.BuilderConfig;
import baocup.builder.util.Json;

/**
 * 本地构件注册表：把「一格意图」展开成带完整 BlockState 属性的一到多格放置单元。
 *
 * 参考 WorldEdit 的 MultiStageReorder 思想（多格方块必须按依赖顺序成组放置），
 * 但保持高性能的批量 setBlock 写入，不逐格模拟玩家放置。
 * AI 默认完全不参与朝向决策；只有在 zone 的 params 里写了 facing/axis 才覆盖。
 */
public final class BlockComponents {
    /** NBT / 实体参数的白名单，拒绝任意标签注入。 */
    private static final Set<String> ALLOWED_KEYS = Set.of(
            "CustomName", "Text1", "Text2", "Text3", "Text4", "GlowingText",
            "Base", "Patterns", "Items", "Color", "FrontText", "BackText",
            "facing", "axis", "hinge", "item", "rotation", "type", "text",
            "invisible", "small", "showArms", "noBasePlate", "yRot");

    private static final BlockComponent GENERIC = new GenericFacingComponent();
    private static final List<BlockComponent> ALL = List.of(
            new DoorComponent(),
            new BedComponent(),
            new DoublePlantComponent(),
            new FenceGateComponent(),
            new StairComponent(),
            new SlabComponent(),
            new PillarComponent(),
            new WallComponent(),
            new CrossCollisionComponent(),
            GENERIC);

    private BlockComponents() {
    }

    public static BlockComponent find(String blockId) {
        String id = blockId == null ? "" : blockId.toLowerCase(java.util.Locale.ROOT);
        for (BlockComponent component : ALL) {
            if (component.matches(id)) {
                return component;
            }
        }
        return GENERIC;
    }

    public static String categoryOf(String blockId) {
        return find(blockId).category();
    }

    /** 需要延后放置的「构件类 zone」（多格结构或带朝向的构件），供 PlanCompiler 排序。 */
    public static boolean isComponentZone(String material) {
        if (material == null) {
            return false;
        }
        String raw = material.trim();
        if (raw.startsWith("entity:")) {
            return true;
        }
        return !(find(stripState(raw)) instanceof GenericFacingComponent);
    }

    /** 展开一次放置请求；返回的每个 Placement 都已带完整状态、组信息与 NBT。 */
    public static List<Placement> expand(ComponentRequest request) {
        if (request.isEntity()) {
            return List.of(entityPlacement(request));
        }
        if (!BuilderConfig.COMPONENTS_ENABLED.getAsBoolean()) {
            Placement plain = single(request, request.explicitState);
            plain.normalize();
            return List.of(plain);
        }
        List<Placement> result = new ArrayList<>(find(request.blockId).expand(request));
        for (Placement placement : result) {
            placement.state = BlockIndex.sanitizeState(placement.block, placement.state);
            if (!request.nbt.isBlank()) {
                placement.nbt = request.nbt;
            }
            placement.normalize();
        }
        return result;
    }

    /** 从 LLM 显式状态串里取某个属性，用于覆盖默认朝向。 */
    private static String propertyFrom(String state, String key) {
        if (state == null || state.isBlank()) {
            return null;
        }
        for (String pair : state.split(",")) {
            String[] kv = pair.split("=", 2);
            if (kv.length == 2 && kv[0].trim().equals(key)) {
                return kv[1].trim();
            }
        }
        return null;
    }

    private static String effectiveFacing(ComponentRequest request) {
        String explicit = propertyFrom(request.explicitState, "facing");
        return explicit == null ? request.facing : explicit;
    }

    private static String effectiveAxis(ComponentRequest request) {
        String explicit = propertyFrom(request.explicitState, "axis");
        return explicit == null ? request.axis : explicit;
    }

    private static String stripState(String material) {
        int bracket = material.indexOf('[');
        return (bracket >= 0 ? material.substring(0, bracket) : material).trim();
    }

    /** 白名单过滤 NBT / 实体附加参数，返回紧凑 JSON；非法输入返回空串。 */
    public static String sanitizeParameters(String rawJson) {
        if (rawJson == null || rawJson.isBlank()) {
            return "";
        }
        JsonObject source;
        try {
            source = Json.GSON.fromJson(rawJson, JsonObject.class);
        } catch (Exception e) {
            return "";
        }
        if (source == null || !source.isJsonObject()) {
            return "";
        }
        JsonObject clean = new JsonObject();
        for (Map.Entry<String, JsonElement> entry : source.entrySet()) {
            if (!ALLOWED_KEYS.contains(entry.getKey())) {
                continue;
            }
            JsonElement value = entry.getValue();
            if (value.isJsonPrimitive() || value.isJsonArray()) {
                clean.add(entry.getKey(), value);
            }
        }
        return clean.size() == 0 ? "" : Json.GSON.toJson(clean);
    }

    /** 把 `entity:minecraft:item_frame[facing=north,item=...]` 拆成类型与参数。 */
    public static Map.Entry<String, Map<String, String>> parseEntityMaterial(String material) {
        String raw = material == null ? "" : material.trim();
        if (!raw.startsWith("entity:")) {
            return Map.entry("", Map.of());
        }
        String body = raw.substring("entity:".length());
        int bracket = body.indexOf('[');
        String type = bracket >= 0 ? body.substring(0, bracket) : body;
        if (!type.contains(":")) {
            type = "minecraft:" + type;
        }
        Map<String, String> props = new LinkedHashMap<>();
        if (bracket >= 0 && body.endsWith("]")) {
            for (String pair : body.substring(bracket + 1, body.length() - 1).split(",")) {
                String[] kv = pair.split("=", 2);
                if (kv.length == 2) {
                    props.put(kv[0].trim(), kv[1].trim());
                }
            }
        }
        return Map.entry(type, props);
    }

    private static Placement entityPlacement(ComponentRequest request) {
        JsonObject description = new JsonObject();
        description.addProperty("type", request.entityType);
        description.addProperty("facing", request.facing);
        for (Map.Entry<String, String> entry : request.entityProps.entrySet()) {
            if (ALLOWED_KEYS.contains(entry.getKey())) {
                description.addProperty(entry.getKey(), entry.getValue());
            }
        }
        Placement placement = new Placement(request.x, request.y, request.z, "minecraft:air", "");
        placement.entity = Json.GSON.toJson(description);
        placement.groupId = request.groupId;
        placement.order = 5;
        placement.normalize();
        return placement;
    }

    private static Placement single(ComponentRequest request, String state) {
        Placement placement = new Placement(request.x, request.y, request.z, request.blockId, state);
        placement.order = 0;
        return placement;
    }

    private static Placement member(ComponentRequest request, int x, int y, int z,
                                     String state, int order) {
        Placement placement = new Placement(x, y, z, request.blockId, state);
        placement.groupId = request.groupId;
        placement.order = order;
        return placement;
    }

    // ------------------------------------------------------------------
    // 具体构件
    // ------------------------------------------------------------------

    /** 门：lower 在下、upper 在正上方，两半 facing/hinge 必须一致，否则会自毁掉落物品。 */
    private static final class DoorComponent implements BlockComponent {
        @Override
        public boolean matches(String id) {
            return id.endsWith("_door") && !id.contains("trapdoor");
        }

        @Override
        public String category() {
            return "门";
        }

        @Override
        public int placementOrder() {
            return 10;
        }

        @Override
        public boolean requiresSupport() {
            return true;
        }

        @Override
        public List<Placement> expand(ComponentRequest request) {
            String facing = effectiveFacing(request);
            String hinge = request.entityProps.getOrDefault("hinge", "left");
            String common = "facing=" + facing + ",hinge=" + hinge + ",open=false,powered=false";
            return List.of(
                    member(request, request.x, request.y, request.z, common + ",half=lower", 1),
                    member(request, request.x, request.y + 1, request.z, common + ",half=upper", 2));
        }
    }

    /** 床：foot 在原位，head 在 facing 方向一格，两格 part/facing 必须配对才能交互。 */
    private static final class BedComponent implements BlockComponent {
        @Override
        public boolean matches(String id) {
            return id.endsWith("_bed");
        }

        @Override
        public String category() {
            return "床";
        }

        @Override
        public int placementOrder() {
            return 10;
        }

        @Override
        public boolean requiresSupport() {
            return true;
        }

        @Override
        public List<Placement> expand(ComponentRequest request) {
            String facing = effectiveFacing(request);
            int[] offset = OrientationResolver.offset(facing);
            String common = "facing=" + facing + ",occupied=false";
            return List.of(
                    member(request, request.x, request.y, request.z, common + ",part=foot", 1),
                    member(request, request.x + offset[0], request.y, request.z + offset[1],
                            common + ",part=head", 2));
        }
    }

    /** 双格花 / 高草：lower + upper 两格。 */
    private static final class DoublePlantComponent implements BlockComponent {
        private static final Set<String> TALL = Set.of(
                "sunflower", "lilac", "rose_bush", "peony", "tall_grass", "large_fern", "pitcher_plant");

        @Override
        public boolean matches(String id) {
            String path = id.contains(":") ? id.substring(id.indexOf(':') + 1) : id;
            return TALL.contains(path);
        }

        @Override
        public String category() {
            return "植被";
        }

        @Override
        public int placementOrder() {
            return 9;
        }

        @Override
        public boolean requiresSupport() {
            return true;
        }

        @Override
        public List<Placement> expand(ComponentRequest request) {
            return List.of(
                    member(request, request.x, request.y, request.z, "half=lower", 1),
                    member(request, request.x, request.y + 1, request.z, "half=upper", 2));
        }
    }

    private static final class StairComponent implements BlockComponent {
        @Override
        public boolean matches(String id) {
            return id.endsWith("_stairs");
        }

        @Override
        public String category() {
            return "楼梯";
        }

        @Override
        public int placementOrder() {
            return 4;
        }

        @Override
        public boolean requiresSupport() {
            return false;
        }

        @Override
        public List<Placement> expand(ComponentRequest request) {
            return List.of(single(request, request.explicitState.isBlank()
                    ? "facing=" + effectiveFacing(request) + ",half=bottom,shape=straight,waterlogged=false"
                    : request.explicitState));
        }
    }

    private static final class SlabComponent implements BlockComponent {
        @Override
        public boolean matches(String id) {
            return id.endsWith("_slab") && !id.contains("double");
        }

        @Override
        public String category() {
            return "台阶";
        }

        @Override
        public int placementOrder() {
            return 3;
        }

        @Override
        public boolean requiresSupport() {
            return false;
        }

        @Override
        public List<Placement> expand(ComponentRequest request) {
            return List.of(single(request, request.explicitState.isBlank()
                    ? "type=bottom,waterlogged=false" : request.explicitState));
        }
    }

    /** 原木 / 茎 / 菌柄：1.21.1 由 RotatedPillarBlock 实现，只有 AXIS 属性。 */
    private static final class PillarComponent implements BlockComponent {
        @Override
        public boolean matches(String id) {
            return id.contains("_log") || id.contains("_stem") || id.contains("_hyphae")
                    || id.endsWith("_wood") || id.startsWith("minecraft:stripped_");
        }

        @Override
        public String category() {
            return "柱/原木";
        }

        @Override
        public int placementOrder() {
            return 3;
        }

        @Override
        public boolean requiresSupport() {
            return false;
        }

        @Override
        public List<Placement> expand(ComponentRequest request) {
            return List.of(single(request, request.explicitState.isBlank()
                    ? "axis=" + effectiveAxis(request) : request.explicitState));
        }
    }

    private static final class FenceGateComponent implements BlockComponent {
        @Override
        public boolean matches(String id) {
            return id.endsWith("_fence_gate");
        }

        @Override
        public String category() {
            return "门";
        }

        @Override
        public int placementOrder() {
            return 4;
        }

        @Override
        public boolean requiresSupport() {
            return false;
        }

        @Override
        public List<Placement> expand(ComponentRequest request) {
            return List.of(single(request, "facing=" + effectiveFacing(request)
                    + ",open=false,powered=false,in_wall=false"));
        }
    }

    /** 围墙：连接属性是 WallSide 枚举而不是布尔值。邻接状态由 PlanCompiler 后处理补齐。 */
    private static final class WallComponent implements BlockComponent {
        @Override
        public boolean matches(String id) {
            if (!id.endsWith("_wall")) {
                return false;
            }
            return !id.contains("banner") && !id.contains("sign") && !id.contains("torch")
                    && !id.contains("head") && !id.contains("skull") && !id.contains("fan")
                    && !id.contains("coral");
        }

        @Override
        public String category() {
            return "围墙";
        }

        @Override
        public int placementOrder() {
            return 3;
        }

        @Override
        public boolean requiresSupport() {
            return false;
        }

        @Override
        public List<Placement> expand(ComponentRequest request) {
            return List.of(single(request, request.explicitState.isBlank()
                    ? "up=false,north=none,east=none,south=none,west=none,waterlogged=false"
                    : request.explicitState));
        }
    }

    /** 栅栏 / 玻璃板 / 铁栏杆：四向布尔连接，无 up。1.21.1 玻璃板是 IronBarsBlock。 */
    private static final class CrossCollisionComponent implements BlockComponent {
        @Override
        public boolean matches(String id) {
            return id.endsWith("_fence") || id.endsWith("_pane") || id.endsWith("_bars");
        }

        @Override
        public String category() {
            return "栅栏/玻璃板";
        }

        @Override
        public int placementOrder() {
            return 3;
        }

        @Override
        public boolean requiresSupport() {
            return false;
        }

        @Override
        public List<Placement> expand(ComponentRequest request) {
            return List.of(single(request, request.explicitState.isBlank()
                    ? "north=false,east=false,south=false,west=false,waterlogged=false"
                    : request.explicitState));
        }
    }

    /** 兜底：任何带 facing 属性的方块（熔炉、箱子、梯子、墙上的火把等）自动补朝向。 */
    private static final class GenericFacingComponent implements BlockComponent {
        @Override
        public boolean matches(String id) {
            return true;
        }

        @Override
        public String category() {
            return "方块";
        }

        @Override
        public int placementOrder() {
            return 0;
        }

        @Override
        public boolean requiresSupport() {
            return false;
        }

        @Override
        public List<Placement> expand(ComponentRequest request) {
            if (!request.explicitState.isBlank()) {
                return List.of(single(request, request.explicitState));
            }
            Set<String> properties = BlockIndex.propertyNames(request.blockId);
            String state = properties.contains("facing")
                    ? "facing=" + effectiveFacing(request) : "";
            return List.of(single(request, state));
        }
    }
}
