package com.gena.brokensignal.pc;

import com.gena.brokensignal.BrokenSignal;
import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/** Server -> client: full visible state of the computer (sent only on open and after an action). */
public record ComputerSyncPayload(BlockPos pos, CompoundTag data) implements CustomPacketPayload {
    public static final Type<ComputerSyncPayload> TYPE =
            new Type<>(ResourceLocation.fromNamespaceAndPath(BrokenSignal.MODID, "pc_sync"));

    public static final StreamCodec<ByteBuf, ComputerSyncPayload> STREAM_CODEC = StreamCodec.composite(
            BlockPos.STREAM_CODEC, ComputerSyncPayload::pos,
            ByteBufCodecs.COMPOUND_TAG, ComputerSyncPayload::data,
            ComputerSyncPayload::new);

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /** Client only; the screen class is resolved lazily inside the lambda. */
    public static void handle(ComputerSyncPayload msg, IPayloadContext ctx) {
        ctx.enqueueWork(() -> com.gena.brokensignal.client.ComputerScreen.receive(msg.pos(), msg.data()));
    }
}
