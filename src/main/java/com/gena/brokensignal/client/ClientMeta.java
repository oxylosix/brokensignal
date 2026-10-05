package com.gena.brokensignal.client;

import com.gena.brokensignal.BrokenSignal;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.client.event.RenderGuiEvent;
import net.neoforged.neoforge.client.event.RenderGuiLayerEvent;
import net.neoforged.neoforge.client.event.ViewportEvent;
import net.neoforged.neoforge.client.gui.VanillaGuiLayers;
import org.slf4j.Logger;
import com.mojang.logging.LogUtils;

/**
 * Client-side effects that look like the game itself misbehaving. Everything here is
 * purely visual/audio and lives inside the Minecraft window: no files, no OS calls,
 * no real user data. Every effect has a hard time limit and cleans up on disconnect.
 */
@EventBusSubscriber(modid = BrokenSignal.MODID, value = Dist.CLIENT)
public final class ClientMeta {
    private static final Logger LOG = LogUtils.getLogger();
    private static final int MAX_EFFECT = 20 * 60;

    private static final class Task {
        int delay;
        final Runnable run;

        Task(int delay, Runnable run) {
            this.delay = delay;
            this.run = run;
        }
    }

    private static final List<Task> TASKS = new ArrayList<>();
    private static int titleRestore = -1;
    private static int hudHidden;
    private static int flicker;
    private static int fogTicks;
    private static int fogTotal;

    private ClientMeta() {}

    private static void later(int delay, Runnable run) {
        if (TASKS.size() < 256) {
            TASKS.add(new Task(delay, run));
        }
    }

    private static int ticks(String arg, int def) {
        if (arg == null || arg.isBlank()) {
            return def;
        }
        try {
            return Math.max(1, Math.min(MAX_EFFECT, Integer.parseInt(arg.trim())));
        } catch (NumberFormatException e) {
            LOG.warn("[BrokenSignal] bad effect length '{}', using {}", arg, def);
            return def;
        }
    }

    public static void handle(String action, String arg) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            return;
        }
        switch (action) {
            case "title" -> {
                // looks exactly like the normal title, but says you are on someone else's server
                String key = "lan".equals(arg) ? "title.multiplayer.lan" : "title.multiplayer.other";
                mc.getWindow().setTitle("Minecraft* " + SharedConstants.getCurrentVersion().getName() + " - " + I18n.get(key));
                titleRestore = 20 * 60 * 4;
            }
            case "lost", "static", "saving", "terrain" -> {
                if (mc.screen != null) {
                    later(40, () -> handle(action, arg));
                    return;
                }
                HorrorScreen.Mode m = switch (action) {
                    case "lost" -> HorrorScreen.Mode.LOST;
                    case "saving" -> HorrorScreen.Mode.SAVING;
                    case "terrain" -> HorrorScreen.Mode.TERRAIN;
                    default -> HorrorScreen.Mode.STATIC;
                };
                mc.setScreen(new HorrorScreen(m));
            }
            case "silence" -> {
                // every sound in the game stops for a while (default ~10 s)
                int len = ticks(arg, 200);
                for (int i = 0; i < len; i += 5) {
                    later(1 + i, () -> {
                        mc.getSoundManager().stop();
                        mc.getMusicManager().stopPlaying();
                    });
                }
            }
            case "pause" -> {
                if (mc.screen == null) {
                    mc.pauseGame(false);
                }
            }
            case "flicker" -> flicker = 3 + mc.player.getRandom().nextInt(3);
            case "hud" -> hudHidden = ticks(arg, 60);
            case "fog" -> {
                fogTotal = ticks(arg, 400);
                fogTicks = fogTotal;
            }
            case "chunkerr" -> {
                String[] xz = arg.trim().split("\\s+");
                ChunkPos cp = mc.player.chunkPosition();
                if (xz.length == 2) {
                    try {
                        cp = new ChunkPos(Integer.parseInt(xz[0]), Integer.parseInt(xz[1]));
                    } catch (NumberFormatException e) {
                        LOG.warn("[BrokenSignal] bad chunk '{}'", arg);
                    }
                }
                SystemToast.onChunkLoadFailure(mc, cp);
            }
            case "wrongname" -> {
                ItemStack held = mc.player.getMainHandItem();
                if (!held.isEmpty()) {
                    mc.gui.setOverlayMessage(Component.literal(misspell(held.getHoverName().getString())), false);
                }
            }
            case "pctoast" -> SystemToast.add(mc.getToasts(), SystemToast.SystemToastId.PERIODIC_NOTIFICATION,
                    Component.translatable("toast.brokensignal.pc.title"),
                    Component.translatable("toast.brokensignal.pc.body"));
            default -> LOG.debug("[BrokenSignal] unknown client effect '{}'", action);
        }
    }

    /** Swap two neighbouring letters: the kind of typo you only notice the second time. */
    static String misspell(String s) {
        if (s.length() < 4) {
            return s;
        }
        int i = 1 + (s.hashCode() & 0x7fffffff) % (s.length() - 2);
        char[] c = s.toCharArray();
        char t = c[i];
        c[i] = c[i + 1];
        c[i + 1] = t;
        return new String(c);
    }

    private static void reset(Minecraft mc) {
        TASKS.clear();
        hudHidden = 0;
        flicker = 0;
        fogTicks = 0;
        if (titleRestore > 0) {
            titleRestore = -1;
            mc.updateTitle();
        }
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            reset(mc);
            return;
        }
        if (titleRestore > 0 && --titleRestore == 0) {
            mc.updateTitle();
        }
        if (hudHidden > 0) {
            hudHidden--;
        }
        if (fogTicks > 0) {
            fogTicks--;
        }
        if (flicker > 0) {
            flicker--;
        }
        if (TASKS.isEmpty()) {
            return;
        }
        List<Task> due = new ArrayList<>();
        for (Task t : TASKS) {
            if (--t.delay <= 0) {
                due.add(t);
            }
        }
        TASKS.removeAll(due);
        for (Task t : due) {
            t.run.run();
        }
    }

    @SubscribeEvent
    public static void onLayer(RenderGuiLayerEvent.Pre event) {
        if (hudHidden <= 0) {
            return;
        }
        var n = event.getName();
        if (n.equals(VanillaGuiLayers.HOTBAR) || n.equals(VanillaGuiLayers.PLAYER_HEALTH) || n.equals(VanillaGuiLayers.FOOD_LEVEL)
                || n.equals(VanillaGuiLayers.EXPERIENCE_BAR) || n.equals(VanillaGuiLayers.EXPERIENCE_LEVEL)
                || n.equals(VanillaGuiLayers.ARMOR_LEVEL) || n.equals(VanillaGuiLayers.CROSSHAIR)
                || n.equals(VanillaGuiLayers.SELECTED_ITEM_NAME)) {
            event.setCanceled(true);
        }
    }

    @SubscribeEvent
    public static void onGui(RenderGuiEvent.Post event) {
        if (flicker > 0) {
            var g = event.getGuiGraphics();
            int a = flicker % 2 == 0 ? 0xE0 : 0x70;
            g.fill(0, 0, g.guiWidth(), g.guiHeight(), a << 24);
        }
    }

    @SubscribeEvent
    public static void onFog(ViewportEvent.RenderFog event) {
        if (fogTicks <= 0) {
            return;
        }
        // ease in over 2 s and out over 2 s so it never "pops"
        float in = Math.min(1F, (fogTotal - fogTicks) / 40F);
        float out = Math.min(1F, fogTicks / 40F);
        float k = Math.min(in, out);
        float far = event.getFarPlaneDistance();
        float target = 18F;
        event.setFarPlaneDistance(far + (target - far) * k);
        event.setNearPlaneDistance(Math.min(event.getNearPlaneDistance(), 2F + (far * 0.75F - 2F) * (1F - k)));
        event.setCanceled(true);
    }
}
