package com.gena.brokensignal;

import com.gena.brokensignal.ext.Echoes;
import com.gena.brokensignal.mimic.Habits;
import com.gena.brokensignal.mimic.MimicDirector;
import com.gena.brokensignal.mimic.Scenes;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.Container;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.neoforge.event.entity.EntityJoinLevelEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

/** v5 wiring: observation for the mimic system, ticking of mimics and scenes, cleanup. */
public final class V5Hooks {
    private V5Hooks() {}

    @SubscribeEvent
    public static void onServerTick(ServerTickEvent.Post event) {
        MimicDirector.tick(event.getServer());
        Scenes.tick(event.getServer());
    }

    @SubscribeEvent
    public static void onPlayerTick(PlayerTickEvent.Post event) {
        if (event.getEntity() instanceof ServerPlayer p && Config.ENABLED.get()) {
            Habits.tick(p);
        }
    }

    @SubscribeEvent
    public static void onBreak(BlockEvent.BreakEvent event) {
        if (event.getPlayer() instanceof ServerPlayer p) {
            Habits.action(p, Habits.BREAK, event.getPos());
            Habits.chop(p, event.getState().is(BlockTags.LOGS));
        }
    }

    @SubscribeEvent
    public static void onPlace(BlockEvent.EntityPlaceEvent event) {
        if (event.getEntity() instanceof ServerPlayer p) {
            Habits.action(p, Habits.PLACE, event.getPos());
        }
    }

    @SubscribeEvent
    public static void onFeed(PlayerInteractEvent.EntityInteract event) {
        if (event.getEntity() instanceof ServerPlayer p && event.getTarget() instanceof Animal a
                && a.isFood(event.getItemStack())) {
            Habits.fed(p, a.getUUID());
        }
    }

    @SubscribeEvent
    public static void onOpen(PlayerInteractEvent.RightClickBlock event) {
        if (!(event.getEntity() instanceof ServerPlayer p) || p.isShiftKeyDown()) {
            return;
        }
        BlockEntity be = p.level().getBlockEntity(event.getPos());
        if (be instanceof Container c) {
            Habits.action(p, Habits.OPEN, event.getPos());
            if (Config.ENABLED.get()) {
                Echoes.onOpen(p, event.getPos().immutable(), c);
            }
        }
    }

    @SubscribeEvent
    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        if (event.getEntity() instanceof ServerPlayer p) {
            MimicDirector.stop(p);
        }
    }

    @SubscribeEvent
    public static void onStopping(ServerStoppingEvent event) {
        MimicDirector.clearAll();
    }

    /** Nothing the mod spawned temporarily may come back from disk after a crash or restart. */
    @SubscribeEvent
    public static void onJoin(EntityJoinLevelEvent event) {
        if (!event.getLevel().isClientSide() && event.loadedFromDisk()
                && (Scenes.stray(event.getEntity()) || event.getEntity().getTags().contains(Scenes.TAG + "_stand"))) {
            event.setCanceled(true);
        }
    }
}
