package com.gena.brokensignal.client;

import com.gena.brokensignal.pc.ComputerActionPayload;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.TreeSet;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.MultiLineEditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.FormattedText;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.FormattedCharSequence;
import net.minecraft.util.RandomSource;
import net.neoforged.neoforge.network.PacketDistributor;
import org.lwjgl.glfw.GLFW;

/**
 * The in-game computer. A purely visual client for the server-side simulation in
 * {@code pc/ComputerService}: every file, message and log line comes from the server,
 * every action is sent back and validated there. Nothing here touches the real machine.
 */
public class ComputerScreen extends Screen {
    // palette: a dim, slightly warm CRT look
    private static final int BG = 0xFF101214;
    private static final int PANEL = 0xFF171A1D;
    private static final int LINE = 0xFF2A2F34;
    private static final int TEXT = 0xFFC9CDC4;
    private static final int DIM = 0xFF7D847C;
    private static final int ACCENT = 0xFF8FB89A;
    private static final int SEL = 0xFF233029;
    private static final int WIN_W = 380;
    private static final int WIN_H = 228;
    private static final int SIDE = 74;
    private static final int BAR = 14;

    private final BlockPos pos;
    private CompoundTag data;
    private final RandomSource rnd = RandomSource.create();

    private String app = "files";
    private String dir = "/";
    private String openFile;
    private int scroll;
    private int msgSel = -1;
    private EditBox input;
    private MultiLineEditBox notes;
    private String notesSent = "";
    private final List<String> history = new ArrayList<>();
    private int histIdx;

    // transient effects
    private int flicker;
    private int freeze;
    private int wrong;
    private int shuffled;
    private String autotype = "";
    private int autotypeIdx;
    private int fakeCursor;
    private double fcx;
    private double fcy;
    private List<String> order;

    private int x0;
    private int y0;

    public ComputerScreen(BlockPos pos, CompoundTag data) {
        super(Component.translatable("screen.brokensignal.computer"));
        this.pos = pos;
        this.data = data;
    }

    /** Called from the sync payload on the client thread. */
    public static void receive(BlockPos pos, CompoundTag data) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.screen instanceof ComputerScreen cs && cs.pos.equals(pos)) {
            cs.update(data);
        } else if (mc.screen == null || mc.screen instanceof ComputerScreen) {
            ComputerScreen cs = new ComputerScreen(pos, data);
            mc.setScreen(cs);
            cs.applyFx(data.getString("fx"));
        }
    }

    private void update(CompoundTag d) {
        this.data = d;
        if (notes != null && !notes.isFocused() && !"notes".equals(app)) {
            notes.setValue(d.getString("notes"));
            notesSent = d.getString("notes");
        }
        if (openFile != null && !files().contains(openFile)) {
            openFile = null; // it was there a moment ago
        }
        applyFx(d.getString("fx"));
    }

    private static void send(String action, String arg) {
        PacketDistributor.sendToServer(new ComputerActionPayload(action, arg == null ? "" : arg));
    }

    private void click(float pitch) {
        if (minecraft != null && !data.getBoolean("sQuiet")) {
            minecraft.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK.value(), pitch, 0.25F));
        }
    }

    // ------------------------------------------------------------------ layout

    @Override
    protected void init() {
        x0 = (width - WIN_W) / 2;
        y0 = (height - WIN_H) / 2;
        input = new EditBox(font, x0 + SIDE + 8, y0 + WIN_H - 18, WIN_W - SIDE - 16, 12, Component.empty());
        input.setBordered(false);
        input.setMaxLength(200);
        input.setTextColor(TEXT);
        addWidget(input);
        notes = new MultiLineEditBox(font, x0 + SIDE + 6, y0 + BAR + 18, WIN_W - SIDE - 12, WIN_H - BAR - 26,
                Component.translatable("screen.brokensignal.notes.empty"), Component.empty());
        notes.setCharacterLimit(4000);
        notes.setValue(data.getString("notes"));
        notesSent = data.getString("notes");
        addWidget(notes);
        layoutFocus();
    }

    private void layoutFocus() {
        input.visible = "term".equals(app);
        notes.visible = "notes".equals(app);
        setFocused("term".equals(app) ? input : "notes".equals(app) ? notes : null);
        input.setFocused("term".equals(app));
        notes.setFocused("notes".equals(app));
    }

    private List<String> apps() {
        List<String> a = new ArrayList<>(List.of("files", "term", "msgs", "notes", "logs", "settings"));
        for (Tag t : data.getList("apps", Tag.TAG_STRING)) {
            String s = t.getAsString();
            if (!a.contains(s)) {
                a.add(s);
            }
        }
        if (shuffled > 0) {
            if (order == null) {
                order = new ArrayList<>(a);
                Collections.shuffle(order, new java.util.Random(rnd.nextLong()));
            }
            return order;
        }
        order = null;
        return a;
    }

    private void open(String a) {
        if (a.equals(app)) {
            return;
        }
        flushNotes();
        app = a;
        scroll = 0;
        msgSel = -1;
        layoutFocus();
        send("app", a);
    }

    private void flushNotes() {
        if (notes != null && !notes.getValue().equals(notesSent)) {
            notesSent = notes.getValue();
            send("notes", notesSent);
        }
    }

    // ------------------------------------------------------------------ fx

    private void applyFx(String fx) {
        if (fx == null || fx.isEmpty()) {
            return;
        }
        for (String f : fx.split(";")) {
            if (f.isEmpty()) {
                continue;
            }
            if (f.startsWith("autotype:")) {
                autotype = f.substring(9);
                autotypeIdx = 0;
                open("term");
            } else if (f.startsWith("app:")) {
                open(f.substring(4));
            } else {
                switch (f) {
                    case "flicker" -> flicker = 4;
                    case "freeze" -> freeze = 50 + rnd.nextInt(40);
                    case "wrong" -> wrong = 14;
                    case "shuffle" -> shuffled = 20 * 20;
                    case "cursor" -> {
                        fakeCursor = 60;
                        fcx = x0 + WIN_W * 0.8;
                        fcy = y0 + WIN_H * 0.7;
                    }
                    case "ping" -> click(1.6F);
                    case "close" -> onClose();
                    default -> { }
                }
            }
        }
    }

    @Override
    public void tick() {
        if (flicker > 0) {
            flicker--;
        }
        if (freeze > 0) {
            freeze--;
        }
        if (wrong > 0) {
            wrong--;
        }
        if (shuffled > 0) {
            shuffled--;
        }
        if (fakeCursor > 0) {
            fakeCursor--;
            fcx += (x0 + 30 - fcx) * 0.04 + rnd.nextGaussian() * 0.6;
            fcy += (y0 + BAR + 30 - fcy) * 0.04 + rnd.nextGaussian() * 0.6;
        }
        if (!autotype.isEmpty() && "term".equals(app) && input != null && rnd.nextInt(3) != 0) {
            if (autotypeIdx < autotype.length()) {
                input.setValue(autotype.substring(0, ++autotypeIdx));
                click(1.9F);
            } else if (rnd.nextInt(12) == 0) {
                input.setValue(""); // it never presses enter
                autotype = "";
            }
        }
    }

    // ------------------------------------------------------------------ data helpers

    private TreeSet<String> files() {
        TreeSet<String> out = new TreeSet<>();
        CompoundTag f = data.getCompound("files");
        boolean hidden = data.getBoolean("sHidden");
        for (String k : f.getAllKeys()) {
            if (hidden || !f.getCompound(k).getBoolean("h")) {
                out.add(k);
            }
        }
        return out;
    }

    private static String parent(String path) {
        int i = path.lastIndexOf('/');
        return i <= 0 ? "/" : path.substring(0, i);
    }

    private static String name(String path) {
        return path.substring(path.lastIndexOf('/') + 1);
    }

    /** Entries directly inside {@link #dir}: folders first, then files. */
    private List<String> entries() {
        TreeSet<String> dirs = new TreeSet<>();
        TreeSet<String> fl = new TreeSet<>();
        boolean hidden = data.getBoolean("sHidden");
        for (Tag t : data.getList("dirs", Tag.TAG_STRING)) {
            String d = t.getAsString();
            if (!d.equals(dir) && parent(d).equals(dir) && (hidden || !name(d).startsWith("."))) {
                dirs.add(d + "/");
            }
        }
        for (String f : files()) {
            String par = parent(f);
            if (par.equals(dir)) {
                fl.add(f);
            } else if (par.startsWith(dir.equals("/") ? "/" : dir + "/")) {
                String rest = par.substring(dir.equals("/") ? 1 : dir.length() + 1);
                int s = rest.indexOf('/');
                String sub = (dir.equals("/") ? "" : dir) + "/" + (s < 0 ? rest : rest.substring(0, s));
                if (hidden || !name(sub).startsWith(".")) {
                    dirs.add(sub + "/");
                }
            }
        }
        List<String> out = new ArrayList<>(dirs);
        out.addAll(fl);
        return out;
    }

    private List<String> strings(String key) {
        List<String> out = new ArrayList<>();
        for (Tag t : data.getList(key, Tag.TAG_STRING)) {
            out.add(t.getAsString());
        }
        return out;
    }

    // ------------------------------------------------------------------ render

    @Override
    public void renderBackground(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        g.fill(0, 0, width, height, 0xB0000000);
        int bg = wrong > 0 ? 0xFF14101A : BG;
        g.fill(x0 - 1, y0 - 1, x0 + WIN_W + 1, y0 + WIN_H + 1, LINE);
        g.fill(x0, y0, x0 + WIN_W, y0 + WIN_H, bg);
        g.fill(x0, y0, x0 + WIN_W, y0 + BAR, PANEL);
        g.fill(x0, y0 + BAR, x0 + WIN_W, y0 + BAR + 1, LINE);
        g.fill(x0, y0 + BAR + 1, x0 + SIDE, y0 + WIN_H, PANEL);
        g.fill(x0 + SIDE, y0 + BAR + 1, x0 + SIDE + 1, y0 + WIN_H, LINE);
        if ("term".equals(app)) {
            g.fill(x0 + SIDE + 4, y0 + WIN_H - 21, x0 + WIN_W - 4, y0 + WIN_H - 20, LINE);
        }
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        String host = data.getString("host");
        g.drawString(font, wrong > 0 ? new StringBuilder(host).reverse().toString() : host, x0 + 6, y0 + 3, DIM, false);
        String clock = data.getString("clock");
        g.drawString(font, clock, x0 + WIN_W - 6 - font.width(clock), y0 + 3, DIM, false);

        List<String> a = apps();
        for (int i = 0; i < a.size(); i++) {
            int y = y0 + BAR + 6 + i * 14;
            boolean sel = a.get(i).equals(app);
            boolean hov = mouseX >= x0 && mouseX < x0 + SIDE && mouseY >= y - 2 && mouseY < y + 11;
            if (sel || hov) {
                g.fill(x0 + 2, y - 2, x0 + SIDE - 2, y + 11, sel ? SEL : 0xFF1E2226);
            }
            String label = Component.translatable("app.brokensignal." + a.get(i)).getString();
            if (wrong > 0 && i == 2) {
                label = ClientMeta.misspell(label);
            }
            g.drawString(font, label, x0 + 8, y, sel ? ACCENT : TEXT, false);
            if ("msgs".equals(a.get(i)) && hasUnread()) {
                g.fill(x0 + SIDE - 9, y + 3, x0 + SIDE - 6, y + 6, ACCENT);
            }
        }

        int cx = x0 + SIDE + 8;
        int cy = y0 + BAR + 6;
        int cw = WIN_W - SIDE - 16;
        g.enableScissor(x0 + SIDE + 1, y0 + BAR + 1, x0 + WIN_W, y0 + WIN_H - ("term".equals(app) ? 22 : 0));
        switch (app) {
            case "files" -> renderFiles(g, cx, cy, cw, mouseX, mouseY);
            case "term" -> renderLines(g, strings("term"), cx, cy, cw, true);
            case "msgs" -> renderMsgs(g, cx, cy, cw, mouseX, mouseY);
            case "notes" -> g.drawString(font, Component.translatable("app.brokensignal.notes"), cx, cy, DIM, false);
            case "logs" -> renderLines(g, strings("logs"), cx, cy, cw, false);
            case "settings" -> renderSettings(g, cx, cy, mouseX, mouseY);
            case "cam" -> renderLines(g, List.of(data.getString("cam").split("\n")), cx, cy, cw, false);
            case "listen" -> renderListen(g, cx, cy, mouseX, mouseY);
            case "unknown" -> renderLines(g, List.of(data.getString("unk").split("\n")), cx, cy, cw, false);
            default -> { }
        }
        g.disableScissor();
        if ("term".equals(app)) {
            g.drawString(font, ">", x0 + SIDE + 2, y0 + WIN_H - 18, ACCENT, false);
            input.render(g, mouseX, mouseY, partialTick);
        }
        if ("notes".equals(app)) {
            notes.render(g, mouseX, mouseY, partialTick);
        }
        if (fakeCursor > 0) {
            int fx = (int) fcx;
            int fy = (int) fcy;
            g.fill(fx, fy, fx + 1, fy + 8, 0xFFE0E0E0);
            g.fill(fx + 1, fy + 1, fx + 2, fy + 6, 0xFFE0E0E0);
            g.fill(fx + 2, fy + 2, fx + 3, fy + 5, 0xFFE0E0E0);
        }
        if (data.getBoolean("sCrt")) {
            for (int y = y0; y < y0 + WIN_H; y += 2) {
                g.fill(x0, y, x0 + WIN_W, y + 1, 0x16000000);
            }
        }
        if (flicker > 0) {
            g.fill(x0, y0, x0 + WIN_W, y0 + WIN_H, (flicker % 2 == 0 ? 0xD0 : 0x50) << 24);
        }
    }

    private boolean hasUnread() {
        for (Tag t : data.getList("msgs", Tag.TAG_COMPOUND)) {
            CompoundTag m = (CompoundTag) t;
            if (!m.getString("ch").isEmpty() && !m.getBoolean("a")) {
                return true;
            }
        }
        return false;
    }

    private void renderLines(GuiGraphics g, List<String> lines, int x, int y, int w, boolean bottom) {
        List<FormattedCharSequence> wrapped = new ArrayList<>();
        for (String l : lines) {
            wrapped.addAll(font.split(FormattedText.of(l.isEmpty() ? " " : l), w));
        }
        int rows = (WIN_H - BAR - (bottom ? 32 : 12)) / 10;
        int start = bottom ? Math.max(0, wrapped.size() - rows - scroll) : Math.min(scroll, Math.max(0, wrapped.size() - rows));
        for (int i = start; i < Math.min(wrapped.size(), start + rows); i++) {
            g.drawString(font, wrapped.get(i), x, y + (i - start) * 10, TEXT, false);
        }
    }

    private void renderFiles(GuiGraphics g, int x, int y, int w, int mx, int my) {
        if (openFile != null) {
            CompoundTag f = data.getCompound("files").getCompound(openFile);
            g.drawString(font, "< " + name(openFile), x, y, ACCENT, false);
            g.drawString(font, f.getString("m"), x + w - font.width(f.getString("m")), y, DIM, false);
            List<String> lines = new ArrayList<>(List.of(f.getString("c").split("\n", -1)));
            renderLines(g, lines, x, y + 14, w, false);
            return;
        }
        g.drawString(font, dir, x, y, DIM, false);
        List<String> e = entries();
        if (e.isEmpty()) {
            g.drawString(font, Component.translatable("screen.brokensignal.empty"), x, y + 16, DIM, false);
        }
        int rows = (WIN_H - BAR - 30) / 12;
        for (int i = scroll; i < Math.min(e.size(), scroll + rows); i++) {
            int ry = y + 14 + (i - scroll) * 12;
            String p = e.get(i);
            boolean folder = p.endsWith("/");
            boolean hov = mx >= x - 2 && mx < x + w && my >= ry - 1 && my < ry + 10;
            if (hov) {
                g.fill(x - 2, ry - 1, x + w, ry + 10, SEL);
            }
            String n = folder ? name(p.substring(0, p.length() - 1)) + "/" : name(p);
            g.drawString(font, n, x + 2, ry, folder ? ACCENT : TEXT, false);
            if (!folder) {
                String m = data.getCompound("files").getCompound(p).getString("m");
                g.drawString(font, m, x + w - font.width(m) - 2, ry, DIM, false);
            }
        }
    }

    private void renderMsgs(GuiGraphics g, int x, int y, int w, int mx, int my) {
        ListTag msgs = data.getList("msgs", Tag.TAG_COMPOUND);
        if (msgs.isEmpty()) {
            g.drawString(font, Component.translatable("screen.brokensignal.nomsgs"), x, y, DIM, false);
            return;
        }
        int ry = y - scroll * 10;
        for (int i = msgs.size() - 1; i >= 0; i--) {
            CompoundTag m = msgs.getCompound(i);
            g.drawString(font, m.getString("f") + "  " + m.getString("t"), x, ry, ACCENT, false);
            ry += 11;
            for (FormattedCharSequence s : font.split(FormattedText.of(m.getString("x")), w)) {
                g.drawString(font, s, x + 4, ry, TEXT, false);
                ry += 10;
            }
            String ch = m.getString("ch");
            if (!ch.isEmpty()) {
                if (m.getBoolean("a")) {
                    g.drawString(font, "> " + m.getString("ans"), x + 4, ry, DIM, false);
                    ry += 10;
                } else {
                    int bx = x + 4;
                    for (String c : ch.split("\\|")) {
                        int bw = font.width(c) + 8;
                        boolean hov = mx >= bx && mx < bx + bw && my >= ry - 1 && my < ry + 10;
                        g.fill(bx, ry - 1, bx + bw, ry + 10, hov ? SEL : LINE);
                        g.drawString(font, c, bx + 4, ry + 1, hov ? ACCENT : TEXT, false);
                        bx += bw + 4;
                    }
                    ry += 12;
                }
            }
            ry += 6;
        }
    }

    private static final String[] SETTINGS = {"hidden", "crt", "quiet"};

    private void renderSettings(GuiGraphics g, int x, int y, int mx, int my) {
        for (int i = 0; i < SETTINGS.length; i++) {
            int ry = y + i * 16;
            boolean on = data.getBoolean("s" + Character.toUpperCase(SETTINGS[i].charAt(0)) + SETTINGS[i].substring(1));
            g.fill(x, ry, x + 10, ry + 10, LINE);
            if (on) {
                g.fill(x + 2, ry + 2, x + 8, ry + 8, ACCENT);
            }
            g.drawString(font, Component.translatable("setting.brokensignal." + SETTINGS[i]), x + 16, ry + 1, TEXT, false);
        }
    }

    private void renderListen(GuiGraphics g, int x, int y, int mx, int my) {
        g.drawString(font, Component.translatable("screen.brokensignal.listen"), x, y, DIM, false);
        int bw = 80;
        boolean hov = mx >= x && mx < x + bw && my >= y + 16 && my < y + 30;
        g.fill(x, y + 16, x + bw, y + 30, hov ? SEL : LINE);
        g.drawCenteredString(font, Component.translatable("screen.brokensignal.listen.go"), x + bw / 2, y + 19, hov ? ACCENT : TEXT);
        for (int i = 0; i < 48; i++) {
            int h = 1 + (int) (Math.abs(Math.sin((i + (minecraft == null ? 0 : minecraft.level == null ? 0 : minecraft.level.getGameTime())) * 0.37)) * 3);
            g.fill(x + i * 4, y + 50 - h, x + i * 4 + 2, y + 50 + h, DIM);
        }
    }

    // ------------------------------------------------------------------ input

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (freeze > 0) {
            return true;
        }
        List<String> a = apps();
        for (int i = 0; i < a.size(); i++) {
            int y = y0 + BAR + 6 + i * 14;
            if (mx >= x0 && mx < x0 + SIDE && my >= y - 2 && my < y + 11) {
                click(1.0F);
                open(a.get(i));
                return true;
            }
        }
        int cx = x0 + SIDE + 8;
        int cy = y0 + BAR + 6;
        int cw = WIN_W - SIDE - 16;
        switch (app) {
            case "files" -> {
                if (openFile != null) {
                    if (my >= cy - 1 && my < cy + 10 && mx < cx + 60) {
                        click(0.9F);
                        openFile = null;
                        scroll = 0;
                    }
                    return true;
                }
                if (my >= cy - 1 && my < cy + 10 && !dir.equals("/")) {
                    click(0.9F);
                    dir = parent(dir);
                    scroll = 0;
                    return true;
                }
                List<String> e = entries();
                int idx = (int) ((my - (cy + 13)) / 12) + scroll;
                if (mx >= cx - 2 && mx < cx + cw && my >= cy + 13 && idx >= 0 && idx < e.size()) {
                    String p = e.get(idx);
                    click(1.1F);
                    if (p.endsWith("/")) {
                        dir = p.substring(0, p.length() - 1);
                    } else {
                        openFile = p;
                        send("read", p);
                    }
                    scroll = 0;
                    return true;
                }
            }
            case "msgs" -> {
                ListTag msgs = data.getList("msgs", Tag.TAG_COMPOUND);
                int ry = cy - scroll * 10;
                for (int i = msgs.size() - 1; i >= 0; i--) {
                    CompoundTag m = msgs.getCompound(i);
                    ry += 11 + font.split(FormattedText.of(m.getString("x")), cw).size() * 10;
                    String ch = m.getString("ch");
                    if (!ch.isEmpty()) {
                        if (!m.getBoolean("a")) {
                            int bx = cx + 4;
                            for (String c : ch.split("\\|")) {
                                int bw = font.width(c) + 8;
                                if (mx >= bx && mx < bx + bw && my >= ry - 1 && my < ry + 10) {
                                    click(1.2F);
                                    send("reply", i + ":" + c);
                                    return true;
                                }
                                bx += bw + 4;
                            }
                            ry += 12;
                        } else {
                            ry += 10;
                        }
                    }
                    ry += 6;
                }
            }
            case "settings" -> {
                for (int i = 0; i < SETTINGS.length; i++) {
                    int ry = cy + i * 16;
                    if (mx >= cx && mx < cx + 140 && my >= ry && my < ry + 10) {
                        String k = SETTINGS[i];
                        boolean on = data.getBoolean("s" + Character.toUpperCase(k.charAt(0)) + k.substring(1));
                        click(1.0F);
                        send("setting", k + "=" + !on);
                        return true;
                    }
                }
            }
            case "listen" -> {
                if (mx >= cx && mx < cx + 80 && my >= cy + 16 && my < cy + 30) {
                    click(0.7F);
                    send("listen", "");
                    return true;
                }
            }
            default -> { }
        }
        return super.mouseClicked(mx, my, button);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double sx, double sy) {
        if ("notes".equals(app)) {
            return super.mouseScrolled(mx, my, sx, sy);
        }
        scroll = Math.max(0, Math.min(400, scroll + (sy > 0 ? ("term".equals(app) ? 1 : -1) : ("term".equals(app) ? -1 : 1))));
        return true;
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        if (key == GLFW.GLFW_KEY_ESCAPE) {
            onClose();
            return true;
        }
        if (freeze > 0) {
            return true;
        }
        if ("term".equals(app) && input.isFocused()) {
            if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) {
                String cmd = input.getValue().trim();
                autotype = "";
                if (!cmd.isEmpty()) {
                    send("cmd", cmd);
                    history.add(cmd);
                    if (history.size() > 50) {
                        history.remove(0);
                    }
                }
                histIdx = history.size();
                input.setValue("");
                scroll = 0;
                click(1.4F);
                return true;
            }
            if (key == GLFW.GLFW_KEY_UP && !history.isEmpty()) {
                histIdx = Math.max(0, histIdx - 1);
                input.setValue(history.get(histIdx));
                return true;
            }
            if (key == GLFW.GLFW_KEY_DOWN && !history.isEmpty()) {
                histIdx = Math.min(history.size(), histIdx + 1);
                input.setValue(histIdx < history.size() ? history.get(histIdx) : "");
                return true;
            }
            return input.keyPressed(key, scan, mods) || true;
        }
        if ("notes".equals(app) && notes.isFocused()) {
            return notes.keyPressed(key, scan, mods) || true;
        }
        if ("files".equals(app) && key == GLFW.GLFW_KEY_BACKSPACE) {
            if (openFile != null) {
                openFile = null;
            } else {
                dir = parent(dir);
            }
            return true;
        }
        return super.keyPressed(key, scan, mods);
    }

    @Override
    public boolean charTyped(char c, int mods) {
        if (freeze > 0) {
            return true;
        }
        return super.charTyped(c, mods);
    }

    @Override
    public void onClose() {
        flushNotes();
        send("close", "");
        super.onClose();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
