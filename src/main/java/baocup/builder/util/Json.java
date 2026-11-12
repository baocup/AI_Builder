package baocup.builder.util;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

public final class Json {
    public static final Gson PRETTY = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    public static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private Json() {
    }

    public static JsonObject object() {
        return new JsonObject();
    }

    public static String str(JsonObject o, String key, String fallback) {
        if (o == null || !o.has(key) || o.get(key).isJsonNull()) {
            return fallback;
        }
        try {
            return o.get(key).getAsString();
        } catch (Exception ignored) {
            return fallback;
        }
    }

    public static int integer(JsonObject o, String key, int fallback) {
        if (o == null || !o.has(key)) {
            return fallback;
        }
        try {
            return o.get(key).getAsInt();
        } catch (Exception ignored) {
            return fallback;
        }
    }

    public static long lng(JsonObject o, String key, long fallback) {
        if (o == null || !o.has(key)) {
            return fallback;
        }
        try {
            return o.get(key).getAsLong();
        } catch (Exception ignored) {
            return fallback;
        }
    }

    public static double dbl(JsonObject o, String key, double fallback) {
        if (o == null || !o.has(key)) {
            return fallback;
        }
        try {
            return o.get(key).getAsDouble();
        } catch (Exception ignored) {
            return fallback;
        }
    }

    public static boolean bool(JsonObject o, String key, boolean fallback) {
        if (o == null || !o.has(key)) {
            return fallback;
        }
        try {
            return o.get(key).getAsBoolean();
        } catch (Exception ignored) {
            return fallback;
        }
    }

    public static JsonObject child(JsonObject o, String key) {
        if (o == null || !o.has(key) || !o.get(key).isJsonObject()) {
            return null;
        }
        return o.getAsJsonObject(key);
    }

    public static List<String> stringList(JsonObject o, String key) {
        List<String> out = new ArrayList<>();
        if (o == null || !o.has(key) || !o.get(key).isJsonArray()) {
            return out;
        }
        for (JsonElement e : o.getAsJsonArray(key)) {
            try {
                out.add(e.getAsString());
            } catch (Exception ignored) {
                // 跳过非法元素
            }
        }
        return out;
    }

    public static JsonArray toArray(Iterable<String> values) {
        JsonArray array = new JsonArray();
        for (String value : values) {
            array.add(value);
        }
        return array;
    }

    /** 全量输出，不做条数限制。 */
    public static JsonObject intMap(Map<String, Integer> map) {
        JsonObject o = new JsonObject();
        for (Map.Entry<String, Integer> e : map.entrySet()) {
            o.addProperty(e.getKey(), e.getValue());
        }
        return o;
    }
}
