package com.gena.brokensignal.pc;

import com.gena.brokensignal.BrokenSignal;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Client -> server: a user action inside the computer screen. Validated server-side. */
public record ComputerActionPayload(String action, String arg) implements CustomPacketPayload {
    public static final Type<ComputerActionPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(BrokenSignal.MODID, "pc_action"));

    public static final StreamCodec<ByteBuf, ComputerActionPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.stringUtf8(64), ComputerActionPayload::action,
            ByteBufCodecs.stringUtf8(4096), ComputerActionPayload::arg,
            ComputerActionPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
