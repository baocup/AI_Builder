package baocup.builder.agent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import baocup.builder.util.Bounds;

/**
 * 自由几何编译器：把模型写的「几何程序」编译成体素坐标。
 *
 * 借鉴 Vibe-CADing / ALICE-SDF 的 geometry-as-code 与 SDF 思路：形状没有白名单，
 * 模型可以写一个任意数学谓词 where(x,y,z)，逐体素求值决定填充；内置快捷形状只是
 * 把常见形体翻译成同一套表达式，帮模型少写字，绝不是允许清单。
 * 本地最终只产出「哪些方块放在哪里」，不关心形状叫什么。
 */
public final class ShapeLanguage {
    public static final class ShapeException extends Exception {
        public ShapeException(String message) {
            super(message);
        }
    }

    public record Compiled(List<int[]> cells, int[] actualRegion, String note, long evaluated) {
    }

    private static final int MAX_EXPRESSION_LENGTH = 4096;
    private static final int MAX_TOKENS = 1024;
    private static final int MAX_DEPTH = 64;

    private ShapeLanguage() {
    }

    /** 内置变量与函数名：vars 不允许覆盖，否则表达式语义会被悄悄改掉。 */
    private static final Set<String> RESERVED = Set.of(
            "x", "y", "z", "cx", "cy", "cz", "rx", "ry", "rz",
            "x0", "y0", "z0", "x1", "y1", "z1", "t", "i", "n", "pi", "e",
            "abs", "min", "max", "floor", "ceil", "round", "sqrt", "pow", "sin", "cos",
            "tan", "atan2", "hypot", "clamp", "step", "smoothstep", "mod", "sign",
            "lerp", "fract", "dist", "dist2", "length", "mix",
            "grid", "hash3", "rand", "noise3");

    /**
     * let 绑定：把 spec.vars 里的命名表达式内联进 where，避免同一个 H 在墙 / 屋顶 / 窗里抄三遍。
     * 值可以是数字常量，也可以是依赖 x/y/z 的表达式；按 JSON 顺序展开，后定义的变量能引用先展开的结果。
     */
    private static String expandVars(String where, JsonObject spec) throws ShapeException {
        JsonObject vars = spec.has("vars") && spec.get("vars").isJsonObject()
                ? spec.getAsJsonObject("vars") : null;
        if (vars == null || vars.isEmpty()) {
            return where;
        }
        String expanded = where;
        for (Map.Entry<String, JsonElement> entry : vars.entrySet()) {
            String name = entry.getKey().trim();
            if (!name.matches("[A-Za-z_][A-Za-z0-9_]*") || RESERVED.contains(name)) {
                throw new ShapeException("vars 变量名非法或与内置名冲突：" + name);
            }
            JsonElement value = entry.getValue();
            if (value == null || value.isJsonNull()) {
                throw new ShapeException("vars." + name + " 没有值");
            }
            String replacement = value.isJsonPrimitive()
                    && value.getAsJsonPrimitive().isNumber()
                    ? value.getAsString() : "(" + value.getAsString() + ")";
            expanded = expanded.replaceAll(
                    "\\b" + java.util.regex.Pattern.quote(name) + "\\b",
                    java.util.regex.Matcher.quoteReplacement(replacement));
        }
        return expanded;
    }

    private record TransformResult(List<int[]> cells, String note) {
    }

    /** clone 阵列 / mirror 镜像：把一套几何复制到多个位置，不必手改四遍参数。 */
    private static TransformResult applyTransforms(List<int[]> cells, JsonObject spec)
            throws ShapeException {
        JsonObject mirror = spec.has("mirror") && spec.get("mirror").isJsonObject()
                ? spec.getAsJsonObject("mirror") : null;
        JsonObject clone = spec.has("clone") && spec.get("clone").isJsonObject()
                ? spec.getAsJsonObject("clone") : null;
        if (mirror == null && clone == null) {
            return new TransformResult(cells, "");
        }
        List<int[]> out = new ArrayList<>(cells);
        StringBuilder note = new StringBuilder();
        if (mirror != null) {
            String axis = text(mirror, "axis", "x").trim().toLowerCase(java.util.Locale.ROOT);
            double at = mirror.has("at") ? mirror.get("at").getAsDouble() : 0.0;
            int component = switch (axis) {
                case "x" -> 0;
                case "y" -> 1;
                case "z" -> 2;
                default -> throw new ShapeException("mirror.axis 只能是 x / y / z，收到：" + axis);
            };
            for (int[] c : cells) {
                int[] copied = c.clone();
                copied[component] = (int) Math.round(2.0 * at - c[component]);
                out.add(copied);
            }
            note.append("；镜像 ").append(axis).append(" @ ").append(at);
        }
        if (clone != null) {
            JsonArray offset = clone.getAsJsonArray("offset");
            if (offset == null || offset.size() != 3) {
                throw new ShapeException("clone.offset 必须是 [dx,dy,dz] 三个整数");
            }
            int dx = offset.get(0).getAsInt();
            int dy = offset.get(1).getAsInt();
            int dz = offset.get(2).getAsInt();
            int count = Math.max(1, Math.min(64,
                    clone.has("count") ? clone.get("count").getAsInt() : 2));
            List<int[]> base = new ArrayList<>(out);
            for (int k = 1; k < count; k++) {
                for (int[] c : base) {
                    out.add(new int[]{c[0] + dx * k, c[1] + dy * k, c[2] + dz * k});
                }
            }
            note.append("；阵列 ").append(count).append(" 份，每份偏移 [")
                    .append(dx).append(',').append(dy).append(',').append(dz).append(']');
        }
        // 副本之间可能重叠，去重后避免同一格被反复写
        Set<Long> seen = new HashSet<>(out.size() * 2);
        List<int[]> deduplicated = new ArrayList<>(out.size());
        for (int[] c : out) {
            if (seen.add(VoxelWorld.key(c[0], c[1], c[2]))) {
                deduplicated.add(c);
            }
        }
        return new TransformResult(deduplicated, note.toString());
    }

    /** 把 draw_shape / remove_blocks 的规格编译成体素列表。 */
    public static Compiled compile(JsonObject spec, Bounds selection, int maxCells)
            throws ShapeException {
        int[] region = readRegion(spec, selection);
        String where = text(spec, "where", "");
        String shape = text(spec, "shape", "");
        String note;
        if (!where.isBlank()) {
            note = "按自定义表达式 where 生成体素";
        } else if (!shape.isBlank()) {
            where = template(shape, spec.has("params") && spec.get("params").isJsonObject()
                    ? spec.getAsJsonObject("params") : new JsonObject());
            note = "快捷形状 " + shape + " 已编译成表达式：" + where;
        } else {
            where = "1";
            note = "未指定 where / shape，按整个 region 填充";
        }
        where = expandVars(where, spec);
        Expression expression = Expression.compile(where);

        long volume = (long) (region[3] - region[0] + 1)
                * (region[4] - region[1] + 1) * (region[5] - region[2] + 1);
        if (volume > maxCells) {
            // 这是包围盒格数，不是 where 实际命中的格数；给出可直接照做的拆分方案
            int calls = (int) Math.ceil((double) volume / maxCells);
            int height = region[4] - region[1] + 1;
            int layersPerCall = Math.max(1, (int) Math.ceil((double) height / calls));
            throw new ShapeException("region 包围盒体积 " + volume + " 超过单次求值上限 "
                    + maxCells + "（上限按包围盒格数算，与 where 实际命中的格数无关）。"
                    + "建议按 y 拆成约 " + calls + " 次调用，每次只取 " + layersPerCall
                    + " 层：x/z 区间保持不变，把 y 区间依次改成 [y0,y0+" + (layersPerCall - 1)
                    + "]、[y0+" + layersPerCall + ",…] 等。");
        }

        double cx = (region[0] + region[3]) / 2.0;
        double cy = (region[1] + region[4]) / 2.0;
        double cz = (region[2] + region[5]) / 2.0;
        double rx = Math.max(0.5, (region[3] - region[0]) / 2.0);
        double ry = Math.max(0.5, (region[4] - region[1]) / 2.0);
        double rz = Math.max(0.5, (region[5] - region[2]) / 2.0);
        double[] vars = new double[18];
        vars[3] = cx;
        vars[4] = cy;
        vars[5] = cz;
        vars[6] = rx;
        vars[7] = ry;
        vars[8] = rz;
        vars[9] = region[0];
        vars[10] = region[1];
        vars[11] = region[2];
        vars[12] = region[3];
        vars[13] = region[4];
        vars[14] = region[5];
        vars[17] = volume;

        List<int[]> cells = new ArrayList<>();
        long index = 0;
        for (int y = region[1]; y <= region[4]; y++) {
            vars[1] = y;
            vars[15] = (y - region[1]) / Math.max(1.0, region[4] - region[1]);
            for (int x = region[0]; x <= region[3]; x++) {
                vars[0] = x;
                for (int z = region[2]; z <= region[5]; z++) {
                    vars[2] = z;
                    vars[16] = index++;
                    if (expression.test(vars)) {
                        cells.add(new int[]{x, y, z});
                    }
                }
            }
        }
        boolean shell = spec.has("shell") && spec.get("shell").getAsBoolean();
        if (shell && !cells.isEmpty()) {
            cells = keepShell(cells);
            note += "；只保留外表面";
        }
        TransformResult transformed = applyTransforms(cells, spec);
        cells = transformed.cells();
        note += transformed.note();
        note += "；实际 region=" + java.util.Arrays.toString(region);
        return new Compiled(cells, region, note, volume);
    }

    private static List<int[]> keepShell(List<int[]> cells) {
        Set<Long> all = new HashSet<>(cells.size() * 2);
        for (int[] c : cells) {
            all.add(VoxelWorld.key(c[0], c[1], c[2]));
        }
        List<int[]> out = new ArrayList<>();
        for (int[] c : cells) {
            boolean interior = all.contains(VoxelWorld.key(c[0] + 1, c[1], c[2]))
                    && all.contains(VoxelWorld.key(c[0] - 1, c[1], c[2]))
                    && all.contains(VoxelWorld.key(c[0], c[1] + 1, c[2]))
                    && all.contains(VoxelWorld.key(c[0], c[1] - 1, c[2]))
                    && all.contains(VoxelWorld.key(c[0], c[1], c[2] + 1))
                    && all.contains(VoxelWorld.key(c[0], c[1], c[2] - 1));
            if (!interior) {
                out.add(c);
            }
        }
        return out;
    }

    private static int[] readRegion(JsonObject spec, Bounds selection) throws ShapeException {
        JsonArray array = spec.getAsJsonArray("region");
        if (array == null || array.size() != 6) {
            throw new ShapeException("region 必须是 6 个绝对整数坐标 [x1,y1,z1,x2,y2,z2]");
        }
        int[] box = new int[6];
        for (int i = 0; i < 6; i++) {
            try {
                box[i] = array.get(i).getAsInt();
            } catch (Exception e) {
                throw new ShapeException("region 的第 " + (i + 1) + " 项不是整数");
            }
        }
        int[] normalized = {
                Math.min(box[0], box[3]), Math.min(box[1], box[4]), Math.min(box[2], box[5]),
                Math.max(box[0], box[3]), Math.max(box[1], box[4]), Math.max(box[2], box[5])
        };
        if (selection != null) {
            int[] clipped = selection.clamp(normalized[0], normalized[1], normalized[2],
                    normalized[3], normalized[4], normalized[5]);
            if (clipped == null) {
                throw new ShapeException("region 完全在选区外，已拒绝");
            }
            return clipped;
        }
        return normalized;
    }

    private static String text(JsonObject o, String key, String fallback) {
        if (o == null || !o.has(key) || o.get(key).isJsonNull()) {
            return fallback;
        }
        try {
            return o.get(key).getAsString();
        } catch (Exception e) {
            return fallback;
        }
    }

    // ------------------------------------------------------------------
    // 快捷形状：全部翻译成表达式，模型随时可以用 where 表达式覆盖 / 组合
    // ------------------------------------------------------------------

    private static String template(String shape, JsonObject p) {
        String s = shape.toLowerCase(Locale.ROOT).trim();
        String r = num(p, "radius", -1);
        String inner = num(p, "innerRadius", 0.5);
        String th = num(p, "thickness", 1.0);
        String turns = num(p, "turns", 3.0);
        String freq = num(p, "frequency", 1.0);
        String amp = num(p, "amplitude", 1.0);
        String power = num(p, "power", 4.0);
        String rad = r.equals("-1") ? "min(rx,rz)" : r;
        String radY = "ry";
        return switch (s) {
            case "box", "rect", "plane", "prism", "extrude" -> "1";
            case "hollow_box", "frame", "rounded_box", "cross" ->
                    "abs(x-cx)>=rx-" + th + " || abs(y-cy)>=ry-" + th
                            + " || abs(z-cz)>=rz-" + th;
            case "sphere" -> "(x-cx)^2+(y-cy)^2+(z-cz)^2<=" + rad + "^2";
            case "ellipsoid", "oval" ->
                    "(x-cx)^2/rx^2+(y-cy)^2/ry^2+(z-cz)^2/rz^2<=1";
            case "circle", "disc" -> "(x-cx)^2+(z-cz)^2<=" + rad + "^2 && y>=y0 && y<=y0+" + th;
            case "ellipse" -> "(x-cx)^2/rx^2+(z-cz)^2/rz^2<=1 && y>=y0 && y<=y0+" + th;
            case "cylinder", "column" -> "(x-cx)^2+(z-cz)^2<=" + rad + "^2 && y>=y0 && y<=y1";
            case "tube", "hollow_cylinder", "ring", "annulus" ->
                    "(x-cx)^2+(z-cz)^2<=" + rad + "^2 && (x-cx)^2+(z-cz)^2>="
                            + "(" + rad + "-" + th + ")^2 && y>=y0 && y<=y1";
            case "cone" -> "(x-cx)^2+(z-cz)^2<=((" + rad + ")*(y1-y)/(y1-y0+0.001))^2 && y<=y1";
            case "truncated_cone" -> "(x-cx)^2+(z-cz)^2<=(" + rad
                    + "-(" + rad + "-" + inner + ")*(y-y0)/(y1-y0+0.001))^2";
            case "pyramid", "tetrahedron" ->
                    "abs(x-cx)/rx+abs(z-cz)/rz<=(y1-y)/(y1-y0+1)+0.02 && y<=y1";
            case "triangle" ->
                    "y>=y0 && y<=y1 && abs(x-cx)<=rx*(y1-y)/(y1-y0+1)+0.02";
            case "polygon" -> "abs(x-cx)/rx+abs(y-cy)/ry+abs(z-cz)/rz<=1";
            case "torus" -> "(sqrt((x-cx)^2+(z-cz)^2)-" + rad + ")^2+(y-cy)^2<="
                    + "max(0.5," + inner + ")^2";
            case "arc" -> "abs(sqrt((x-cx)^2+(y-cy)^2)-" + rad + ")<=" + th
                    + " && y>=cy && abs(z-cz)<=" + th;
            case "spiral" -> "abs(sqrt((x-cx)^2+(z-cz)^2)-(" + inner
                    + "+(" + rad + "-" + inner + ")*mod(atan2(z-cz,x-cx)+pi,2*pi)/(2*pi*"
                    + turns + ")))<=" + th;
            case "helix" -> "abs((x-cx)-" + rad + "*cos(2*pi*" + turns
                    + "*t))<=" + th + " && abs((z-cz)-" + rad + "*sin(2*pi*" + turns
                    + "*t))<=" + th;
            case "wave", "sin_wave" -> "abs((y-y0)-(" + amp + "*sin(2*pi*" + freq
                    + "*(x-x0)/(x1-x0+0.001))+" + "ry))<=" + th;
            case "paraboloid" -> "(y-y0)>=((x-cx)^2+(z-cz)^2)/(" + rad
                    + "^2+0.001)*(y1-y0)";
            case "hyperboloid" -> "(x-cx)^2/rx^2+(z-cz)^2/rz^2-(y-cy)^2/" + radY + "^2<=1";
            case "superellipse" -> "(abs(x-cx)/rx)^" + power
                    + "+(abs(z-cz)/rz)^" + power + "<=1 && y>=y0 && y<=y1";
            case "dome", "vault" -> "(x-cx)^2+(y-y0)^2+(z-cz)^2<=" + rad + "^2 && y>=y0";
            case "arch" -> "(x-cx)^2+(y-y0)^2<=" + rad + "^2 && y>=y0 && abs(z-cz)<=" + th;
            case "gable_roof" -> "y<=y1-abs(x-cx)/rx*(y1-y0)";
            case "hip_roof" -> "y<=y1-max(abs(x-cx)/rx,abs(z-cz)/rz)*(y1-y0)";
            case "staircase", "stairs" -> "y==y0+floor((x-x0)/max(1," + th + "))";
            case "diagonal", "line", "polyline" ->
                    "abs((x-x0)/(x1-x0+0.001)-(z-z0)/(z1-z0+0.001))<=" + th + "/max(rx,rz)";
            case "checker" -> "mod(x+y+z,2)==0";
            case "lattice" -> "mod(x-x0,3)==0 || mod(y-y0,3)==0 || mod(z-z0,3)==0";
            case "star" -> "sqrt((x-cx)^2+(z-cz)^2)<=" + rad
                    + "*(0.6+0.4*cos(5*atan2(z-cz,x-cx))) && y>=y0 && y<=y0+" + th;
            case "shell" -> "1";
            case "noise" -> "mod(abs(floor(sin(x*12.9898+y*78.233+z*37.719)*43758.5453)),1)<0.5";
            case "revolve" -> "abs(sqrt((x-cx)^2+(z-cz)^2)-(" + rad
                    + "*sin(pi*t)))<=" + th;
            default -> "1";
        };
    }

    private static String num(JsonObject p, String key, double fallback) {
        JsonElement element = p == null ? null : p.get(key);
        double value = fallback;
        if (element != null && !element.isJsonNull()) {
            try {
                value = element.getAsDouble();
            } catch (Exception ignored) {
                // 用默认值
            }
        }
        if (value == Math.rint(value)) {
            return Long.toString((long) value);
        }
        return Double.toString(value);
    }

    // ------------------------------------------------------------------
    // 安全的数学表达式求值器：递归下降，无外部依赖、无反射、无循环
    // ------------------------------------------------------------------

    private interface Node {
        double eval(double[] v);
    }

    private record Constant(double value) implements Node {
        public double eval(double[] v) {
            return value;
        }
    }

    private record Variable(int index) implements Node {
        public double eval(double[] v) {
            return v[index];
        }
    }

    private record Binary(String op, Node left, Node right) implements Node {
        public double eval(double[] v) {
            double a = left.eval(v);
            double b = right.eval(v);
            return switch (op) {
                case "+" -> a + b;
                case "-" -> a - b;
                case "*" -> a * b;
                case "/" -> b == 0 ? 0 : a / b;
                case "%" -> b == 0 ? 0 : a - b * Math.floor(a / b);
                case "^" -> Math.pow(a, b);
                case "==" -> Math.abs(a - b) < 1e-9 ? 1 : 0;
                case "!=" -> Math.abs(a - b) >= 1e-9 ? 1 : 0;
                case ">" -> a > b ? 1 : 0;
                case "<" -> a < b ? 1 : 0;
                case ">=" -> a >= b ? 1 : 0;
                case "<=" -> a <= b ? 1 : 0;
                case "&&" -> (a != 0 && b != 0) ? 1 : 0;
                case "||" -> (a != 0 || b != 0) ? 1 : 0;
                default -> 0;
            };
        }
    }

    private record Unary(String op, Node operand) implements Node {
        public double eval(double[] v) {
            double a = operand.eval(v);
            return switch (op) {
                case "-" -> -a;
                case "!" -> a == 0 ? 1 : 0;
                default -> a;
            };
        }
    }

    private record Call(String name, List<Node> args) implements Node {
        public double eval(double[] v) {
            double[] a = new double[args.size()];
            for (int i = 0; i < args.size(); i++) {
                a[i] = args.get(i).eval(v);
            }
            return apply(name, a);
        }
    }

    private static double apply(String name, double[] a) {
        double x = a.length > 0 ? a[0] : 0;
        double y = a.length > 1 ? a[1] : 0;
        double z = a.length > 2 ? a[2] : 0;
        return switch (name) {
            case "abs" -> Math.abs(x);
            case "floor" -> Math.floor(x);
            case "ceil" -> Math.ceil(x);
            case "round" -> Math.rint(x);
            case "sqrt" -> Math.sqrt(Math.max(0, x));
            case "sin" -> Math.sin(x);
            case "cos" -> Math.cos(x);
            case "tan" -> Math.tan(x);
            case "atan2" -> Math.atan2(x, y);
            case "sign" -> Math.signum(x);
            case "fract" -> x - Math.floor(x);
            case "pow" -> Math.pow(x, y);
            case "mod" -> y == 0 ? 0 : x - y * Math.floor(x / y);
            case "step" -> x >= y ? 1 : 0;
            case "clamp" -> Math.max(y, Math.min(z, x));
            case "lerp", "mix" -> x + (y - x) * z;
            case "smoothstep" -> {
                double t = Math.max(0, Math.min(1, (z - x) / (y - x == 0 ? 1 : y - x)));
                yield t * t * (3 - 2 * t);
            }
            case "min" -> min(a);
            case "max" -> max(a);
            case "hypot" -> Math.hypot(x, y);
            case "length", "dist2" -> Math.sqrt(x * x + y * y + z * z);
            case "dist" -> a.length >= 6
                    ? Math.sqrt((x - a[3]) * (x - a[3]) + (y - a[4]) * (y - a[4])
                    + (z - a[5]) * (z - a[5]))
                    : Math.sqrt(x * x + y * y + z * z);
            // 网格 / 噪声原语：柱网、窗格、行道树、树冠斑驳不必再到外部逐格算
            case "grid" -> {
                double period = Math.abs(y) < 1e-9 ? 1 : y;
                double m = Math.abs(mod(x, period));
                yield Math.min(m, period - m);
            }
            case "hash3", "rand" -> hash3(x, y, z);
            case "noise3" -> noise3(x, y, z);
            default -> 0;
        };
    }

    private static double mod(double x, double n) {
        return n == 0 ? 0 : x - n * Math.floor(x / n);
    }

    /** 确定性整数哈希，同一坐标永远得到同一个 [0,1) 值。 */
    private static double hash3(double x, double y, double z) {
        long h = (long) Math.floor(x) * 374761393L
                + (long) Math.floor(y) * 668265263L
                + (long) Math.floor(z) * 2246822519L;
        h = (h ^ (h >>> 13)) * 1274126177L;
        h ^= h >>> 16;
        return (h >>> 11) / (double) (1L << 53);
    }

    /** 三线性插值的 value noise，输出 [0,1)，可用于树冠深浅、地形起伏。 */
    private static double noise3(double x, double y, double z) {
        double ix = Math.floor(x), iy = Math.floor(y), iz = Math.floor(z);
        double fx = fade(x - ix), fy = fade(y - iy), fz = fade(z - iz);
        double c00 = lerp(hash3(ix, iy, iz), hash3(ix + 1, iy, iz), fx);
        double c10 = lerp(hash3(ix, iy + 1, iz), hash3(ix + 1, iy + 1, iz), fx);
        double c01 = lerp(hash3(ix, iy, iz + 1), hash3(ix + 1, iy, iz + 1), fx);
        double c11 = lerp(hash3(ix, iy + 1, iz + 1), hash3(ix + 1, iy + 1, iz + 1), fx);
        return lerp(lerp(c00, c10, fy), lerp(c01, c11, fy), fz);
    }

    private static double fade(double t) {
        return t * t * t * (t * (t * 6 - 15) + 10);
    }

    private static double lerp(double a, double b, double t) {
        return a + (b - a) * t;
    }

    private static double min(double[] values) {
        double result = values.length == 0 ? 0 : values[0];
        for (double d : values) {
            result = Math.min(result, d);
        }
        return result;
    }

    private static double max(double[] values) {
        double result = values.length == 0 ? 0 : values[0];
        for (double d : values) {
            result = Math.max(result, d);
        }
        return result;
    }

    public static final class Expression {
        private static final Map<String, Integer> VARIABLES = new HashMap<>();

        static {
            String[] names = {"x", "y", "z", "cx", "cy", "cz", "rx", "ry", "rz",
                    "x0", "y0", "z0", "x1", "y1", "z1", "t", "i", "n"};
            for (int i = 0; i < names.length; i++) {
                VARIABLES.put(names[i], i);
            }
        }

        private final Node root;

        private Expression(Node root) {
            this.root = root;
        }

        public static Expression compile(String source) throws ShapeException {
            if (source == null || source.isBlank()) {
                throw new ShapeException("表达式为空");
            }
            if (source.length() > MAX_EXPRESSION_LENGTH) {
                throw new ShapeException("表达式超过 " + MAX_EXPRESSION_LENGTH + " 字符上限");
            }
            List<String> tokens = lex(source);
            if (tokens.size() > MAX_TOKENS) {
                throw new ShapeException("表达式 token 数超过 " + MAX_TOKENS);
            }
            Parser parser = new Parser(tokens);
            Node node = parser.parseOr(0);
            if (parser.position < tokens.size()) {
                throw new ShapeException("表达式在 token「" + tokens.get(parser.position) + "」处有多余内容");
            }
            return new Expression(node);
        }

        public boolean test(double[] vars) {
            return Math.abs(root.eval(vars)) > 1e-9;
        }

        private static List<String> lex(String source) throws ShapeException {
            List<String> out = new ArrayList<>();
            int i = 0;
            while (i < source.length()) {
                char c = source.charAt(i);
                if (Character.isWhitespace(c)) {
                    i++;
                    continue;
                }
                if (Character.isDigit(c) || (c == '.' && i + 1 < source.length()
                        && Character.isDigit(source.charAt(i + 1)))) {
                    int start = i;
                    while (i < source.length() && (Character.isDigit(source.charAt(i))
                            || source.charAt(i) == '.' || source.charAt(i) == 'e'
                            || source.charAt(i) == 'E'
                            || ((source.charAt(i) == '+' || source.charAt(i) == '-')
                            && i > start && (source.charAt(i - 1) == 'e'
                            || source.charAt(i - 1) == 'E')))) {
                        i++;
                    }
                    out.add(source.substring(start, i));
                    continue;
                }
                if (Character.isLetter(c) || c == '_') {
                    int start = i;
                    while (i < source.length() && (Character.isLetterOrDigit(source.charAt(i))
                            || source.charAt(i) == '_')) {
                        i++;
                    }
                    out.add(source.substring(start, i));
                    continue;
                }
                if (i + 1 < source.length()) {
                    String pair = source.substring(i, i + 2);
                    if (List.of("&&", "||", "==", "!=", ">=", "<=").contains(pair)) {
                        out.add(pair);
                        i += 2;
                        continue;
                    }
                }
                if ("+-*/%^<>()!,.".contains(String.valueOf(c))) {
                    out.add(String.valueOf(c));
                    i++;
                    continue;
                }
                throw new ShapeException("表达式含非法字符「" + c + "」");
            }
            return out;
        }

        private static final class Parser {
            private final List<String> tokens;
            private int position;

            Parser(List<String> tokens) {
                this.tokens = tokens;
            }

            private String peek() {
                return position < tokens.size() ? tokens.get(position) : "";
            }

            private boolean take(String token) {
                if (token.equals(peek())) {
                    position++;
                    return true;
                }
                return false;
            }

            Node parseOr(int depth) throws ShapeException {
                checkDepth(depth);
                Node node = parseAnd(depth + 1);
                while (take("||")) {
                    node = new Binary("||", node, parseAnd(depth + 1));
                }
                return node;
            }

            Node parseAnd(int depth) throws ShapeException {
                checkDepth(depth);
                Node node = parseCompare(depth + 1);
                while (take("&&")) {
                    node = new Binary("&&", node, parseCompare(depth + 1));
                }
                return node;
            }

            Node parseCompare(int depth) throws ShapeException {
                checkDepth(depth);
                Node node = parseAdd(depth + 1);
                while (List.of("==", "!=", ">", "<", ">=", "<=").contains(peek())) {
                    String op = tokens.get(position++);
                    node = new Binary(op, node, parseAdd(depth + 1));
                }
                return node;
            }

            Node parseAdd(int depth) throws ShapeException {
                checkDepth(depth);
                Node node = parseMul(depth + 1);
                while (peek().equals("+") || peek().equals("-")) {
                    String op = tokens.get(position++);
                    node = new Binary(op, node, parseMul(depth + 1));
                }
                return node;
            }

            Node parseMul(int depth) throws ShapeException {
                checkDepth(depth);
                Node node = parsePow(depth + 1);
                while (List.of("*", "/", "%").contains(peek())) {
                    String op = tokens.get(position++);
                    node = new Binary(op, node, parsePow(depth + 1));
                }
                return node;
            }

            Node parsePow(int depth) throws ShapeException {
                checkDepth(depth);
                Node node = parseUnary(depth + 1);
                if (take("^")) {
                    return new Binary("^", node, parsePow(depth + 1));
                }
                return node;
            }

            Node parseUnary(int depth) throws ShapeException {
                checkDepth(depth);
                if (take("-")) {
                    return new Unary("-", parseUnary(depth + 1));
                }
                if (take("!")) {
                    return new Unary("!", parseUnary(depth + 1));
                }
                if (take("+")) {
                    return parseUnary(depth + 1);
                }
                return parsePrimary(depth + 1);
            }

            Node parsePrimary(int depth) throws ShapeException {
                checkDepth(depth);
                String token = peek();
                if (token.isEmpty()) {
                    throw new ShapeException("表达式意外结束");
                }
                if (token.equals("(")) {
                    position++;
                    Node node = parseOr(depth + 1);
                    if (!take(")")) {
                        throw new ShapeException("缺少右括号");
                    }
                    return node;
                }
                position++;
                if (Character.isDigit(token.charAt(0)) || token.charAt(0) == '.') {
                    try {
                        return new Constant(Double.parseDouble(token));
                    } catch (NumberFormatException e) {
                        throw new ShapeException("非法数字「" + token + "」");
                    }
                }
                if (token.equals("pi")) {
                    return new Constant(Math.PI);
                }
                if (token.equals("e")) {
                    return new Constant(Math.E);
                }
                if (take("(")) {
                    List<Node> args = new ArrayList<>();
                    if (!peek().equals(")")) {
                        args.add(parseOr(depth + 1));
                        while (take(",")) {
                            args.add(parseOr(depth + 1));
                        }
                    }
                    if (!take(")")) {
                        throw new ShapeException("函数 " + token + " 缺少右括号");
                    }
                    return new Call(token.toLowerCase(Locale.ROOT), args);
                }
                Integer index = VARIABLES.get(token.toLowerCase(Locale.ROOT));
                if (index == null) {
                    throw new ShapeException("未知变量或函数「" + token + "」");
                }
                return new Variable(index);
            }

            private static void checkDepth(int depth) throws ShapeException {
                if (depth > MAX_DEPTH) {
                    throw new ShapeException("表达式嵌套过深");
                }
            }
        }
    }
}
