package dev.theunquiet.core;

import java.util.concurrent.CopyOnWriteArrayList;
import net.minecraft.server.MinecraftServer;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.tick.ServerTickEvent;
import net.neoforged.fml.common.Mod;

@Mod(HorrorCore.MOD_ID)
public final class HorrorCore {
    public static final String MOD_ID = "unquietcore";
    private static final CopyOnWriteArrayList<ServerTickListener> TICK_LISTENERS = new CopyOnWriteArrayList<>();

    public HorrorCore(IEventBus modBus) {
        NeoForge.EVENT_BUS.addListener(this::onServerTick);
    }

    public static void addServerTickListener(ServerTickListener listener) {
        TICK_LISTENERS.addIfAbsent(listener);
    }

    private void onServerTick(ServerTickEvent.Post event) {
        for (ServerTickListener listener : TICK_LISTENERS) {
            listener.onServerTick(event.getServer());
        }
    }

    @FunctionalInterface
    public interface ServerTickListener {
        void onServerTick(MinecraftServer server);
    }
}
