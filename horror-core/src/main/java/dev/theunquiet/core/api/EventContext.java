package dev.theunquiet.core.api;

import dev.theunquiet.core.data.HorrorMemory;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;

public record EventContext(
        ServerLevel level,
        ServerPlayer player,
        HorrorMemory memory,
        long worldTime,
        long activePlayTicks,
        RandomSource random) {
}
