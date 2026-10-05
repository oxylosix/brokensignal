package dev.theunquiet.game;

import dev.theunquiet.UnquietConfig;
import dev.theunquiet.core.api.EventContext;
import dev.theunquiet.core.api.HorrorEvent;
import dev.theunquiet.core.api.HorrorEventRegistry;
import dev.theunquiet.core.api.EventCategory;
import dev.theunquiet.core.data.HorrorMemory;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.resources.ResourceLocation;

public final class EventDirector {
    private static final long FIRST_EVENT_AFTER_TICKS = 72_000L;
    private static final long DECISION_INTERVAL = 1_200L;
    private static final long GLOBAL_COOLDOWN = 6_000L;

    private EventDirector() {
    }

    public static boolean force(ServerPlayer player, ResourceLocation eventId) {
        HorrorEvent event = HorrorEventRegistry.get(eventId);
        if (event == null) {
            return false;
        }
        ServerLevel level = player.serverLevel();
        ServerLevel overworld = level.getServer().overworld();
        HorrorMemory memory = HorrorMemory.get(overworld);
        long now = overworld.getGameTime();
        EventContext context = new EventContext(
                level, player, memory, now, memory.activeTicks(player.getUUID()), player.getRandom());
        event.action().accept(context);
        memory.recordEvent(player.getUUID(), event.id().toString(), now);
        return true;
    }

    public static void tick(MinecraftServer server) {
        ServerLevel overworld = server.overworld();
        long now = overworld.getGameTime();
        if (now % 20 != 0) {
            return;
        }

        HorrorMemory memory = HorrorMemory.get(overworld);
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            memory.addActiveTicks(player.getUUID(), 20);
            if (!UnquietConfig.HORROR_ENABLED.get()
                    || memory.activeTicks(player.getUUID()) < FIRST_EVENT_AFTER_TICKS
                    || now % DECISION_INTERVAL != 0) {
                continue;
            }
            if (!eligibleForNewEvent(memory, player, now)) {
                continue;
            }
            double intensity = UnquietConfig.INTENSITY.get();
            if (intensity <= 0 || player.getRandom().nextDouble() >= 0.006 * intensity) {
                continue;
            }

            EventContext context = new EventContext(
                    player.serverLevel(), player, memory, now, memory.activeTicks(player.getUUID()), player.getRandom());
            List<HorrorEvent> eligible = new ArrayList<>();
            int totalWeight = 0;
            for (HorrorEvent event : HorrorEventRegistry.all()) {
                if (event.minimumPlayTicks() > context.activePlayTicks()
                        || isCoolingDown(memory, player, event, now)
                        || !event.condition().test(context)
                        || (event.category() == EventCategory.ENTITY && !UnquietConfig.ENTITY_EVENTS.get())
                        || (event.category() == EventCategory.MULTIPLAYER && !UnquietConfig.MULTIPLAYER_EVENTS.get())
                        || (!UnquietConfig.RARE_EVENTS.get() && event.rarity().ordinal() >= 2)) {
                    continue;
                }
                eligible.add(event);
                totalWeight += event.rarity().weight();
            }
            if (totalWeight == 0) {
                continue;
            }
            int choice = player.getRandom().nextInt(totalWeight);
            for (HorrorEvent event : eligible) {
                choice -= event.rarity().weight();
                if (choice < 0) {
                    event.action().accept(context);
                    memory.recordEvent(player.getUUID(), event.id().toString(), now);
                    break;
                }
            }
        }
    }

    private static boolean eligibleForNewEvent(HorrorMemory memory, ServerPlayer player, long now) {
        long lastEvent = memory.lastGlobalEventTick(player.getUUID());
        return lastEvent == Long.MIN_VALUE || now - lastEvent >= GLOBAL_COOLDOWN;
    }

    private static boolean isCoolingDown(HorrorMemory memory, ServerPlayer player, HorrorEvent event, long now) {
        long lastSeen = memory.lastSeenAt(player.getUUID(), event.id().toString());
        return lastSeen != Long.MIN_VALUE && now - lastSeen < event.cooldownTicks();
    }
}
