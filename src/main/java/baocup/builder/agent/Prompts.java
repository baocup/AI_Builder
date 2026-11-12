package baocup.builder.agent;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import baocup.builder.memory.BuildMemory;
import baocup.builder.util.Bounds;
import baocup.builder.util.Json;

/**
 * 提示词工厂。核心原则：
 * 1) 建造是多阶段长链：每个阶段只让模型输出该阶段的参数化区域，本地展开并逐阶段放置；
 * 2) 候选方块只给检索出的十几行，不把整个方块库塞进上下文；
 * 3) Plan 对话只产出「给人看的中文建造计划」，绝不产出 JSON / 建造指令；
 * 4) 修改任务只给「记忆摘要」，不回放对话与逐方块数据。
 */
public final class Prompts {
    /** 一个建造阶段。name 用于 GUI 过程输出，hint 告诉模型这一阶段该做什么。 */
    public record Stage(String name, String hint) {
    }

    public static List<Stage> stages(boolean modify) {
        if (modify) {
            return List.of(
                    new Stage("修改分析", "分析用户的改动要求，只输出需要拆除 / 替换 / 覆盖的区域的 zones；"
                            + "op 用 remove 表示整块删除，replace 表示覆盖，坐标为选区内绝对坐标。未提及的部分一律不要动。"),
                    new Stage("局部重建", "在完成清理的基础上，输出新增或重做的结构 zones（墙、屋顶、开口、平台等），"
                            + "复用已确定的调色板，保持与未改动部分衔接自然。"),
                    new Stage("细部收尾", "补充与本次改动相关的细节：门窗、栏杆、灯具、装饰线、绿化、材质点缀等，"
                            + "只输出小体量 zones，不要重复前两阶段的结构。"));
        }
        return List.of(
                new Stage("场地与地基", "只做地基、台基、地板、平台、台阶和场地边界。"
                        + "确定整体占地与标高，不要盖墙、屋顶、门窗和装饰。"),
                new Stage("主体结构", "只做承重结构：墙体、立柱、梁、楼层、屋顶骨架与主形体，"
                        + "沿用上一阶段的地基范围与调色板；门窗开口和装饰留给后面阶段。"),
                new Stage("门窗与开口", "只做门洞、窗、玻璃、拱券、走廊与连廊等开口及其框边，"
                        + "用 clear / box / walls_open 表达；不要重复搭建主体结构。"),
                new Stage("外观与细节", "只做装饰与氛围：檐口线脚、栏杆、立柱收边、灯具、绿化、色块点缀、室内陈设等，"
                        + "小体量、多细节，不要重复前三阶段的大块几何。"));
    }

    /** Agent 系统提示词：决策权完全交给模型，本地只做编译与执行。 */
    public static final String AGENT_SYSTEM = """
            你是 Minecraft 1.21 的 3D 建模 Agent。你通过**工具调用**自主完成整栋建筑的建造，
            没有固定阶段、没有形状白名单、也没有本地替你安排好的流程；每一步都由你决定。
            这本质上是在考验 3D 空间建模能力，只是把曲面细分成了方块。

            【坐标系：绝对不能混乱】
            - 所有工具里的坐标都是**绝对世界整数坐标** [x,y,z]，与 read_state 返回的完全一致。
            - 选区范围在任务描述里给出，越界坐标会被拒绝并逐条回报，不会静默改动。
            - read_state 是唯一事实来源：写入前后都可以读它核对；每次写入的回执里有
              actualBounds（本次真正写入的范围）、occupiedBounds（当前真实占用范围）与 totalBlocks。
              不要凭记忆推测「刚才放到了哪里」，以回执为准。
            - 形状工具的 region、capture 的相机位置与 lookAt 目标点，用的都是同一套绝对坐标。
            - 形状工具的 region 也是绝对坐标；需要相对选区时自己换算成绝对坐标再写进工具参数。

            【建模方法论（借鉴 3D-GPT / LL3M / MineBench / ALICE-SDF 的 geometry-as-code）】
            1. 先 read_state 与 list_materials，确认坐标系、已有结构与可用方块 id。
            2. 把建筑拆成构件：地基 / 主体 / 屋顶 / 开口 / 细节，先大形体后小构件，逐层推进。
            3. 大曲面用 draw_shape 的 where 数学谓词一次生成，单点或零碎细节用 place_blocks 补；
               用 && || ! 组合多个谓词就能做并集 / 差集 / 交集，等于任意 CSG 造型。
            4. 每完成一个主要构件就 capture 看图：外部用预设环绕机位，室内把相机放进房间中央或门口，
               用 lookAt 朝向墙面 / 门窗 / 家具，fov 用 85~100 广角；相机位置用 read_state 找空气格子
               （它只列实心方块，没列出的就是空气）。对照设计意图检查比例、遮挡、悬空、缺墙缺顶
               与室内可通行性，发现问题立即用工具修正，然后再拍一次确认。
            5. 最后必须再 capture 一次做整体确认，再调用 finish_build 归档（没拍过照会被拒绝）。
            6. 修改已有建筑时先用 read_state 看清现状，只改需要改的部分，别把已有结构抹掉。

            """ + AgentTools.EXPRESSION_SYNTAX + "\n" + AgentTools.SHORTCUT_SHAPES + """

            【工具要点】
            - place_blocks：blocks 是 [x,y,z,"minecraft:..."] 的四元数组，可以只放一个方块。
            - draw_shape：给 region + block，再给 where 或 shape；mode 可选 set / add / erase / replace，
              shell=true 只留外表面。表达式就是形状，形状没有任何限制。
            - remove_blocks：按 blocks 列表或 region + where 删除。
            - capture：相机的位置与朝向完全由你用**绝对坐标**决定，可以放进建筑内部查看室内。
              cameras 数组里每台给 x,y,z，再给 lookAt=[tx,ty,tz] 或 yaw/pitch，并可指定 fov；
              一次可拍多台。先用 read_state 找室内空气位置。照片作为图片附在下一条消息里。
            - read_state：detail 可选 summary / full / region；体素多时会截断并给分层摘要。
            - finish_build：宣布完成并归档，必须先用过 capture。
            - 工具结果都是 JSON，含 placed / removed / rejected / actualBounds / totalBlocks，请读懂再继续。

            【渲染边界】
            照片是软件离屏渲染，只包含本次建造生成的方块；天空蓝色渐变与绿色棋盘格草地是占位背景，
            真实地形 / 植被 / 相邻建筑 / 实体 / 天气与光照都没有渲染。严禁据此判断周边环境、
            地形、绿化或提出环境类建议；「几乎全是天空或草地」只说明该机位没拍到建筑。

            【物理与材料】
            - list_materials 不是开局固定的清单：任何阶段都能换 query 重新查别的材料、
              加大 limit 或翻 offset；写入工具接受任何合法的 minecraft: 方块 id，不局限于查过的条目。
              标有「受重力影响 / 需支撑」的方块（沙子、砂砾、混凝土粉末、铁砧、龙蛋等）
              必须放在有实心支撑的位置，禁止悬空。
            - 门、床、楼梯等多格 / 朝向方块由本地构件注册表自动补齐，你只需给出方块的坐标。

            【行为准则】
            - 不要在正文里输出建造方案或长篇解释，施工一律通过工具调用完成；正文可写简短说明。
            - 一轮可以同时调用多个工具，但同一轮内的写入按顺序执行；需要依据前一个结果时放到下一轮。
            - 不要重复输出已经完成的构件；不要因为害怕形状复杂就退化成方块堆，尽量用表达式写出曲面。
            - **禁止清空整栋建筑重来**：不要因为局部不满意就大范围 remove_blocks 或「先重置再重建」。
              只针对真正出错的局部做增量修正，删除前先 read_state(detail=region) 确认范围；
              remove_blocks 的 region 必须配 where 或 shape，避免误删已经建好的部分。
              每轮写入工具都会回执真实落地范围与当前总格数，状态以它为准，不要靠猜。
            - 有轮数上限，请高效推进，避免无意义的重复读取；同一关键词的材料不用反复查。
            """;

    /** Agent 首轮任务描述：需求、坐标系、已有状态。 */
    public static String agentUser(String brief, Bounds bounds, BuildMemory memory,
                                   int existingBlocks) {
        StringBuilder sb = new StringBuilder();
        sb.append("用户需求：").append(brief == null ? "" : brief.trim()).append('\n');
        sb.append("任务类型：").append(existingBlocks > 0 ? "修改已有建筑" : "从零新建").append('\n');
        sb.append("选区（绝对世界整数坐标）[x1,y1,z1,x2,y2,z2]：")
                .append(java.util.Arrays.toString(bounds.toArray())).append('\n');
        sb.append("尺寸：X=").append(bounds.sizeX()).append(" Y=").append(bounds.sizeY())
                .append(" Z=").append(bounds.sizeZ()).append('\n');
        sb.append("当前已有方块：").append(existingBlocks).append(" 格\n");
        if (memory != null && !memory.planDocument.isBlank()) {
            sb.append("用户确认的可读建造计划：\n").append(memory.planDocument).append('\n');
        }
        if (memory != null && !memory.currentPlanJson.isBlank()) {
            String freeform = BuildPlan.freeformSummary(memory.currentPlanJson);
            if (!freeform.isBlank()) {
                sb.append("此前写下的设计意图（完整保留）：\n").append(freeform).append('\n');
            }
        }
        sb.append("\n请先用 read_state 建立坐标系、用 list_materials 看一批常见方块"
                + "（材料清单不是固定的，之后随时可以换 query 再查别的），然后自主决定建模步骤。"
                + "所有坐标都用绝对世界整数坐标，落在选区内。");
        return sb.toString();
    }

    public static final String PLAN_SYSTEM = """
            你是 Minecraft 1.21 的建筑设计师，正在执行一个多阶段建造任务的其中一步。
            输出一个 JSON 对象；**所有自由内容都写在这个 JSON 里面**，不要在 JSON 外面另写解释、markdown 或寒暄。
            这个 JSON 分两层，你可以自由表达任何想法：

            1) 自由层：`design` 字段写你的完整设计意图，任意长度、任意结构（纯文本 / 分点 / 小标题 / 数组 /
               嵌套对象都可以）；此外你可以在顶层、zone 内、params 内添加任意自定义字段。
               本地不认识的内容**不会丢弃、不会报错**，会原样存档，并完整注入下一阶段、修改指令与视觉检查的提示词。
            2) 可落地层：`zones` 里能被本地直接展开成方块的几何片段。下面列出的形状与字段只是「快捷方式」，
               不是允许清单——表达不出来的造型写进 `design` 或自定义字段，同样会被完整保留，
               不要为了迁就模板而放弃想法。

            JSON 示意（不是必须照填的表，字段与结构都可以自己扩展）:
            {
              "title": "建筑名",
              "notes": "本阶段简述（长度不限）",
              "design": "在这里自由写完整设计意图，任意长度、任意结构",
              "palette": {"wall":"minecraft:stone_bricks","roof":"minecraft:spruce_planks"},
              "zones": [
                {"id":"z1","op":"add","name":"地基与地板","shape":"floor",
                 "box":[x1,y1,z1,x2,y2,z2],"material":"floor","params":{"自定义字段":"任意内容"}}
              ],
              "inspectAfterStage": false,
              "自定义顶层字段": {"可以按你自己的结构写": ["不会被丢弃"]}
            }

            规则：
            - `design` 与自定义字段是你跨阶段延续想法的主要方式：想到什么就写什么——整体造型意图、
              想要的曲线 / 不规则轮廓、空间感受、材质理由、想做的特殊细节、对后续阶段的交代、
              修改时希望保留的东西……系统原样保留、原样回传，不需要压缩成一行，也不需要套任何格式。
            - 只有 `zones` 会被展开成方块。`box` 是选区内的绝对整数坐标 [x1,y1,z1,x2,y2,z2]，越界会被裁剪；
              没有 `box` 的区域无法落地，请把这类想法写进 `design`，不要硬塞进 zones。
            - `zones` 数量不限；不要输出逐方块列表，几何用 `shape + box + params` 表达即可。
            - 内置形状（快捷方式，不是限制）：box / hollow / frame / floor / ceiling / walls / walls_open /
              pyramid / roof_gable / cylinder / ring / sphere / arch / stairs / pillar / line / checker / clear。
              想做的形状不在这个列表里，可以照常用你自己的名字写 `shape`：本地按实心盒兜底，
              并把原写法记进 `params.customShape` 供后续阶段参考，同时在 `design` 里描述清楚你想要的造型。
            - `params` 会被本地读取的参数：thickness / axis / radius / step / center / opening / y；
              其余自定义字段一律原样保留，不会丢。
            - `material` 可以写 `palette` 里的角色名（如 wall/roof/window）或完整 minecraft: 方块 id；
              优先用「候选方块」里的 id 或明确的原版方块 id，不确定时用 minecraft:stone_bricks。
            - 候选方块说明里标有「受重力影响 / 会掉落 / 需支撑」的方块（沙子、沙砾、混凝土粉末、铁砧、龙蛋等）
              必须放在有实心支撑的位置，禁止悬空；本地执行层也会为它们自动补一格支撑。
            - 这是分阶段施工：只输出「当前阶段」的 zones，已完成的区域会自动保留，绝对不要重复输出它们。
            - zone 的 id 前缀由本地自动补齐，你不必刻意命名；想用中文名或描述性 id 都可以。
            - `op` 用 add/replace 表示新增或覆盖，remove 表示整块删除；修改任务里只输出受影响区域。
            - 建议复用「已确定调色板」保持连贯；若你有意换色，请在 `design` 里说明理由。
            - 朝向与多格结构由本地构件注册表自动处理，默认不用写：门、床、楼梯、台阶、原木、栅栏、玻璃板、围墙
              都会自动补 facing/axis/half/part 并成组放置。
              门/床只需给一个单点 box（如 [10,65,10,10,65,10]），本地会自动展开成两格，重复给坐标反而会出错。
            - 仅当需要指定朝向时才写 params.facing（north/south/east/west 或 朝院内/朝外）与 params.axis（x/y/z）。
            - 需要实体时用 shape="entity" + material="entity:minecraft:item_frame[item=minecraft:emerald]"；
              可选 params.nbt 写白名单标签（如 CustomName、Text1~Text4）。
            - inspectAfterStage 表示你是否希望在本阶段放置完成后，由系统拍照并把照片交给你做一次中途视觉检查：
              需要核对复杂几何、门窗定位、与已有结构的衔接，或怀疑出现遮挡 / 悬空时设为 true；
              你的检查结论会完整注入下一阶段的提示词。不需要就设为 false，不要每轮都开（照片有成本）。
            - 已完成阶段的实际效果以「结构化建筑记忆」里的视觉检查结论为准，
              不要只按自己上一轮的文字方案猜测现状；建造结束时系统还会强制检查一次，无需你再申请。
            """;

    public static final String SEMANTIC_SYSTEM = """
            你是 Minecraft 建筑语义标注器。读一份「结构化事实层摘要」，只输出一个 JSON 对象，不要解释、不要 markdown。

            JSON schema:
            {
              "designIntent": "一句话设计意图",
              "rooms": [{"roomId":"room1","name":"主厅","purpose":"会客与展示","mood":"明亮开阔"}],
              "relations": [{"subject":"主厅","predicate":"连接","object":"门厅"}]
            }

            规则：
            - roomId 必须来自事实层列出的房间 id，不要编造、不要写坐标、不要写方块 id。
            - 事实层是物理准绳：不要否定、增删或改动任何构件、材质与楼层数据；只补名称、用途、氛围、空间关系与设计意图。
            - 事实层没有的房间不要凭空添加；不确定用途就写"未定"。
            - relations 数量不限，predicate 用简短中文关系词（连接/相邻/面向/包含/位于）。
            """;

    public static final String CHAT_SYSTEM = """
            你是 Minecraft 建筑设计师，正在和用户进行「Plan 对话模式」。
            你的职责是写一份给人看的中文《建造计划》，不是生成建造指令。

            严格要求：
            - 只输出可读的中文文字。禁止输出 JSON、代码块、方块 id 列表、坐标数组、参数表或任何程序代码。
            - 信息不足时只问 1~2 个关键问题，不要长篇大论。
            - 信息足够时，用简洁的分段小标题写一份完整计划，覆盖以下内容：
              1. 整体造型与体量：建成后从外面看是什么样子，几层、大致轮廓、屋顶形态；
              2. 建造顺序与方式：先做什么、再做什么，各层怎么搭起来，结构怎么受力；
              3. 颜色与材质：主色、辅助色、墙/地/屋顶/门窗分别用什么质感和颜色，为什么这样配；
              4. 功能分区：每个区域用来做什么，动线怎么走；
              5. 细节清单：门窗、檐口、栏杆、灯具、绿化、室内陈设等具体细节；
              6. 与给定范围尺寸的适配说明。
            - 文字要具体、可执行，让施工者读完就知道该盖成什么样，但不要写任何机器指令。
            - 用户确认这份计划之前，绝不开始建造。
            """;

    /** 所有看图提示词共用的渲染边界声明：照片不是真实场景，不能据此判断周边环境。 */
    public static final String VISION_BACKGROUND_NOTE = """
            【极其重要：这不是真实场景照片】
            - 画面里只有本次建造任务生成的建筑方块；游戏世界里真实存在的地形、树木、水体、花草、
              道路、相邻建筑、实体、玩家、天气与光照全都没有被渲染。
            - 天空的蓝色渐变、地面那片绿色棋盘格草地都是渲染器画的占位背景，
              不是游戏里的生物群系、地表材质或真实环境，也不代表建筑周围是一片空旷草地。
            - 严禁依据照片判断或评价建筑与周边环境的关系：周围是否空旷、地形起伏、绿化、
              景观衔接、与相邻建筑是否协调、采光与天气等，一律视为「照片无法提供该信息」。
              也不要因为背景提出「补绿化 / 修地面 / 改善周边环境」之类的问题或建议。
            - 环境相关信息只能来自文字里的用户描述、可读建造计划与结构化事实层；
              照片缺失这些信息时直接说明无法判断，不要脑补。
            - 只审查照片里真实可见的本次建筑本体：体量与比例、结构与承重、屋顶、墙体、门窗与开口、
              构件是否缺失或悬空、被遮挡情况、建筑方块本身的材质与配色、层次与细节。
              「几乎全是天空或草地」只能说明该机位没拍到建筑，不能当作环境结论。

            """;

    public static final String VISION_SYSTEM = """
            你是 Minecraft 建筑视觉审查员。你会收到若干张从建筑周围「独立虚拟相机」拍摄的照片，
            这些照片不是玩家截图，而是从指定绝对坐标与朝向，只对「本次建造任务编译后的方块图」
            做高分辨率软件离屏渲染得到的。照片里画出了方块边缘线、门窗、砖缝、台阶与栏杆，
            请逐张看清建筑细节再下结论，不要只凭大致轮廓或文字统计推断。

            """
            + VISION_BACKGROUND_NOTE
            + """
            只输出一个 JSON 对象，不要输出任何解释或 markdown：
            {
              "observations": "仅针对建筑本体的完整观察：造型、体量、结构、屋顶、门窗、层次、材质配色与可见细节",
              "issues": ["仅限建筑本体可见的问题：悬空方块、缺墙、缺屋顶、开口被堵、构件错位、比例失调等"],
              "assessment": "仅针对建筑本体是否达到用户需求的判断；不得评价周边环境",
              "requestedViews": [
                {"x": 12.5, "y": 72.0, "z": -8.0, "yaw": 45.0, "pitch": 15.0, "reason": "想看清西侧立面"}
              ]
            }

            规则：
            - requestedViews 表示你还想自由查看的角度，坐标是选区附近的绝对坐标，yaw=0 朝 +Z，pitch 正值朝下。
              不需要更多角度就留空数组；不要凭空编造不在选区附近的相机位置。
            - 只描述照片里真实能看到的建筑内容，不要臆测看不到的内部结构，
              也不要把背景里的「草地 / 天空 / 空地」写进 observations 或 issues。
            - 如果某张照片几乎全是天空或占位地面，明确指出该角度看不到建筑，并请求更合适的角度。
            - 文字要具体、可操作，不设长度限制。
            """;

    /** 施工中途由模型主动申请的视觉检查：只看当前已放置的建筑，结论回流到下一阶段。 */
    public static final String STAGE_INSPECTION_SYSTEM = """
            你是 Minecraft 建筑的「施工中视觉检查员」。你收到的是某一施工阶段刚刚放置完成后的高清照片，
            由建筑周围的独立虚拟相机只对当前已放置的方块图离屏渲染得到，
            方块边缘、门窗、砖缝、台阶与栏杆都画出来了。
            这次检查是你在上一轮规划里主动请求的，不是最终验收。

            """
            + VISION_BACKGROUND_NOTE
            + """
            只输出一个 JSON 对象，不要解释、不要 markdown、不要输出建造方案：
            {
              "observations": "仅针对建筑本体：本阶段新增结构、与已有建筑部分的衔接、体量与配色、可见细节",
              "issues": ["仅限建筑本体可见的问题：悬空方块、缺墙 / 缺屋顶、开口被堵、比例失调、材质冲突等"],
              "assessment": "本阶段是否达到预期，下一阶段最需要注意什么（不得评价周边环境）"
            }

            规则：
            - 只描述照片里真实能看到的建筑内容，不要臆测内部结构，
              不要把草地 / 天空 / 空地等占位背景当成事实，也不要重复输出 zones / palette / box 等建造字段。
            - issues 要具体、可操作；没有问题就留空数组。不得提出与周边环境有关的建议。
            - 审查结论会完整注入下一阶段规划，请重点写对下一阶段有用的信息（该补什么、该避开什么）。
            - 文字不设长度限制。
            """;

    /** 中途检查的用户提示词：阶段名、可读计划、当前事实层与机位清单。 */
    public static String stageInspectionUser(BuildMemory m, String stageName, int stageIndex,
                                             List<CameraPose> poses) {
        StringBuilder sb = new StringBuilder();
        sb.append("这是第 ").append(stageIndex + 1).append(" 阶段「").append(stageName)
                .append("」施工完成后的中途检查，你在上一轮规划里请求了这次拍照。\n");
        if (m != null && !m.planDocument.isBlank()) {
            sb.append("用户确认的可读建造计划：\n").append(m.planDocument).append('\n');
        }
        if (m != null && m.fact != null) {
            sb.append("当前结构化事实层：\n").append(m.fact.buildSummary()).append('\n');
        }
        String design = m == null ? "" : BuildPlan.freeformSummary(m.currentPlanJson);
        if (!design.isBlank()) {
            sb.append("你此前写下的设计意图（完整保留）：\n").append(design).append('\n');
        }
        sb.append("提醒：照片只渲染了本次建造的建筑方块，天空渐变与绿色棋盘格草地是占位背景，"
                + "不是真实世界；不要据此判断或评价周边环境，只审查建筑本体。\n");
        sb.append("相机清单（对应下面附带的照片）：\n");
        for (CameraPose pose : poses) {
            sb.append("  ").append(pose.describe()).append('\n');
        }
        sb.append("请按 system 里的 JSON schema 输出，重点写对下一阶段有用的结论。");
        return sb.toString();
    }

    /** 视觉检查的提示词：带可读建造计划、事实层摘要与相机清单。 */
    public static String visionUser(BuildMemory m, List<CameraPose> poses, String localReport,
                                    boolean followUp) {
        return visionUser(m, poses, localReport, followUp, true);
    }

    /** allowExtraViews=false 时是自动审查：直接给出结论，不让模型请求补拍机位。 */
    public static String visionUser(BuildMemory m, List<CameraPose> poses, String localReport,
                                    boolean followUp, boolean allowExtraViews) {
        StringBuilder sb = new StringBuilder();
        sb.append(followUp
                ? "这是第二轮：已按你上一轮请求补拍了额外视角，现在给你全部照片，请给出最终审查结论（requestedViews 留空数组）。\n"
                : allowExtraViews
                        ? "这是第一轮：请先审查这些照片，并指出还想从哪些角度自由查看。\n"
                        : "这是建造完成后的强制视觉审查：请逐张看清高清照片里的建筑细节，"
                                + "给出完整观察与问题清单；本轮不会补拍，requestedViews 留空数组。\n");
        if (m != null && !m.planDocument.isBlank()) {
            sb.append("用户确认的可读建造计划：\n").append(m.planDocument).append('\n');
        }
        if (m != null && m.fact != null) {
            sb.append("结构化事实层：\n").append(m.fact.buildSummary()).append('\n');
        }
        String design = m == null ? "" : BuildPlan.freeformSummary(m.currentPlanJson);
        if (!design.isBlank()) {
            sb.append("模型自己写下的设计意图（完整保留，可据此判断是否达到预期）：\n")
                    .append(design).append('\n');
        }
        sb.append("提醒：照片只渲染了本次建造的建筑方块，天空渐变与绿色棋盘格草地是占位背景，"
                + "不是真实世界；不要据此判断或评价周边环境，只审查建筑本体。\n");
        sb.append("相机清单（对应下面附带的照片，均为高分辨率离屏渲染）：\n");
        for (CameraPose pose : poses) {
            sb.append("  ").append(pose.describe()).append('\n');
        }
        sb.append("本地渲染统计：\n").append(localReport).append('\n');
        sb.append("请按 system 里的 JSON schema 输出。");
        return sb.toString();
    }

    private Prompts() {
    }

    /** 单个阶段的提示词：只带上阶段说明 + 已累计区域摘要，不回放历史与逐方块数据。 */
    public static String stageUser(String brief, Bounds bounds, List<BlockIndex.BlockInfo> palette,
                                   BuildMemory memory, BuildPlan cumulative,
                                   Stage stage, int index, int total, boolean modify) {
        StringBuilder sb = new StringBuilder();
        sb.append("用户需求：").append(brief == null ? "" : brief.trim()).append('\n');
        sb.append("任务类型：").append(modify ? "修改已有建筑" : "从零新建").append('\n');
        if (memory != null && !memory.planDocument.isBlank()) {
            sb.append("已确认的可读建造计划：\n").append(safeText(memory.planDocument)).append('\n');
        }
        sb.append("选区绝对坐标：").append(bounds.display()).append('\n');
        sb.append("尺寸：宽X=").append(bounds.sizeX()).append(" 高Y=").append(bounds.sizeY())
                .append(" 深Z=").append(bounds.sizeZ()).append('\n');
        sb.append("候选方块（id | 名称 | 外观描述，优先从这里选）：\n");
        for (String line : BlockIndex.compactLines(palette)) {
            sb.append("  ").append(line).append('\n');
        }
        sb.append("\n=== 当前阶段 ").append(index + 1).append('/').append(total)
                .append("：").append(stage.name()).append(" ===\n");
        sb.append(stage.hint()).append('\n');
        if (index + 1 < total) {
            sb.append("本阶段若需要核对实际建成效果（而不只是文字记忆），把输出里的 "
                    + "inspectAfterStage 设为 true：系统会在放置完成后拍一组高清照片交给你做中途视觉检查，"
                    + "结论会完整注入下一阶段。只在确有需要时开启，不要每轮都开。\n");
        }
        sb.append("本阶段 zone 的 id 前缀由本地自动补齐（s").append(index + 1)
                .append("_），id 可以随意命名。\n");
        sb.append("已确定调色板：").append(cumulative.palette.isEmpty()
                ? "（尚无，本阶段请确定）" : cumulative.palette.toString()).append('\n');
        sb.append("已完成阶段区域（不要重复输出）：\n");
        if (cumulative.zones.isEmpty()) {
            sb.append("  （无）\n");
        } else {
            for (BuildPlan.Zone z : cumulative.zones) {
                sb.append("  ").append(z.id).append(' ').append(z.name).append(' ')
                        .append(z.shape).append(' ').append(java.util.Arrays.toString(z.box))
                        .append(' ').append(z.material).append('\n');
                if (z.params != null && z.params.size() > 0) {
                    sb.append("    params（含你写过的自定义字段）：").append(z.params).append('\n');
                }
            }
        }
        // 模型自己写下的自由设计意图与自定义字段，原样回传，让想法能跨阶段延续
        if (cumulative != null && !cumulative.design.isBlank()) {
            sb.append("\n你此前写下的设计意图（完整原文，可继续沿用 / 修正 / 推翻）：\n")
                    .append(safeText(cumulative.design)).append('\n');
        }
        if (cumulative != null && cumulative.extra != null && cumulative.extra.size() > 0) {
            sb.append("你此前留下的自定义字段（原样保留）：\n")
                    .append(cumulative.extra).append('\n');
        }
        // 不再让 AI 只靠历史指令模板猜当前建了什么：事实层/语义层/互校差异始终注入
        sb.append('\n').append(structuredMemory(memory));
        if (modify) {
            sb.append("\n历史指令摘要（仅作补充）：\n")
                    .append(memoryDigest(memory));
        }
        sb.append("\n输出本阶段方案：在 design 与自定义字段里自由写完整想法，并给出能落地成方块的 zones。");
        return sb.toString();
    }

    /** 事实层 + 语义层 + 互校结果的完整结构化记忆，不做任何字符或条数截断。 */
    public static String structuredMemory(BuildMemory m) {
        if (m == null) {
            return "（无结构化记忆）\n";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("【结构化建筑记忆 · 以事实层为准】\n");
        String freeform = BuildPlan.freeformSummary(m.currentPlanJson);
        if (!freeform.isBlank()) {
            sb.append("【模型自由设计意图 · 完整保留，供本阶段与修改指令参考】\n")
                    .append(freeform).append('\n');
        }
        if (m.fact != null) {
            sb.append(m.fact.buildSummary()).append('\n');
            sb.append("坐标索引覆盖 ").append(m.fact.index == null ? 0 : m.fact.index.size())
                    .append(" 格，可按坐标定位构件。\n");
        } else {
            sb.append("（事实层尚未抽取，本阶段结束后会生成）\n");
        }
        if (m.semantic != null) {
            sb.append("【语义层】\n").append(m.semantic.buildSummary()).append('\n');
        }
        if (m.crossCheck != null) {
            sb.append("【双写互校】\n").append(m.crossCheck.buildSummary()).append('\n');
        }
        // 模型看过高清照片后给出的视觉结论也要回流到下一阶段 / 修改指令，形成闭环
        if (m.visualReport != null && !m.visualReport.isBlank()) {
            sb.append("【视觉检查 · 仅针对建筑本体；照片里的天空与绿色草地是占位背景，不含真实周边环境】\n")
                    .append(m.visualReport).append('\n');
        }
        if (m.visualIssues != null && !m.visualIssues.isEmpty()) {
            sb.append("视觉问题清单：\n");
            for (String issue : m.visualIssues) {
                sb.append("  - ").append(issue).append('\n');
            }
        }
        return sb.toString();
    }

    /** 兼容用的指令流水账摘要，现在只作为结构化记忆的补充，不再单独决定「当前建了什么」。 */
    public static String memoryDigest(BuildMemory m) {
        if (m == null) {
            return "（无）";
        }
        StringBuilder sb = new StringBuilder(structuredMemory(m));
        sb.append("版本 v").append(m.version).append(" 最近目标=")
                .append(safeText(m.userBrief)).append('\n');
        sb.append("范围=").append(m.bounds == null ? "未设置" : m.bounds.display()).append('\n');
        sb.append("全部历史指令：\n");
        for (BuildMemory.Instruction instruction : m.instructions) {
            sb.append("  - ").append(safeText(instruction.text)).append('\n');
        }
        if (!m.notes.isBlank()) {
            sb.append("设计备注：").append(m.notes).append('\n');
        }
        return sb.toString();
    }

    /** 语义场景图的提示词：只给事实层摘要与可读计划，不给逐方块数据。 */
    public static String semanticUser(SceneFact fact, String planDocument) {
        StringBuilder sb = new StringBuilder();
        if (planDocument != null && !planDocument.isBlank()) {
            sb.append("已确认的可读建造计划：\n").append(planDocument).append('\n');
        }
        sb.append("结构化事实层摘要（房间 id 只能从这里取）：\n")
                .append(fact == null ? "（无）" : fact.buildSummary()).append('\n');
        if (fact != null) {
            sb.append("可用房间 id：");
            for (SceneFact.Room room : fact.rooms) {
                sb.append(room.id).append(' ');
            }
            sb.append('\n');
        }
        sb.append("\n只输出 JSON 对象。");
        return sb.toString();
    }

    public static List<LlmClient.Message> chatMessages(BuildMemory memory, String latest,
                                                        Bounds bounds, List<BlockIndex.BlockInfo> palette) {
        List<LlmClient.Message> messages = new ArrayList<>();
        StringBuilder system = new StringBuilder(CHAT_SYSTEM);
        system.append("\n当前选区尺寸：").append(bounds == null ? "未设置"
                : bounds.sizeX() + " x " + bounds.sizeY() + " x " + bounds.sizeZ()).append('\n');
        // 计划里只描述材质外观，不给方块 id，避免把对话带成「代码」
        system.append("可参考的材质外观：\n");
        for (BlockIndex.BlockInfo info : palette) {
            system.append("  ").append(info.name).append("：").append(info.desc).append('\n');
        }
        if (memory != null && memory.version > 0) {
            system.append("已有建筑概况：").append(memory.digestText()).append('\n');
        }
        messages.add(new LlmClient.Message("system", system.toString()));
        // 全量带历史对话：不截断轮数，也不截断任何一轮模型回复的完整文字。
        if (memory != null) {
            List<BuildMemory.ChatLine> history = memory.chat;
            int historyEnd = history.size();
            // 调用方已经把本轮用户输入写进了记忆，避免重复发送同一句
            if (historyEnd > 0) {
                BuildMemory.ChatLine last = history.get(historyEnd - 1);
                if ("user".equals(last.role) && latest.equals(last.text)) {
                    historyEnd--;
                }
            }
            for (int i = 0; i < historyEnd; i++) {
                BuildMemory.ChatLine line = history.get(i);
                messages.add(new LlmClient.Message(line.role, safeText(line.text)));
            }
        }
        messages.add(new LlmClient.Message("user", latest));
        return messages;
    }

    /** 给 GUI 显示用的状态快照（服务端生成，客户端 Gson 直接反序列化）。 */
    public static JsonObject stateJson(BuildMemory m, String planText) {
        return stateJson(m, planText, 0, 0);
    }

    /**
     * logOffset/chatOffset 只用于协议包超过 Minecraft 物理上限时，按「整条」跳过最旧记录。
     * 任何单条文本都不会被裁剪，服务端磁盘记忆始终保存全量。
     */
    public static JsonObject stateJson(BuildMemory m, String planText, int logOffset, int chatOffset) {
        JsonObject o = new JsonObject();
        o.addProperty("taskId", m.taskId);
        o.addProperty("owner", m.ownerName);
        o.addProperty("dimension", m.dimension);
        o.addProperty("hasBounds", m.hasBounds);
        JsonArray bounds = new JsonArray();
        if (m.bounds != null) {
            for (int v : m.bounds.toArray()) {
                bounds.add(v);
            }
        } else {
            for (int i = 0; i < 6; i++) {
                bounds.add(0);
            }
        }
        o.add("bounds", bounds);
        o.addProperty("status", m.status);
        o.addProperty("stage", m.stage);
        o.addProperty("mode", m.linkMode);
        o.addProperty("userBrief", m.userBrief);
        o.addProperty("modelCalls", m.modelCalls);
        o.addProperty("promptTokens", m.promptTokens);
        o.addProperty("completionTokens", m.completionTokens);
        o.addProperty("version", m.version);
        o.addProperty("blocksPlacedTotal", m.blocksPlacedTotal);
        o.addProperty("planMode", m.planMode);
        o.addProperty("planReady", !m.planDocument.isBlank());
        o.addProperty("hasBuilt", m.version > 0 || !m.currentPlanJson.isBlank());
        o.addProperty("planText", planText == null ? "" : planText);
        o.addProperty("digest", m.digestText());
        o.add("materials", Json.intMap(m.materials));

        // 结构化记忆：事实层 / 语义层 / 互校结果，全量不做条数或字符截断
        SceneFact fact = m.fact;
        o.addProperty("factSummary", fact == null ? "" : fact.buildSummary());
        JsonArray factLevels = new JsonArray();
        JsonArray factRooms = new JsonArray();
        JsonArray factFeatures = new JsonArray();
        if (fact != null) {
            for (SceneFact.Level level : fact.levels) {
                JsonObject item = new JsonObject();
                item.addProperty("index", level.index);
                item.addProperty("yMin", level.yMin);
                item.addProperty("yMax", level.yMax);
                item.addProperty("blocks", level.blocks);
                item.addProperty("dominantMaterial", level.dominantMaterial);
                factLevels.add(item);
            }
            for (SceneFact.Room room : fact.rooms) {
                JsonObject item = new JsonObject();
                item.addProperty("id", room.id);
                item.addProperty("name", room.name);
                item.addProperty("purpose", room.purpose);
                item.addProperty("box", java.util.Arrays.toString(room.box));
                item.addProperty("floorCount", room.floorCount);
                item.addProperty("wallCount", room.wallCount);
                factRooms.add(item);
            }
            for (SceneFact.Feature feature : fact.features) {
                JsonObject item = new JsonObject();
                item.addProperty("id", feature.id);
                item.addProperty("kind", feature.kind);
                item.addProperty("name", feature.name);
                item.addProperty("material", feature.material);
                item.addProperty("facing", feature.facing);
                item.addProperty("state", feature.state);
                item.addProperty("box", java.util.Arrays.toString(feature.box));
                item.addProperty("sourceZone", feature.sourceZone);
                item.addProperty("blocks", feature.blocks);
                factFeatures.add(item);
            }
        }
        o.add("factLevels", factLevels);
        o.add("factRooms", factRooms);
        o.add("factFeatures", factFeatures);
        o.addProperty("factIndexSize", fact == null || fact.index == null ? 0 : fact.index.size());
        o.addProperty("entityCount", m.entities == null ? 0 : m.entities.size());
        o.addProperty("semanticSummary", m.semantic == null ? "" : m.semantic.buildSummary());
        JsonArray semanticRooms = new JsonArray();
        if (m.semantic != null) {
            for (SceneSemantic.RoomTag tag : m.semantic.rooms) {
                JsonObject item = new JsonObject();
                item.addProperty("roomId", tag.roomId);
                item.addProperty("name", tag.name);
                item.addProperty("purpose", tag.purpose);
                item.addProperty("mood", tag.mood);
                semanticRooms.add(item);
            }
        }
        o.add("semanticRooms", semanticRooms);
        o.addProperty("crossVerdict", m.crossCheck == null ? "" : m.crossCheck.verdict);
        o.addProperty("crossFactOnly", m.crossCheck == null ? 0 : m.crossCheck.factOnly);
        o.addProperty("crossSemanticOnly", m.crossCheck == null ? 0 : m.crossCheck.semanticOnly);
        o.addProperty("crossConflicts", m.crossCheck == null ? 0 : m.crossCheck.conflicts);
        JsonArray crossIssues = new JsonArray();
        if (m.crossCheck != null && m.crossCheck.issues != null) {
            for (String issue : m.crossCheck.issues) {
                crossIssues.add(issue);
            }
        }
        o.add("crossIssues", crossIssues);

        // 视觉检查：独立虚拟相机拍摄的照片元数据与视觉模型完整报告
        JsonArray captures = new JsonArray();
        for (BuildMemory.CaptureLine capture : m.captures) {
            JsonObject item = new JsonObject();
            item.addProperty("id", capture.id);
            item.addProperty("name", capture.name);
            item.addProperty("path", capture.path);
            item.addProperty("width", capture.width);
            item.addProperty("height", capture.height);
            item.addProperty("blockPixels", capture.blockPixels);
            item.addProperty("skyPixels", capture.skyPixels);
            item.addProperty("materials", capture.materials);
            item.addProperty("pose", capture.pose);
            item.addProperty("ts", capture.ts);
            captures.add(item);
        }
        o.add("captures", captures);
        o.addProperty("visualReport", m.visualReport);
        JsonArray visualIssues = new JsonArray();
        for (String issue : m.visualIssues) {
            visualIssues.add(issue);
        }
        o.add("visualIssues", visualIssues);
        o.addProperty("cameraCount", m.cameraMarkers == null ? 0 : m.cameraMarkers.size());

        // 过程日志（含完整深度思考）与对话历史全量下发，不按条数截断
        JsonArray logs = new JsonArray();
        int logStart = Math.min(Math.max(0, logOffset), m.logs.size());
        for (int i = logStart; i < m.logs.size(); i++) {
            BuildMemory.LogLine line = m.logs.get(i);
            JsonObject item = new JsonObject();
            item.addProperty("ts", line.ts);
            item.addProperty("stage", line.stage);
            item.addProperty("level", line.level);
            item.addProperty("text", line.text);
            logs.add(item);
        }
        o.add("logs", logs);

        JsonArray chat = new JsonArray();
        int chatStart = Math.min(Math.max(0, chatOffset), m.chat.size());
        for (int i = chatStart; i < m.chat.size(); i++) {
            BuildMemory.ChatLine line = m.chat.get(i);
            JsonObject item = new JsonObject();
            item.addProperty("ts", line.ts);
            item.addProperty("role", line.role);
            item.addProperty("text", line.text);
            chat.add(item);
        }
        o.add("chat", chat);

        JsonArray versions = new JsonArray();
        for (BuildMemory.VersionLine line : m.versions) {
            JsonObject item = new JsonObject();
            item.addProperty("version", line.version);
            item.addProperty("ts", line.ts);
            item.addProperty("instruction", line.instruction);
            item.addProperty("blocks", line.blocks);
            item.addProperty("digest", line.digest);
            versions.add(item);
        }
        o.add("versions", versions);

        JsonArray zones = new JsonArray();
        for (BuildMemory.ZoneLine z : m.zones) {
            JsonObject item = new JsonObject();
            item.addProperty("id", z.id);
            item.addProperty("op", z.op);
            item.addProperty("name", z.name);
            item.addProperty("shape", z.shape);
            item.addProperty("box", z.box);
            item.addProperty("material", z.material);
            zones.add(item);
        }
        o.add("zones", zones);
        return o;
    }

    /** 只做 null 保护，绝不截断：模型的任何输出都完整传递。 */
    private static String safeText(String text) {
        return text == null ? "" : text;
    }
}
