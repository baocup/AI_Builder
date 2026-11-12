package baocup.builder.client;

import baocup.builder.item.BuildCommanderItem;
import baocup.builder.item.ItemData;
import baocup.builder.net.ModPackets;
import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.InputEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * 建造指令物品的左键选点。
 *
 * Minecraft 的「左键点空」事件只在客户端触发（LeftClickEmpty 明确说明服务端不知情），
 * 所以统一在客户端用攻击键触发事件做边沿检测，再发一个自定义包给服务端，
 * 由服务端用 player.blockPosition() 记录玩家自身坐标。
 *
 * 用 ClientTickEvent.Post 记录按键是否按住，过滤掉「按住左键持续挖掘」时每 tick 的重复事件，
 * 保证一次按下只算一次选点。
 */
public final class SelectionInput {
    private static boolean attackHeld;

    @SubscribeEvent
    public void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        attackHeld = mc.options.keyAttack.isDown();
    }

    @SubscribeEvent
    public void onAttack(InputEvent.InteractionKeyMappingTriggered event) {
        if (!event.isAttack()) {
            return;
        }
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen != null || mc.player == null || mc.getConnection() == null) {
            return;
        }
        Player player = mc.player;
        ItemStack main = player.getItemInHand(event.getHand());
        ItemStack off = player.getItemInHand(net.minecraft.world.InteractionHand.OFF_HAND);
        ItemStack held = main.getItem() instanceof BuildCommanderItem ? main
                : (off.getItem() instanceof BuildCommanderItem ? off : null);
        if (held == null) {
            return;
        }
        // 无论是否重复都取消原版攻击/挖掘，只保留挥手反馈，避免选点时敲掉方块
        event.setCanceled(true);
        event.setSwingHand(true);
        // 同一次按下里 startAttack 与 continueAttack 会各发一次事件；attackHeld 做边沿检测，
        // 只让第一次真正选点，后续（含按住不放的每 tick 重复）仅取消不发包。
        if (attackHeld) {
            return;
        }
        attackHeld = true;
        // UUID 由服务端权威分配（客户端不生成），避免创造模式拿到的物品出现两个不同的 taskId
        String taskId = ItemData.taskId(held);
        PacketDistributor.sendToServer(new ModPackets.C2SAction(
                taskId == null ? "" : taskId, "select_point", "{}"));
    }
}
