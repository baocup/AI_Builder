package baocup.builder.agent;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BiFunction;
import java.util.function.Supplier;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import baocup.builder.BuilderMod;
import baocup.builder.util.Json;

/**
 * 极简 MCP（Model Context Protocol）服务端：JSON-RPC 2.0 over HTTP。
 *
 * 外部 Agent（Codex / Claude Code / Cursor / WorkBuddy / DeepSeekHarness …）用
 * streamable HTTP 方式连 {@code http://127.0.0.1:<port>/mcp}，
 * 通过 {@code tools/call} 直接驱动游戏内建造，不需要轮询任何文件。
 */
public final class McpServer {
    /** 与 2025-06-18 版 MCP 规范对齐；客户端传更老的版本也会被接受。 */
    public static final String PROTOCOL_VERSION = "2025-06-18";
    private static final String SERVER_NAME = "minecraft-builder";
    private static final String SERVER_VERSION = "1.0.0";

    private final String token;
    private final Supplier<JsonArray> toolsSupplier;
    private final BiFunction<String, JsonObject, JsonObject> toolCaller;

    private HttpServer http;
    private ExecutorService pool;
    /** MCP 规范要求 initialize 回包带 Mcp-Session-Id；宽松模式不强制客户端回传。 */
    private final String sessionId = java.util.UUID.randomUUID().toString().replace("-", "");

    public McpServer(String token, Supplier<JsonArray> toolsSupplier,
                     BiFunction<String, JsonObject, JsonObject> toolCaller) {
        this.token = token == null ? "" : token.trim();
        this.toolsSupplier = toolsSupplier;
        this.toolCaller = toolCaller;
    }

    public void start(String host, int port) throws IOException {
        http = HttpServer.create(new InetSocketAddress(host, port), 32);
        pool = Executors.newFixedThreadPool(4, runnable -> {
            Thread thread = new Thread(runnable, "builder-mcp");
            thread.setDaemon(true);
            return thread;
        });
        http.setExecutor(pool);
        http.createContext("/mcp", this::handle);
        http.start();
        BuilderMod.LOGGER.info("[建造] MCP 已监听 http://{}:{}/mcp（Tool calling 直连，不读文件）",
                host, port);
    }

    public void stop() {
        if (http != null) {
            http.stop(0);
            http = null;
        }
        if (pool != null) {
            pool.shutdownNow();
            pool = null;
        }
    }

    private void handle(HttpExchange exchange) throws IOException {
        try {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                respond(exchange, 405, "{\"error\":\"MCP 端点只接受 POST\"}");
                return;
            }
            if (!authorized(exchange)) {
                respond(exchange, 401, "{\"error\":\"MCP token 不匹配\"}");
                return;
            }
            JsonElement request;
            try (InputStream in = exchange.getRequestBody()) {
                request = Json.GSON.fromJson(
                        new String(in.readAllBytes(), StandardCharsets.UTF_8), JsonElement.class);
            } catch (Exception e) {
                respond(exchange, 400, "{\"error\":\"请求不是合法 JSON\"}");
                return;
            }
            boolean initialize = containsInitialize(request);
            JsonElement response = dispatch(request);
            if (response == null) {
                // 通知（notifications/initialized 等）按规范返回 202 且无 body
                if (initialize) {
                    exchange.getResponseHeaders().set("Mcp-Session-Id", sessionId);
                }
                exchange.sendResponseHeaders(202, -1);
                return;
            }
            if (initialize) {
                exchange.getResponseHeaders().set("Mcp-Session-Id", sessionId);
            }
            respond(exchange, 200, Json.GSON.toJson(response));
        } finally {
            exchange.close();
        }
    }

    private boolean authorized(HttpExchange exchange) {
        if (token.isBlank()) {
            return true;
        }
        String header = exchange.getRequestHeaders().getFirst("Authorization");
        if (header == null) {
            return false;
        }
        String value = header.trim();
        if (value.regionMatches(true, 0, "Bearer ", 0, 7)) {
            value = value.substring(7).trim();
        }
        return token.equals(value);
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static boolean containsInitialize(JsonElement request) {
        if (request == null || request.isJsonNull()) {
            return false;
        }
        if (request.isJsonArray()) {
            for (JsonElement element : request.getAsJsonArray()) {
                if (isInitialize(element)) {
                    return true;
                }
            }
            return false;
        }
        return isInitialize(request);
    }

    private static boolean isInitialize(JsonElement element) {
        return element != null && element.isJsonObject()
                && "initialize".equals(Json.str(element.getAsJsonObject(), "method", ""));
    }

    /** 支持单条与批量 JSON-RPC；通知不产生响应。 */
    public JsonElement dispatch(JsonElement request) {
        if (request == null || request.isJsonNull()) {
            return null;
        }
        if (request.isJsonArray()) {
            JsonArray responses = new JsonArray();
            for (JsonElement item : request.getAsJsonArray()) {
                JsonObject one = dispatchOne(item);
                if (one != null) {
                    responses.add(one);
                }
            }
            return responses.isEmpty() ? null : responses;
        }
        return dispatchOne(request);
    }

    private JsonObject dispatchOne(JsonElement element) {
        if (element == null || !element.isJsonObject()) {
            return null;
        }
        JsonObject request = element.getAsJsonObject();
        JsonElement id = request.get("id");
        if (id == null || id.isJsonNull()) {
            return null;
        }
        String method = Json.str(request, "method", "");
        JsonObject response = new JsonObject();
        response.addProperty("jsonrpc", "2.0");
        response.add("id", id);
        try {
            JsonObject result = switch (method) {
                case "initialize" -> initializeResult();
                case "ping" -> new JsonObject();
                case "tools/list" -> toolsList();
                case "tools/call" -> toolCall(request.getAsJsonObject("params"));
                default -> null;
            };
            if (result == null) {
                response.add("error", error(-32601, "未实现的方法：" + method));
            } else {
                response.add("result", result);
            }
        } catch (Exception e) {
            BuilderMod.LOGGER.warn("[建造] MCP 处理 {} 失败：{}", method, e.toString());
            response.add("error", error(-32603, "服务端异常：" + e));
        }
        return response;
    }

    private JsonObject initializeResult() {
        JsonObject result = new JsonObject();
        result.addProperty("protocolVersion", PROTOCOL_VERSION);
        JsonObject capabilities = new JsonObject();
        JsonObject tools = new JsonObject();
        tools.addProperty("listChanged", false);
        capabilities.add("tools", tools);
        result.add("capabilities", capabilities);
        JsonObject info = new JsonObject();
        info.addProperty("name", SERVER_NAME);
        info.addProperty("version", SERVER_VERSION);
        result.add("serverInfo", info);
        // MCP 规范字段是 instructions（原来的 serverInstruction 会被客户端忽略）
        result.addProperty("instructions",
                "所有坐标都是绝对世界整数坐标。选区由玩家手杖决定，也可调用 request_bounds 自主设定；"
                        + "但改选区会重置本地建模，调用前请先 submit_build。"
                        + "建模只在客户端本地，必须 submit_build 才会写进世界：它只提交相对上次的变化，"
                        + "本地删除会以 minecraft:air 传播。照片以 captureDir / photoPaths 绝对路径落盘，"
                        + "MCP 不回传图片字节。");
        return result;
    }

    private JsonObject toolsList() {
        JsonArray tools = toolsSupplier == null ? new JsonArray() : toolsSupplier.get();
        if (tools == null) {
            tools = new JsonArray();
        }
        JsonObject result = new JsonObject();
        result.add("tools", tools);
        return result;
    }

    private JsonObject toolCall(JsonObject params) {
        JsonObject result = new JsonObject();
        JsonArray content = new JsonArray();
        boolean failed = false;
        try {
            if (params == null) {
                throw new IllegalArgumentException("缺少 params");
            }
            String name = Json.str(params, "name", "");
            JsonObject arguments = params.getAsJsonObject("arguments");
            if (arguments == null) {
                arguments = new JsonObject();
            }
            JsonObject payload = toolCaller == null ? null
                    : toolCaller.apply(name, arguments);
            if (payload == null) {
                payload = new JsonObject();
                payload.addProperty("error", "未知工具：" + name);
            }
            failed = Json.bool(payload, "isError", false);
            payload.remove("isError");
            JsonObject text = new JsonObject();
            text.addProperty("type", "text");
            text.addProperty("text", Json.GSON.toJson(payload));
            content.add(text);
        } catch (Exception e) {
            failed = true;
            JsonObject text = new JsonObject();
            text.addProperty("type", "text");
            JsonObject payload = new JsonObject();
            payload.addProperty("error", e.getMessage() == null ? e.toString() : e.getMessage());
            text.addProperty("text", Json.GSON.toJson(payload));
            content.add(text);
        }
        result.add("content", content);
        result.addProperty("isError", failed);
        return result;
    }

    private static JsonObject error(int code, String message) {
        JsonObject error = new JsonObject();
        error.addProperty("code", code);
        error.addProperty("message", message);
        return error;
    }
}
