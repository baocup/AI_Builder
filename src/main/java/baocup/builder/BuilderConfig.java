package baocup.builder;

import java.util.Locale;

import net.neoforged.neoforge.common.ModConfigSpec;

/** 所有与 LLM、执行速率、容量上限相关的可调参数。 */
public final class BuilderConfig {
    private static final ModConfigSpec.Builder B = new ModConfigSpec.Builder();

    public static final ModConfigSpec.ConfigValue<String> API_BASE;
    public static final ModConfigSpec.ConfigValue<String> API_KEY;
    /** 接入方式：auto = 按 apiBase 自动识别 / tokenplan = 千问 AI Token Plan / standard。 */
    public static final ModConfigSpec.ConfigValue<String> API_PRESET;
    public static final ModConfigSpec.ConfigValue<String> MODEL;
    /** 模型能力三态配置：auto = 按模型名自动识别已知模型 / yes / no。 */
    public static final ModConfigSpec.ConfigValue<String> MODEL_VISION;
    public static final ModConfigSpec.ConfigValue<String> MODEL_TOOLS;
    /** 纯文本请求的思考参数：auto / off / enable_thinking / full。 */
    public static final ModConfigSpec.ConfigValue<String> MODEL_THINKING;
    public static final ModConfigSpec.IntValue TIMEOUT_SECONDS;
    /** Agent 工具轮单独超时：深度思考可能长达数万字，比普通文本 / 看图请求慢得多。 */
    public static final ModConfigSpec.IntValue AGENT_TIMEOUT_SECONDS;
    public static final ModConfigSpec.IntValue MAX_RETRIES;
    public static final ModConfigSpec.DoubleValue TEMPERATURE;
    /** 0 = 不设限：请求里不带 max_tokens，输出长度完全由模型 / 服务端决定。 */
    public static final ModConfigSpec.IntValue OUTPUT_TOKEN_LIMIT;
    /** 输入上下文预算上限，默认 500K token；超出时按预算裁剪最早的历史消息。 */
    public static final ModConfigSpec.IntValue MAX_INPUT_TOKENS;
    /** 纯文本链路的深度思考强制开启，这里只调节强度；多模态请求不发送该字段。 */
    public static final ModConfigSpec.ConfigValue<String> REASONING_EFFORT;

    public static final ModConfigSpec.IntValue BLOCKS_PER_TICK;
    public static final ModConfigSpec.IntValue MAX_BLOCKS_PER_TASK;
    public static final ModConfigSpec.LongValue MAX_VOLUME;
    public static final ModConfigSpec.BooleanValue ECHO_TO_CHAT;
    public static final ModConfigSpec.BooleanValue SAVE_PLACEMENTS;
    public static final ModConfigSpec.BooleanValue COMPONENTS_ENABLED;
    public static final ModConfigSpec.BooleanValue AUTO_SUPPORT;
    public static final ModConfigSpec.BooleanValue SEMANTIC_ENABLED;
    public static final ModConfigSpec.BooleanValue SEMANTIC_CORRECTION;

    /** Agent：真正的 tool calling 与自由几何编译。 */
    public static final ModConfigSpec.BooleanValue AGENT_TOOLS_ENABLED;
    public static final ModConfigSpec.BooleanValue AGENT_THINKING;
    /** Agent 轮用 SSE 流式返回，让深度思考内容能逐段实时写进输出。 */
    public static final ModConfigSpec.BooleanValue AGENT_STREAM;
    /** tools 的 JSON Schema 模式与 tool_choice 字段，用于适配不同端点的参数校验。 */
    public static final ModConfigSpec.ConfigValue<String> TOOLS_SCHEMA_MODE;
    public static final ModConfigSpec.ConfigValue<String> TOOL_CHOICE;
    public static final ModConfigSpec.IntValue AGENT_MAX_ROUNDS;
    public static final ModConfigSpec.IntValue AGENT_MAX_CELLS_PER_CALL;
    public static final ModConfigSpec.IntValue AGENT_MAX_BLOCKS_PER_CALL;
    public static final ModConfigSpec.IntValue AGENT_MAX_READ_BLOCKS;

    /** 千问 AI Token Plan 专属 OpenAI 兼容端点（华北 2 北京，个人版 / 团队版）。 */
    public static final String TOKEN_PLAN_BASE_URL =
            "https://token-plan.cn-beijing.maas.aliyuncs.com/compatible-mode/v1";
    /** 国际站 Token Plan 端点。 */
    public static final String TOKEN_PLAN_INTL_BASE_URL =
            "https://token-plan.ap-southeast-1.maas.aliyuncs.com/compatible-mode/v1";
    /** Token Plan 专属 Key 前缀；普通按量计费 Key 为 sk-，两者不能混用。 */
    public static final String TOKEN_PLAN_KEY_PREFIX = "sk-sp-";

    /** 已知模型：文本 + 图片 + tools。 */
    public static final String MODEL_DEEPSEEK = "deepseek-v4.1-flash-1017";
    /** 已知模型（快照版）：Qwen3.8-Max 原生多模态，文本 + 图片 + tools。 */
    public static final String MODEL_QWEN = "qwen3.8-max-0902";
    /** Qwen3.8-Max 正式版（后台会迭代）与轻量版；Token Plan 也支持 model=auto。 */
    public static final String MODEL_QWEN_LATEST = "qwen3.8-max";
    public static final String MODEL_QWEN_FLASH = "qwen3.8-flash";
    public static final String DEFAULT_MODEL = MODEL_DEEPSEEK;

    /** 纯文本请求的思考参数档位。 */
    public enum Thinking {
        /** 不发送任何思考参数。 */
        OFF,
        /** 只发 enable_thinking=true（Qwen3 系列）。 */
        ENABLE_THINKING,
        /** enable_thinking + reasoning_effort（DeepSeek 兼容端点）。 */
        FULL
    }

    /** 视觉检查：独立虚拟相机 + 离屏渲染 + 同一个模型的多模态输入。 */
    public static final ModConfigSpec.ConfigValue<String> VISION_MODEL;
    public static final ModConfigSpec.BooleanValue VISUAL_ENABLED;
    public static final ModConfigSpec.IntValue VISUAL_WIDTH;
    public static final ModConfigSpec.IntValue VISUAL_HEIGHT;
    public static final ModConfigSpec.ConfigValue<String> VISION_DETAIL;
    public static final ModConfigSpec.IntValue VISION_TIMEOUT_SECONDS;
    public static final ModConfigSpec.IntValue VISUAL_VIEWS;
    public static final ModConfigSpec.IntValue VISION_MAX_EXTRA_VIEWS;
    public static final ModConfigSpec.IntValue VISION_MAX_IMAGES;
    public static final ModConfigSpec.IntValue VISION_INSPECTION_IMAGES;
    public static final ModConfigSpec.IntValue VISION_MAX_STAGE_INSPECTIONS;
    public static final ModConfigSpec.BooleanValue CAMERA_MARKERS;
    public static final ModConfigSpec.BooleanValue VISUAL_FINAL_INSPECTION;

    /** MCP：外部 Agent 通过 tool calling 直连建造，不读文件、不调用模型 API。 */
    public static final ModConfigSpec.BooleanValue MCP_ENABLED;
    public static final ModConfigSpec.ConfigValue<String> MCP_HOST;
    public static final ModConfigSpec.IntValue MCP_PORT;
    public static final ModConfigSpec.ConfigValue<String> MCP_TOKEN;
    public static final ModConfigSpec.IntValue MCP_BOUNDS_TIMEOUT_SECONDS;
    public static final ModConfigSpec.IntValue MCP_BOUNDS_WAIT_SECONDS;
    public static final ModConfigSpec.IntValue MCP_TOOL_TIMEOUT_SECONDS;

    public static final ModConfigSpec SPEC;

    static {
        B.comment("LLM：兼容 OpenAI /chat/completions 的服务（DashScope、DeepSeek、OpenAI、本地 vLLM 等）").push("llm");
        API_BASE = B.comment("接口地址，填到 /v1 为止。可写简写 tokenplan / tokenplan-intl 自动展开为"
                        + "千问 AI Token Plan 专属端点；不要带 /chat/completions")
                .define("apiBase", "https://dashscope.aliyuncs.com/compatible-mode/v1");
        API_KEY = B.comment("API Key；留空时进入离线模式。千问 AI Token Plan 必须用 sk-sp- 开头的专属 Key，"
                        + "与普通按量计费的 sk- Key 不能混用")
                .define("apiKey", "");
        API_PRESET = B.comment("接入方式：auto = 按 apiBase 自动识别 / tokenplan = 千问 AI Token Plan / "
                        + "standard = 普通按量或自建兼容端点")
                .define("apiPreset", "auto");
        MODEL = B.comment("主模型 ID。已知：" + MODEL_DEEPSEEK + "、" + MODEL_QWEN + "（Qwen3.8-Max 快照）、"
                        + MODEL_QWEN_LATEST + "、" + MODEL_QWEN_FLASH
                        + "，都支持文本 + 图片 + tools；Token Plan 还可填 auto 由服务端选模型。"
                        + "其它 ID 也能填，能力用下面三项指定")
                .define("model", DEFAULT_MODEL);
        MODEL_VISION = B.comment("主模型是否支持图片输入：auto / yes / no（已知模型都支持，默认 yes）。"
                        + "设为 no 时 Agent 的拍照结果只回传文本与本地渲染统计，不发送 image_url")
                .define("modelVision", "auto");
        MODEL_TOOLS = B.comment("主模型是否支持 tools / function calling：auto / yes / no。"
                        + "no 时自动回退到旧的固定阶段链路")
                .define("modelTools", "auto");
        MODEL_THINKING = B.comment("纯文本请求的思考参数：auto = 按模型名自动识别 / off / "
                        + "enable_thinking（只发该字段）/ full（再加 reasoning_effort）")
                .define("modelThinking", "auto");
        TIMEOUT_SECONDS = B.comment("纯文本请求超时（秒）：Plan 对话 / 语义层 / 旧回退链路")
                .defineInRange("timeoutSeconds", 300, 10, 3600);
        AGENT_TIMEOUT_SECONDS = B.comment("Agent 工具轮超时（秒）：深度思考可能数万字，"
                        + "超时太短会在 tool_calls 到达前截断响应，导致误判为「没有工具调用」")
                .defineInRange("agentTimeoutSeconds", 900, 10, 3600);
        MAX_RETRIES = B.defineInRange("maxRetries", 2, 0, 5);
        TEMPERATURE = B.defineInRange("temperature", 0.5, 0.0, 2.0);
        // 键名刻意不同于旧版的 maxOutputTokens：旧配置文件里残留的 1800 / 256 会被当成无关键忽略，
        // 不会在升级后继续把输出截断。
        // 输出永不设限：默认不带 max_tokens。这个字段只为「强制要求该字段」的服务保留兼容能力，
        // 任何正整数都会限制模型输出，正常情况一律保持 0。
        OUTPUT_TOKEN_LIMIT = B.comment("输出兼容字段；0 = 完全不设限（不发送 max_tokens，默认）。"
                        + "仅当接口强制要求该字段时才填正整数")
                .defineInRange("outputTokenLimit", 0, 0, 10000000);
        MAX_INPUT_TOKENS = B.comment("输入上下文预算上限（token）。默认 500000 = 500K；"
                        + "超出时按预算丢掉最早的历史消息，不会截断用户当前指令")
                .defineInRange("maxInputTokens", 500000, 1000, 10000000);
        // 深度思考强制开启：请求里始终带 enable_thinking=true，不再提供关闭开关。
        // 键名刻意不复用旧版 disableThinking，避免旧配置残留把思考关掉。
        REASONING_EFFORT = B.comment("纯文本链路的思考强度 high / medium / low（多模态请求不发送）。"
                        + "若接口不认识 reasoning_effort 字段，设为空字符串即可不发送该字段（enable_thinking 仍为 true）")
                .define("reasoningEffort", "high");
        B.pop();

        B.comment("建造执行与限制").push("build");
        BLOCKS_PER_TICK = B.defineInRange("blocksPerTick", 512, 16, 8192);
        MAX_BLOCKS_PER_TASK = B.defineInRange("maxBlocksPerTask", 120000, 1000, 1000000);
        MAX_VOLUME = B.comment("选区体积软上限；默认 10 亿格（等同于不限制），只想防误选时才调小")
                .defineInRange("maxSelectionVolume", 1000000000L, 1L, 2147483647L);
        ECHO_TO_CHAT = B.comment("把 Agent 的过程输出同步发到玩家聊天栏")
                .define("echoToChat", false);
        SAVE_PLACEMENTS = B.comment("保存每个版本的完整方块图（gzip），用于修改时做差量对比与回滚")
                .define("savePlacements", true);
        COMPONENTS_ENABLED = B.comment("启用本地构件注册表：自动补门/床/楼梯等方块的朝向与多格结构")
                .define("componentsEnabled", true);
        AUTO_SUPPORT = B.comment("门/床等构件下方为空时自动补一格支撑方块")
                .define("autoSupport", true);
        SEMANTIC_ENABLED = B.comment("整栋建造完成后调用 1 次模型生成语义场景图（房间用途/关系/设计意图）")
                .define("semanticEnabled", true);
        SEMANTIC_CORRECTION = B.comment("互校发现差异时最多再触发 1 次修正型模型调用（默认关闭以省 token）")
                .define("semanticCorrection", false);
        B.pop();

        B.comment("Agent：模型通过 tool calling 自主决定建造，本地只编译并执行体素").push("agent");
        AGENT_TOOLS_ENABLED = B.comment("启用真正的工具调用 Agent；关闭则回退到旧的固定阶段链路")
                .define("agentToolsEnabled", true);
        AGENT_THINKING = B.comment("工具调用轮是否发送思考参数：开启后模型返回 reasoning_content，"
                        + "深度思考才能写进 Agent 输出；端点不接受时设为 false")
                .define("agentThinking", true);
        AGENT_STREAM = B.comment("Agent 轮使用 SSE 流式返回，把深度思考逐段实时打印到 Agent 输出；"
                        + "端点不支持流式时自动回退到非流式（配置设 false 可强制关闭）")
                .define("agentStream", true);
        TOOLS_SCHEMA_MODE = B.comment("tools 的 JSON Schema：compat = 去掉 enum / minItems / maxItems 等约束"
                        + "（兼容 Qwen / Token Plan，可选值仍写进 description）/ full = 完整 schema")
                .define("toolsSchemaMode", "compat");
        TOOL_CHOICE = B.comment("工具选择：auto = 发送 tool_choice=auto / none = 不发送该字段")
                .define("toolChoice", "auto");
        AGENT_MAX_ROUNDS = B.comment("一次建造最多多少轮工具调用，防止模型停不下来")
                .defineInRange("agentMaxRounds", 40, 1, 200);
        AGENT_MAX_CELLS_PER_CALL = B.comment("单次 draw_shape 最多枚举多少个候选体素，防止卡服")
                .defineInRange("agentMaxCellsPerCall", 200000, 1000, 2000000);
        AGENT_MAX_BLOCKS_PER_CALL = B.comment("单次工具调用最多写入多少格方块")
                .defineInRange("agentMaxBlocksPerCall", 60000, 100, 500000);
        AGENT_MAX_READ_BLOCKS = B.comment("read_state 单次最多返回多少个体素（超出会返回分层摘要）")
                .defineInRange("agentMaxReadBlocks", 20000, 100, 200000);
        B.pop();

        B.comment("视觉检查：建筑周围的独立虚拟相机，离屏渲染高分辨率照片交给同一个模型看").push("visual");
        VISUAL_ENABLED = B.comment("启用视觉检查（拍照 + 多模态审查）")
                .define("visualEnabled", true);
        VISION_MODEL = B.comment("视觉模型名；留空复用 llm.model。已知模型都支持图片；"
                        + "如需让独立视觉审查换用其它模型可在这里单独指定")
                .define("visionModel", "");
        VISUAL_WIDTH = B.comment("每张照片宽度（像素）。高清才能看清门窗、砖缝、栏杆等建筑细节")
                .defineInRange("visualWidth", 1024, 64, 2048);
        VISUAL_HEIGHT = B.comment("每张照片高度（像素）。建议与宽度保持 16:10")
                .defineInRange("visualHeight", 640, 64, 2048);
        VISION_DETAIL = B.comment("image_url 的 detail：high 让模型按高分辨率分块读图；"
                        + "端点不认识该字段时设为空字符串即可不发送")
                .define("visionDetail", "high");
        VISION_TIMEOUT_SECONDS = B.comment("多模态请求超时（秒）：高清多图比纯文本慢，单独放宽避免被文本超时截断")
                .defineInRange("visionTimeoutSeconds", 600, 10, 3600);
        VISUAL_VIEWS = B.comment("建筑周围的环绕相机数量（另加俯视与人眼高度各 1 台）")
                .defineInRange("visualViews", 8, 4, 16);
        VISION_MAX_EXTRA_VIEWS = B.comment("AI 自由查看时最多追加几个自定义视角")
                .defineInRange("visionMaxExtraViews", 4, 0, 12);
        VISION_MAX_IMAGES = B.comment("单次多模态请求最多携带几张照片，避免一次塞入过多高清图")
                .defineInRange("visionMaxImages", 8, 1, 16);
        VISION_INSPECTION_IMAGES = B.comment("模型请求的中途视觉检查每次拍几张高清照片"
                        + "（环绕主视角 + 俯视 + 人眼高度优先，更多再补对侧环绕）")
                .defineInRange("visionInspectionImages", 3, 1, 8);
        VISION_MAX_STAGE_INSPECTIONS = B.comment("一次建造里最多允许模型自主请求几次中途检查；"
                        + "0 = 关闭中途检查，只做完成后的强制检查")
                .defineInRange("visionMaxStageInspections", 2, 0, 8);
        CAMERA_MARKERS = B.comment("在建筑周围放置可见名称的隐形相机标记实体，方便玩家看到相机位置")
                .define("cameraMarkers", true);
        VISUAL_FINAL_INSPECTION = B.comment("建造完成后强制拍一组高清照片并交给模型审查一遍；"
                        + "只要视觉检查启用，这一步必做，不由模型决定")
                .define("visualFinalInspection", true);
        B.pop();

        B.comment("MCP：外部 Agent 通过 tool calling 直连建造，不轮询文件、不调用模型 API").push("mcp");
        MCP_ENABLED = B.comment("启用游戏内 MCP 服务端（streamable HTTP，端点 /mcp）")
                .define("mcpEnabled", true);
        MCP_HOST = B.comment("监听地址；建议保持 127.0.0.1，只对本机 Agent 开放")
                .define("mcpHost", "127.0.0.1");
        MCP_PORT = B.comment("监听端口，外部 Agent 连 http://<host>:<port>/mcp")
                .defineInRange("mcpPort", 8765, 1024, 65535);
        MCP_TOKEN = B.comment("访问 token；留空不校验（仅本机可用），填了则客户端需带 Authorization: Bearer <token>")
                .define("mcpToken", "");
        MCP_BOUNDS_TIMEOUT_SECONDS = B.comment("坐标授权等待玩家 /builder approve 的秒数，超时自动拒绝")
                .defineInRange("mcpBoundsTimeoutSeconds", 300, 10, 3600);
        MCP_BOUNDS_WAIT_SECONDS = B.comment("request_bounds 同步等待玩家批准的秒数；超过就返回 status=pending"
                        + "（请求仍然有效，玩家批准后 get_context 能看到新范围），避免 MCP 客户端自己超时")
                .defineInRange("mcpBoundsWaitSeconds", 45, 5, 600);
        MCP_TOOL_TIMEOUT_SECONDS = B.comment("单个 MCP 工具调用最长等待秒数（finish_build 要等世界写完）")
                .defineInRange("mcpToolTimeoutSeconds", 120, 10, 1800);
        B.pop();

        SPEC = B.build();
    }

    private BuilderConfig() {
    }

    public static String apiBase() {
        String value = API_BASE.get();
        String text = value == null ? "" : value.trim();
        // 简写：省得手抄一长串专属端点，也避免把 /chat/completions 误填进来
        if (text.equalsIgnoreCase("tokenplan") || text.equalsIgnoreCase("token-plan")) {
            return TOKEN_PLAN_BASE_URL;
        }
        if (text.equalsIgnoreCase("tokenplan-intl") || text.equalsIgnoreCase("token-plan-intl")) {
            return TOKEN_PLAN_INTL_BASE_URL;
        }
        return text.replaceAll("/+$", "");
    }

    private static String apiPreset() {
        String value = API_PRESET.get();
        return value == null ? "auto" : value.trim().toLowerCase(Locale.ROOT);
    }

    /** 是否走千问 AI Token Plan：显式 tokenplan / standard 优先，auto 按端点域名判断。 */
    public static boolean usingTokenPlan() {
        String preset = apiPreset();
        if ("tokenplan".equals(preset) || "token-plan".equals(preset)) {
            return true;
        }
        if ("standard".equals(preset)) {
            return false;
        }
        return apiBase().toLowerCase(Locale.ROOT).contains("token-plan.");
    }

    public static String apiKey() {
        String value = API_KEY.get();
        return value == null ? "" : value.trim();
    }

    public static String model() {
        String value = MODEL.get();
        return value == null || value.isBlank() ? DEFAULT_MODEL : value.trim();
    }

    /** 视觉模型；留空时复用主模型。需要让独立视觉审查换用其它模型时在这里指定。 */
    public static String visionModel() {
        String value = VISION_MODEL.get();
        return value == null || value.isBlank() ? model() : value.trim();
    }

    /** 按模型名归类已知模型；未收录的模型走保守预设，可用配置显式覆盖。 */
    private static String modelFamily(String model) {
        String name = model == null ? "" : model.toLowerCase(Locale.ROOT);
        // Qwen3.8-Max 是原生多模态；Token Plan 的 model=auto 也由千问系列承接
        if (name.contains("qwen") || "auto".equals(name)) {
            return "qwen";
        }
        if (name.contains("deepseek")) {
            return "deepseek";
        }
        // Token Plan 端点只提供千问系列，未收录的 ID 也按 Qwen3 的参数习惯处理
        return usingTokenPlan() ? "qwen" : "unknown";
    }

    /** 三态配置：auto / 未填写时回落到按模型名推断的预设值。 */
    private static boolean triState(ModConfigSpec.ConfigValue<String> value, boolean preset) {
        String configured = value.get();
        String text = configured == null ? "auto" : configured.trim().toLowerCase(Locale.ROOT);
        return switch (text) {
            case "yes", "true", "on", "1" -> true;
            case "no", "false", "off", "0" -> false;
            default -> preset;
        };
    }

    /** 已知模型（含 Qwen3.8-Max）都支持 image_url 图片输入；仅 modelVision=no 才关闭。 */
    public static boolean supportsVision() {
        return triState(MODEL_VISION, true);
    }

    /** 是否支持 tools / function calling；两个已知模型都支持，未知模型默认也支持。 */
    public static boolean supportsTools() {
        return triState(MODEL_TOOLS, true);
    }

    /** 纯文本请求该发送哪些思考参数。Qwen3 系列只认 enable_thinking，不认 reasoning_effort。 */
    public static Thinking thinkingFor(String model) {
        String configured = MODEL_THINKING.get();
        String text = configured == null ? "auto" : configured.trim().toLowerCase(Locale.ROOT);
        return switch (text) {
            case "off", "no", "false" -> Thinking.OFF;
            case "enable_thinking" -> Thinking.ENABLE_THINKING;
            case "full" -> Thinking.FULL;
            default -> "qwen".equals(modelFamily(model))
                    ? Thinking.ENABLE_THINKING : Thinking.FULL;
        };
    }

    /** image_url.detail；空字符串表示不发送该字段。 */
    public static String visionDetail() {
        String value = VISION_DETAIL.get();
        return value == null ? "" : value.trim();
    }

    public static int visionTimeoutSeconds() {
        return VISION_TIMEOUT_SECONDS.getAsInt();
    }

    public static int agentTimeoutSeconds() {
        return AGENT_TIMEOUT_SECONDS.getAsInt();
    }

    public static int visionMaxImages() {
        return VISION_MAX_IMAGES.getAsInt();
    }

    public static int visionInspectionImages() {
        return VISION_INSPECTION_IMAGES.getAsInt();
    }

    public static int visionMaxStageInspections() {
        return VISION_MAX_STAGE_INSPECTIONS.getAsInt();
    }

    public static boolean visualFinalInspection() {
        return VISUAL_FINAL_INSPECTION.getAsBoolean();
    }

    public static boolean agentToolsEnabled() {
        return AGENT_TOOLS_ENABLED.getAsBoolean();
    }

    public static boolean agentThinking() {
        return AGENT_THINKING.getAsBoolean();
    }

    public static boolean agentStream() {
        return AGENT_STREAM.getAsBoolean();
    }

    public static boolean mcpEnabled() {
        return MCP_ENABLED.getAsBoolean();
    }

    public static String mcpHost() {
        String value = MCP_HOST.get();
        return value == null || value.isBlank() ? "127.0.0.1" : value.trim();
    }

    public static int mcpPort() {
        return MCP_PORT.getAsInt();
    }

    public static String mcpToken() {
        String value = MCP_TOKEN.get();
        return value == null ? "" : value.trim();
    }

    public static int mcpBoundsTimeoutSeconds() {
        return MCP_BOUNDS_TIMEOUT_SECONDS.getAsInt();
    }

    public static int mcpBoundsWaitSeconds() {
        return MCP_BOUNDS_WAIT_SECONDS.getAsInt();
    }

    public static int mcpToolTimeoutSeconds() {
        return MCP_TOOL_TIMEOUT_SECONDS.getAsInt();
    }

    /** 是否生成精简版 tools schema（默认 true）。 */
    public static boolean toolsSchemaCompat() {
        String value = TOOLS_SCHEMA_MODE.get();
        return !"full".equalsIgnoreCase(value == null ? "" : value.trim());
    }

    /** 是否发送 tool_choice=auto（默认 true；端点拒收时设 toolChoice=none）。 */
    public static boolean sendToolChoice() {
        String value = TOOL_CHOICE.get();
        return !"none".equalsIgnoreCase(value == null ? "" : value.trim());
    }

    public static int agentMaxRounds() {
        return AGENT_MAX_ROUNDS.getAsInt();
    }

    public static int agentMaxCellsPerCall() {
        return AGENT_MAX_CELLS_PER_CALL.getAsInt();
    }

    public static int agentMaxBlocksPerCall() {
        return AGENT_MAX_BLOCKS_PER_CALL.getAsInt();
    }

    public static int agentMaxReadBlocks() {
        return AGENT_MAX_READ_BLOCKS.getAsInt();
    }

    /** 返回 0 表示不限制输出长度。 */
    public static int outputTokenLimit() {
        return OUTPUT_TOKEN_LIMIT.getAsInt();
    }

    public static int maxInputTokens() {
        return MAX_INPUT_TOKENS.getAsInt();
    }

    /** 空字符串表示不发送 reasoning_effort（enable_thinking 仍强制为 true）。 */
    public static String reasoningEffort() {
        String value = REASONING_EFFORT.get();
        return value == null ? "" : value.trim();
    }
}
