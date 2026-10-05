package com.gena.brokensignal.pc;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerPlayer;

/**
 * Virtual file system of the in-game computer. It lives ONLY inside the player's NBT
 * (persistent data key "brokensignal_pc"): nothing here ever touches the real disk.
 *
 * File entry keys: c content, m modified (display string), h hidden, n night-only,
 * v vanishes when the computer is closed, k looks corrupted, r read count,
 * key/plain for encrypted files.
 */
public final class Vfs {
    public static final String KEY = "brokensignal_pc";
    public static final int MAX_FILES = 96;
    public static final int MAX_LOG = 40;
    public static final int MAX_TERM = 80;
    public static final int MAX_MSGS = 24;

    private final CompoundTag t;

    private Vfs(CompoundTag t) {
        this.t = t;
    }

    public static Vfs of(ServerPlayer p) {
        CompoundTag d = p.getPersistentData();
        if (!d.contains(KEY, Tag.TAG_COMPOUND)) {
            d.put(KEY, new CompoundTag());
        }
        return new Vfs(d.getCompound(KEY));
    }

    public CompoundTag raw() {
        return t;
    }

    public boolean initialized() {
        return t.getBoolean("init");
    }

    public void markInitialized() {
        t.putBoolean("init", true);
    }

    private CompoundTag sub(String k) {
        if (!t.contains(k, Tag.TAG_COMPOUND)) {
            t.put(k, new CompoundTag());
        }
        return t.getCompound(k);
    }

    public ListTag strings(String k) {
        if (!t.contains(k, Tag.TAG_LIST)) {
            t.put(k, new ListTag());
        }
        return t.getList(k, Tag.TAG_STRING);
    }

    public ListTag compounds(String k) {
        if (!t.contains(k, Tag.TAG_LIST)) {
            t.put(k, new ListTag());
        }
        return t.getList(k, Tag.TAG_COMPOUND);
    }

    // ------------------------------------------------------------------ files

    public CompoundTag files() {
        return sub("files");
    }

    public static String norm(String path) {
        String s = path.trim().replace('\\', '/');
        if (!s.startsWith("/")) {
            s = "/" + s;
        }
        while (s.contains("//")) {
            s = s.replace("//", "/");
        }
        if (s.length() > 1 && s.endsWith("/")) {
            s = s.substring(0, s.length() - 1);
        }
        return s;
    }

    public CompoundTag put(String path, String content, String modified) {
        CompoundTag files = files();
        String key = norm(path);
        if (!files.contains(key) && files.size() >= MAX_FILES) {
            return new CompoundTag(); // full: silently keep the old state, never grow without bound
        }
        CompoundTag f = files.contains(key, Tag.TAG_COMPOUND) ? files.getCompound(key) : new CompoundTag();
        f.putString("c", content);
        f.putString("m", modified);
        files.put(key, f);
        mkdir(parent(key));
        return f;
    }

    public CompoundTag get(String path) {
        String key = norm(path);
        return files().contains(key, Tag.TAG_COMPOUND) ? files().getCompound(key) : null;
    }

    public boolean exists(String path) {
        return get(path) != null;
    }

    public boolean remove(String path) {
        String key = norm(path);
        if (files().contains(key)) {
            files().remove(key);
            return true;
        }
        return false;
    }

    public boolean rename(String from, String to) {
        CompoundTag f = get(from);
        if (f == null) {
            return false;
        }
        remove(from);
        files().put(norm(to), f);
        mkdir(parent(norm(to)));
        return true;
    }

    public List<String> paths() {
        return new ArrayList<>(new TreeSet<>(files().getAllKeys()));
    }

    // ------------------------------------------------------------------ dirs

    public static String parent(String path) {
        String p = norm(path);
        int i = p.lastIndexOf('/');
        return i <= 0 ? "/" : p.substring(0, i);
    }

    public static String name(String path) {
        String p = norm(path);
        return p.substring(p.lastIndexOf('/') + 1);
    }

    public void mkdir(String dir) {
        String d = norm(dir);
        if ("/".equals(d)) {
            return;
        }
        ListTag dirs = strings("dirs");
        for (Tag tag : dirs) {
            if (tag.getAsString().equals(d)) {
                return;
            }
        }
        dirs.add(StringTag.valueOf(d));
        mkdir(parent(d));
    }

    public boolean isDir(String dir) {
        String d = norm(dir);
        if ("/".equals(d)) {
            return true;
        }
        for (Tag tag : strings("dirs")) {
            if (tag.getAsString().equals(d)) {
                return true;
            }
        }
        return false;
    }

    public void rmdir(String dir) {
        String d = norm(dir);
        ListTag dirs = strings("dirs");
        dirs.removeIf(tag -> tag.getAsString().equals(d) || tag.getAsString().startsWith(d + "/"));
        for (String p : paths()) {
            if (p.startsWith(d + "/")) {
                files().remove(p);
            }
        }
    }

    /** Immediate children of a directory: sub-directories end with '/'. */
    public List<String> children(String dir) {
        String d = norm(dir);
        String prefix = "/".equals(d) ? "/" : d + "/";
        TreeSet<String> dirsOut = new TreeSet<>();
        TreeSet<String> filesOut = new TreeSet<>();
        for (Tag tag : strings("dirs")) {
            String s = tag.getAsString();
            if (s.startsWith(prefix) && s.length() > prefix.length() && s.indexOf('/', prefix.length()) < 0) {
                dirsOut.add(s.substring(prefix.length()) + "/");
            }
        }
        for (String s : files().getAllKeys()) {
            if (s.startsWith(prefix) && s.indexOf('/', prefix.length()) < 0) {
                filesOut.add(s.substring(prefix.length()));
            }
        }
        List<String> out = new ArrayList<>(dirsOut);
        out.addAll(filesOut);
        return out;
    }

    // ------------------------------------------------------------------ lists

    public static void push(ListTag list, Tag value, int cap) {
        list.add(value);
        while (list.size() > cap) {
            list.remove(0);
        }
    }

    public void log(String line) {
        push(strings("logs"), StringTag.valueOf(line), MAX_LOG);
    }

    public void term(String line) {
        for (String l : line.split("\n", -1)) {
            push(strings("term"), StringTag.valueOf(l), MAX_TERM);
        }
    }

    public void history(String cmd) {
        push(strings("hist"), StringTag.valueOf(cmd), 30);
    }

    public CompoundTag msg(String from, String text, String time, String choices, String node) {
        CompoundTag m = new CompoundTag();
        m.putString("f", from);
        m.putString("x", text);
        m.putString("t", time);
        m.putString("ch", choices == null ? "" : choices);
        m.putString("n", node == null ? "" : node);
        push(compounds("msgs"), m, MAX_MSGS);
        t.putBoolean("unread", true);
        return m;
    }

    public boolean appUnlocked(String app) {
        for (Tag tag : strings("apps")) {
            if (tag.getAsString().equals(app)) {
                return true;
            }
        }
        return false;
    }

    public boolean unlock(String app) {
        if (appUnlocked(app)) {
            return false;
        }
        strings("apps").add(StringTag.valueOf(app));
        return true;
    }

    /** One-shot screen effects waiting for the next time the computer is opened. */
    public void queueFx(String fx) {
        push(strings("fxq"), StringTag.valueOf(fx), 6);
    }

    public String notes() {
        return t.getString("notes");
    }

    public void setNotes(String s) {
        t.putString("notes", s.length() > 4000 ? s.substring(0, 4000) : s);
    }

    public int clockOffset() {
        return t.getInt("clock");
    }

    public void setClockOffset(int ticks) {
        t.putInt("clock", ticks);
    }

    public boolean setting(String k) {
        return sub("set").getBoolean(k);
    }

    public void setSetting(String k, boolean v) {
        sub("set").putBoolean(k, v);
    }
}
