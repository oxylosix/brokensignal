import java.awt.*;
import java.awt.event.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.SecureRandom;
import java.util.*;
import javax.sound.sampled.*;
import javax.swing.*;

/**
 * Broken Signal Companion: a visible, optional desktop app the mod can talk to.
 *
 * Guarantees (see PROTOCOL.md):
 *  - started only by the user; always visible (window + tray icon); one click turns it off;
 *  - listens only on 127.0.0.1, accepts only the mod client that knows the session token;
 *  - reads and writes only inside <home>/BrokenSignalCompanion; deletes nothing outside it;
 *  - no network besides loopback, no autostart, no admin rights, no access to other programs.
 *  - a file named STOP in its folder shuts it down within a second.
 */
public final class BrokenSignalCompanion {
    static final Path DIR = Paths.get(System.getProperty("user.home"), "BrokenSignalCompanion");
    static final Set<String> MADE = Collections.synchronizedSet(new HashSet<>());
    static volatile boolean effects = true;
    static volatile PrintWriter client;
    static volatile long lastScene;
    static JLabel status;
    static final Random R = new Random();

    public static void main(String[] args) throws Exception {
        Files.createDirectories(DIR);
        Files.deleteIfExists(DIR.resolve("STOP"));
        ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
        byte[] raw = new byte[18];
        new SecureRandom().nextBytes(raw);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);
        Files.write(DIR.resolve("session.txt"), Arrays.asList(String.valueOf(server.getLocalPort()), token), StandardCharsets.UTF_8);
        SwingUtilities.invokeAndWait(BrokenSignalCompanion::ui);
        daemon(() -> accept(server, token));
        daemon(BrokenSignalCompanion::watch);
        while (true) {
            if (Files.exists(DIR.resolve("STOP"))) {
                quit();
            }
            Thread.sleep(700);
        }
    }

    static void daemon(Runnable r) {
        Thread t = new Thread(r);
        t.setDaemon(true);
        t.start();
    }

    static void quit() {
        try {
            Files.deleteIfExists(DIR.resolve("session.txt"));
        } catch (IOException ignored) {
            // nothing else to clean: the folder stays for the user
        }
        System.exit(0);
    }

    // ------------------------------------------------------------------ visible UI

    static void ui() {
        JFrame f = new JFrame("Broken Signal Companion");
        f.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        f.addWindowListener(new WindowAdapter() {
            @Override public void windowClosing(WindowEvent e) { quit(); }
        });
        status = new JLabel("Ждёт Minecraft (включите companion=true в brokensignal-client.toml)");
        JCheckBox on = new JCheckBox("Разрешить эффекты", true);
        on.addActionListener(e -> effects = on.isSelected());
        JButton off = new JButton("Выключить");
        off.addActionListener(e -> quit());
        JButton open = new JButton("Открыть папку");
        open.addActionListener(e -> { try { Desktop.getDesktop().open(DIR.toFile()); } catch (Exception ex) { status.setText(ex.getMessage()); } });
        JPanel p = new JPanel(new BorderLayout(8, 8));
        p.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));
        p.add(status, BorderLayout.NORTH);
        p.add(new JLabel("<html>Работает только с этой папкой: " + DIR + "<br>Сеть: только 127.0.0.1. Экстренно: файл STOP в папке.</html>"), BorderLayout.CENTER);
        JPanel b = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        b.add(on); b.add(open); b.add(off);
        p.add(b, BorderLayout.SOUTH);
        f.setContentPane(p);
        f.pack();
        f.setLocationByPlatform(true);
        f.setVisible(true);
        if (SystemTray.isSupported()) {
            try {
                Image img = new java.awt.image.BufferedImage(16, 16, java.awt.image.BufferedImage.TYPE_INT_ARGB);
                Graphics g = img.getGraphics(); g.setColor(Color.DARK_GRAY); g.fillRect(2, 2, 12, 12); g.dispose();
                PopupMenu m = new PopupMenu();
                MenuItem q = new MenuItem("Выключить"); q.addActionListener(e -> quit());
                m.add(q);
                TrayIcon ti = new TrayIcon(img, "Broken Signal Companion (включён)", m);
                ti.addActionListener(e -> f.setVisible(true));
                SystemTray.getSystemTray().add(ti);
            } catch (AWTException e) {
                status.setText("Значок в трее недоступен: " + e.getMessage());
            }
        }
    }

    static void setStatus(String s) {
        SwingUtilities.invokeLater(() -> status.setText(s));
    }

    // ------------------------------------------------------------------ link

    static void accept(ServerSocket server, String token) {
        while (true) {
            try (Socket s = server.accept()) {
                BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
                if (!("HELLO " + token).equals(in.readLine())) {
                    continue;
                }
                client = new PrintWriter(new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8), true);
                setStatus("Minecraft подключён");
                String line;
                while ((line = in.readLine()) != null) {
                    if (line.startsWith("CUE ")) {
                        String body = line.substring(4);
                        int t = body.indexOf('\t');
                        String cue = t < 0 ? body : body.substring(0, t);
                        String arg = t < 0 ? "" : body.substring(t + 1);
                        SwingUtilities.invokeLater(() -> cue(cue, arg));
                    }
                }
            } catch (IOException e) {
                setStatus("Связь потеряна: " + e.getMessage());
            }
            client = null;
            setStatus("Ждёт Minecraft");
        }
    }

    static void report(String kind, String arg) {
        PrintWriter w = client;
        if (w != null) {
            w.println("EV " + kind + "\t" + arg.replace('\n', ' ').replace('\t', ' '));
        }
    }

    /** Deleting a file the companion left is noticed, and only those files. */
    static void watch() {
        try (WatchService ws = FileSystems.getDefault().newWatchService()) {
            DIR.register(ws, StandardWatchEventKinds.ENTRY_DELETE);
            while (true) {
                WatchKey k = ws.take();
                for (WatchEvent<?> e : k.pollEvents()) {
                    String name = String.valueOf(e.context());
                    if (MADE.remove(name)) {
                        report("file_gone", name);
                    }
                }
                k.reset();
            }
        } catch (IOException | InterruptedException e) {
            setStatus("Наблюдение за папкой остановлено: " + e.getMessage());
        }
    }

    // ------------------------------------------------------------------ scenes (only the companion's own windows)

    static void cue(String cue, String arg) {
        if (cue.equals("test") || arg.equals("test")) {
            note("проверка.txt", "связь есть", 0);
            return;
        }
        long now = System.currentTimeMillis();
        if (!effects || now - lastScene < 60_000) {
            return;
        }
        switch (cue) {
            case "focus_lost" -> {
                // rarely: while you are on the desktop, someone knocks. The game will have an open door.
                if (R.nextFloat() < 0.12F) {
                    lastScene = now;
                    Timer t = new Timer(4000 + R.nextInt(6000), e -> door());
                    t.setRepeats(false);
                    t.start();
                }
            }
            case "after_betrayal" -> {
                lastScene = now;
                note(safe(arg) + ".txt", "", 1);
            }
            case "self_seen" -> {
                lastScene = now;
                note("вчера.txt", safe(arg), 1);
            }
            case "while_away" -> {
                lastScene = now;
                knock(3);
            }
            default -> { }
        }
    }

    static String safe(String s) {
        String r = s.replaceAll("[^\\p{L}\\p{N}_ .-]", "").trim();
        return r.isEmpty() ? "note" : r.length() > 32 ? r.substring(0, 32) : r;
    }

    /** A small borderless window: a door, drawn, slightly open. No text. Gone in a few seconds. */
    static void door() {
        JWindow w = new JWindow();
        w.setSize(120, 170);
        Rectangle sc = GraphicsEnvironment.getLocalGraphicsEnvironment().getMaximumWindowBounds();
        w.setLocation(sc.x + sc.width - 150, sc.y + sc.height - 200);
        w.setContentPane(new JComponent() {
            @Override protected void paintComponent(Graphics g0) {
                Graphics2D g = (Graphics2D) g0;
                g.setColor(new Color(18, 18, 20)); g.fillRect(0, 0, 120, 170);
                g.setColor(new Color(92, 64, 40)); g.fillRect(28, 20, 64, 130);
                g.setColor(new Color(8, 8, 8)); g.fillRect(28, 20, 14, 130);
                g.setColor(new Color(60, 42, 26)); g.drawRect(28, 20, 64, 130);
                g.setColor(new Color(170, 150, 90)); g.fillOval(80, 85, 5, 5);
            }
        });
        w.setAlwaysOnTop(true);
        w.setVisible(true);
        knock(2);
        Timer t = new Timer(4500, e -> w.dispose());
        t.setRepeats(false);
        t.start();
    }

    /**
     * A notepad window with a file in the companion folder. Typing and closing are reported
     * (only the first words); closing within 3 seconds counts as "closed". Deleting the file
     * from the folder later is noticed by the watcher.
     */
    static void note(String name, String text, int keep) {
        Path f = DIR.resolve(name);
        try {
            Files.writeString(f, text, StandardCharsets.UTF_8);
            if (keep > 0) {
                MADE.add(name);
            }
        } catch (IOException e) {
            setStatus("Не удалось записать " + name);
            return;
        }
        JFrame w = new JFrame(name + " — Broken Signal Companion");
        JTextArea a = new JTextArea(text, 8, 34);
        a.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        w.add(new JScrollPane(a));
        w.pack();
        w.setLocationByPlatform(true);
        long opened = System.currentTimeMillis();
        w.setDefaultCloseOperation(WindowConstants.DISPOSE_ON_CLOSE);
        w.addWindowListener(new WindowAdapter() {
            @Override public void windowClosed(WindowEvent e) {
                String typed = a.getText().trim();
                try {
                    if (Files.exists(f)) {
                        Files.writeString(f, typed, StandardCharsets.UTF_8);
                    }
                } catch (IOException ex) {
                    setStatus("Не удалось сохранить " + name);
                }
                if (System.currentTimeMillis() - opened < 3000) {
                    report("closed", name);
                } else if (!typed.isEmpty() && !typed.equals(text)) {
                    report("typed", typed.length() > 48 ? typed.substring(0, 48) : typed);
                }
            }
        });
        w.setVisible(true);
    }

    /** Soft knocks synthesised in memory: no sound files, quiet, short. */
    static void knock(int n) {
        daemon(() -> {
            try {
                float rate = 22050F;
                AudioFormat fmt = new AudioFormat(rate, 16, 1, true, false);
                try (SourceDataLine line = AudioSystem.getSourceDataLine(fmt)) {
                    line.open(fmt);
                    line.start();
                    for (int k = 0; k < n; k++) {
                        byte[] buf = new byte[(int) (rate * 0.42) * 2];
                        for (int i = 0; i < buf.length / 2 && i < rate * 0.09; i++) {
                            double env = Math.exp(-i / (rate * 0.014));
                            double v = (Math.sin(2 * Math.PI * 95 * i / rate) * 0.8 + (R.nextDouble() - 0.5) * 0.4) * env * 0.35;
                            short sv = (short) (v * Short.MAX_VALUE);
                            buf[2 * i] = (byte) sv;
                            buf[2 * i + 1] = (byte) (sv >> 8);
                        }
                        line.write(buf, 0, buf.length);
                    }
                    line.drain();
                }
            } catch (LineUnavailableException e) {
                setStatus("Звук недоступен: " + e.getMessage());
            }
        });
    }
}
