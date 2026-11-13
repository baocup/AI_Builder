package baocup.builder.agent;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import javax.imageio.ImageIO;

import baocup.builder.util.Bounds;

/**
 * 独立虚拟相机的离屏渲染器。
 *
 * 不读取玩家屏幕、不调用客户端 GameRenderer、也不修改玩家视角；
 * 而是从 CameraPose 给定的绝对位置与朝向，对「编译后的方块图」做一次软件光线投射，
 * 直接生成 PNG。因此专用服务器也能拍照，且每张照片都是可复现的确定性结果。
 *
 * 支持透视与正交两种投影（pose.orthographic）：正交用于俯视平面图，避免塔顶向外发散。
 * 命中后按面内坐标画方块边缘线与细纹理，门窗、砖缝、栏杆、台阶等细节才看得清；
 * 距离远到一格不足几个像素时自动关掉细节，避免远处出现噪点。
 */
public final class VisualRenderer {
    private static final double INF = Double.POSITIVE_INFINITY;
    private static final double EPS = 1e-9;
    /** 包围盒向外多留的格数，保证建筑外沿与地面不会被裁掉。 */
    private static final double MARGIN = 3.0;
    private static final int SKY_TOP = 0x6FA8DC;
    private static final int SKY_HORIZON = 0xD6E6F2;
    private static final int GROUND = 0x5B8C4A;
    private static final int FALLBACK = 0x9A9A9A;
    /** 占位地面的网格线：明显区别于真实方块，俯视时能判断地面是否铺满。 */
    private static final int GROUND_GRID = 0x2C4327;
    /** 离屏渲染没有真实生物群系数据，用典型草原色给 tinted 方块着色。 */
    private static final int GRASS_TINT = 0x79C05A;
    private static final int FOLIAGE_TINT = 0x59AE30;
    private static final int WATER_TINT = 0x3F76E4;

    private VisualRenderer() {
    }

    public static final class Frame {
        public byte[] png = new byte[0];
        public int width;
        public int height;
        public int blockPixels;
        public int skyPixels;
        public Map<String, Integer> materials = new LinkedHashMap<>();
    }

    public static Frame render(CameraPose pose, Map<Long, Placement> voxels,
                               Bounds bounds, int width, int height) throws IOException {
        Frame frame = new Frame();
        frame.width = width;
        frame.height = height;
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);

        double yaw = Math.toRadians(pose.yaw);
        double pitch = Math.toRadians(pose.pitch);
        double cosPitch = Math.cos(pitch);
        double sinPitch = Math.sin(pitch);
        double cosYaw = Math.cos(yaw);
        double sinYaw = Math.sin(yaw);

        // 与 Entity.calculateViewVector 一致：yaw=0 朝 +Z，pitch 正值朝下
        double fx = -sinYaw * cosPitch;
        double fy = -sinPitch;
        double fz = cosYaw * cosPitch;
        // 屏幕右向量 = cross(forward, worldUp) 归一化
        double rx = -cosYaw;
        double ry = 0.0;
        double rz = -sinYaw;
        // 屏幕上向量 = cross(right, forward)
        double ux = -sinYaw * sinPitch;
        double uy = cosPitch;
        double uz = cosYaw * sinPitch;

        boolean ortho = pose.orthographic;
        double tanHalf = Math.tan(Math.toRadians(Math.max(20.0F, Math.min(110.0F, pose.fov))) / 2.0);
        double aspect = width / (double) Math.max(1, height);
        // 正交下 halfHeight 是「半屏对应的世界格数」，透视下是角半径
        double halfHeight = ortho
                ? (pose.orthoWidth > 0 ? pose.orthoWidth / 2.0
                        : Math.max(bounds.sizeX(), bounds.sizeZ()) * 0.62)
                : tanHalf;
        double halfWidth = halfHeight * aspect;
        double worldPerPixel = 2.0 * halfHeight / Math.max(1, height);
        double groundY = bounds.minY() - 1.0;
        // 模型可以把相机放进建筑内部；若机位正好落在实心方块里，跳过这个方块，
        // 否则每条射线都会命中相机自身，整张照片只剩一种颜色。
        int cameraCellX = floor(pose.x);
        int cameraCellY = floor(pose.y);
        int cameraCellZ = floor(pose.z);
        Placement cameraBlock = voxels.get(
                PlanCompiler.packedKey(cameraCellX, cameraCellY, cameraCellZ));
        boolean cameraInSolid = cameraBlock != null
                && !"minecraft:air".equals(cameraBlock.block);
        double span = Math.max(bounds.sizeX(), Math.max(bounds.sizeY(), bounds.sizeZ()));
        double maxDist = span * 3.0 + 48.0;
        double loX = bounds.minX() - MARGIN;
        double hiX = bounds.maxX() + 1.0 + MARGIN;
        double loY = groundY - MARGIN;
        double hiY = bounds.maxY() + 1.0 + MARGIN;
        double loZ = bounds.minZ() - MARGIN;
        double hiZ = bounds.maxZ() + 1.0 + MARGIN;

        for (int py = 0; py < height; py++) {
            double sy = 1.0 - 2.0 * (py + 0.5) / height;
            for (int px = 0; px < width; px++) {
                double sx = 2.0 * (px + 0.5) / width - 1.0;
                double startX = pose.x;
                double startY = pose.y;
                double startZ = pose.z;
                double dx;
                double dy;
                double dz;
                if (ortho) {
                    // 正交：所有射线方向相同，只把起点沿屏幕平面平移
                    startX += rx * sx * halfWidth + ux * sy * halfHeight;
                    startY += ry * sx * halfWidth + uy * sy * halfHeight;
                    startZ += rz * sx * halfWidth + uz * sy * halfHeight;
                    dx = fx;
                    dy = fy;
                    dz = fz;
                } else {
                    dx = fx + rx * sx * halfWidth + ux * sy * halfHeight;
                    dy = fy + ry * sx * halfWidth + uy * sy * halfHeight;
                    dz = fz + rz * sx * halfWidth + uz * sy * halfHeight;
                }
                double length = Math.sqrt(dx * dx + dy * dy + dz * dz);
                if (length < EPS) {
                    image.setRGB(px, py, SKY_HORIZON);
                    frame.skyPixels++;
                    continue;
                }
                dx /= length;
                dy /= length;
                dz /= length;

                int rgb = -1;
                String hitMaterial = null;

                // ray-box：只遍历建筑包围盒内的体素，高清照片才不会把时间耗在空气上
                double enter = 0.0;
                double exit = maxDist;
                boolean miss = false;
                if (Math.abs(dx) < EPS) {
                    if (startX < loX || startX > hiX) miss = true;
                } else {
                    double a = (loX - startX) / dx;
                    double b = (hiX - startX) / dx;
                    enter = Math.max(enter, Math.min(a, b));
                    exit = Math.min(exit, Math.max(a, b));
                }
                if (!miss) {
                    if (Math.abs(dy) < EPS) {
                        if (startY < loY || startY > hiY) miss = true;
                    } else {
                        double a = (loY - startY) / dy;
                        double b = (hiY - startY) / dy;
                        enter = Math.max(enter, Math.min(a, b));
                        exit = Math.min(exit, Math.max(a, b));
                    }
                }
                if (!miss) {
                    if (Math.abs(dz) < EPS) {
                        if (startZ < loZ || startZ > hiZ) miss = true;
                    } else {
                        double a = (loZ - startZ) / dz;
                        double b = (hiZ - startZ) / dz;
                        enter = Math.max(enter, Math.min(a, b));
                        exit = Math.min(exit, Math.max(a, b));
                    }
                }
                miss |= enter > exit;

                if (!miss) {
                    double baseT = Math.max(0.0, enter);
                    double limit = Math.min(exit, maxDist) - baseT;
                    double ox = startX + dx * baseT;
                    double oy = startY + dy * baseT;
                    double oz = startZ + dz * baseT;
                    int cx = floor(ox);
                    int cy = floor(oy);
                    int cz = floor(oz);
                    int stepX = dx > EPS ? 1 : (dx < -EPS ? -1 : 0);
                    int stepY = dy > EPS ? 1 : (dy < -EPS ? -1 : 0);
                    int stepZ = dz > EPS ? 1 : (dz < -EPS ? -1 : 0);
                    double deltaX = stepX == 0 ? INF : Math.abs(1.0 / dx);
                    double deltaY = stepY == 0 ? INF : Math.abs(1.0 / dy);
                    double deltaZ = stepZ == 0 ? INF : Math.abs(1.0 / dz);
                    double maxX = stepX == 0 ? INF
                            : (stepX > 0 ? cx + 1.0 - ox : ox - cx) / Math.abs(dx);
                    double maxY = stepY == 0 ? INF
                            : (stepY > 0 ? cy + 1.0 - oy : oy - cy) / Math.abs(dy);
                    double maxZ = stepZ == 0 ? INF
                            : (stepZ > 0 ? cz + 1.0 - oz : oz - cz) / Math.abs(dz);
                    int nx = 0;
                    int ny = 0;
                    int nz = 0;
                    double travel = 0.0;

                    while (travel <= limit) {
                        Placement placement = voxels.get(PlanCompiler.packedKey(cx, cy, cz));
                        boolean selfCell = cameraInSolid
                                && cx == cameraCellX && cy == cameraCellY && cz == cameraCellZ;
                        if (!selfCell && placement != null
                                && !"minecraft:air".equals(placement.block)) {
                            double distance = baseT + travel;
                            double hx = ox + dx * (travel + 1e-5);
                            double hy = oy + dy * (travel + 1e-5);
                            double hz = oz + dz * (travel + 1e-5);
                            double u;
                            double v;
                            if (nx != 0) {
                                u = fract(hz);
                                v = fract(hy);
                            } else if (ny != 0) {
                                u = fract(hx);
                                v = fract(hz);
                            } else {
                                u = fract(hx);
                                v = fract(hy);
                            }
                            rgb = shade(colorOf(placement.block, ny), nx, ny, nz, distance,
                                    u, v, cx, cy, cz, distance * worldPerPixel);
                            hitMaterial = placement.block;
                            break;
                        }
                        double next = Math.min(maxX, Math.min(maxY, maxZ));
                        if (!Double.isFinite(next) || next > limit) {
                            break;
                        }
                        travel = next;
                        if (maxX <= maxY && maxX <= maxZ) {
                            cx += stepX;
                            maxX += deltaX;
                            nx = -stepX;
                            ny = 0;
                            nz = 0;
                        } else if (maxY <= maxZ) {
                            cy += stepY;
                            maxY += deltaY;
                            nx = 0;
                            ny = -stepY;
                            nz = 0;
                        } else {
                            cz += stepZ;
                            maxZ += deltaZ;
                            nx = 0;
                            ny = 0;
                            nz = -stepZ;
                        }
                    }
                }

                if (rgb < 0 && dy < -EPS) {
                    // 没打到方块但射线朝下：与地面平面求交，画出带明显网格的占位地面
                    double t = (groundY - startY) / dy;
                    if (t > 0 && t < maxDist) {
                        double gx = startX + dx * t;
                        double gz = startZ + dz * t;
                        int checker = ((floor(gx) + floor(gz)) & 1) == 0 ? 26 : -26;
                        double edge = Math.min(
                                Math.min(fract(gx), 1.0 - fract(gx)),
                                Math.min(fract(gz), 1.0 - fract(gz)));
                        rgb = fog(pose.groundGrid && edge < 0.045
                                ? GROUND_GRID : shift(GROUND, checker), t);
                    }
                }
                if (rgb < 0) {
                    double up = Math.max(0.0, Math.min(1.0, dy * 1.4 + 0.35));
                    rgb = blend(SKY_HORIZON, SKY_TOP, up);
                    frame.skyPixels++;
                } else {
                    frame.blockPixels++;
                    if (hitMaterial != null) {
                        frame.materials.merge(hitMaterial, 1, Integer::sum);
                    }
                }
                image.setRGB(px, py, rgb);
            }
        }

        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            ImageIO.write(image, "png", out);
            frame.png = out.toByteArray();
        }
        return frame;
    }

    private static double fract(double value) {
        return value - Math.floor(value);
    }

    /**
     * 按方块 id 与命中面取色。
     *
     * block_index.json 的 hex 是整张贴图的平均色：草方块偏土褐、树叶偏灰。
     * 游戏里这些方块顶面与侧面异色，或随生物群系着色，直接拿平均色画会让草地看起来没铺。
     * 离屏渲染拿不到真实 biome，用典型草原色近似，保证截图里草地是绿的、树冠不是灰的。
     */
    private static int colorOf(String blockId, int ny) {
        String id = blockId == null ? "" : blockId.toLowerCase(Locale.ROOT);
        if (ny > 0 && isGrassTop(id)) {
            return GRASS_TINT;
        }
        if (isFoliage(id)) {
            return FOLIAGE_TINT;
        }
        if (id.equals("minecraft:water")) {
            return WATER_TINT;
        }
        BlockIndex.BlockInfo info = BlockIndex.get().get(blockId);
        String hex = info == null ? null : info.hex;
        if (hex == null || hex.length() < 7 || !hex.startsWith("#")) {
            return FALLBACK;
        }
        try {
            return Integer.parseInt(hex.substring(1, 7), 16);
        } catch (NumberFormatException e) {
            return FALLBACK;
        }
    }

    /** 顶面与侧面异色的方块：只有朝上的那一面才是草地绿。 */
    private static boolean isGrassTop(String id) {
        return id.equals("minecraft:grass_block") || id.equals("minecraft:mycelium")
                || id.equals("minecraft:podzol") || id.endsWith("_nylium")
                || id.equals("minecraft:dirt_path");
    }

    /** 随生物群系着色的植物；樱花树叶本身是粉色贴图，保留索引色。 */
    private static boolean isFoliage(String id) {
        if (id.equals("minecraft:cherry_leaves")) {
            return false;
        }
        return id.endsWith("_leaves") || id.equals("minecraft:grass")
                || id.equals("minecraft:tall_grass") || id.equals("minecraft:fern")
                || id.equals("minecraft:large_fern") || id.equals("minecraft:vine")
                || id.equals("minecraft:lily_pad") || id.equals("minecraft:sugar_cane");
    }

    /** 按命中面法线打光；近处再叠加方块边缘线与确定性细纹理，让方砖 / 窗格 / 台阶的轮廓可辨。 */
    private static int shade(int base, int nx, int ny, int nz, double distance,
                             double u, double v, int cx, int cy, int cz, double worldPerPixel) {
        double light = 0.62 + 0.30 * Math.max(0.0, ny)
                + 0.12 * Math.max(0.0, -nx) + 0.08 * Math.max(0.0, nz);
        if (ny < 0) {
            light -= 0.16;
        }
        if (worldPerPixel < 0.12) {
            double edge = Math.min(Math.min(u, 1.0 - u), Math.min(v, 1.0 - v));
            double edgeWidth = Math.max(0.012, Math.min(0.06, worldPerPixel * 0.6));
            if (edge < edgeWidth) {
                light *= 0.72;
            }
            int subU = (int) Math.min(3.0, u * 4.0);
            int subV = (int) Math.min(3.0, v * 4.0);
            light *= 1.0 + noise(cx, cy, cz, subU, subV, nx + 3 * ny + 7 * nz) * 0.05;
        }
        light = Math.max(0.28, Math.min(1.15, light));
        int r = clampByte((int) (((base >> 16) & 0xFF) * light));
        int g = clampByte((int) (((base >> 8) & 0xFF) * light));
        int b = clampByte((int) ((base & 0xFF) * light));
        return fog((r << 16) | (g << 8) | b, distance);
    }

    /** 确定性 hash 噪声：不引入随机数，保证同一机位每次拍出的照片完全一致。 */
    private static double noise(int x, int y, int z, int u, int v, int face) {
        long h = x * 374761393L + y * 668265263L + z * 2246822519L
                + u * 3266489917L + v * 1013904223L + face * 2654435761L;
        h ^= h >>> 13;
        h *= 1274126177L;
        h ^= h >>> 16;
        return ((h & 0xFFFF) / 32768.0) - 1.0;
    }

    /** 雾比旧版更淡：远景的材质和轮廓不能被冲成一片灰白。 */
    private static int fog(int rgb, double distance) {
        double factor = 1.0 / (1.0 + distance * 0.006);
        factor = Math.max(0.78, factor);
        int r = (int) (((rgb >> 16) & 0xFF) * factor + SKY_HORIZON_R() * (1 - factor));
        int g = (int) (((rgb >> 8) & 0xFF) * factor + SKY_HORIZON_G() * (1 - factor));
        int b = (int) ((rgb & 0xFF) * factor + SKY_HORIZON_B() * (1 - factor));
        return (clampByte(r) << 16) | (clampByte(g) << 8) | clampByte(b);
    }

    private static int SKY_HORIZON_R() { return (SKY_HORIZON >> 16) & 0xFF; }
    private static int SKY_HORIZON_G() { return (SKY_HORIZON >> 8) & 0xFF; }
    private static int SKY_HORIZON_B() { return SKY_HORIZON & 0xFF; }

    private static int shift(int rgb, int delta) {
        return (clampByte(((rgb >> 16) & 0xFF) + delta) << 16)
                | (clampByte(((rgb >> 8) & 0xFF) + delta) << 8)
                | clampByte((rgb & 0xFF) + delta);
    }

    private static int blend(int low, int high, double t) {
        int r = (int) (((low >> 16) & 0xFF) * (1 - t) + ((high >> 16) & 0xFF) * t);
        int g = (int) (((low >> 8) & 0xFF) * (1 - t) + ((high >> 8) & 0xFF) * t);
        int b = (int) ((low & 0xFF) * (1 - t) + (high & 0xFF) * t);
        return (r << 16) | (g << 8) | b;
    }

    private static int clampByte(int value) {
        return Math.max(0, Math.min(255, value));
    }

    private static int floor(double value) {
        return (int) Math.floor(value);
    }
}
