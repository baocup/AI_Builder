package baocup.builder.item;

import java.util.UUID;
import java.util.function.Consumer;

import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.component.CustomData;

/** 物品上的数据统一放在 CUSTOM_DATA 组件里，避免额外注册 DataComponentType。 */
public final class ItemData {
    public static final String TASK_ID = "builder_task_id";
    public static final String P1 = "p1";
    public static final String P2 = "p2";
    public static final String DIM = "dim";
    public static final String LAST_TICK = "last_select_tick";

    private ItemData() {
    }

    public static CompoundTag read(ItemStack stack) {
        return stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
    }

    public static void update(ItemStack stack, Consumer<CompoundTag> updater) {
        CustomData.update(DataComponents.CUSTOM_DATA, stack, updater);
    }

    public static String taskId(ItemStack stack) {
        String id = read(stack).getString(TASK_ID);
        return id == null || id.isEmpty() ? null : id;
    }

    /** 每个物品栈在第一次使用时获得一个全新的 UUID；合成时也会预分配，因此彼此不会串记忆。 */
    public static String ensureTaskId(ItemStack stack) {
        String existing = taskId(stack);
        if (existing != null) {
            return existing;
        }
        String fresh = UUID.randomUUID().toString();
        update(stack, tag -> tag.putString(TASK_ID, fresh));
        return fresh;
    }

    public static BlockPos point(CompoundTag tag, String key) {
        if (!tag.contains(key)) {
            return null;
        }
        int[] p = tag.getIntArray(key);
        return p.length == 3 ? new BlockPos(p[0], p[1], p[2]) : null;
    }

    public static void putPoint(CompoundTag tag, String key, BlockPos pos) {
        tag.putIntArray(key, new int[] { pos.getX(), pos.getY(), pos.getZ() });
    }
}
