package baocup.builder.agent;

import java.util.Map;

import baocup.builder.util.Bounds;

/** 构件展开的入参。由 PlanCompiler 解析 zone 的 material / params 后组装。 */
public final class ComponentRequest {
    public final int x;
    public final int y;
    public final int z;
    /** 纯方块 id；实体占位时为 minecraft:air。 */
    public final String blockId;
    /** LLM 显式给出的状态串（id[props] 里的 props），可为空。 */
    public final String explicitState;
    /** 已由 OrientationResolver 解析好的水平朝向 north/south/east/west。 */
    public final String facing;
    /** 已由 OrientationResolver 解析好的轴 x/y/z。 */
    public final String axis;
    /** 多格构件组标识。 */
    public final String groupId;
    /** 白名单 NBT 的 JSON 字符串，可为空。 */
    public final String nbt;
    /** 非空表示这是一个实体放置请求。 */
    public final String entityType;
    /** 实体附加参数（facing / item / rotation / text 等）。 */
    public final Map<String, String> entityProps;
    public final Bounds bounds;

    public ComponentRequest(int x, int y, int z, String blockId, String explicitState,
                            String facing, String axis, String groupId, String nbt,
                            String entityType, Map<String, String> entityProps, Bounds bounds) {
        this.x = x;
        this.y = y;
        this.z = z;
        this.blockId = blockId;
        this.explicitState = explicitState == null ? "" : explicitState;
        this.facing = facing;
        this.axis = axis;
        this.groupId = groupId;
        this.nbt = nbt == null ? "" : nbt;
        this.entityType = entityType == null ? "" : entityType;
        this.entityProps = entityProps == null ? Map.of() : entityProps;
        this.bounds = bounds;
    }

    public boolean isEntity() {
        return !entityType.isBlank();
    }
}
