package com.gena.brokensignal;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** Server -> client: run a client-side ("computer") horror effect. */
public record MetaPayload(String action, String arg) implements CustomPacketPayload {
    public static final Type<MetaPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(BrokenSignal.MODID, "meta"));

    public static final StreamCodec<ByteBuf, MetaPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, MetaPayload::action,
            ByteBufCodecs.STRING_UTF8, MetaPayload::arg,
            MetaPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /** Only ever called on the client; the client class is loaded lazily inside the lambda. */
    public static void handle(MetaPayload msg, IPayloadContext ctx) {
        ctx.enqueueWork(() -> com.gena.brokensignal.client.ClientMeta.handle(msg.action(), msg.arg()));
    }
}
