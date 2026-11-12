package baocup.builder.agent;

/**
 * 一台独立的虚拟相机。
 *
 * 它不是玩家视角、也不是截取玩家屏幕：位置/朝向完全由建筑包围盒或 AI 指定的绝对坐标决定，
 * 渲染时由 VisualRenderer 从这台相机的视锥对编译后的方块图做离屏光线投射。
 */
public final class CameraPose {
    public String id = "";
    public String name = "";
    public double x;
    public double y;
    public double z;
    /** 度，Minecraft 约定：yaw=0 朝 +Z，pitch 正值朝下（与 Entity.calculateViewVector 一致）。 */
    public float yaw;
    public float pitch;
    public float fov = 70.0F;
    public String reason = "";

    public CameraPose() {
    }

    public CameraPose(String id, String name, double x, double y, double z,
                      float yaw, float pitch, String reason) {
        this.id = id;
        this.name = name;
        this.x = x;
        this.y = y;
        this.z = z;
        this.yaw = yaw;
        this.pitch = pitch;
        this.reason = reason == null ? "" : reason;
    }

    /** 让相机看向给定目标点。 */
    public static CameraPose lookingAt(String id, String name, double x, double y, double z,
                                       double tx, double ty, double tz, String reason) {
        double dx = tx - x;
        double dy = ty - y;
        double dz = tz - z;
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
        float pitch = (float) Math.toDegrees(-Math.atan2(dy, horizontal));
        return new CameraPose(id, name, x, y, z, yaw, pitch, reason);
    }

    public String describe() {
        return id + "「" + name + "」(" + Math.round(x) + "," + Math.round(y)
                + "," + Math.round(z) + ") yaw=" + Math.round(yaw)
                + " pitch=" + Math.round(pitch) + " fov=" + Math.round(fov)
                + (reason.isEmpty() ? "" : " 目的=" + reason);
    }
}
