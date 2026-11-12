package baocup.builder.memory;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import com.google.gson.reflect.TypeToken;

import baocup.builder.util.Bounds;
import baocup.builder.util.Json;

/** 每个玩家当前选中的坐标范围；坐标选择物品写入，建造 GUI 读取，重启后自动恢复。 */
public final class SelectionStore {
    public static final class Entry {
        public String dimension = "minecraft:overworld";
        public int[] bounds;

        public Entry() {
        }

        public Entry(String dimension, int[] bounds) {
            this.dimension = dimension;
            this.bounds = bounds;
        }
    }

    private final Map<String, Entry> entries = new LinkedHashMap<>();

    public void load(String json) {
        try {
            Map<String, Entry> loaded = Json.GSON.fromJson(json, new TypeToken<Map<String, Entry>>() {}.getType());
            if (loaded != null) {
                entries.clear();
                entries.putAll(loaded);
            }
        } catch (Exception ignored) {
            // 损坏的选区文件直接忽略
        }
    }

    public String toJson() {
        return Json.GSON.toJson(entries);
    }

    public Entry get(UUID player) {
        return entries.get(player.toString());
    }

    public void set(UUID player, Bounds bounds, String dimension) {
        entries.put(player.toString(), new Entry(dimension, bounds.toArray()));
    }

    public void remove(UUID player) {
        entries.remove(player.toString());
    }
}
