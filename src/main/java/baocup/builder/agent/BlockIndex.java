package baocup.builder.agent;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import baocup.builder.BuilderMod;
import baocup.builder.util.Json;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;

/**
 * 方块外观索引数据库。
 * 数据来源：tools/generate_assets.py 读取原版客户端 jar 的 assets/minecraft/textures/block/*.png，
 * 逐像素统计主色 / 明度 / 纹理方差 / 透明度 / 细节密度，再结合方块语义生成中文外观描述。
 * 运行时只把「检索出的十几行候选」放进提示词，而不是把上千个方块全塞给模型。
 */
public final class BlockIndex {
    public static final class BlockInfo {
        public String id = "";
        public String name = "";
        public String desc = "";
        public String hex = "#808080";
        public String category = "杂项";
        /** 物理特性说明：会重力掉落 / 需要支撑等，空字符串表示无特殊物理行为。 */
        public String physics = "";
        public List<String> tags = new ArrayList<>();
        public boolean buildable = true;
    }

    private static final String RESOURCE = "/assets/builder/block_index.json";
    private static final String BASELINE = "石砖 木板 玻璃 台阶 屋顶 灯 柱子 墙体 地板";
    private static final String[] ALWAYS = {
            "minecraft:stone_bricks", "minecraft:oak_planks", "minecraft:spruce_planks",
            "minecraft:cobblestone", "minecraft:glass", "minecraft:glass_pane",
            "minecraft:quartz_block", "minecraft:white_concrete", "minecraft:oak_log",
            "minecraft:stone_brick_stairs", "minecraft:lantern", "minecraft:deepslate_bricks"
    };
    private static final String[][] STYLE_HINTS = {
            { "中世纪", "城堡", "欧式", "复古", "教堂", "塔", "石砖 圆石 云杉木 深色木板 石砖楼梯 錾制石砖 玻璃板" },
            { "现代", "别墅", "极简", "白色", "玻璃幕墙", "混凝土 石英 玻璃 平滑石头 铁块 白色" },
            { "日式", "东方", "中式", "和风", "樱花", "樱花木 深色橡木 竹 砂岩 陶瓦 白色羊毛" },
            { "田园", "木屋", "小屋", "乡村", "橡木木板 原木 圆石 玻璃 台阶 干草" },
            { "沙漠", "埃及", "砂岩", "砂岩 陶瓦 砂 黄色 平滑砂岩" },
            { "下界", "地狱", "暗黑", "黑石 下界砖块 玄武岩 深板岩 岩浆" },
            { "蒸汽", "工业", "机械", "铜块 铁块 切制铜块 深板岩 锁链 磨制" },
            { "水下", "海洋", "海晶", "海晶石 暗海晶石 海晶灯 青色 玻璃" },
    };

    private static volatile BlockIndex instance;

    private final Map<String, BlockInfo> byId = new LinkedHashMap<>();
    private final List<BlockInfo> all = new ArrayList<>();
    private Path external;

    private BlockIndex() {
        load();
    }

    public static BlockIndex get() {
        if (instance == null) {
            synchronized (BlockIndex.class) {
                if (instance == null) {
                    instance = new BlockIndex();
                }
            }
        }
        return instance;
    }

    /** 允许用 config/builder/block_index.json 覆盖 / 扩充内置索引（例如手工补充模组方块的外观）。 */
    public void loadExternal(Path gameDir) {
        external = gameDir.resolve("config/builder/block_index.json");
        if (!Files.isRegularFile(external)) {
            return;
        }
        try (InputStreamReader reader = new InputStreamReader(
                Files.newInputStream(external), StandardCharsets.UTF_8)) {
            JsonObject root = Json.GSON.fromJson(reader, JsonObject.class);
            JsonArray blocks = root == null ? null : root.getAsJsonArray("blocks");
            int added = 0;
            if (blocks != null) {
                for (JsonElement e : blocks) {
                    if (!e.isJsonObject()) {
                        continue;
                    }
                    BlockInfo info = Json.GSON.fromJson(e, BlockInfo.class);
                    if (info == null || info.id == null || info.id.isBlank()) {
                        continue;
                    }
                    if (info.tags == null) {
                        info.tags = new ArrayList<>();
                    }
                    if (info.physics == null || info.physics.isBlank()) {
                        info.physics = physicsOf(info.id);
                    }
                    if (!byId.containsKey(info.id)) {
                        all.add(info);
                    }
                    byId.put(info.id, info);
                    added++;
                }
            }
            if (added > 0) {
                BuilderMod.LOGGER.info("[建造] 外部方块外观索引补充了 {} 条记录", added);
            }
        } catch (Exception e) {
            BuilderMod.LOGGER.warn("[建造] 读取外部方块索引失败：{}", e.getMessage());
        }
    }

    private void load() {
        InputStream stream = null;
        if (external != null && Files.isRegularFile(external)) {
            try {
                stream = Files.newInputStream(external);
            } catch (Exception ignored) {
                stream = null;
            }
        }
        if (stream == null) {
            stream = BlockIndex.class.getResourceAsStream(RESOURCE);
        }
        if (stream == null) {
            BuilderMod.LOGGER.warn("[建造] 找不到方块外观索引 {}", RESOURCE);
        } else {
            try (InputStreamReader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
                JsonObject root = Json.GSON.fromJson(reader, JsonObject.class);
                JsonArray blocks = root == null ? null : root.getAsJsonArray("blocks");
                if (blocks != null) {
                    for (JsonElement e : blocks) {
                        if (!e.isJsonObject()) {
                            continue;
                        }
                        BlockInfo info = Json.GSON.fromJson(e, BlockInfo.class);
                        if (info == null || info.id == null || info.id.isBlank()) {
                            continue;
                        }
                        if (info.tags == null) {
                            info.tags = new ArrayList<>();
                        }
                        if (info.physics == null) {
                            info.physics = "";
                        }
                        if (info.physics.isBlank()) {
                            info.physics = physicsOf(info.id);
                        }
                        byId.put(info.id, info);
                        all.add(info);
                    }
                }
                BuilderMod.LOGGER.info("[建造] 方块外观索引已加载：{} 个条目", all.size());
            } catch (Exception e) {
                BuilderMod.LOGGER.error("[建造] 解析方块外观索引失败", e);
            }
        }
        // 兜底：注册表里存在但索引里没有的方块（例如其它 Mod 添加的方块）也纳入检索。
        try {
            for (ResourceLocation key : BuiltInRegistries.BLOCK.keySet()) {
                String id = key.toString();
                if (!byId.containsKey(id)) {
                    BlockInfo info = synthesize(id);
                    byId.put(id, info);
                    all.add(info);
                }
            }
        } catch (Exception e) {
            BuilderMod.LOGGER.warn("[建造] 枚举方块注册表失败：{}", e.getMessage());
        }
    }

    private static BlockInfo synthesize(String id) {
        BlockInfo info = new BlockInfo();
        info.id = id;
        info.name = id.substring(id.indexOf(':') + 1).replace('_', ' ');
        info.category = guessCategory(id);
        info.desc = info.name + "：索引中没有对应贴图记录，外观按名称推断为" + info.category
                + "类方块；适合" + guessUsage(info.category) + "。";
        info.tags.add(info.category);
        info.tags.add("名称推断");
        info.buildable = !id.contains("air") && !id.contains("portal") && !id.contains("water");
        info.physics = physicsOf(id);
        if (!info.physics.isBlank()) {
            info.desc += info.physics + "。";
            info.tags.add(isGravityBlock(id) ? "会掉落" : "需支撑");
            info.tags.add("需支撑");
        }
        return info;
    }

    public static final String GRAVITY_NOTE = "受重力影响：正下方没有实心方块时会掉落成实体，"
            + "可能堆积或消失；建造时必须保证每一格正下方都有支撑，禁止悬空使用";
    public static final String SUPPORT_NOTE = "需要依附/支撑：下方或侧面缺少支撑时会被破坏；不要悬空放置";

    /** 与 tools/generate_assets.py 的 physics_of 保持一致，用于索引里没有的模组方块。 */
    public static String physicsOf(String rawId) {
        if (rawId == null) {
            return "";
        }
        String id = normalize(rawId);
        String key = id.substring(id.indexOf(':') + 1);
        if (key.contains("sandstone") || key.contains("soul_sand") || key.contains("powder_snow")) {
            return "";
        }
        boolean falling = key.equals("sand") || key.equals("red_sand") || key.equals("gravel")
                || key.equals("dragon_egg") || key.equals("anvil") || key.equals("chipped_anvil")
                || key.equals("damaged_anvil")
                || key.startsWith("suspicious_sand") || key.startsWith("suspicious_gravel")
                || key.endsWith("concrete_powder") || key.endsWith("_gravel");
        if (falling) {
            return GRAVITY_NOTE;
        }
        if (key.equals("scaffolding") || key.startsWith("pointed_dripstone") || key.endsWith("_carpet")) {
            return SUPPORT_NOTE;
        }
        return "";
    }

    public static boolean isGravityBlock(String rawId) {
        return GRAVITY_NOTE.equals(physicsOf(rawId));
    }

    public static boolean needsSupport(String rawId) {
        return !physicsOf(rawId).isEmpty();
    }

    private static String guessCategory(String id) {
        if (id.contains("plank") || id.contains("log") || id.contains("wood")) return "木材";
        if (id.contains("glass")) return "玻璃";
        if (id.contains("concrete") || id.contains("terracotta")) return "现代饰面";
        if (id.contains("wool") || id.contains("carpet")) return "软质织物";
        if (id.contains("leaves") || id.contains("flower") || id.contains("grass")) return "植物";
        if (id.contains("lantern") || id.contains("torch") || id.contains("glow")) return "光源";
        if (id.contains("iron") || id.contains("copper") || id.contains("gold")) return "金属机械";
        return "石材";
    }

    private static String guessUsage(String category) {
        return switch (category) {
            case "木材" -> "梁柱、地板、屋顶";
            case "玻璃" -> "窗户与幕墙";
            case "现代饰面" -> "平整外墙与色块";
            case "软质织物" -> "室内软装";
            case "植物" -> "绿化景观";
            case "光源" -> "照明点缀";
            case "金属机械" -> "工业风细节";
            default -> "墙体、地基、路面";
        };
    }

    public int size() {
        return all.size();
    }

    public BlockInfo get(String id) {
        return id == null ? null : byId.get(normalize(id));
    }

    public boolean isValid(String id) {
        return toState(id) != null;
    }

    /** 该方块默认 BlockState 支持的属性名集合，供构件注册表过滤非法状态项。 */
    public static Set<String> propertyNames(String blockId) {
        BlockState state = toState(blockId);
        if (state == null) {
            return java.util.Set.of();
        }
        Set<String> names = new java.util.HashSet<>();
        for (Property<?> property : state.getProperties()) {
            names.add(property.getName());
        }
        return names;
    }

    /** 过滤掉目标方块不支持的属性，避免构件生成的属性串里混入无关项。 */
    public static String sanitizeState(String blockId, String stateString) {
        if (stateString == null || stateString.isBlank()) {
            return "";
        }
        Set<String> allowed = propertyNames(blockId);
        if (allowed.isEmpty()) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (String pair : stateString.split(",")) {
            String[] kv = pair.split("=", 2);
            if (kv.length != 2) {
                continue;
            }
            String name = kv[0].trim();
            if (!allowed.contains(name)) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(name).append('=').append(kv[1].trim());
        }
        return sb.toString();
    }

    private static String normalize(String id) {
        String value = id.trim().toLowerCase(Locale.ROOT);
        int bracket = value.indexOf('[');
        if (bracket >= 0) {
            value = value.substring(0, bracket);
        }
        return value.contains(":") ? value : "minecraft:" + value;
    }

    /**
     * 放置到世界前的修正：让方块「按设计存在」，而不是被原版物理规则抹掉。
     *
     * 目前只处理树叶：默认 persistent=false 的树叶会按到原木的距离随机衰减，
     * 建筑里的树叶大多是悬空的，不锁定就会在几秒内消失，导致玩家看到的比渲染少。
     */
    public static BlockState forPlacement(BlockState state) {
        if (state == null) {
            return null;
        }
        try {
            net.minecraft.world.level.block.state.properties.BooleanProperty persistent =
                    net.minecraft.world.level.block.LeavesBlock.PERSISTENT;
            if (state.hasProperty(persistent) && !state.getValue(persistent)) {
                return state.setValue(persistent, Boolean.TRUE);
            }
        } catch (Exception ignored) {
            // 属性访问异常就原样返回
        }
        return state;
    }

    /** 受重力影响、放下就会下落的方块（沙子 / 沙砾 / 混凝土粉末 / 铁砧等）。 */
    public static boolean isGravity(BlockState state) {
        return state != null
                && state.getBlock() instanceof net.minecraft.world.level.block.FallingBlock;
    }

    /** 把方块 id（可带 [key=value] 状态）解析成真实 BlockState。 */
    @SuppressWarnings({ "rawtypes", "unchecked" })
    public static BlockState toState(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String id = raw.trim().toLowerCase(Locale.ROOT);
        String props = null;
        int bracket = id.indexOf('[');
        if (bracket >= 0 && id.endsWith("]")) {
            props = id.substring(bracket + 1, id.length() - 1);
            id = id.substring(0, bracket);
        }
        if (!id.contains(":")) {
            id = "minecraft:" + id;
        }
        ResourceLocation key;
        try {
            key = ResourceLocation.parse(id);
            if (!BuiltInRegistries.BLOCK.containsKey(key)) {
                return null;
            }
        } catch (Exception e) {
            return null;
        }
        BlockState state = BuiltInRegistries.BLOCK.get(key).defaultBlockState();
        if (props != null) {
            for (String pair : props.split(",")) {
                String[] kv = pair.split("=", 2);
                if (kv.length != 2) {
                    continue;
                }
                for (Property property : state.getProperties()) {
                    if (!property.getName().equals(kv[0].trim())) {
                        continue;
                    }
                    Optional<?> parsed = property.getValue(kv[1].trim());
                    if (parsed.isPresent()) {
                        state = (BlockState) state.setValue(property, (Comparable) parsed.get());
                    }
                    break;
                }
            }
        }
        return state;
    }

    private static Set<String> tokenize(String query) {
        Set<String> tokens = new LinkedHashSet<>();
        if (query == null) {
            return tokens;
        }
        String lower = query.toLowerCase(Locale.ROOT);
        StringBuilder ascii = new StringBuilder();
        StringBuilder cjk = new StringBuilder();
        for (int i = 0; i <= lower.length(); i++) {
            char c = i < lower.length() ? lower.charAt(i) : ' ';
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_') {
                ascii.append(c);
            } else {
                if (ascii.length() >= 2) {
                    tokens.add(ascii.toString());
                }
                ascii.setLength(0);
            }
            if (c >= 0x4E00 && c <= 0x9FFF) {
                cjk.append(c);
            } else {
                flushCjk(tokens, cjk);
                cjk.setLength(0);
            }
        }
        flushCjk(tokens, cjk);
        return tokens;
    }

    private static void flushCjk(Set<String> tokens, StringBuilder cjk) {
        String s = cjk.toString();
        for (int i = 0; i < s.length(); i++) {
            tokens.add(s.substring(i, i + 1));
            if (i + 2 <= s.length()) {
                tokens.add(s.substring(i, i + 2));
            }
        }
    }

    public List<BlockInfo> search(String query, int limit) {
        Set<String> tokens = tokenize(query);
        if (tokens.isEmpty()) {
            return new ArrayList<>();
        }
        List<double[]> scored = new ArrayList<>();
        for (int i = 0; i < all.size(); i++) {
            BlockInfo info = all.get(i);
            double score = info.buildable ? 1.0 : 0.0;
            String id = info.id.toLowerCase(Locale.ROOT);
            for (String token : tokens) {
                if (token.length() < 1) {
                    continue;
                }
                double weight = token.length() >= 2 ? 1.0 : 0.4;
                for (String tag : info.tags) {
                    if (tag.contains(token)) {
                        score += 6 * weight;
                    }
                }
                if (info.name.contains(token)) score += 5 * weight;
                if (id.contains(token)) score += 4 * weight;
                if (info.category.contains(token)) score += 3 * weight;
                if (info.desc.contains(token)) score += 1.5 * weight;
            }
            if (score > 1.0) {
                scored.add(new double[] { score, i });
            }
        }
        scored.sort((a, b) -> Double.compare(b[0], a[0]));
        List<BlockInfo> out = new ArrayList<>();
        for (double[] entry : scored) {
            if (out.size() >= limit) {
                break;
            }
            out.add(all.get((int) entry[1]));
        }
        return out;
    }

    /** 给用户描述挑一批「够用但不浪费 token」的候选方块。 */
    public List<BlockInfo> paletteFor(String brief, String style, int limit) {
        Map<String, BlockInfo> chosen = new LinkedHashMap<>();
        String combined = (brief == null ? "" : brief) + " " + (style == null ? "" : style);
        for (String[] hint : STYLE_HINTS) {
            for (int i = 0; i < hint.length - 1; i++) {
                if (combined.contains(hint[i])) {
                    for (BlockInfo info : search(hint[hint.length - 1], 6)) {
                        if (info.buildable) chosen.putIfAbsent(info.id, info);
                    }
                    break;
                }
            }
        }
        for (BlockInfo info : search(combined, 8)) {
            if (info.buildable) chosen.putIfAbsent(info.id, info);
        }
        for (BlockInfo info : search(BASELINE, 6)) {
            if (info.buildable) chosen.putIfAbsent(info.id, info);
        }
        for (String id : ALWAYS) {
            BlockInfo info = byId.get(id);
            if (info != null && info.buildable) {
                chosen.putIfAbsent(id, info);
            }
        }
        List<BlockInfo> out = new ArrayList<>();
        for (BlockInfo info : chosen.values()) {
            if (out.size() >= limit) {
                break;
            }
            out.add(info);
        }
        return out;
    }

    /** 供给 LLM 的极简单行描述，避免把完整 JSON 塞进提示词。 */
    /** 候选方块说明必须完整带上物理特性，让模型知道沙子/沙砾会掉落。 */
    public static String compactLine(BlockInfo info) {
        String desc = info.desc == null ? "" : info.desc;
        String physics = (info.physics == null || info.physics.isBlank())
                ? physicsOf(info.id) : info.physics;
        if (!physics.isBlank() && !desc.contains(physics)) {
            desc = desc + " " + physics;
        }
        return info.id + " | " + info.name + " | " + desc;
    }

    public static List<String> compactLines(List<BlockInfo> infos) {
        List<String> lines = new ArrayList<>();
        for (BlockInfo info : infos) {
            lines.add(compactLine(info));
        }
        return lines;
    }

    /** 从索引里挑一个安全的替代方块，用于 LLM 输出非法 id 时兜底。 */
    public String fallbackMaterial(String wanted) {
        BlockInfo info = get(wanted);
        if (info != null && info.buildable && isValid(info.id)) {
            return info.id;
        }
        for (String id : ALWAYS) {
            if (isValid(id)) {
                return id;
            }
        }
        return "minecraft:stone_bricks";
    }

}
