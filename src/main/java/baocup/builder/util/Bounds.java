package baocup.builder.util;

import net.minecraft.core.BlockPos;

/** 选区包围盒。全部使用 public 字段，便于 Gson 直接序列化到记忆文件。 */
public final class Bounds {
    public int x1;
    public int y1;
    public int z1;
    public int x2;
    public int y2;
    public int z2;

    /** Gson 用 */
    public Bounds() {
    }

    public Bounds(int x1, int y1, int z1, int x2, int y2, int z2) {
        this.x1 = x1;
        this.y1 = y1;
        this.z1 = z1;
        this.x2 = x2;
        this.y2 = y2;
        this.z2 = z2;
        normalize();
    }

    public static Bounds of(BlockPos a, BlockPos b) {
        return new Bounds(a.getX(), a.getY(), a.getZ(), b.getX(), b.getY(), b.getZ());
    }

    public static Bounds fromArray(int[] a) {
        if (a == null || a.length != 6) {
            return null;
        }
        return new Bounds(a[0], a[1], a[2], a[3], a[4], a[5]);
    }

    public int[] toArray() {
        return new int[] { x1, y1, z1, x2, y2, z2 };
    }

    public Bounds copy() {
        return new Bounds(x1, y1, z1, x2, y2, z2);
    }

    public void normalize() {
        if (x1 > x2) {
            int t = x1;
            x1 = x2;
            x2 = t;
        }
        if (y1 > y2) {
            int t = y1;
            y1 = y2;
            y2 = t;
        }
        if (z1 > z2) {
            int t = z1;
            z1 = z2;
            z2 = t;
        }
    }

    public int minX() {
        return x1;
    }

    public int minY() {
        return y1;
    }

    public int minZ() {
        return z1;
    }

    public int maxX() {
        return x2;
    }

    public int maxY() {
        return y2;
    }

    public int maxZ() {
        return z2;
    }

    public int sizeX() {
        return x2 - x1 + 1;
    }

    public int sizeY() {
        return y2 - y1 + 1;
    }

    public int sizeZ() {
        return z2 - z1 + 1;
    }

    public long volume() {
        return (long) sizeX() * sizeY() * sizeZ();
    }

    public boolean contains(int x, int y, int z) {
        return x >= x1 && x <= x2 && y >= y1 && y <= y2 && z >= z1 && z <= z2;
    }

    /** 把任意长方体裁剪进选区；完全在选区外时返回 null。 */
    public int[] clamp(int ax1, int ay1, int az1, int ax2, int ay2, int az2) {
        int loX = Math.max(x1, Math.min(ax1, ax2));
        int hiX = Math.min(x2, Math.max(ax1, ax2));
        int loY = Math.max(Math.max(y1, -64), Math.min(ay1, ay2));
        int hiY = Math.min(Math.min(y2, 319), Math.max(ay1, ay2));
        int loZ = Math.max(z1, Math.min(az1, az2));
        int hiZ = Math.min(z2, Math.max(az1, az2));
        if (loX > hiX || loY > hiY || loZ > hiZ) {
            return null;
        }
        return new int[] { loX, loY, loZ, hiX, hiY, hiZ };
    }

    public BlockPos center() {
        return new BlockPos((x1 + x2) / 2, y1, (z1 + z2) / 2);
    }

    public String display() {
        return "(" + x1 + "," + y1 + "," + z1 + ") -> (" + x2 + "," + y2 + "," + z2 + ")  "
                + sizeX() + "x" + sizeY() + "x" + sizeZ();
    }
}
