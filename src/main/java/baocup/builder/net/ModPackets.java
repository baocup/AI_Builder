package baocup.builder.net;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import baocup.builder.BuilderMod;

/**
 * 为了把协议面积压到最小，客户端与服务端之间只保留两个载荷：
 * C2SAction（带动作名的 JSON 指令）和 S2CState（完整 GUI 状态快照）。
 */
public final class ModPackets {
    /**
     * Minecraft 1.21.1 的 ClientboundCustomPayloadPacket 硬上限是 1048576 字节，
     * 留一点余量给 packet id 与其它字段。这里只是协议物理极限，不是内容限制。
     */
    public static final int MAX_JSON = 1000000;

    private ModPackets() {
    }

    public record C2SAction(String taskId, String action, String data) implements CustomPacketPayload {
        public static final Type<C2SAction> TYPE =
                new Type<>(ResourceLocation.fromNamespaceAndPath(BuilderMod.MODID, "action"));
        public static final StreamCodec<RegistryFriendlyByteBuf, C2SAction> STREAM_CODEC = StreamCodec.of(
                (buf, value) -> {
                    buf.writeUtf(value.taskId, 128);
                    buf.writeUtf(value.action, 64);
                    buf.writeUtf(value.data == null ? "" : value.data, MAX_JSON);
                },
                buf -> new C2SAction(buf.readUtf(128), buf.readUtf(64), buf.readUtf(MAX_JSON)));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    /**
     * 客户端建模完成后向服务端提交方块。几何编译、VoxelWorld、拍照渲染全部在客户端完成，
     * 服务端只负责把最终方块写进世界，因此多个玩家 / 多个 Agent 天然互不干扰。
     *
     * payload = {"bounds":[x1,y1,z1,x2,y2,z2],"blocks":[[x,y,z,"minecraft:state"],...]}，
     * 每包不超过 MAX_JSON，客户端按批发送，last 标记最后一批。
     */
    public record C2SCommit(String taskId, String dimension, String payload, boolean last)
            implements CustomPacketPayload {
        public static final Type<C2SCommit> TYPE =
                new Type<>(ResourceLocation.fromNamespaceAndPath(BuilderMod.MODID, "commit"));
        public static final StreamCodec<RegistryFriendlyByteBuf, C2SCommit> STREAM_CODEC = StreamCodec.of(
                (buf, value) -> {
                    buf.writeUtf(value.taskId, 128);
                    buf.writeUtf(value.dimension, 128);
                    buf.writeUtf(value.payload == null ? "{}" : value.payload, MAX_JSON);
                    buf.writeBoolean(value.last);
                },
                buf -> new C2SCommit(buf.readUtf(128), buf.readUtf(128),
                        buf.readUtf(MAX_JSON), buf.readBoolean()));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }

    public record S2CState(String taskId, boolean open, String json) implements CustomPacketPayload {
        public static final Type<S2CState> TYPE =
                new Type<>(ResourceLocation.fromNamespaceAndPath(BuilderMod.MODID, "state"));
        public static final StreamCodec<RegistryFriendlyByteBuf, S2CState> STREAM_CODEC = StreamCodec.of(
                (buf, value) -> {
                    buf.writeUtf(value.taskId, 128);
                    buf.writeBoolean(value.open);
                    buf.writeUtf(value.json == null ? "{}" : value.json, MAX_JSON);
                },
                buf -> new S2CState(buf.readUtf(128), buf.readBoolean(), buf.readUtf(MAX_JSON)));

        @Override
        public Type<? extends CustomPacketPayload> type() {
            return TYPE;
        }
    }
}
