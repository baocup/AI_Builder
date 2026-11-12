package baocup.builder.agent;

import java.util.List;
import java.util.Map;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import baocup.builder.util.Json;

/**
 * Agent 的工具清单（OpenAI function calling 的 tools 数组）。
 *
 * 借鉴 3D-GPT / LL3M「几何即代码」与 MineBench「voxel.exec 工具模式」：
 * 模型不再填固定形状表，而是用数学谓词表达式、几何快捷原语或直接体素列表描述形状；
 * 本地只负责把这些编译成「哪些方块放在哪里」。
 */
public final class AgentTools {
    public static final String READ_STATE = "read_state";
    public static final String LIST_MATERIALS = "list_materials";
    public static final String PLACE_BLOCKS = "place_blocks";
    public static final String DRAW_SHAPE = "draw_shape";
    public static final String REMOVE_BLOCKS = "remove_blocks";
    public static final String CAPTURE = "capture";
    public static final String FINISH_BUILD = "finish_build";

    public record Tool(String name, String description, String parameterSchema) {
        public JsonObject toJson() {
            JsonObject wrapper = new JsonObject();
            wrapper.addProperty("type", "function");
            JsonObject function = new JsonObject();
            function.addProperty("name", name);
            function.addProperty("description", description);
            function.add("parameters", Json.GSON.fromJson(parameterSchema, JsonObject.class));
            wrapper.add("function", function);
            return wrapper;
        }
    }

    /** 表达式里可用的变量与函数，写进 draw_shape 描述，模型不必猜。 */
    public static final String EXPRESSION_SYNTAX = """
            表达式 where 是任意返回布尔值的数学谓词，逐个体素求值，结果为真的格子会被填充。
            变量：x y z（绝对世界整数坐标）、cx cy cz（region 中心）、rx ry rz（region 半尺寸）、
                  x0 y0 z0 x1 y1 z1（region 边界）、t（0~1 的归一化高度）、i（本格子序号）、n（region 总体素数）、pi e。
            运算符：+ - * / % ^、== != > < >= <=、&& || !、括号、逗号。
            函数：abs min max floor ceil round sqrt pow sin cos tan atan2 hypot clamp step smoothstep
                  mod sign lerp fract dist dist2 length mix。
            例如椭球：(x-cx)^2/rx^2+(y-cy)^2/ry^2+(z-cz)^2/rz^2<=1
            圆柱：(x-cx)^2+(z-cz)^2<=rx^2 && y>=y0 && y<=y1
            斜屋顶：y<=y1-(abs(x-cx)/rx)*(y1-y0)*0.6
            用 && || ! 可以做任意并集 / 差集 / 交集，形状没有任何白名单限制。""";

    /** 内置快捷形状只是帮模型少写式子，不是允许清单；where 表达式永远优先。 */
    public static final String SHORTCUT_SHAPES = """
            可选快捷形状 shape（配 params；给了 where 时 where 优先）：
            box / hollow_box / frame / plane / line / diagonal / polyline / rect / circle / ellipse /
            sphere / ellipsoid / cylinder / cone / truncated_cone / pyramid / tetrahedron / triangle /
            polygon / prism / torus / arc / spiral / helix / wave / paraboloid / hyperboloid /
            superellipse / rounded_box / ring / star / cross / checker / noise / lattice / staircase /
            dome / gable_roof / hip_roof / arch / vault / shell（只取外表面）/ extrude / revolve。
            params 常用键：radius, innerRadius, rx, ry, rz, thickness, axis, start, end, points,
            segments, turns, height, width, frequency, amplitude, phase, seed, hollow, facing, steps。
            不在列表里的形状名也允许：直接写 where 表达式即可，本地不会报错。""";

    public static final List<Tool> ALL = List.of(
            new Tool(READ_STATE, """
                    读取当前建筑的权威状态（JSON 化体素）。所有坐标都是绝对世界整数坐标。
                    在任何写入前后都可以调用它来核对坐标、避免混乱；修改已有建筑时应当先调用一次。
                    detail=full 返回完整体素列表（超过上限会返回分层摘要并提示用 region 缩小范围）；
                    detail=summary 只返回包围盒、材质统计与总格数；detail=region 返回指定 box 内的体素。
                    返回值里的 selection 是选区、occupiedBounds 是当前真实占用范围，请以它为准，不要凭记忆推测。
                    """, """
                    {"type":"object","properties":{
                      "detail":{"type":"string","enum":["full","summary","region"],"description":"默认 summary"},
                      "region":{"type":"array","items":{"type":"integer"},"minItems":6,"maxItems":6,
                        "description":"detail=region 时的 [x1,y1,z1,x2,y2,z2] 绝对坐标"},
                      "maxBlocks":{"type":"integer","description":"full/region 最多返回多少格，默认 20000"}
                    },"required":[]}
                    """),
            new Tool(LIST_MATERIALS, """
                    查询可用方块。返回 id、中文名、外观描述与物理特性（重力 / 需支撑）。
                    这不是开局固定的清单：建造过程中任何一轮都可以重新调用，用新的 query 查别的材料，
                    或用 limit / offset 翻页看更多。query 会走整个方块外观索引的关键词检索，返回 id、
                    中文名、外观描述与物理特性（重力 / 需支撑）；不填 query 时只给一批通用基线作起步建议。
                    写入工具接受任何合法的 minecraft: 方块 id，不局限于这里查过的条目。
                    标有「受重力影响 / 需支撑」的方块必须放在有实心支撑的位置。
                    """, """
                    {"type":"object","properties":{
                      "query":{"type":"string","description":"关键词，如 木/石/玻璃/门/楼梯/灯/铜/陶瓦；留空给基线候选"},
                      "limit":{"type":"integer","description":"本页返回条数，默认 40，最大 100"},
                      "offset":{"type":"integer","description":"从第几条开始返回，用于翻页，默认 0"}
                    },"required":[]}
                    """),
            new Tool(PLACE_BLOCKS, """
                    直接放置单个方块或一批方块（最细粒度的建模方式）。
                    blocks 是 [x,y,z,"minecraft:..."] 的四元数组，坐标为绝对世界整数坐标。
                    越界、非整数、y 超范围、非法方块 id 会被拒绝并在结果里逐条回报，不会静默改动。
                    结果会返回本次真正写入的格子数、被拒条目、实际写入坐标范围与当前总格数，请据此核对。
                    """, """
                    {"type":"object","properties":{
                      "blocks":{"type":"array","items":{"type":"array","minItems":4,"maxItems":5,
                        "description":"[x,y,z,block] 或 [x,y,z,block,state]"}},
                      "mode":{"type":"string","enum":["set","add"],"description":"set 覆盖，add 只填空气；默认 set"}
                    },"required":["blocks"]}
                    """),
            new Tool(DRAW_SHAPE, """
                    按形状 / 数学表达式一次生成一片体素，是大规模建模的主要工具。
                    必须给 region（绝对坐标包围盒 [x1,y1,z1,x2,y2,z2]）和 block。
                    形状完全自由：既可以直接写 where 数学谓词，也可以选内置快捷形状，两者可组合。
                    """ + EXPRESSION_SYNTAX + "\n" + SHORTCUT_SHAPES + """
                    mode=set 覆盖、add 只填空气、erase 清空、replace 只替换指定旧方块；
                    shell=true 时只保留外表面（挖空内部）。region 会被裁剪到选区并回报实际范围。
                    结果同样返回实际写入坐标范围、格数、被拒原因与当前总格数。
                    """, """
                    {"type":"object","properties":{
                      "region":{"type":"array","items":{"type":"integer"},"minItems":6,"maxItems":6,
                        "description":"[x1,y1,z1,x2,y2,z2] 绝对世界整数坐标包围盒"},
                      "block":{"type":"string","description":"方块 id，如 minecraft:stone_bricks"},
                      "where":{"type":"string","description":"任意数学谓词表达式；给了它就用它决定哪些格子被填充"},
                      "shape":{"type":"string","description":"可选快捷形状名，不在列表里也不会报错"},
                      "params":{"type":"object","description":"快捷形状参数，可写任意自定义键"},
                      "mode":{"type":"string","enum":["set","add","erase","replace"],"description":"默认 set"},
                      "replaceBlock":{"type":"string","description":"mode=replace 时只替换这个方块 id"},
                      "shell":{"type":"boolean","description":"只保留外表面，默认 false"},
                      "state":{"type":"string","description":"可选 BlockState 属性串"}
                    },"required":["region","block"]}
                    """),
            new Tool(REMOVE_BLOCKS, """
                    局部删除方块，用于修正出错的小区域，**不要用它清空整栋建筑重来**。
                    可以给出 blocks 坐标列表，或给出 region + where / shape 只删满足条件的格子。
                    只给 region 而不给 where / shape 会被拒绝（防止误删整片）；确实要清空整个 region
                    时显式传 all=true。单次删除有格数上限，超出会被回报为 rejected。
                    删除前建议先 read_state(detail=region) 确认范围；结果会回报实际删除数与剩余总格数。
                    """, """
                    {"type":"object","properties":{
                      "blocks":{"type":"array","items":{"type":"array","minItems":3,"maxItems":3},
                        "description":"[x,y,z] 绝对坐标列表"},
                      "region":{"type":"array","items":{"type":"integer"},"minItems":6,"maxItems":6},
                      "where":{"type":"string","description":"谓词，只删满足条件的格子"},
                      "shape":{"type":"string","description":"快捷形状，配合 params 使用"},
                      "params":{"type":"object","description":"快捷形状参数"},
                      "all":{"type":"boolean","description":"确要清空整个 region 时显式设为 true，默认 false"}
                    },"required":[]}
                    """),
            new Tool(CAPTURE, """
                    从独立虚拟相机拍照并直接看图，用于自检 3D 造型、比例、遮挡、室内布局与细节。
                    相机的位置和朝向完全由你决定：cameras 里每台相机给一组**绝对世界坐标** x,y,z，
                    可以放在建筑外面、屋顶上方、贴着某个立面，也可以放进建筑内部（大厅中央、
                    房间内、楼梯间、门窗旁边）查看室内。一次可以指定多台相机，逐张回传。
                    朝向二选一：给 lookAt=[tx,ty,tz] 让相机看向某个绝对坐标点（最直观），
                    或直接给 yaw/pitch；yaw=0 朝 +Z、90 朝 -X，pitch 正值朝下，单位都是度。
                    fov 是水平视场角（20~110，默认 70）；室内小空间建议 85~100 广角。
                    想找室内的空气位置，先用 read_state 的 detail=region：它只列出实心方块，
                    没列出的格子就是空气，把相机放在那里最清楚。坐标可以是小数以微调机位。
                    不填 cameras 时才用 view 的预设环绕 / 俯视 / 人眼机位；view=none 表示只用 cameras。
                    回执会给出每台相机的实际绝对坐标、yaw/pitch/fov，以及是否落在实心方块内。

                    照片是软件离屏渲染：只包含本次建造生成的方块，天空渐变与绿色棋盘格草地是占位背景，
                    真实地形 / 植被 / 相邻建筑 / 实体 / 光照都没有渲染，严禁据此判断周边环境。
                    若通过 modelVision=no 关闭了图片能力，照片仍会落盘，但回执改为文字说明与本地渲染统计，
                    此时请用 read_state 核对建筑状态，不要反复等待照片。
                    建议大形体搭完拍一次、室内搭完放相机进去拍一次、修正后再整体拍一次。
                    """, """
                    {"type":"object","properties":{
                      "cameras":{"type":"array","description":"自定义机位数组，可一次拍多台；坐标是绝对世界坐标，允许小数",
                        "items":{"type":"object","properties":{
                          "name":{"type":"string","description":"机位名，会写在照片元数据与回执里"},
                          "x":{"type":"number","description":"绝对世界坐标 X"},
                          "y":{"type":"number","description":"绝对世界坐标 Y"},
                          "z":{"type":"number","description":"绝对世界坐标 Z"},
                          "lookAt":{"type":"array","items":{"type":"number"},"minItems":3,"maxItems":3,
                            "description":"[tx,ty,tz] 绝对坐标目标点；给了它就自动算 yaw/pitch"},
                          "yaw":{"type":"number","description":"度，0 朝 +Z、90 朝 -X"},
                          "pitch":{"type":"number","description":"度，正值朝下"},
                          "fov":{"type":"number","description":"水平视场角 20~110，默认 70；室内建议 85~100"},
                          "reason":{"type":"string","description":"这台相机想核对什么"}
                        },"required":["x","y","z"]}},
                      "view":{"type":"string","enum":["around","top","eye","all","none"],
                        "description":"不填 cameras 时使用的预设机位；默认 around"},
                      "maxCameras":{"type":"integer","description":"最多拍几台，默认按 visionMaxImages"},
                      "reason":{"type":"string","description":"这次拍照的整体目的"}
                    },"required":[]}
                    """),
            new Tool(FINISH_BUILD, """
                    宣布建造完成并归档版本。调用前必须至少用过一次 capture 看图确认；
                    若还没拍照，本工具会拒绝并提示你先拍照。归档会写入完整方块图与统计。
                    """, """
                    {"type":"object","properties":{
                      "summary":{"type":"string","description":"最终成果说明，会写入记忆"},
                      "title":{"type":"string","description":"建筑名"}
                    },"required":["summary"]}
                    """));

    /** 默认用兼容模式；端点确认支持完整 JSON Schema 时可传 false。 */
    public static JsonArray definitions() {
        return definitions(true);
    }

    public static JsonArray definitions(boolean compat) {
        JsonArray array = new JsonArray();
        for (Tool tool : ALL) {
            JsonObject wrapper = tool.toJson();
            if (compat) {
                JsonObject function = wrapper.getAsJsonObject("function");
                JsonObject parameters = function == null ? null
                        : function.getAsJsonObject("parameters");
                if (parameters != null) {
                    simplifySchema(parameters);
                }
            }
            array.add(wrapper);
        }
        return array;
    }

    /** 这些约束关键字在 Qwen / DashScope 兼容层常被判为 invalid_parameter_error。 */
    private static final List<String> REMOVED_SCHEMA_KEYS = List.of(
            "enum", "minItems", "maxItems", "minimum", "maximum",
            "minLength", "maxLength", "pattern", "additionalProperties",
            "format", "default", "exclusiveMinimum", "exclusiveMaximum");

    /**
     * 只保留各端点都接受的 JSON Schema 关键字（type / properties / required / description / items）。
     * enum 的可选值先写进 description 再删除，语义不丢；参数合法性本来就由本地 ToolExecutor 校验。
     */
    private static void simplifySchema(JsonObject schema) {
        JsonElement allowed = schema.get("enum");
        if (allowed != null && allowed.isJsonArray()) {
            String description = Json.str(schema, "description", "");
            String values = allowed.getAsJsonArray().toString();
            schema.addProperty("description", description.isBlank()
                    ? "可选值：" + values : description + "；可选值：" + values);
        }
        for (String key : REMOVED_SCHEMA_KEYS) {
            schema.remove(key);
        }
        JsonArray required = schema.getAsJsonArray("required");
        if (required != null && required.isEmpty()) {
            schema.remove("required");
        }
        JsonObject properties = schema.getAsJsonObject("properties");
        if (properties != null) {
            for (Map.Entry<String, JsonElement> entry : properties.entrySet()) {
                if (entry.getValue().isJsonObject()) {
                    simplifySchema(entry.getValue().getAsJsonObject());
                }
            }
        }
        JsonElement items = schema.get("items");
        if (items != null && items.isJsonObject()) {
            simplifySchema(items.getAsJsonObject());
        }
    }

    private AgentTools() {
    }
}
