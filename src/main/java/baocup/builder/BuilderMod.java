package baocup.builder;

import java.util.function.Consumer;

import org.slf4j.Logger;

import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.logging.LogUtils;

import baocup.builder.agent.AgentManager;
import baocup.builder.net.ModPackets;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.CreativeModeTabs;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.config.ModConfig;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.BuildCreativeModeTabContentsEvent;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;

@Mod(BuilderMod.MODID)
public class BuilderMod {
    public static final String MODID = "builder";
    public static final Logger LOGGER = LogUtils.getLogger();

    /**
     * 由客户端专属类注入。这样公共注册代码无需引用任何客户端类，专用服务器也不会加载它们。
     */
    public static volatile Consumer<ModPackets.S2CState> clientStateHandler = null;

    public BuilderMod(IEventBus modEventBus, ModContainer container) {
        ModItems.ITEMS.register(modEventBus);

        modEventBus.addListener(this::registerPayloads);
        modEventBus.addListener(this::addCreative);
        container.registerConfig(ModConfig.Type.COMMON, BuilderConfig.SPEC);
        NeoForge.EVENT_BUS.register(this);
    }

    private void registerPayloads(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("1");
        registrar.playToServer(ModPackets.C2SAction.TYPE, ModPackets.C2SAction.STREAM_CODEC,
                (payload, context) -> AgentManager.get().handleAction(context.player(), payload));
        registrar.playToServer(ModPackets.C2SCommit.TYPE, ModPackets.C2SCommit.STREAM_CODEC,
                (payload, context) -> AgentManager.get()
                        .applyClientCommit(context.player(), payload));
        registrar.playToClient(ModPackets.S2CState.TYPE, ModPackets.S2CState.STREAM_CODEC,
                (payload, context) -> {
                    Consumer<ModPackets.S2CState> handler = clientStateHandler;
                    if (handler != null) {
                        handler.accept(payload);
                    }
                });
    }

    private void addCreative(BuildCreativeModeTabContentsEvent event) {
        if (event.getTabKey() == CreativeModeTabs.TOOLS_AND_UTILITIES) {
            event.accept(ModItems.BUILD_COMMANDER);
            event.accept(ModItems.EXTERNAL_BUILD_WAND);
        }
    }

    /** /builder approve [taskId] | deny [taskId] | status：坐标授权只有玩家能批准。 */
    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        event.getDispatcher().register(Commands.literal("builder")
                .then(Commands.literal("approve")
                        .executes(context -> approve(context, ""))
                        .then(Commands.argument("taskId", StringArgumentType.string())
                                .executes(context -> approve(context,
                                        StringArgumentType.getString(context, "taskId")))))
                .then(Commands.literal("deny")
                        .executes(context -> deny(context, ""))
                        .then(Commands.argument("taskId", StringArgumentType.string())
                                .executes(context -> deny(context,
                                        StringArgumentType.getString(context, "taskId")))))
                .then(Commands.literal("status").executes(context -> {
                    String message = AgentManager.get().mcpStatus();
                    context.getSource().sendSuccess(() -> Component.literal(message), false);
                    return 1;
                })));
    }

    private static int approve(CommandContext<CommandSourceStack> context, String taskId)
            throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        String message = AgentManager.get().approveBounds(player, taskId);
        context.getSource().sendSuccess(() -> Component.literal("§a" + message), false);
        return 1;
    }

    private static int deny(CommandContext<CommandSourceStack> context, String taskId)
            throws CommandSyntaxException {
        ServerPlayer player = context.getSource().getPlayerOrException();
        String message = AgentManager.get().denyBounds(player, taskId);
        context.getSource().sendSuccess(() -> Component.literal("§c" + message), false);
        return 1;
    }

    @SubscribeEvent
    public void onServerStarting(ServerStartingEvent event) {
        AgentManager.init(event.getServer());
    }

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        AgentManager.shutdown();
    }

    @SubscribeEvent
    public void onServerTick(ServerTickEvent.Post event) {
        AgentManager.get().tick(event.getServer());
    }
}
