package baocup.builder.item;

import java.util.List;

import baocup.builder.agent.AgentManager;
import baocup.builder.memory.BuildMemory;
import baocup.builder.util.Bounds;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;

/**
 * 建造指令物品。物品内部只保存一个 UUID，是这个建造任务全部记忆的指针。
 * 每合成一个新物品都会分配新的 UUID，因此不会与已有任务的坐标 / Agent 记忆冲突。
 */
public class BuildCommanderItem extends Item {
    public BuildCommanderItem(Properties properties) {
        super(properties);
    }

    @Override
    public void onCraftedBy(ItemStack stack, Level level, Player player) {
        super.onCraftedBy(stack, level, player);
        if (!level.isClientSide) {
            ItemData.ensureTaskId(stack);
        }
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        open(context.getLevel(), context.getPlayer(), context.getItemInHand());
        return InteractionResult.sidedSuccess(context.getLevel().isClientSide);
    }

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        open(level, player, stack);
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
    }

    protected void open(Level level, Player player, ItemStack stack) {
        if (level.isClientSide || !(player instanceof ServerPlayer serverPlayer)) {
            return;
        }
        AgentManager.get().openGui(serverPlayer, ItemData.ensureTaskId(stack), linkMode());
    }

    /** 该物品使用的建造链路：internal = 游戏内 LLM Agent，external = 外部 Agent（MCP）。 */
    protected String linkMode() {
        return "internal";
    }

    @Override
    public void appendHoverText(ItemStack stack, TooltipContext context, List<Component> lines, TooltipFlag flag) {
        String id = ItemData.taskId(stack);
        lines.add(Component.literal("§7右键打开建造 GUI：建造 / 修改 / Plan 对话 / 视觉检查"));
        lines.add(Component.literal("§7左键两次：用「你自己的坐标」设定建造范围起终点"));
        CompoundTag selection = ItemData.read(stack);
        BlockPos p1 = ItemData.point(selection, ItemData.P1);
        BlockPos p2 = ItemData.point(selection, ItemData.P2);
        if (p1 != null) {
            lines.add(Component.literal("§8起点 " + format(p1)));
        }
        if (p2 != null) {
            lines.add(Component.literal("§a终点 " + format(p2) + "  " + Bounds.of(p1, p2).display()));
        }
        if (id == null) {
            lines.add(Component.literal("§8新物品：首次使用时会绑定一份独立记忆"));
            return;
        }
        lines.add(Component.literal("§8任务指针：" + id.substring(0, Math.min(13, id.length())) + "…"));
        CompoundTag tag = ItemData.read(stack);
        // 摘要由服务端回写到物品上，因此在多人游戏的客户端也能看到任务状态
        if (tag.contains("ptr_version")) {
            lines.add(Component.literal("§7记忆 v" + tag.getInt("ptr_version") + " · " + tag.getString("ptr_status")));
            lines.add(Component.literal("§7模型调用 " + tag.getInt("ptr_calls") + " 次 · 已放置 "
                    + tag.getLong("ptr_blocks") + " 方块"));
            String brief = tag.getString("ptr_brief");
            if (!brief.isBlank()) {
                lines.add(Component.literal("§8最近目标：" + brief));
            }
        } else {
            BuildMemory memory = AgentManager.get().peekMemory(id);
            if (memory != null) {
                lines.add(Component.literal("§7版本 v" + memory.version + " · " + memory.status));
                lines.add(Component.literal("§7模型调用 " + memory.modelCalls + " 次 · 已放置 "
                        + memory.blocksPlacedTotal + " 方块"));
            } else {
                lines.add(Component.literal("§8记忆将在首次打开 GUI 时创建"));
            }
        }
    }

    protected static String format(BlockPos pos) {
        return "(" + pos.getX() + ", " + pos.getY() + ", " + pos.getZ() + ")";
    }
}
