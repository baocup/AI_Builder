package baocup.builder.agent;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Stream;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import baocup.builder.BuilderConfig;
import baocup.builder.BuilderMod;
import baocup.builder.util.Json;

/**
 * OpenAI /chat/completions 兼容客户端。所有网络调用都在独立线程池里执行，
 * 服务端主线程只轮询 CompletableFuture，绝不阻塞 tick。
 */
public final class LlmClient {
    public record Result(boolean ok, String content, int promptTokens, int completionTokens,
                         long elapsedMs, String error, String reasoning) {
        public static Result fail(String error) {
            return new Result(false, "", 0, 0, 0L, error, "");
        }
    }

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10)).build();
    private final ExecutorService pool = Executors.newFixedThreadPool(2, runnable -> {
        Thread t = new Thread(runnable, "builder-agent-llm");
        t.setDaemon(true);
        return t;
    });

    private volatile boolean accessModeLogged;

    public boolean isConfigured() {
        boolean configured = !BuilderConfig.apiKey().isBlank() && !BuilderConfig.apiBase().isBlank();
        if (configured) {
            logAccessMode();
        }
        return configured;
    }

    /** 只记一次：Token Plan 专属 Key 与普通按量 Key 混用会走按量计费，必须给出明确提示。 */
    private void logAccessMode() {
        if (accessModeLogged) {
            return;
        }
        accessModeLogged = true;
        String key = BuilderConfig.apiKey();
        if (!BuilderConfig.usingTokenPlan()) {
            BuilderMod.LOGGER.info("[建造] LLM 接入：标准 OpenAI 兼容端点 {}，模型 {}",
                    BuilderConfig.apiBase(), BuilderConfig.model());
            return;
        }
        if (!key.startsWith(BuilderConfig.TOKEN_PLAN_KEY_PREFIX)) {
            BuilderMod.LOGGER.warn("[建造] 当前是千问 AI Token Plan 端点，但 API Key 不是 {} 前缀；"
                            + "专属 Key 与普通按量 Key 不能混用，否则会走按量计费或被拒绝",
                    BuilderConfig.TOKEN_PLAN_KEY_PREFIX);
        }
        BuilderMod.LOGGER.info("[建造] LLM 接入：千问 AI Token Plan，端点 {}，模型 {}，Key 前缀 {}…",
                BuilderConfig.apiBase(), BuilderConfig.model(),
                key.substring(0, Math.min(6, key.length())));
    }

    public CompletableFuture<Result> chat(String system, String user) {
        if (!isConfigured()) {
            return CompletableFuture.completedFuture(Result.fail("未配置 API Key，进入离线模板模式"));
        }
        return CompletableFuture.supplyAsync(() -> call(system, user), pool);
    }

    public CompletableFuture<Result> chat(List<Message> messages) {
        if (!isConfigured()) {
            return CompletableFuture.completedFuture(Result.fail("未配置 API Key，进入离线模板模式"));
        }
        return CompletableFuture.supplyAsync(() -> call(messages), pool);
    }

    public record Message(String role, String content) {
    }

    private Result call(String system, String user) {
        List<Message> messages = new ArrayList<>();
        messages.add(new Message("system", system));
        messages.add(new Message("user", user));
        return call(messages);
    }

    private Result call(List<Message> messages) {
        return send(buildBody(trimToInputBudget(messages)));
    }

    /**
     * 多模态调用：照片以 data URL 放进 content 数组，模型取 visionModel（留空复用主模型）。
     * 需要让视觉审查换用其它模型时可单独指定 visionModel；留空则与主模型一致。
     */
    public CompletableFuture<Result> chatWithImages(String system, String user, List<byte[]> images) {
        if (!isConfigured()) {
            return CompletableFuture.completedFuture(Result.fail("未配置 API Key，跳过视觉调用"));
        }
        if (images == null || images.isEmpty()) {
            return CompletableFuture.completedFuture(Result.fail("没有可用照片"));
        }
        String visionModel = BuilderConfig.visionModel();
        if (!BuilderConfig.supportsVision()) {
            return CompletableFuture.completedFuture(Result.fail("图片能力已关闭（modelVision=no）："
                    + "视觉模型 " + visionModel + " 收不到 image_url；把 modelVision 设为 yes 即可恢复看图"));
        }
        return CompletableFuture.supplyAsync(() -> send(buildVisionBody(system, user, images),
                BuilderConfig.visionTimeoutSeconds()), pool);
    }

    private Result send(String body) {
        return send(body, BuilderConfig.TIMEOUT_SECONDS.getAsInt());
    }

    private Result send(String body, int timeoutSeconds) {
        int attempts = Math.max(1, BuilderConfig.MAX_RETRIES.getAsInt() + 1);
        String lastError = "未知错误";
        long started = System.currentTimeMillis();
        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                HttpRequest request = HttpRequest.newBuilder(URI.create(BuilderConfig.apiBase() + "/chat/completions"))
                        .timeout(Duration.ofSeconds(timeoutSeconds))
                        .header("Content-Type", "application/json; charset=utf-8")
                        .header("Authorization", "Bearer " + BuilderConfig.apiKey())
                        .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                        .build();
                HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                int status = response.statusCode();
                if (status < 200 || status >= 300) {
                    lastError = "HTTP " + status + " " + asText(response.body());
                } else {
                    Result parsed = parse(response.body(), System.currentTimeMillis() - started);
                    if (parsed.ok()) {
                        return parsed;
                    }
                    lastError = parsed.error();
                }
            } catch (Exception e) {
                lastError = e.getClass().getSimpleName() + ": " + asText(String.valueOf(e.getMessage()));
            }
            if (attempt < attempts) {
                try {
                    Thread.sleep(400L * attempt);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        BuilderMod.LOGGER.warn("[建造] 模型调用失败（{} 次尝试）：{}", attempts, lastError);
        return Result.fail(lastError);
    }

    /** minimal=true 表示只发最小兼容字段集（带图 / 带 tools 的请求），不发思考参数。 */
    private static JsonObject baseBody(String model, boolean minimal) {
        JsonObject root = new JsonObject();
        root.addProperty("model", model);
        root.addProperty("temperature", BuilderConfig.TEMPERATURE.getAsDouble());
        // 默认不发送 max_tokens：输出长度完全不设限，由模型 / 服务端自行决定
        int outputLimit = BuilderConfig.outputTokenLimit();
        if (outputLimit > 0) {
            root.addProperty("max_tokens", outputLimit);
        }
        root.addProperty("stream", false);
        if (!minimal) {
            // 思考参数按模型能力档位发送：Qwen3 只认 enable_thinking，
            // DeepSeek 兼容端点还能带 reasoning_effort；off 时一个都不发。
            BuilderConfig.Thinking thinking = BuilderConfig.thinkingFor(model);
            if (thinking != BuilderConfig.Thinking.OFF) {
                root.addProperty("enable_thinking", true);
                if (thinking == BuilderConfig.Thinking.FULL) {
                    String effort = BuilderConfig.reasoningEffort();
                    if (!effort.isBlank()) {
                        root.addProperty("reasoning_effort", effort);
                    }
                }
            }
        }
        return root;
    }

    private static String buildBody(List<Message> messages) {
        JsonObject root = baseBody(BuilderConfig.model(), false);
        JsonArray array = new JsonArray();
        for (Message message : messages) {
            JsonObject item = new JsonObject();
            item.addProperty("role", message.role());
            item.addProperty("content", message.content());
            array.add(item);
        }
        root.add("messages", array);
        return Json.GSON.toJson(root);
    }

    private static final long MAX_IMAGE_BYTES = 10L * 1024 * 1024;

    private static String buildVisionBody(String system, String user, List<byte[]> images) {
        JsonObject root = baseBody(BuilderConfig.visionModel(), true);
        JsonArray messages = new JsonArray();
        JsonObject sys = new JsonObject();
        sys.addProperty("role", "system");
        sys.addProperty("content", system);
        messages.add(sys);

        JsonObject usr = new JsonObject();
        usr.addProperty("role", "user");
        JsonArray content = new JsonArray();
        JsonObject text = new JsonObject();
        text.addProperty("type", "text");
        text.addProperty("text", user);
        content.add(text);

        int maxImages = BuilderConfig.visionMaxImages();
        String detail = BuilderConfig.visionDetail();
        long totalBytes = 0;
        int used = 0;
        for (byte[] png : images) {
            if (png == null || png.length == 0 || used >= maxImages) {
                continue;
            }
            if (png.length > MAX_IMAGE_BYTES) {
                BuilderMod.LOGGER.warn("[建造] 单张照片 {} 字节超过上限，已跳过", png.length);
                continue;
            }
            JsonObject image = new JsonObject();
            image.addProperty("type", "image_url");
            JsonObject url = new JsonObject();
            url.addProperty("url", "data:image/png;base64,"
                    + java.util.Base64.getEncoder().encodeToString(png));
            // high 让模型按高分辨率分块读图，而不是缩略到低分辨率；看建筑细节必须开
            if (!detail.isBlank()) {
                url.addProperty("detail", detail);
            }
            image.add("image_url", url);
            content.add(image);
            totalBytes += png.length;
            used++;
        }
        BuilderMod.LOGGER.info("[建造] 多模态请求：模型 {}，{} 张高清照片，原始 PNG 共 {} KB，detail={}",
                BuilderConfig.visionModel(), used, totalBytes / 1024,
                detail.isBlank() ? "（不发送）" : detail);
        usr.add("content", content);
        messages.add(usr);
        root.add("messages", messages);
        return Json.GSON.toJson(root);
    }

    private static final int MESSAGE_OVERHEAD = 4;

    /**
     * 输入预算保护：默认允许 500K token，超出时优先丢最早的历史消息。
     * system 提示词与最后一条用户指令始终保留；只有极端情况才压缩 system 的尾部。
     */
    private static List<Message> trimToInputBudget(List<Message> messages) {
        int budget = BuilderConfig.maxInputTokens();
        int total = 0;
        for (Message message : messages) {
            total += estimateTokens(message.content()) + MESSAGE_OVERHEAD;
        }
        if (total <= budget) {
            return messages;
        }
        int before = total;
        List<Message> out = new ArrayList<>(messages);
        int dropped = 0;
        // 索引 0 是 system，最后一条是当前用户指令，从 1 开始丢最早的历史
        while (out.size() > 2 && total > budget) {
            Message removed = out.remove(1);
            total -= estimateTokens(removed.content()) + MESSAGE_OVERHEAD;
            dropped++;
        }
        if (total > budget && !out.isEmpty()) {
            int other = 0;
            for (int i = 1; i < out.size(); i++) {
                other += estimateTokens(out.get(i).content()) + MESSAGE_OVERHEAD;
            }
            int allowance = Math.max(2000, budget - other - MESSAGE_OVERHEAD);
            Message system = out.get(0);
            String clipped = clipToTokens(system.content(), allowance);
            out.set(0, new Message(system.role(), clipped));
            total = other + estimateTokens(clipped) + MESSAGE_OVERHEAD;
        }
        BuilderMod.LOGGER.info("[建造] 输入按 {} token 预算裁剪：丢弃 {} 条历史消息，估算 {} -> {} token",
                budget, dropped, before, total);
        return out;
    }

    /** 粗估 token：CJK 每字约 1 token，ASCII 约 4 字符 1 token。仅用于预算保护，不用于计费。 */
    private static int estimateTokens(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int tokens = 0;
        int asciiRun = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) < 0x80) {
                asciiRun++;
                if (asciiRun == 4) {
                    tokens++;
                    asciiRun = 0;
                }
            } else {
                tokens++;
            }
        }
        return tokens + (asciiRun > 0 ? 1 : 0);
    }

    private static String clipToTokens(String text, int limit) {
        if (text == null) {
            return "";
        }
        if (estimateTokens(text) <= limit) {
            return text;
        }
        int used = 0;
        int asciiRun = 0;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < text.length() && used < limit; i++) {
            char c = text.charAt(i);
            sb.append(c);
            if (c < 0x80) {
                asciiRun++;
                if (asciiRun == 4) {
                    used++;
                    asciiRun = 0;
                }
            } else {
                used++;
            }
        }
        return sb.append("\n（输入预算已裁剪提示词尾部）").toString();
    }

    private static Result parse(String raw, long elapsed) {
        JsonObject root;
        try {
            root = Json.GSON.fromJson(raw, JsonObject.class);
        } catch (Exception e) {
            return Result.fail("响应不是合法 JSON：" + asText(raw));
        }
        if (root == null) {
            return Result.fail("响应为空");
        }
        if (root.has("error")) {
            return Result.fail("接口返回错误：" + asText(root.get("error").toString()));
        }
        JsonArray choices = root.getAsJsonArray("choices");
        if (choices == null || choices.isEmpty()) {
            return Result.fail("响应缺少 choices");
        }
        JsonObject first = choices.get(0).getAsJsonObject();
        JsonObject message = Json.child(first, "message");
        String content = message == null ? Json.str(first, "text", "") : Json.str(message, "content", "");
        // 深度思考开启后，思考链在 reasoning_content，正文在 content；绝不把思考链当方案来解析。
        String reasoning = message == null ? "" : Json.str(message, "reasoning_content", "");
        if (content.isBlank()) {
            return new Result(false, "", 0, 0, elapsed,
                    "模型未返回正文" + (reasoning.isBlank() ? "" : "（仅返回 " + reasoning.length() + " 字思考内容）"),
                    reasoning);
        }
        JsonObject usage = Json.child(root, "usage");
        int prompt = usage == null ? 0 : Json.integer(usage, "prompt_tokens", 0);
        int completion = usage == null ? 0 : Json.integer(usage, "completion_tokens", 0);
        if (usage == null) {
            prompt = content.length() / 2;
            completion = (content.length() + reasoning.length()) / 2;
        }
        return new Result(true, content, prompt, completion, elapsed, "", reasoning);
    }

    /** 错误诊断也保留完整原文，不做任何裁剪或折叠。 */
    private static String asText(String text) {
        return text == null ? "" : text;
    }

    /** 从模型输出里稳健地抠出第一个 JSON 对象（容忍 markdown 代码块与前后解释）。 */
    public static String extractJson(String text) {
        if (text == null) {
            return null;
        }
        String cleaned = text.trim();
        int fence = cleaned.indexOf("```");
        if (fence >= 0) {
            int endFence = cleaned.indexOf("```", fence + 3);
            if (endFence > fence) {
                String inside = cleaned.substring(fence + 3, endFence).trim();
                if (inside.toLowerCase().startsWith("json")) {
                    inside = inside.substring(4).trim();
                }
                cleaned = inside;
            }
        }
        int start = cleaned.indexOf('{');
        int end = cleaned.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return null;
        }
        return cleaned.substring(start, end + 1).replaceAll(",\\s*([}\\]])", "$1");
    }

    // ------------------------------------------------------------------
    // 真正的 tool calling：tools 声明 / tool_calls 解析 / role=tool 回执
    // ------------------------------------------------------------------

    public record ToolCall(String id, String name, String arguments) {
    }

    public record AgentReply(boolean ok, String content, String reasoning,
                             List<ToolCall> toolCalls, int promptTokens, int completionTokens,
                             long elapsedMs, String error) {
        public static AgentReply fail(String error) {
            return new AgentReply(false, "", "", List.of(), 0, 0, 0L, error);
        }
    }

    /** 一条 Agent 消息：system / user（可带图）/ assistant（可带 tool_calls）/ tool 回执。 */
    public static final class AgentMessage {
        private final JsonObject json;

        private AgentMessage(JsonObject json) {
            this.json = json;
        }

        public static AgentMessage system(String text) {
            JsonObject o = new JsonObject();
            o.addProperty("role", "system");
            o.addProperty("content", text == null ? "" : text);
            return new AgentMessage(o);
        }

        public static AgentMessage userText(String text) {
            JsonObject o = new JsonObject();
            o.addProperty("role", "user");
            o.addProperty("content", text == null ? "" : text);
            return new AgentMessage(o);
        }

        /** 图片不能塞进 role=tool 的文本回执，按兼容惯例另起一条 user 多模态消息。 */
        public static AgentMessage userWithImages(String text, List<byte[]> pngs) {
            JsonObject o = new JsonObject();
            o.addProperty("role", "user");
            JsonArray content = new JsonArray();
            JsonObject caption = new JsonObject();
            caption.addProperty("type", "text");
            caption.addProperty("text", text == null ? "" : text);
            content.add(caption);
            String detail = BuilderConfig.visionDetail();
            int used = 0;
            for (byte[] png : pngs) {
                if (png == null || png.length == 0 || png.length > MAX_IMAGE_BYTES
                        || used >= BuilderConfig.visionMaxImages()) {
                    continue;
                }
                JsonObject image = new JsonObject();
                image.addProperty("type", "image_url");
                JsonObject url = new JsonObject();
                url.addProperty("url", "data:image/png;base64,"
                        + java.util.Base64.getEncoder().encodeToString(png));
                if (!detail.isBlank()) {
                    url.addProperty("detail", detail);
                }
                image.add("image_url", url);
                content.add(image);
                used++;
            }
            o.add("content", content);
            return new AgentMessage(o);
        }

        public static AgentMessage assistant(String content, String reasoning, List<ToolCall> calls) {
            JsonObject o = new JsonObject();
            o.addProperty("role", "assistant");
            o.addProperty("content", content == null ? "" : content);
            if (reasoning != null && !reasoning.isBlank()) {
                o.addProperty("reasoning_content", reasoning);
            }
            if (calls != null && !calls.isEmpty()) {
                JsonArray array = new JsonArray();
                for (ToolCall call : calls) {
                    JsonObject item = new JsonObject();
                    item.addProperty("id", call.id());
                    item.addProperty("type", "function");
                    JsonObject function = new JsonObject();
                    function.addProperty("name", call.name());
                    function.addProperty("arguments",
                            call.arguments() == null ? "{}" : call.arguments());
                    item.add("function", function);
                    array.add(item);
                }
                o.add("tool_calls", array);
            }
            return new AgentMessage(o);
        }

        public static AgentMessage tool(String callId, String result) {
            JsonObject o = new JsonObject();
            o.addProperty("role", "tool");
            o.addProperty("tool_call_id", callId == null ? "" : callId);
            o.addProperty("content", result == null ? "" : result);
            return new AgentMessage(o);
        }

        public boolean hasImages() {
            return json.has("content") && json.get("content").isJsonArray();
        }

        /** 图片能力被关闭时，把多模态消息降级成纯文本，避免整轮 tool_calls 被端点拒收。 */
        public AgentMessage withoutImages() {
            if (!hasImages()) {
                return this;
            }
            JsonArray parts = json.getAsJsonArray("content");
            String caption = "";
            if (parts.size() > 0 && parts.get(0).isJsonObject()) {
                caption = Json.str(parts.get(0).getAsJsonObject(), "text", "");
            }
            return AgentMessage.userText(caption
                    + "\n[图片能力已关闭（modelVision=no）：照片已保存到本地磁盘，本条消息不含 image_url。"
                    + "请用 read_state 与 capture 回执里的 localReport / cameras 文本核对建筑状态]");
        }

        JsonObject toJson() {
            return json;
        }
    }

    /**
     * 流式思考片段的线程安全队列：后台 IO 线程只往里放，服务端主线程每 tick 取走写进输出。
     * 后台线程直接碰 BuildMemory 会有并发风险，所以这里只做数据中转。
     */
    public static final class StreamProgress {
        private final ConcurrentLinkedQueue<String> chunks = new ConcurrentLinkedQueue<>();
        private volatile boolean emitted;

        void append(String text) {
            if (text != null && !text.isEmpty()) {
                chunks.add(text);
                emitted = true;
            }
        }

        /** 取走当前累积的所有片段拼成一段；没有新片段时返回空串。 */
        public String drain() {
            StringBuilder sb = new StringBuilder();
            String chunk;
            while ((chunk = chunks.poll()) != null) {
                sb.append(chunk);
            }
            return sb.toString();
        }

        /** 本轮是否真的流式收到过思考内容，用于避免结束后再重复写一遍。 */
        public boolean hasEmitted() {
            return emitted;
        }

        /** 回退时只丢掉还没显示过的片段，保留「已流式输出过」这个事实，避免日志重复写整段。 */
        void clearPending() {
            chunks.clear();
        }

        void reset() {
            clearPending();
            emitted = false;
        }
    }

    /** 一次 Agent 请求：future 给状态机轮询，progress 给主线程逐 tick 取思考片段。 */
    public record AgentRequest(CompletableFuture<AgentReply> future, StreamProgress progress) {
    }

    private static final class ToolCallAcc {
        String id = "";
        String name = "";
        final StringBuilder arguments = new StringBuilder();
    }

    /** 一轮 Agent 请求：带完整消息历史与 tools 声明，返回模型可能给出的 tool_calls。 */
    public AgentRequest agentChat(List<AgentMessage> messages, JsonArray tools) {
        StreamProgress progress = new StreamProgress();
        if (!isConfigured()) {
            return completed(progress, AgentReply.fail("未配置 API Key，Agent 模式不可用"));
        }
        String mainModel = BuilderConfig.model();
        if (!BuilderConfig.supportsTools()) {
            return completed(progress, AgentReply.fail("模型 " + mainModel
                    + " 被标记为不支持 tools / function calling；把 agentToolsEnabled 设为 false 回退旧链路，"
                    + "或把 modelTools 设为 yes"));
        }
        List<AgentMessage> effective = messages;
        boolean multimodal = effective.stream().anyMatch(AgentMessage::hasImages);
        if (multimodal && !BuilderConfig.supportsVision()) {
            // 接入纯文本模型时可用 modelVision=no 关闭图片能力；否则端点会 400 拒收，
            // 整轮 tool_calls 全废。这里把图片消息换成文字说明，照片仍落盘，
            // 模型靠 read_state / localReport 核对。
            List<AgentMessage> textOnly = new ArrayList<>(effective.size());
            for (AgentMessage message : effective) {
                textOnly.add(message.withoutImages());
            }
            effective = textOnly;
            multimodal = false;
        }
        String body = buildAgentBody(effective, tools, multimodal, BuilderConfig.agentStream());
        // 回退用的最简请求：非流式且不带思考参数，端点排斥 stream / thinking 时仍能继续建造
        String fallback = fallbackBody(body);
        CompletableFuture<AgentReply> future = CompletableFuture.supplyAsync(
                () -> sendAgent(body, fallback, BuilderConfig.agentTimeoutSeconds(), progress), pool);
        return new AgentRequest(future, progress);
    }

    private static AgentRequest completed(StreamProgress progress, AgentReply reply) {
        return new AgentRequest(CompletableFuture.completedFuture(reply), progress);
    }

    /** 把流式请求改造成最保守的非流式请求，用于自动回退。 */
    private static String fallbackBody(String body) {
        try {
            JsonObject root = Json.GSON.fromJson(body, JsonObject.class);
            if (root == null) {
                return body;
            }
            root.addProperty("stream", false);
            root.remove("enable_thinking");
            root.remove("reasoning_effort");
            return Json.GSON.toJson(root);
        } catch (Exception e) {
            return body;
        }
    }

    private static String buildAgentBody(List<AgentMessage> messages, JsonArray tools,
                                         boolean multimodal, boolean stream) {
        // 带 tools / 图片时很多端点会拒绝思考参数；agentThinking 打开时才发。
        // Qwen3 档位只发 enable_thinking，不带 reasoning_effort。
        boolean minimal = multimodal || !BuilderConfig.agentThinking();
        JsonObject root = baseBody(BuilderConfig.model(), minimal);
        root.addProperty("stream", stream);
        JsonArray array = new JsonArray();
        for (AgentMessage message : messages) {
            array.add(message.toJson());
        }
        root.add("messages", array);
        root.add("tools", tools);
        if (BuilderConfig.sendToolChoice()) {
            root.addProperty("tool_choice", "auto");
        }
        return Json.GSON.toJson(root);
    }

    /** 优先走 SSE 流式（思考内容实时可见）；端点不支持时自动回退到原来的非流式重试。 */
    private AgentReply sendAgent(String body, String fallback, int timeoutSeconds,
                                 StreamProgress progress) {
        if (BuilderConfig.agentStream()) {
            AgentReply streamed = tryStreaming(body, timeoutSeconds, progress);
            if (streamed != null) {
                if (streamed.ok()) {
                    return streamed;
                }
                BuilderMod.LOGGER.warn("[建造] 流式响应解析失败（{}），回退非流式", streamed.error());
            } else {
                BuilderMod.LOGGER.warn("[建造] 端点不支持流式 Agent 请求，已回退非流式；"
                        + "可把 agentStream 设为 false 关闭尝试");
            }
            // 回退会重新拿到完整 reasoning；只清掉还没显示过的片段，保留已流式输出的事实
            progress.clearPending();
        }
        return sendAgentOnce(fallback, timeoutSeconds);
    }

    private HttpRequest newRequest(String body, int timeoutSeconds) {
        return HttpRequest.newBuilder(URI.create(BuilderConfig.apiBase() + "/chat/completions"))
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .header("Content-Type", "application/json; charset=utf-8")
                .header("Authorization", "Bearer " + BuilderConfig.apiKey())
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
    }

    /** SSE 流式解析：逐块把 reasoning_content 放进 progress，同时累积正文与分片 tool_calls。 */
    private AgentReply tryStreaming(String body, int timeoutSeconds, StreamProgress progress) {
        long started = System.currentTimeMillis();
        try {
            HttpResponse<Stream<String>> response = http.send(
                    newRequest(body, timeoutSeconds), HttpResponse.BodyHandlers.ofLines());
            int status = response.statusCode();
            StringBuilder reasoning = new StringBuilder();
            StringBuilder content = new StringBuilder();
            Map<Integer, ToolCallAcc> calls = new TreeMap<>();
            int promptTokens = 0;
            int completionTokens = 0;
            int chunks = 0;
            boolean done = false;
            boolean timedOut = false;
            try (Stream<String> lines = response.body()) {
                if (status < 200 || status >= 300) {
                    String error = lines.findFirst().orElse("");
                    BuilderMod.LOGGER.warn("[建造] 流式请求 HTTP {}：{}", status, error);
                    return null;
                }
                Iterator<String> iterator = lines.iterator();
                while (iterator.hasNext()) {
                    String line = iterator.next();
                    if (line == null || !line.startsWith("data:")) {
                        continue;
                    }
                    String data = line.substring(5).trim();
                    if ("[DONE]".equals(data)) {
                        done = true;
                        break;
                    }
                    if (data.isEmpty()) {
                        continue;
                    }
                    JsonObject chunk;
                    try {
                        chunk = Json.GSON.fromJson(data, JsonObject.class);
                    } catch (Exception e) {
                        continue;
                    }
                    if (chunk == null) {
                        continue;
                    }
                    chunks++;
                    JsonObject usage = Json.child(chunk, "usage");
                    if (usage != null) {
                        promptTokens = Json.integer(usage, "prompt_tokens", promptTokens);
                        completionTokens = Json.integer(usage, "completion_tokens", completionTokens);
                    }
                    JsonArray choices = chunk.getAsJsonArray("choices");
                    if (choices == null || choices.isEmpty()) {
                        continue;
                    }
                    JsonObject delta = Json.child(choices.get(0).getAsJsonObject(), "delta");
                    if (delta == null) {
                        continue;
                    }
                    String thinking = Json.str(delta, "reasoning_content", "");
                    if (!thinking.isEmpty()) {
                        reasoning.append(thinking);
                        progress.append(thinking);
                    }
                    String text = Json.str(delta, "content", "");
                    if (!text.isEmpty()) {
                        content.append(text);
                    }
                    accumulateToolCalls(delta.getAsJsonArray("tool_calls"), calls);
                    if (System.currentTimeMillis() - started > timeoutSeconds * 1000L) {
                        timedOut = true;
                        break;
                    }
                }
            }
            if (chunks == 0) {
                BuilderMod.LOGGER.warn("[建造] 流式响应没有 SSE 数据块，回退非流式");
                return null;
            }
            if (!done) {
                // 超时截断时 tool_calls 往往还没分片到达；绝不能把它当成「模型没调用工具」，
                // 否则会像之前那样被判为空轮次、两轮后归档成 0 格方块。
                String why = timedOut
                        ? "流式响应超过 " + timeoutSeconds + "s 仍未结束（已收 " + chunks
                                + " 个数据块、思考 " + reasoning.length() + " 字），tool_calls 可能尚未到达"
                        : "流式响应提前结束，未收到 [DONE]（已收 " + chunks + " 个数据块）";
                BuilderMod.LOGGER.warn("[建造] {}；不把不完整响应当成本轮结果，请提高 agentTimeoutSeconds", why);
                return AgentReply.fail(why + "；请提高 agentTimeoutSeconds");
            }
            List<ToolCall> toolCalls = new ArrayList<>();
            for (ToolCallAcc acc : calls.values()) {
                if (acc.name.isBlank()) {
                    continue;
                }
                String id = acc.id.isBlank() ? "call_" + toolCalls.size() : acc.id;
                toolCalls.add(new ToolCall(id, acc.name, acc.arguments.toString()));
            }
            if (reasoning.length() == 0 && content.length() == 0 && toolCalls.isEmpty()) {
                return AgentReply.fail("流式响应没有正文 / 工具调用 / 思考内容");
            }
            return new AgentReply(true, content.toString(), reasoning.toString(), toolCalls,
                    promptTokens, completionTokens, System.currentTimeMillis() - started, "");
        } catch (Exception e) {
            BuilderMod.LOGGER.warn("[建造] 流式读取异常，回退非流式：{}", e.toString());
            return null;
        }
    }

    /** 流式 tool_calls 是分片发的，按 index 把 id / name / arguments 拼回完整调用。 */
    private static void accumulateToolCalls(JsonArray deltas, Map<Integer, ToolCallAcc> calls) {
        if (deltas == null) {
            return;
        }
        for (JsonElement element : deltas) {
            try {
                JsonObject item = element.getAsJsonObject();
                int index = Json.integer(item, "index", 0);
                ToolCallAcc acc = calls.computeIfAbsent(index, k -> new ToolCallAcc());
                String id = Json.str(item, "id", "");
                if (!id.isBlank()) {
                    acc.id = id;
                }
                JsonObject function = Json.child(item, "function");
                if (function != null) {
                    String name = Json.str(function, "name", "");
                    if (!name.isBlank()) {
                        acc.name = name;
                    }
                    String arguments = Json.str(function, "arguments", "");
                    if (!arguments.isEmpty()) {
                        acc.arguments.append(arguments);
                    }
                }
            } catch (Exception ignored) {
                // 跳过坏分片
            }
        }
    }

    private AgentReply sendAgentOnce(String body, int timeoutSeconds) {
        int attempts = Math.max(1, BuilderConfig.MAX_RETRIES.getAsInt() + 1);
        String lastError = "未知错误";
        long started = System.currentTimeMillis();
        for (int attempt = 1; attempt <= attempts; attempt++) {
            try {
                HttpResponse<String> response = http.send(newRequest(body, timeoutSeconds),
                        HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                int status = response.statusCode();
                if (status < 200 || status >= 300) {
                    lastError = "HTTP " + status + " " + asText(response.body());
                    if (status == 400) {
                        logRequestDiagnostics(body, lastError);
                        lastError += diagnose400();
                    }
                } else {
                    AgentReply reply = parseAgentReply(response.body(),
                            System.currentTimeMillis() - started);
                    if (reply.ok()) {
                        return reply;
                    }
                    lastError = reply.error();
                }
            } catch (Exception e) {
                lastError = e.getClass().getSimpleName() + ": "
                        + asText(String.valueOf(e.getMessage()));
            }
            if (attempt < attempts) {
                try {
                    Thread.sleep(400L * attempt);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        BuilderMod.LOGGER.warn("[建造] Agent 工具调用失败（{} 次尝试）：{}", attempts, lastError);
        return AgentReply.fail(lastError);
    }

    /** 400 时把请求结构打进日志：不打印 API Key 与图片 base64，只留可定位的字段概况。 */
    private static void logRequestDiagnostics(String body, String error) {
        try {
            JsonObject root = Json.GSON.fromJson(body, JsonObject.class);
            if (root == null) {
                return;
            }
            StringBuilder info = new StringBuilder("[建造] 400 请求诊断：model=")
                    .append(Json.str(root, "model", "?"))
                    .append("，顶层字段=").append(root.keySet())
                    .append("，body=").append(body.length()).append(" 字符");
            JsonArray messages = root.getAsJsonArray("messages");
            if (messages != null) {
                info.append("，messages=").append(messages.size()).append(" 条[");
                for (int i = 0; i < messages.size(); i++) {
                    JsonObject message = messages.get(i).getAsJsonObject();
                    if (i > 0) {
                        info.append(',');
                    }
                    info.append(Json.str(message, "role", "?"));
                    if (message.has("tool_calls")) {
                        info.append("+tool_calls");
                    }
                    if (message.get("content") != null && message.get("content").isJsonArray()) {
                        info.append("+images");
                    }
                }
                info.append(']');
            }
            JsonArray tools = root.getAsJsonArray("tools");
            if (tools != null) {
                info.append("，tools=").append(tools.size()).append(" 个[");
                for (int i = 0; i < tools.size(); i++) {
                    if (i > 0) {
                        info.append(',');
                    }
                    JsonObject function = tools.get(i).getAsJsonObject()
                            .getAsJsonObject("function");
                    info.append(function == null ? "?" : Json.str(function, "name", "?"));
                }
                info.append(']');
            }
            info.append("，tool_choice=").append(root.has("tool_choice"))
                    .append("，schemaMode=")
                    .append(BuilderConfig.toolsSchemaCompat() ? "compat" : "full");
            BuilderMod.LOGGER.warn("{}，响应={}", info, error);
            if (tools != null) {
                BuilderMod.LOGGER.warn("[建造] 400 请求 tools 完整内容：{}", Json.GSON.toJson(tools));
            }
        } catch (Exception e) {
            BuilderMod.LOGGER.warn("[建造] 400 请求诊断失败：{}", e.toString());
        }
    }

    /** 把笼统的 invalid_parameter_error 翻译成可执行的排查顺序。 */
    private static String diagnose400() {
        StringBuilder hint = new StringBuilder("\n排查顺序：1) 先确认模型 ID「")
                .append(BuilderConfig.model()).append("」被当前端点接受");
        if (BuilderConfig.usingTokenPlan()) {
            hint.append("（Token Plan 官方 ID：qwen3.8-max / qwen3.8-max-0902 / "
                    + "qwen3.8-flash / auto）；");
        } else {
            hint.append("；");
        }
        hint.append("2) 把 toolChoice 设为 none 再试；")
                .append("3) 把 toolsSchemaMode 在 compat / full 之间切换；")
                .append("4) 把 modelThinking 设为 off、agentThinking 保持 false；")
                .append("5) 确认端点不支持 function calling 时把 modelTools 设为 no，自动回退旧链路。");
        return hint.toString();
    }

    private static AgentReply parseAgentReply(String raw, long elapsed) {
        JsonObject root;
        try {
            root = Json.GSON.fromJson(raw, JsonObject.class);
        } catch (Exception e) {
            return AgentReply.fail("响应不是合法 JSON：" + asText(raw));
        }
        if (root == null) {
            return AgentReply.fail("响应为空");
        }
        if (root.has("error")) {
            return AgentReply.fail("接口返回错误：" + asText(root.get("error").toString()));
        }
        JsonArray choices = root.getAsJsonArray("choices");
        if (choices == null || choices.isEmpty()) {
            return AgentReply.fail("响应缺少 choices");
        }
        JsonObject message = Json.child(choices.get(0).getAsJsonObject(), "message");
        if (message == null) {
            return AgentReply.fail("响应缺少 message");
        }
        String content = Json.str(message, "content", "");
        String reasoning = Json.str(message, "reasoning_content", "");
        List<ToolCall> calls = new ArrayList<>();
        JsonArray rawCalls = message.getAsJsonArray("tool_calls");
        if (rawCalls != null) {
            for (JsonElement element : rawCalls) {
                try {
                    JsonObject item = element.getAsJsonObject();
                    JsonObject function = Json.child(item, "function");
                    if (function == null) {
                        continue;
                    }
                    String id = Json.str(item, "id", "call_" + calls.size());
                    String name = Json.str(function, "name", "");
                    String arguments = Json.str(function, "arguments", "{}");
                    if (!name.isBlank()) {
                        calls.add(new ToolCall(id, name, arguments));
                    }
                } catch (Exception ignored) {
                    // 跳过非法 tool call
                }
            }
        }
        if (content.isBlank() && calls.isEmpty()) {
            return AgentReply.fail("模型既没返回正文也没返回 tool_calls"
                    + (reasoning.isBlank() ? "" : "（仅返回 " + reasoning.length() + " 字思考）"));
        }
        JsonObject usage = Json.child(root, "usage");
        int prompt = usage == null ? 0 : Json.integer(usage, "prompt_tokens", 0);
        int completion = usage == null ? 0 : Json.integer(usage, "completion_tokens", 0);
        return new AgentReply(true, content, reasoning, calls, prompt, completion, elapsed, "");
    }

    public void shutdown() {
        pool.shutdownNow();
    }
}
