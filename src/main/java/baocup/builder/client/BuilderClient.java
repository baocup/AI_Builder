package baocup.builder.client;

import baocup.builder.BuilderMod;
import baocup.builder.net.ModPackets;
import baocup.builder.util.Json;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.fml.ModContainer;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;

/** 客户端入口：注入 S2C 回调、注册左键选点监听，并在本地启动外部 Agent 的 MCP 接口。 */
@Mod(value = BuilderMod.MODID, dist = Dist.CLIENT)
public class BuilderClient {
    /** 配置要等注册完成后才可读，所以推迟到第一次客户端 tick 再启动本地宿主。 */
    private static boolean hostStarted;

    public BuilderClient(ModContainer container) {
        BuilderMod.clientStateHandler = BuilderClient::onState;
        BuilderMod.clientQueryHandler = ClientBuildHost.INSTANCE::onQueryResult;
        NeoForge.EVENT_BUS.register(new SelectionInput());
        NeoForge.EVENT_BUS.addListener(BuilderClient::onClientTick);
    }

    private static void onClientTick(ClientTickEvent.Post event) {
        if (!hostStarted) {
            hostStarted = true;
            ClientBuildHost.INSTANCE.init();
        }
        ClientBuildHost.INSTANCE.tick();
    }

    private static void onState(ModPackets.S2CState payload) {
        Minecraft mc = Minecraft.getInstance();
        ClientState state;
        try {
            state = Json.GSON.fromJson(payload.json(), ClientState.class);
        } catch (Exception e) {
            state = null;
        }
        if (state == null) {
            state = new ClientState();
        }
        if (state.taskId == null || state.taskId.isBlank()) {
            state.taskId = payload.taskId();
        }
        final ClientState snapshot = state;
        mc.execute(() -> {
            if (payload.open() || !(mc.screen instanceof BuilderScreen screen)
                    || !screen.taskId.equals(snapshot.taskId)) {
                mc.setScreen(new BuilderScreen(snapshot));
            } else {
                screen.applyState(snapshot);
            }
        });
    }
}
