package com.gena.brokensignal.mimic;

import com.gena.brokensignal.HorrorState;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;

/**
 * What the game has learned about how a player plays. Only in-game data: positions, rotations,
 * blocks broken/placed, containers opened. Used by mimics to behave like that player and to
 * replay "work clips" (a few minutes of what the player did) on a later day.
 *
 * Persistent part lives in HorrorState under "habits"; the rolling buffer is memory-only.
 */
public final class Habits {
    public static final byte NONE = 0, BREAK = 1, PLACE = 2, OPEN = 3, ATTACK = 4;

    /** One sample every SAMPLE ticks. */
    public static final int SAMPLE = 10;
    private static final int BUFFER = 240;
    private static final int CLIP_LEN = 120;
    private static final int MAX_CLIPS = 6;

    public record Sample(double x, double y, double z, float yaw, float pitch, byte action, long actPos, boolean sprint, boolean crouch) {}

    public record Clip(long day, String dim, List<Sample> samples) {
        public BlockPos origin() {
            Sample s = samples.get(0);
            return BlockPos.containing(s.x, s.y, s.z);
        }
    }

    private static final Map<UUID, Live> LIVE = new HashMap<>();

    private static final class Live {
        final ArrayDeque<Sample> buf = new ArrayDeque<>();
        byte pendingAction = NONE;
        long pendingPos = 0L;
        int recentWork = 0;
        long lastClipAt = -100000L;
    }

    private Habits() {}

    private static Live live(ServerPlayer p) {
        return LIVE.computeIfAbsent(p.getUUID(), k -> new Live());
    }

    public static void forget(ServerPlayer p) {
        LIVE.remove(p.getUUID());
    }

    private static CompoundTag tag(ServerPlayer p) {
        CompoundTag root = HorrorState.of(p).raw();
        if (!root.contains("habits", Tag.TAG_COMPOUND)) {
            root.put("habits", new CompoundTag());
        }
        return root.getCompound("habits");
    }

    // ------------------------------------------------------------------ recording

    public static void tick(ServerPlayer p) {
        if (p.tickCount % SAMPLE != 0 || p.isSpectator()) {
            return;
        }
        Live l = live(p);
        Sample s = new Sample(p.getX(), p.getY(), p.getZ(), p.getYRot(), p.getXRot(), l.pendingAction, l.pendingPos,
                p.isSprinting(), p.isCrouching());
        l.pendingAction = NONE;
        l.buf.addLast(s);
        while (l.buf.size() > BUFFER) {
            Sample old = l.buf.removeFirst();
            if (old.action == BREAK || old.action == PLACE) {
                l.recentWork = Math.max(0, l.recentWork - 1);
            }
        }
        CompoundTag h = tag(p);
        h.putInt("samples", h.getInt("samples") + 1);
        if (s.sprint) {
            h.putInt("sprint", h.getInt("sprint") + 1);
        }
        if (s.crouch) {
            h.putInt("crouch", h.getInt("crouch") + 1);
        }
        if (!p.getMainHandItem().isEmpty()) {
            h.putString("hand", BuiltInRegistries.ITEM.getKey(p.getMainHandItem().getItem()).toString());
        }
        long now = HorrorState.of(p).now();
        // a work clip: the player has been breaking/placing for a while; keep the last CLIP_LEN samples
        if (l.recentWork >= 6 && l.buf.size() >= CLIP_LEN && now - l.lastClipAt > 20L * 60 * 6) {
            saveClip(p, l);
            l.lastClipAt = now;
            l.recentWork = 0;
        }
    }

    public static void action(ServerPlayer p, byte action, BlockPos pos) {
        Live l = live(p);
        l.pendingAction = action;
        l.pendingPos = pos.asLong();
        CompoundTag h = tag(p);
        String k = switch (action) {
            case BREAK -> "breaks";
            case PLACE -> "places";
            case OPEN -> "opens";
            default -> "attacks";
        };
        h.putInt(k, h.getInt(k) + 1);
        if (action == BREAK || action == PLACE) {
            l.recentWork++;
        }
    }

    public static void chop(ServerPlayer p, boolean log) {
        CompoundTag h = tag(p);
        String k = log ? "chops" : "digs";
        h.putInt(k, h.getInt(k) + 1);
    }

    private static void saveClip(ServerPlayer p, Live l) {
        List<Sample> all = new ArrayList<>(l.buf);
        List<Sample> part = all.subList(all.size() - CLIP_LEN, all.size());
        ListTag samples = new ListTag();
        for (Sample s : part) {
            CompoundTag t = new CompoundTag();
            t.putFloat("x", (float) s.x);
            t.putFloat("y", (float) s.y);
            t.putFloat("z", (float) s.z);
            t.putByte("r", (byte) Math.round(s.yaw / 360F * 256F));
            t.putByte("p", (byte) Math.round(s.pitch));
            if (s.action != NONE) {
                t.putByte("a", s.action);
                t.putLong("ap", s.actPos);
            }
            if (s.sprint) {
                t.putBoolean("s", true);
            }
            samples.add(t);
        }
        CompoundTag clip = new CompoundTag();
        clip.putLong("day", p.serverLevel().getDayTime() / 24000L);
        clip.putString("dim", p.serverLevel().dimension().location().toString());
        clip.put("s", samples);
        CompoundTag h = tag(p);
        ListTag clips = h.getList("clips", Tag.TAG_COMPOUND);
        clips.add(clip);
        while (clips.size() > MAX_CLIPS) {
            clips.remove(0);
        }
        h.put("clips", clips);
    }

    // ------------------------------------------------------------------ reading

    public static List<Clip> clips(ServerPlayer p) {
        ListTag clips = tag(p).getList("clips", Tag.TAG_COMPOUND);
        List<Clip> out = new ArrayList<>();
        for (int i = 0; i < clips.size(); i++) {
            CompoundTag c = clips.getCompound(i);
            ListTag ss = c.getList("s", Tag.TAG_COMPOUND);
            List<Sample> list = new ArrayList<>(ss.size());
            for (int j = 0; j < ss.size(); j++) {
                CompoundTag t = ss.getCompound(j);
                list.add(new Sample(t.getFloat("x"), t.getFloat("y"), t.getFloat("z"), (t.getByte("r") & 0xFF) * 360F / 256F,
                        t.getByte("p"), t.getByte("a"), t.getLong("ap"), t.getBoolean("s"), false));
            }
            if (!list.isEmpty()) {
                out.add(new Clip(c.getLong("day"), c.getString("dim"), list));
            }
        }
        return out;
    }

    /** A clip recorded on an earlier in-game day in the player's current dimension, or null. */
    public static Clip olderClip(ServerPlayer p) {
        long today = p.serverLevel().getDayTime() / 24000L;
        String dim = p.serverLevel().dimension().location().toString();
        Clip best = null;
        for (Clip c : clips(p)) {
            if (c.day < today && c.dim.equals(dim)) {
                best = c;
            }
        }
        return best;
    }

    /** The last few seconds of live samples (for "does the same as you a moment later"). */
    public static List<Sample> recent(ServerPlayer p, int n) {
        List<Sample> all = new ArrayList<>(live(p).buf);
        return all.subList(Math.max(0, all.size() - n), all.size());
    }

    public static float sprintRatio(ServerPlayer p) {
        CompoundTag h = tag(p);
        int n = Math.max(1, h.getInt("samples"));
        return (float) h.getInt("sprint") / n;
    }

    public static float crouchRatio(ServerPlayer p) {
        CompoundTag h = tag(p);
        int n = Math.max(1, h.getInt("samples"));
        return (float) h.getInt("crouch") / n;
    }

    /** "chop", "dig" or "build": what this player does most. */
    public static String trade(ServerPlayer p) {
        CompoundTag h = tag(p);
        int chop = h.getInt("chops"), dig = h.getInt("digs"), build = h.getInt("places");
        if (build > chop && build > dig) {
            return "build";
        }
        return dig > chop ? "dig" : "chop";
    }

    /**
     * How well the game "knows" this player, 40..100. Grows with observed play time (about two
     * hours of play to reach 100). Never shown to anyone.
     */
    public static int similarity(ServerPlayer p) {
        return Math.min(100, 40 + tag(p).getInt("samples") / 240);
    }

    // ------------------------------------------------------------------ pets (fed animals)

    public static void fed(ServerPlayer p, UUID animal) {
        CompoundTag pets = tag(p).getCompound("pets");
        String k = animal.toString();
        pets.putInt(k, pets.getInt(k) + 1);
        if (pets.size() > 24) {
            String weakest = null;
            int w = Integer.MAX_VALUE;
            for (String key : pets.getAllKeys()) {
                if (pets.getInt(key) < w) {
                    w = pets.getInt(key);
                    weakest = key;
                }
            }
            pets.remove(weakest);
        }
        tag(p).put("pets", pets);
    }

    /** Animals this player has fed at least {@code min} times. */
    public static List<UUID> pets(ServerPlayer p, int min) {
        CompoundTag pets = tag(p).getCompound("pets");
        List<UUID> out = new ArrayList<>();
        for (String k : pets.getAllKeys()) {
            if (pets.getInt(k) >= min) {
                try {
                    out.add(UUID.fromString(k));
                } catch (IllegalArgumentException e) {
                    pets.remove(k);
                    com.mojang.logging.LogUtils.getLogger().debug("dropping malformed pet id {}", k);
                    break;
                }
            }
        }
        return out;
    }

    public static Vec3 pos(Sample s) {
        return new Vec3(s.x, s.y, s.z);
    }
}
