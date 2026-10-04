package com.gena.brokensignal.client;

import com.gena.brokensignal.BrokenSignal;
import com.gena.brokensignal.Config;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import net.minecraft.ChatFormatting;
import net.minecraft.Util;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/** Client-side "computer" effects: window title, fake screens, notes, screenshots, personal messages. */
@EventBusSubscriber(modid = BrokenSignal.MODID, value = Dist.CLIENT)
public final class ClientMeta {
    private static final class Task {
        int delay;
        final Runnable run;

        Task(int delay, Runnable run) {
            this.delay = delay;
            this.run = run;
        }
    }

    private static final List<Task> TASKS = new ArrayList<>();
    private static final Random RND = new Random();
    private static int titleRestore = -1;

    private static final String[] USER_LINES = {
            "%user%.",
            "%user%, уже %time%. почему ты не спишь?",
            "я знаю твоё настоящее имя, %user%",
            "%time%. в это время ты обычно один, %user%",
            "%user%, выключи свет. так лучше видно",
            "ты сидишь за компьютером, %user%. я тоже"
    };

    private ClientMeta() {}

    private static String user() {
        String u = System.getProperty("user.name");
        return u == null || u.isBlank() ? "player" : u;
    }

    private static String time() {
        return LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm"));
    }

    private static String fill(String s) {
        return s.replace("%user%", user()).replace("%time%", time())
                .replace("%os%", System.getProperty("os.name", "unknown"));
    }

    private static void later(int delay, Runnable run) {
        TASKS.add(new Task(delay, run));
    }

    private static void say(String text) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            return;
        }
        MutableComponent c = Component.literal("<")
                .append(Component.literal("??????").withStyle(ChatFormatting.OBFUSCATED))
                .append("> ")
                .append(Component.literal(text));
        mc.gui.getChat().addMessage(c);
    }

    private static void system(String text) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) {
            mc.gui.getChat().addMessage(Component.literal(text).withStyle(ChatFormatting.DARK_GRAY));
        }
    }

    public static void handle(String action, String arg) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            return;
        }
        switch (action) {
            case "title" -> {
                mc.getWindow().setTitle(arg);
                titleRestore = 20 * 25;
            }
            case "lost", "static", "jumpscare" -> {
                if (mc.screen != null) {
                    // a menu is open (inventory, chat...): try again a bit later
                    later(40, () -> handle(action, arg));
                    return;
                }
                HorrorScreen.Mode mode = action.equals("lost") ? HorrorScreen.Mode.LOST
                        : action.equals("static") ? HorrorScreen.Mode.STATIC : HorrorScreen.Mode.JUMPSCARE;
                mc.setScreen(new HorrorScreen(mode, arg));
            }
            case "user" -> say(fill(USER_LINES[RND.nextInt(USER_LINES.length)]));
            case "trace" -> {
                system("[s1gnal] установка соединения...");
                later(50, () -> system(String.format("[s1gnal] трассировка: 192.168.%d.%d -> 10.0.%d.%d -> ***.***.***.***",
                        RND.nextInt(3), 2 + RND.nextInt(250), RND.nextInt(255), RND.nextInt(255))));
                later(110, () -> system(fill("[s1gnal] устройство найдено: %os% / %user%")));
                later(170, () -> system(fill("[s1gnal] местное время: %time%")));
                later(240, () -> {
                    system("[s1gnal] соединение установлено.");
                    say("я внутри");
                    mc.getWindow().setTitle("s1gnal - подключено");
                    titleRestore = 20 * 30;
                });
            }
            case "note" -> writeNote(mc, arg);
            case "screenshot" -> {
                Screenshot.grab(mc.gameDirectory, mc.getMainRenderTarget(), msg -> { });
                later(60, () -> say("красиво получилось. посмотри в папке screenshots"));
            }
            default -> { }
        }
    }

    private static void writeNote(Minecraft mc, String name) {
        String text = fill(String.join(System.lineSeparator(),
                "%user%.",
                "",
                "сейчас %time%. ты играешь как " + name + ", но я знаю, кто ты на самом деле.",
                "",
                "я был в твоём мире, пока тебя не было.",
                "я стоял за тобой, когда ты строил.",
                "я гасил твои факелы.",
                "",
                "не закрывай этот файл.",
                "не закрывай игру.",
                "не оборачивайся.",
                "",
                "- s1gnal"));
        try {
            Path dir = mc.gameDirectory.toPath().resolve("brokensignal");
            Files.createDirectories(dir);
            Path file = dir.resolve("s1gnal.txt");
            Files.writeString(file, text, StandardCharsets.UTF_8);
            if (Config.OPEN_FILES.get()) {
                Util.getPlatform().openFile(file.toFile());
            }
            say("я оставил тебе записку. папка brokensignal");
        } catch (IOException e) {
            say("я хотел оставить тебе записку...");
        }
    }

    @SubscribeEvent
    public static void onClientTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) {
            TASKS.clear();
            if (titleRestore > 0) {
                titleRestore = -1;
                mc.updateTitle();
            }
            return;
        }
        if (titleRestore > 0 && --titleRestore == 0) {
            mc.updateTitle();
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
}
