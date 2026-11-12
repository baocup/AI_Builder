package baocup.builder.agent;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import baocup.builder.util.Bounds;
import baocup.builder.util.Json;

/**
 * Agent 的「参数化建造方案」：只描述十几个几何区域，不描述逐方块数据。
 * 真正的上万次方块放置由 PlanCompiler 在本地零 token 地展开。
 */
public final class BuildPlan {
    private static final Set<String> SHAPES = Set.of(
            "box", "hollow", "frame", "floor", "ceiling", "walls", "walls_open",
            "pyramid", "roof_gable", "cylinder", "ring", "sphere", "arch",
            "stairs", "pillar", "line", "checker", "clear", "entity");
    private static final Set<String> OPS = Set.of("add", "replace", "remove");
    /** 顶层标准字段；其余字段一律原样保留，不因为本地不认识就丢弃。 */
    private static final Set<String> STANDARD_ROOT_KEYS = Set.of(
            "title", "notes", "palette", "zones", "inspectAfterStage",
            "design", "designNotes", "intent", "freeform");

    public String title = "";
    public String notes = "";
    /** 模型自由撰写的完整设计意图，任意长度 / 任意内容；本地不解析、不裁剪，跨阶段原样保留。 */
    public String design = "";
    /** 顶层所有非标准字段（含任意嵌套结构），原样存档并回传，绝不丢弃。 */
    public JsonObject extra = new JsonObject();
    public Map<String, String> palette = new LinkedHashMap<>();
    public List<Zone> zones = new ArrayList<>();
    /** 模型在本轮输出里主动请求：本阶段施工完成后拍照复查一次（施工中途检查由模型自主决定）。 */
    public boolean inspectAfterStage;

    public static final class Zone {
        public String id = "";
        public String op = "add";
        public String name = "";
        public String shape = "box";
        public int[] box = new int[6];
        public String material = "";
        public JsonObject params = new JsonObject();

        public int paramInt(String key, int fallback) {
            return Json.integer(params, key, fallback);
        }

        public String paramStr(String key, String fallback) {
            return Json.str(params, key, fallback);
        }

        /** 可选的语义朝向提示（north/south/east/west、朝院内/朝外、中文方位）；默认由本地推导。 */
        public String facingHint() {
            return paramStr("facing", "");
        }

        /** 可选的轴向提示 x/y/z；默认按 zone 盒子最长轴推导。 */
        public String axisHint() {
            return paramStr("axis", "");
        }

        /** 可选的构件名提示，仅供事实层归类，不影响自动识别。 */
        public String componentHint() {
            return paramStr("component", "");
        }

        /** 可选的白名单 NBT 参数（JSON 对象或字符串），由 BlockComponents 过滤。 */
        public String nbtJson() {
            JsonElement element = params == null ? null : params.get("nbt");
            return element == null || element.isJsonNull() ? "" : element.toString();
        }
    }

    public static BuildPlan parse(String raw) {
        String json = LlmClient.extractJson(raw);
        if (json == null) {
            throw new IllegalArgumentException("模型输出里没有 JSON 对象");
        }
        JsonObject root;
        try {
            root = Json.GSON.fromJson(json, JsonObject.class);
        } catch (Exception e) {
            throw new IllegalArgumentException("JSON 解析失败：" + e.getMessage());
        }
        if (root == null || !root.isJsonObject()) {
            throw new IllegalArgumentException("JSON 根节点不是对象");
        }
        BuildPlan plan = new BuildPlan();
        plan.title = Json.str(root, "title", "未命名建筑");
        plan.notes = Json.str(root, "notes", "");
        plan.design = freeText(root, "design", "designNotes", "intent", "freeform");
        plan.inspectAfterStage = Json.bool(root, "inspectAfterStage", false);
        // 模型自己扩展的顶层字段一律原样保留，不因为本地不认识就丢弃
        for (Map.Entry<String, JsonElement> e : root.entrySet()) {
            if (!STANDARD_ROOT_KEYS.contains(e.getKey())) {
                plan.extra.add(e.getKey(), e.getValue());
            }
        }
        JsonObject palette = Json.child(root, "palette");
        if (palette != null) {
            for (Map.Entry<String, JsonElement> e : palette.entrySet()) {
                try {
                    plan.palette.put(e.getKey(), e.getValue().getAsString());
                } catch (Exception ignored) {
                    // 忽略非法材质条目
                }
            }
        }
        JsonArray zones = root.getAsJsonArray("zones");
        if (zones != null) {
            int i = 0;
            for (JsonElement element : zones) {
                if (!element.isJsonObject()) {
                    continue;
                }
                JsonObject o = element.getAsJsonObject();
                Zone zone = new Zone();
                zone.id = Json.str(o, "id", "z" + (++i));
                zone.name = Json.str(o, "name", zone.id);
                zone.op = Json.str(o, "op", "add").toLowerCase(Locale.ROOT);
                zone.shape = Json.str(o, "shape", "box").toLowerCase(Locale.ROOT);
                zone.material = Json.str(o, "material", "");
                zone.box = readBox(o.get("box"));
                JsonObject params = Json.child(o, "params");
                zone.params = params == null ? new JsonObject() : params;
                plan.zones.add(zone);
            }
        }
        return plan;
    }

    /** 自由文本字段：字符串原样取，对象 / 数组也转成 JSON 文本保留，绝不截断。 */
    private static String freeText(JsonObject root, String... keys) {
        for (String key : keys) {
            JsonElement element = root.get(key);
            if (element == null || element.isJsonNull()) {
                continue;
            }
            if (element.isJsonPrimitive()) {
                String text = element.getAsString();
                if (!text.isBlank()) {
                    return text;
                }
            } else {
                return element.toString();
            }
        }
        return "";
    }

    /** 从累计方案 JSON 里取回模型写过的自由设计意图，供 structuredMemory 注入后续阶段。 */
    public static String freeformSummary(String planJson) {
        if (planJson == null || planJson.isBlank()) {
            return "";
        }
        try {
            JsonObject root = Json.GSON.fromJson(planJson, JsonObject.class);
            if (root == null) {
                return "";
            }
            StringBuilder sb = new StringBuilder();
            String design = freeText(root, "design", "designNotes", "intent", "freeform");
            if (!design.isBlank()) {
                sb.append(design).append('\n');
            }
            for (Map.Entry<String, JsonElement> e : root.entrySet()) {
                if (!STANDARD_ROOT_KEYS.contains(e.getKey())) {
                    sb.append(e.getKey()).append("：").append(e.getValue()).append('\n');
                }
            }
            return sb.toString();
        } catch (Exception ignored) {
            return "";
        }
    }

    private static int[] readBox(JsonElement element) {
        if (element == null || !element.isJsonArray()) {
            return null;
        }
        JsonArray array = element.getAsJsonArray();
        if (array.size() != 6) {
            return null;
        }
        int[] box = new int[6];
        for (int i = 0; i < 6; i++) {
            try {
                box[i] = array.get(i).getAsInt();
            } catch (Exception e) {
                return null;
            }
        }
        return box;
    }

    /** 阶段前缀由本地补齐并去重：模型不必记住 id 命名规则，重复 id 也不会互相覆盖。 */
    public void normalizeZoneIds(String prefix) {
        String safePrefix = prefix == null ? "" : prefix;
        Set<String> used = new HashSet<>();
        for (int i = 0; i < zones.size(); i++) {
            Zone zone = zones.get(i);
            String id = zone.id == null ? "" : zone.id.trim();
            if (!id.startsWith(safePrefix)) {
                id = safePrefix + id;
            }
            if (id.isBlank() || !used.add(id)) {
                id = safePrefix + "z" + (i + 1);
                int suffix = 1;
                while (!used.add(id)) {
                    id = safePrefix + "z" + (i + 1) + "_" + (++suffix);
                }
            }
            zone.id = id;
        }
    }

    /** 本地校验：只裁剪越界坐标、替换非法方块 id，绝不因数量丢弃模型输出的任何区域。 */
    public void validate(Bounds bounds, BlockIndex index, List<String> issues) {
        if (palette == null) {
            palette = new LinkedHashMap<>();
        }
        if (zones == null) {
            zones = new ArrayList<>();
        }
        // 先把 palette 里的角色名解析为真实方块 id
        Map<String, String> resolved = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : palette.entrySet()) {
            String id = e.getValue();
            if (id != null && !id.contains(":")) {
                String candidate = "minecraft:" + id;
                id = index.isValid(candidate) ? candidate : index.fallbackMaterial(candidate);
            }
            if (id == null || !index.isValid(id)) {
                issues.add("调色板角色 " + e.getKey() + " 的方块无效，已替换为石砖");
                id = index.fallbackMaterial("");
            }
            resolved.put(e.getKey().toLowerCase(Locale.ROOT), id);
        }
        palette = resolved;

        List<Zone> kept = new ArrayList<>();
        for (Zone zone : zones) {
            if (!OPS.contains(zone.op)) {
                zone.op = "add";
            }
            if (zone.params == null) {
                zone.params = new JsonObject();
            }
            if (!SHAPES.contains(zone.shape)) {
                issues.add("区域 " + zone.id + " 用了自定义形状 " + zone.shape
                        + "，几何按实心盒兜底；原写法已存进 params.customShape，后续阶段仍能看到");
                if (!zone.params.has("customShape")) {
                    zone.params.addProperty("customShape", zone.shape);
                }
                zone.shape = "box";
            }
            if (zone.box == null) {
                issues.add("区域 " + zone.id + " 缺少 box，已丢弃");
                continue;
            }
            int[] clipped = bounds.clamp(zone.box[0], zone.box[1], zone.box[2],
                    zone.box[3], zone.box[4], zone.box[5]);
            if (clipped == null) {
                issues.add("区域 " + zone.id + " 完全在选区外，已丢弃");
                continue;
            }
            zone.box = clipped;
            String material = zone.material;
            if (material == null || material.isBlank()) {
                material = "wall";
            }
            // entity: 前缀走独立实体通道，不做方块注册表校验
            if (!material.startsWith("entity:")) {
                // 拆出显式状态串，只校验基础 id，状态项留给 BlockIndex.sanitizeState 过滤
                int bracket = material.indexOf('[');
                String base = (bracket >= 0 ? material.substring(0, bracket) : material).trim();
                String state = (bracket >= 0 && material.endsWith("]"))
                        ? material.substring(bracket + 1, material.length() - 1) : "";
                if (!base.contains(":")) {
                    String role = base.toLowerCase(Locale.ROOT);
                    base = palette.getOrDefault(role, index.fallbackMaterial(role));
                }
                if (!index.isValid(base)) {
                    issues.add("区域 " + zone.id + " 的方块 " + base + " 不存在，已替换");
                    base = index.fallbackMaterial("");
                    state = "";
                }
                material = state.isBlank() ? base : base + "[" + state + "]";
            }
            zone.material = material;
            if (zone.op.equals("remove")) {
                zone.material = "minecraft:air";
            }
            kept.add(zone);
        }
        zones = kept;
    }

    public boolean isEmpty() {
        return zones.isEmpty();
    }

    public String toJson() {
        JsonObject root = new JsonObject();
        root.addProperty("title", title);
        root.addProperty("notes", notes);
        JsonObject paletteJson = new JsonObject();
        for (Map.Entry<String, String> e : palette.entrySet()) {
            paletteJson.addProperty(e.getKey(), e.getValue());
        }
        root.add("palette", paletteJson);
        // 自由设计意图与所有自定义字段原样写回累计方案，下一阶段与修改指令还能读到
        if (design != null && !design.isBlank()) {
            root.addProperty("design", design);
        }
        if (extra != null) {
            for (Map.Entry<String, JsonElement> e : extra.entrySet()) {
                if (!root.has(e.getKey())) {
                    root.add(e.getKey(), e.getValue());
                }
            }
        }
        JsonArray array = new JsonArray();
        for (Zone z : zones) {
            JsonObject o = new JsonObject();
            o.addProperty("id", z.id);
            o.addProperty("op", z.op);
            o.addProperty("name", z.name);
            o.addProperty("shape", z.shape);
            JsonArray box = new JsonArray();
            for (int v : z.box) {
                box.add(v);
            }
            o.add("box", box);
            o.addProperty("material", z.material);
            o.add("params", z.params);
            array.add(o);
        }
        root.add("zones", array);
        return Json.GSON.toJson(root);
    }

    /** GUI 与「记忆摘要」用的紧凑文本，永远不会超过几十行。 */
    public String summaryText() {
        StringBuilder sb = new StringBuilder();
        sb.append("方案：").append(title);
        sb.append('\n');
        sb.append("材质：");
        int n = 0;
        for (Map.Entry<String, String> e : palette.entrySet()) {
            if (n++ > 0) {
                sb.append("、");
            }
            sb.append(e.getKey()).append('=').append(e.getValue());
        }
        sb.append('\n');
        for (Zone z : zones) {
            sb.append("· [").append(z.op).append("] ").append(z.name)
                    .append("  ").append(z.shape).append(" (")
                    .append(z.box[0]).append(',').append(z.box[1]).append(',').append(z.box[2])
                    .append(")~(")
                    .append(z.box[3]).append(',').append(z.box[4]).append(',').append(z.box[5])
                    .append(") ").append(z.material).append('\n');
        }
        if (!notes.isBlank()) {
            sb.append("备注：").append(notes);
        }
        return sb.toString();
    }

    /** 离线兜底：没有任何 API Key 时也能生成一个真正可建造的参数化方案。 */
    public static BuildPlan fallback(Bounds bounds, String brief) {
        BuildPlan plan = new BuildPlan();
        String text = brief == null ? "" : brief;
        boolean modern = text.contains("现代") || text.contains("别墅") || text.contains("玻璃");
        plan.title = "内置模板 · " + (modern ? "现代小屋" : "通用房舍");
        plan.palette.put("floor", modern ? "minecraft:smooth_stone" : "minecraft:oak_planks");
        plan.palette.put("wall", modern ? "minecraft:white_concrete" : "minecraft:stone_bricks");
        plan.palette.put("trim", modern ? "minecraft:quartz_block" : "minecraft:oak_log");
        plan.palette.put("roof", modern ? "minecraft:light_gray_concrete" : "minecraft:spruce_planks");
        plan.palette.put("window", "minecraft:glass_pane");
        plan.palette.put("light", "minecraft:lantern");

        int x1 = bounds.minX(), y1 = bounds.minY(), z1 = bounds.minZ();
        int x2 = bounds.maxX(), z2 = bounds.maxZ();
        int height = Math.min(bounds.sizeY() - 2, Math.max(3, Math.min(9, bounds.sizeY() / 2 + 3)));
        int top = Math.min(y1 + height, bounds.maxY());

        plan.zones.add(zone("z1", "add", "地基与地板", "floor", arr(x1, y1, z1, x2, y1, z2), "floor", null));
        plan.zones.add(zone("z2", "add", "主体四壁", "hollow", arr(x1, y1 + 1, z1, x2, top, z2), "wall", null));
        JsonObject openings = new JsonObject();
        openings.addProperty("thickness", 1);
        JsonArray door = new JsonArray();
        door.add((x1 + x2) / 2);
        door.add(y1 + 1);
        door.add(z2);
        door.add((x1 + x2) / 2);
        door.add(y1 + 2);
        door.add(z2);
        openings.add("opening", door);
        plan.zones.add(zone("z3", "add", "正面入口", "clear", arr((x1 + x2) / 2, y1 + 1, z2, (x1 + x2) / 2, y1 + 2, z2), "minecraft:air", openings));
        int wy = y1 + 2;
        for (int x = x1 + 2; x <= x2 - 2; x += 3) {
            plan.zones.add(zone("w" + x, "add", "侧窗", "clear", arr(x, wy, z1, x, wy + 1, z1), "minecraft:air", null));
            plan.zones.add(zone("w" + x + "b", "add", "侧窗玻璃", "box", arr(x, wy, z1, x, wy + 1, z1), "window", null));
        }
        plan.zones.add(zone("z4", "add", "坡屋顶", "roof_gable", arr(x1, top + 1, z1, x2, Math.min(top + 4, bounds.maxY()), z2), "roof", null));
        plan.zones.add(zone("z5", "add", "四角立柱", "frame", arr(x1, y1 + 1, z1, x2, top, z2), "trim", null));
        return plan;
    }

    /** 离线模式也按阶段施工：把内置模板拆成若干批，每批单独编译、单独放置。 */
    public static List<BuildPlan> fallbackStages(Bounds bounds, String brief, int count) {
        BuildPlan base = fallback(bounds, brief);
        List<List<Zone>> groups = List.of(new ArrayList<>(), new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
        for (Zone z : base.zones) {
            if (z.id.equals("z1")) {
                groups.get(0).add(z);
            } else if (z.id.equals("z2") || z.id.equals("z4") || z.id.equals("z5")) {
                groups.get(1).add(z);
            } else {
                groups.get(2).add(z);
            }
        }
        int x1 = bounds.minX(), y1 = bounds.minY(), z1 = bounds.minZ();
        int x2 = bounds.maxX(), z2 = bounds.maxZ();
        int top = Math.min(y1 + 6, bounds.maxY());
        groups.get(3).add(zone("d1", "add", "正面檐口线脚", "line",
                arr(x1, top, z2, x2, top, z2), "trim", null));
        groups.get(3).add(zone("d2", "add", "入口照明", "pillar",
                arr(x1 + 1, y1 + 1, z2 - 1, x1 + 1, y1 + 2, z2 - 1), "light", null));
        groups.get(3).add(zone("d3", "add", "侧立面照明", "pillar",
                arr(x2 - 1, y1 + 1, z2 - 1, x2 - 1, y1 + 2, z2 - 1), "light", null));

        // 典型构件样板：自检与离线模板都能覆盖多格结构、NBT 与实体三条通道
        int cx = (x1 + x2) / 2;
        groups.get(2).add(zone("dDoor", "add", "正门", "box",
                arr(cx, y1 + 1, z2, cx, y1 + 1, z2), "minecraft:oak_door", null));
        groups.get(3).add(zone("dBed", "add", "室内床", "box",
                arr(x1 + 2, y1 + 1, z1 + 2, x1 + 2, y1 + 1, z1 + 2), "minecraft:red_bed", null));
        groups.get(3).add(zone("dFrame", "add", "墙上展示框", "entity",
                arr(x1 + 3, y1 + 2, z1 + 1, x1 + 3, y1 + 2, z1 + 1),
                "entity:minecraft:item_frame[item=minecraft:emerald]", null));

        int stages = Math.max(1, count);
        List<List<Zone>> buckets = new ArrayList<>();
        for (int i = 0; i < stages; i++) {
            buckets.add(new ArrayList<>());
        }
        for (int g = 0; g < groups.size(); g++) {
            buckets.get(Math.min(stages - 1, g * stages / groups.size())).addAll(groups.get(g));
        }
        List<BuildPlan> out = new ArrayList<>();
        for (int i = 0; i < stages; i++) {
            BuildPlan stage = new BuildPlan();
            stage.title = base.title;
            stage.notes = "离线模板第 " + (i + 1) + "/" + stages + " 阶段";
            stage.palette.putAll(base.palette);
            stage.zones.addAll(buckets.get(i));
            out.add(stage);
        }
        return out;
    }

    private static Zone zone(String id, String op, String name, String shape, int[] box,
                             String material, JsonObject params) {
        Zone z = new Zone();
        z.id = id;
        z.op = op;
        z.name = name;
        z.shape = shape;
        z.box = box;
        z.material = material;
        z.params = params == null ? new JsonObject() : params;
        return z;
    }

    private static int[] arr(int a, int b, int c, int d, int e, int f) {
        return new int[] { a, b, c, d, e, f };
    }
}
