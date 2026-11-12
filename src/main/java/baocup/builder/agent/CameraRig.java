package baocup.builder.agent;

import java.util.ArrayList;
import java.util.List;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import baocup.builder.util.Bounds;
import baocup.builder.util.Json;

/** 建筑周围的相机阵列：环绕视角 + 俯视 + 人眼高度，以及 AI 自由指定的额外视角。 */
public final class CameraRig {
    private static final String[] DIRECTIONS = {
            "东南", "南", "西南", "西", "西北", "北", "东北", "东"
    };

    private CameraRig() {
    }

    public static List<CameraPose> around(Bounds bounds, int count) {
        List<CameraPose> poses = new ArrayList<>();
        double cx = (bounds.minX() + bounds.maxX()) / 2.0;
        double cy = (bounds.minY() + bounds.maxY()) / 2.0;
        double cz = (bounds.minZ() + bounds.maxZ()) / 2.0;
        double radius = Math.max(bounds.sizeX(), bounds.sizeZ()) * 1.35 + 6.0;
        double height = bounds.sizeY() * 0.9 + 4.0;
        int n = Math.max(4, Math.min(16, count));
        for (int i = 0; i < n; i++) {
            double angle = 2.0 * Math.PI * i / n;
            double x = cx + radius * Math.cos(angle);
            double z = cz + radius * Math.sin(angle);
            String name = DIRECTIONS[i % DIRECTIONS.length] + "环绕视角 " + (i + 1);
            poses.add(CameraPose.lookingAt("v" + (i + 1), name, x, cy + height, z,
                    cx, cy, cz, "环绕建筑外立面"));
        }
        // 俯视：从正上方看整体平面布局
        poses.add(new CameraPose("top", "正上方俯视", cx,
                bounds.maxY() + Math.max(bounds.sizeX(), bounds.sizeZ()) * 0.85 + 6.0, cz,
                0.0F, 89.0F, "俯视平面布局与屋顶"));
        // 人眼高度：检查门窗、入口、可通行性
        poses.add(CameraPose.lookingAt("eye", "入口人眼高度", cx, bounds.minY() + 2.0,
                cz - radius, cx, bounds.minY() + 2.0, cz, "检查入口与门窗"));
        return poses;
    }

    /**
     * 中途检查用的代表性机位：优先环绕对角两台 + 俯视 + 人眼高度，数量不够再补其它环绕角度。
     * 比完整环绕阵列省照片，同时保证整体、平面布局与入口细节都看得到。
     */
    public static List<CameraPose> representative(Bounds bounds, int count) {
        List<CameraPose> all = around(bounds, 4);
        List<CameraPose> selected = new ArrayList<>();
        selected.add(all.get(0));                    // 环绕主视角
        selected.add(all.get(all.size() - 2));       // 俯视平面布局
        selected.add(all.get(all.size() - 1));       // 入口人眼高度
        selected.add(all.get(2));                    // 对侧环绕
        int wanted = Math.max(1, Math.min(count, all.size()));
        if (wanted <= selected.size()) {
            return new ArrayList<>(selected.subList(0, wanted));
        }
        for (CameraPose pose : all) {
            if (selected.size() >= wanted) {
                break;
            }
            if (!selected.contains(pose)) {
                selected.add(pose);
            }
        }
        return selected;
    }

    /** 解析 GUI 里的自定义视角字符串：x,y,z,yaw,pitch。 */
    public static CameraPose custom(String text) {
        String[] parts = text == null ? new String[0] : text.split("[,，\\s]+");
        if (parts.length < 5) {
            throw new IllegalArgumentException("自定义视角需要 5 个数字：x,y,z,yaw,pitch");
        }
        try {
            double x = Double.parseDouble(parts[0]);
            double y = Double.parseDouble(parts[1]);
            double z = Double.parseDouble(parts[2]);
            float yaw = Float.parseFloat(parts[3]);
            float pitch = Float.parseFloat(parts[4]);
            return new CameraPose("custom-" + System.currentTimeMillis(), "自定义相机",
                    x, y, z, yaw, pitch, "玩家手动指定");
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("视角参数必须是数字：" + e.getMessage());
        }
    }

    /** 解析视觉模型「我还想看哪些角度」的输出，坐标会被裁剪到选区附近的合理范围。 */
    public static List<CameraPose> requestedViews(String raw, Bounds bounds, int max) {
        List<CameraPose> out = new ArrayList<>();
        if (raw == null || raw.isBlank()) {
            return out;
        }
        String json = LlmClient.extractJson(raw);
        if (json == null) {
            return out;
        }
        JsonObject root;
        try {
            root = Json.GSON.fromJson(json, JsonObject.class);
        } catch (Exception e) {
            return out;
        }
        if (root == null || !root.isJsonObject()) {
            return out;
        }
        JsonArray views = root.getAsJsonArray("requestedViews");
        if (views == null) {
            return out;
        }
        double cx = (bounds.minX() + bounds.maxX()) / 2.0;
        double cy = (bounds.minY() + bounds.maxY()) / 2.0;
        double cz = (bounds.minZ() + bounds.maxZ()) / 2.0;
        double limit = Math.max(bounds.sizeX(), bounds.sizeZ()) * 3.0 + 32.0;
        for (JsonElement element : views) {
            if (out.size() >= max || !element.isJsonObject()) {
                break;
            }
            JsonObject o = element.getAsJsonObject();
            try {
                double x = Json.dbl(o, "x", cx);
                double y = Json.dbl(o, "y", cy + 6);
                double z = Json.dbl(o, "z", cz);
                // 只允许在选区附近活动，避免 AI 把相机放到世界另一端
                x = clamp(x, cx - limit, cx + limit);
                y = clamp(y, bounds.minY() - 8.0, bounds.maxY() + limit);
                z = clamp(z, cz - limit, cz + limit);
                float yaw = (float) Json.dbl(o, "yaw", 0);
                float pitch = (float) Json.dbl(o, "pitch", 12);
                String reason = Json.str(o, "reason", "AI 追加视角");
                out.add(new CameraPose("ai" + (out.size() + 1), "AI 自由视角 " + (out.size() + 1),
                        x, y, z, yaw, pitch, reason));
            } catch (Exception ignored) {
                // 单条非法视角不影响其它
            }
        }
        return out;
    }

    private static double clamp(double value, double lo, double hi) {
        return Math.max(lo, Math.min(hi, value));
    }
}
