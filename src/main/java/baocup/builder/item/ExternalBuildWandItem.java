package baocup.builder.item;

import java.util.List;

import baocup.builder.util.Bounds;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;

/**
 * 外部 Agent 建造手杖：只负责「选坐标」和「被外部 Agent 通过 MCP 读取 taskId」。
 *
 * 它完全不参与游戏内 LLM Agent 的启动、Plan 对话、语义层与视觉审查，
 * 因此用外部 Agent 建造时不会消耗任何游戏内模型 token。
 */
public class ExternalBuildWandItem extends BuildCommanderItem {
    public ExternalBuildWandItem(Properties properties) {
        super(properties);
    }

    @Override
    protected String linkMode() {
        return "external";
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> lines,
                                TooltipFlag flag) {
        lines.add(Component.literal("§b外部 Agent 建造手杖 §7（不调用游戏内模型）"));
        lines.add(Component.literal("§71. 左键两次设定建造范围起终点"));
        lines.add(Component.literal("§72. 保持手持，去外部 Agent 对话（MCP 会读到此任务 id）"));
        lines.add(Component.literal("§73. 右键打开本页面查看外部 Agent 的动作日志"));
        CompoundTag tag = ItemData.read(stack);
        BlockPos p1 = ItemData.point(tag, ItemData.P1);
        BlockPos p2 = ItemData.point(tag, ItemData.P2);
        if (p1 != null) {
            lines.add(Component.literal("§8起点 " + format(p1)));
        }
        if (p2 != null) {
            lines.add(Component.literal("§a终点 " + format(p2) + "  " + Bounds.of(p1, p2).display()));
        }
        String id = ItemData.taskId(stack);
        if (id == null) {
            lines.add(Component.literal("§8任务 id：首次使用时分配"));
        } else {
            lines.add(Component.literal("§8任务 id：" + id.substring(0, Math.min(13, id.length())) + "…"));
        }
    }
}
