package baocup.builder.agent;

import java.util.List;

/** 构件注册表的扩展点：把「一格意图」展开成一到多格带完整状态的放置单元。 */
public interface BlockComponent {
    boolean matches(String blockId);

    /** 事实层类别，如 门 / 床 / 楼梯 / 开口 / 家具。 */
    String category();

    /** 组排序基准；数值大的构件组会在普通方块之后放置。 */
    int placementOrder();

    /** 是否需要下方实心支撑（门 / 床为 true）。 */
    boolean requiresSupport();

    List<Placement> expand(ComponentRequest request);
}
