package com.gena.brokensignal.ext;

import com.gena.brokensignal.BrokenSignal;
import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Client -> server: something happened outside the game window. Kinds are a closed list
 * (see Outside#onReport); args are short, sanitised and never contain anything from the
 * user's system other than what they did inside the companion's own windows/folder.
 */
public record CompanionPayload(String kind, String arg) implements CustomPacketPayload {
    public static final Type<CompanionPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(BrokenSignal.MODID, "outside"));

    public static final StreamCodec<ByteBuf, CompanionPayload> STREAM_CODEC = StreamCodec.composite(
            ByteBufCodecs.stringUtf8(32), CompanionPayload::kind,
            ByteBufCodecs.stringUtf8(96), CompanionPayload::arg,
            CompanionPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
