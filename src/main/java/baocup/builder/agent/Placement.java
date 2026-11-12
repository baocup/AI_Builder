package baocup.builder.agent;

/**
 * 一个待放置单元。故意做成纯 POJO，便于直接 gzip+JSON 落到本地记忆里。
 *
 * 除了坐标与方块 id，还携带：完整 BlockState 属性串、白名单 NBT、实体描述、多格构件组信息。
 * 旧版 gzip 存档只有 x/y/z/block，新增字段反序列化后为 null，统一由 normalize()/signature() 兜底。
 */
public final class Placement {
    public int x;
    public int y;
    public int z;
    /** 纯方块 id，如 minecraft:oak_door；实体占位用 minecraft:air。 */
    public String block;
    /** BlockState 属性串，如 "facing=north,half=lower,hinge=left"；空表示默认状态。 */
    public String state;
    /** 白名单 NBT 的 JSON 表示，空表示无 BlockEntity 数据。 */
    public String nbt;
    /** 实体描述 JSON，非空时由实体通道处理而非 setBlock。 */
    public String entity;
    /** 需要移除的实体 UUID（差量删除通道），空表示无。 */
    public String entityRemove;
    /** 多格构件组标识，单格构件为空。 */
    public String groupId;
    /** 组内放置顺序：数值小的先放（支撑块 → 主块 → 从属块）。 */
    public int order;

    public Placement() {
    }

    public Placement(int x, int y, int z, String block) {
        this(x, y, z, block, "");
    }

    public Placement(int x, int y, int z, String block, String state) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.block = block;
        this.state = state == null ? "" : state;
    }

    public long key() {
        return (((long) x & 0x3FFFFFF) << 38) | (((long) z & 0x3FFFFFF) << 12) | ((long) (y + 64) & 0xFFF);
    }

    /** 兼容旧存档：把 null 字段归零，并给实体一个由坐标派生的稳定标识。 */
    public void normalize() {
        if (block == null) block = "minecraft:air";
        if (state == null) state = "";
        if (nbt == null) nbt = "";
        if (entity == null) entity = "";
        if (entityRemove == null) entityRemove = "";
        if (groupId == null) groupId = "";
    }

    /** 交给 BlockIndex.toState() 的完整 id[属性] 形式。 */
    public String fullState() {
        String base = block == null ? "minecraft:air" : block;
        return state == null || state.isBlank() ? base : base + "[" + state + "]";
    }

    /** 差量比较用的完整签名：方块 + 状态 + NBT + 实体。只比 block 会漏掉「仅改朝向」的修改。 */
    public String signature() {
        return fullState() + "|" + nz(nbt) + "|" + nz(entity) + "|" + nz(entityRemove);
    }

    public boolean isEntity() {
        return entity != null && !entity.isBlank();
    }

    public boolean isEntityRemoval() {
        return entityRemove != null && !entityRemove.isBlank();
    }

    private static String nz(String value) {
        return value == null ? "" : value;
    }
}
