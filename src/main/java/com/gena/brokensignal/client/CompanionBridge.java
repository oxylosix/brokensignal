package com.gena.brokensignal.client;

import com.gena.brokensignal.BrokenSignal;
import com.gena.brokensignal.ClientConfig;
import com.gena.brokensignal.ext.CompanionPayload;
import com.mojang.logging.LogUtils;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;
import org.slf4j.Logger;

/**
 * Client end of the Minecraft <-> companion link.
 *
 *  - Off unless {@code companion = true} in brokensignal-client.toml.
 *  - Connects only to 127.0.0.1, to the port written by the companion into its own folder
 *    ({@code <user home>/BrokenSignalCompanion/session.txt}), and authenticates with the
 *    random token from that file. No other host, no other file is ever touched.
 *  - Text protocol, one line per message: {@code HELLO <token>}, {@code CUE <cue>\t<arg>}
 *    from the mod; {@code EV <kind>\t<arg>} from the companion.
 *  - Also measures how long the game window was unfocused (alt-tab) — only the duration.
 */
@EventBusSubscriber(modid = BrokenSignal.MODID, value = Dist.CLIENT)
public final class CompanionBridge {
    private static final Logger LOG = LogUtils.getLogger();
    private static final ConcurrentLinkedQueue<String[]> INBOX = new ConcurrentLinkedQueue<>();
    private static volatile Socket socket;
    private static volatile Writer out;
    private static volatile boolean connecting;
    private static int retryIn = 20 * 5;
    private static long unfocusedAt = -1;
    private static boolean announced;

    private CompanionBridge() {}

    public static Path folder() {
        return Path.of(System.getProperty("user.home"), "BrokenSignalCompanion");
    }

    public static boolean connected() {
        return out != null;
    }

    /** Called from ClientMeta for the "ext" action. Silently ignored if the companion is not running. */
    public static void cue(String cueAndArg) {
        int i = cueAndArg.indexOf('|');
        String cue = i < 0 ? cueAndArg : cueAndArg.substring(0, i);
        String arg = i < 0 ? "" : cueAndArg.substring(i + 1);
        send("CUE " + cue + "\t" + arg.replace('\n', ' '));
    }

    private static void send(String line) {
        Writer w = out;
        if (w == null) {
            return;
        }
        try {
            w.write(line + "\n");
            w.flush();
        } catch (IOException e) {
            LOG.info("[BrokenSignal] companion went away: {}", e.getMessage());
            close();
        }
    }

    private static void close() {
        Socket s = socket;
        socket = null;
        out = null;
        announced = false;
        if (s != null) {
            try {
                s.close();
            } catch (IOException e) {
                LOG.debug("[BrokenSignal] companion socket close: {}", e.getMessage());
            }
        }
        INBOX.add(new String[] {"bye", ""});
    }

    private static void connectAsync() {
        Path session = folder().resolve("session.txt");
        if (connecting || !Files.isRegularFile(session) || Files.exists(folder().resolve("STOP"))) {
            return;
        }
        connecting = true;
        Thread t = new Thread(() -> {
            try {
                List<String> lines = Files.readAllLines(session, StandardCharsets.UTF_8);
                int port = Integer.parseInt(lines.get(0).trim());
                String token = lines.get(1).trim();
                Socket s = new Socket();
                s.connect(new InetSocketAddress(InetAddress.getLoopbackAddress(), port), 800);
                Writer w = new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8);
                w.write("HELLO " + token + "\n");
                w.flush();
                socket = s;
                out = w;
                INBOX.add(new String[] {"hello", "1"});
                BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
                connecting = false;
                String line;
                while ((line = in.readLine()) != null) {
                    if (line.startsWith("EV ") && line.length() < 200) {
                        String body = line.substring(3);
                        int tab = body.indexOf('\t');
                        INBOX.add(tab < 0 ? new String[] {body, ""} : new String[] {body.substring(0, tab), body.substring(tab + 1)});
                    }
                }
                close();
            } catch (IOException | RuntimeException e) {
                LOG.debug("[BrokenSignal] companion not reachable: {}", e.getMessage());
                socket = null;
                out = null;
            } finally {
                connecting = false;
            }
        }, "BrokenSignal-companion");
        t.setDaemon(true);
        t.start();
    }

    @SubscribeEvent
    public static void onTick(ClientTickEvent.Post event) {
        Minecraft mc = Minecraft.getInstance();
        boolean inWorld = mc.player != null && mc.getConnection() != null;
        if (ClientConfig.COMPANION.get()) {
            if (out == null && --retryIn <= 0) {
                retryIn = 20 * 15;
                connectAsync();
            }
        } else if (out != null) {
            close();
        }
        if (!inWorld) {
            unfocusedAt = -1;
            return;
        }
        if (out != null && !announced) {
            announced = true;
            report("hello", "1");
        }
        String[] ev;
        while ((ev = INBOX.poll()) != null) {
            report(ev[0], ev[1]);
        }
        // alt-tab measurement: only the duration, nothing about what was focused instead
        long now = System.currentTimeMillis();
        if (!mc.isWindowActive()) {
            if (unfocusedAt < 0) {
                unfocusedAt = now;
                send("CUE focus_lost\t");
            }
        } else if (unfocusedAt >= 0) {
            long secs = (now - unfocusedAt) / 1000;
            unfocusedAt = -1;
            send("CUE focus_back\t" + secs);
            if (secs >= 5) {
                report("away", String.valueOf(secs));
            }
        }
    }

    private static void report(String kind, String arg) {
        try {
            PacketDistributor.sendToServer(new CompanionPayload(kind, arg.length() > 90 ? arg.substring(0, 90) : arg));
        } catch (RuntimeException e) {
            // server without the mod (or not in a world yet): nothing to tell
            LOG.debug("[BrokenSignal] could not report {}: {}", kind, e.getMessage());
        }
    }
}
