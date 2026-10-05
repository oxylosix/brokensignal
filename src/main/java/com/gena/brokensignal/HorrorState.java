package com.gena.brokensignal;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerPlayer;

/**
 * Everything the mod remembers about one player. Lives in the player's persistent NBT, so it is
 * saved with the player file, survives restarts and is copied on death (see HorrorEvents.onClone).
 *
 * Clock: "ticks" is the player's own play time in this world (only advances while online), so
 * cooldowns, chains and delayed consequences never fire while the player is away and never all fire
 * at once after a long break.
 */
public final class HorrorState {
    public static final String ROOT = "brokensignal_state";
    public static final String TICKS = "brokensignal_ticks";
    private static final int HISTORY = 24;

    private final ServerPlayer player;
    private final CompoundTag tag;

    private HorrorState(ServerPlayer player, CompoundTag tag) {
        this.player = player;
        this.tag = tag;
    }

    public static HorrorState of(ServerPlayer p) {
        CompoundTag d = p.getPersistentData();
        if (!d.contains(ROOT, Tag.TAG_COMPOUND)) {
            d.put(ROOT, new CompoundTag());
        }
        return new HorrorState(p, d.getCompound(ROOT));
    }

    public CompoundTag raw() {
        return tag;
    }

    private CompoundTag sub(String key) {
        if (!tag.contains(key, Tag.TAG_COMPOUND)) {
            tag.put(key, new CompoundTag());
        }
        return tag.getCompound(key);
    }

    private ListTag list(String key, int type) {
        if (!tag.contains(key, Tag.TAG_LIST)) {
            tag.put(key, new ListTag());
        }
        return tag.getList(key, type);
    }

    // ------------------------------------------------------------ clock / progression

    public long now() {
        return player.getPersistentData().getInt(TICKS);
    }

    public int awareness() {
        return tag.getInt("aw");
    }

    public void addAwareness(int n) {
        tag.putInt("aw", Math.max(0, awareness() + n));
    }

    public int maxPhase() {
        return tag.getInt("maxPhase");
    }

    public void setMaxPhase(int phase) {
        tag.putInt("maxPhase", phase);
    }

    public int forcedPhase() {
        return tag.contains("forcePhase") ? tag.getInt("forcePhase") : -1;
    }

    public void setForcedPhase(int phase) {
        if (phase < 0) {
            tag.remove("forcePhase");
        } else {
            tag.putInt("forcePhase", phase);
        }
    }

    public float tension() {
        return tag.getFloat("tension");
    }

    public void setTension(float t) {
        tag.putFloat("tension", Math.max(0.0F, t));
    }

    public long nextEventAt() {
        return tag.getLong("next");
    }

    public void setNextEventAt(long t) {
        tag.putLong("next", t);
    }

    public String pace() {
        return tag.contains("pace") ? tag.getString("pace") : "calm";
    }

    public void setPace(String pace) {
        tag.putString("pace", pace);
    }

    // ------------------------------------------------------------ memory

    public int count(String key) {
        return sub("mem").getInt(key);
    }

    public int inc(String key) {
        CompoundTag m = sub("mem");
        int v = m.getInt(key) + 1;
        m.putInt(key, v);
        return v;
    }

    public void setCount(String key, int v) {
        sub("mem").putInt(key, v);
    }

    public String text(String key) {
        return sub("txt").getString(key);
    }

    public void setText(String key, String value) {
        sub("txt").putString(key, value);
    }

    public long time(String key) {
        return sub("time").getLong(key);
    }

    public void setTime(String key, long t) {
        sub("time").putLong(key, t);
    }

    public boolean flag(String key) {
        return sub("flags").getBoolean(key);
    }

    public void setFlag(String key, boolean v) {
        if (v) {
            sub("flags").putBoolean(key, true);
        } else {
            sub("flags").remove(key);
        }
    }

    public BlockPos pos(String key) {
        CompoundTag p = sub("pos");
        return p.contains(key) ? BlockPos.of(p.getLong(key)) : null;
    }

    public void setPos(String key, BlockPos pos) {
        if (pos == null) {
            sub("pos").remove(key);
        } else {
            sub("pos").putLong(key, pos.asLong());
        }
    }

    // ------------------------------------------------------------ event history

    public long lastFired(String id) {
        CompoundTag c = sub("last");
        return c.contains(id) ? c.getLong(id) : Long.MIN_VALUE / 4;
    }

    public long lastCategory(String cat) {
        CompoundTag c = sub("cat");
        return c.contains(cat) ? c.getLong(cat) : Long.MIN_VALUE / 4;
    }

    public int timesFired(String id) {
        return sub("fired").getInt(id);
    }

    public boolean onceDone(String id) {
        return sub("once").getBoolean(id);
    }

    public void record(EventDef def) {
        long t = now();
        sub("last").putLong(def.id, t);
        sub("cat").putLong(def.cat.name(), t);
        CompoundTag fired = sub("fired");
        fired.putInt(def.id, fired.getInt(def.id) + 1);
        if (def.once) {
            sub("once").putBoolean(def.id, true);
        }
        ListTag h = list("hist", Tag.TAG_STRING);
        h.add(StringTag.valueOf(def.id));
        while (h.size() > HISTORY) {
            h.remove(0);
        }
        tag.putString("lastCat", def.cat.name());
    }

    public List<String> history() {
        ListTag h = list("hist", Tag.TAG_STRING);
        List<String> out = new ArrayList<>(h.size());
        for (int i = 0; i < h.size(); i++) {
            out.add(h.getString(i));
        }
        return out;
    }

    public String lastCategoryName() {
        return tag.getString("lastCat");
    }

    public void resetEvent(String id) {
        sub("last").remove(id);
        sub("once").remove(id);
        sub("fired").remove(id);
    }

    // ------------------------------------------------------------ persistent scheduler

    public record Scheduled(String id, long at, String arg) {}

    public void schedule(String id, long at, String arg) {
        ListTag s = list("sched", Tag.TAG_COMPOUND);
        if (s.size() >= 32) {
            return; // bounded: never let consequences pile up
        }
        CompoundTag c = new CompoundTag();
        c.putString("id", id);
        c.putLong("at", at);
        c.putString("arg", arg == null ? "" : arg);
        s.add(c);
    }

    public List<Scheduled> popDue(long now) {
        ListTag s = list("sched", Tag.TAG_COMPOUND);
        List<Scheduled> due = new ArrayList<>();
        for (int i = s.size() - 1; i >= 0; i--) {
            CompoundTag c = s.getCompound(i);
            if (c.getLong("at") <= now) {
                due.add(new Scheduled(c.getString("id"), c.getLong("at"), c.getString("arg")));
                s.remove(i);
            }
        }
        return due;
    }

    public List<Scheduled> scheduled() {
        ListTag s = list("sched", Tag.TAG_COMPOUND);
        List<Scheduled> out = new ArrayList<>();
        for (int i = 0; i < s.size(); i++) {
            CompoundTag c = s.getCompound(i);
            out.add(new Scheduled(c.getString("id"), c.getLong("at"), c.getString("arg")));
        }
        return out;
    }

    // ------------------------------------------------------------ chains

    public int chainStep(String id) {
        CompoundTag c = sub("chains");
        return c.contains(id) ? c.getCompound(id).getInt("s") : -1;
    }

    public long chainDue(String id) {
        CompoundTag c = sub("chains");
        return c.contains(id) ? c.getCompound(id).getLong("at") : 0L;
    }

    public void setChain(String id, int step, long due) {
        CompoundTag c = new CompoundTag();
        c.putInt("s", step);
        c.putLong("at", due);
        sub("chains").put(id, c);
    }

    public boolean chainDone(String id) {
        return sub("chainsDone").getBoolean(id);
    }

    public void finishChain(String id) {
        sub("chains").remove(id);
        sub("chainsDone").putBoolean(id, true);
    }

    public void resetChain(String id) {
        sub("chains").remove(id);
        sub("chainsDone").remove(id);
    }

    public List<String> activeChains() {
        return new ArrayList<>(sub("chains").getAllKeys());
    }

    public int chainsFinished() {
        return sub("chainsDone").size();
    }

    // ------------------------------------------------------------ places

    /** Remember a position in a bounded list (computers, event sites, hot spots). */
    public void remember(String key, BlockPos pos, int max) {
        ListTag l = list(key, Tag.TAG_LONG);
        long v = pos.asLong();
        for (int i = 0; i < l.size(); i++) {
            if (((net.minecraft.nbt.LongTag) l.get(i)).getAsLong() == v) {
                l.remove(i);
                break;
            }
        }
        l.add(net.minecraft.nbt.LongTag.valueOf(v));
        while (l.size() > max) {
            l.remove(0);
        }
    }

    public void forget(String key, BlockPos pos) {
        ListTag l = list(key, Tag.TAG_LONG);
        long v = pos.asLong();
        for (int i = l.size() - 1; i >= 0; i--) {
            if (((net.minecraft.nbt.LongTag) l.get(i)).getAsLong() == v) {
                l.remove(i);
            }
        }
    }

    public List<BlockPos> places(String key) {
        ListTag l = list(key, Tag.TAG_LONG);
        List<BlockPos> out = new ArrayList<>(l.size());
        for (int i = 0; i < l.size(); i++) {
            out.add(BlockPos.of(((net.minecraft.nbt.LongTag) l.get(i)).getAsLong()));
        }
        return out;
    }

    /** Hot spots: 16x16 cells where the player spends time. Returns the visit count of the cell. */
    public int visit(BlockPos pos) {
        ListTag l = list("spots", Tag.TAG_COMPOUND);
        int cx = pos.getX() >> 4;
        int cz = pos.getZ() >> 4;
        for (int i = 0; i < l.size(); i++) {
            CompoundTag c = l.getCompound(i);
            if (c.getInt("cx") == cx && c.getInt("cz") == cz) {
                int n = c.getInt("n") + 1;
                c.putInt("n", n);
                c.putLong("p", pos.asLong());
                return n;
            }
        }
        if (l.size() >= 12) {
            int min = 0;
            for (int i = 1; i < l.size(); i++) {
                if (l.getCompound(i).getInt("n") < l.getCompound(min).getInt("n")) {
                    min = i;
                }
            }
            l.remove(min);
        }
        CompoundTag c = new CompoundTag();
        c.putInt("cx", cx);
        c.putInt("cz", cz);
        c.putInt("n", 1);
        c.putLong("p", pos.asLong());
        l.add(c);
        return 1;
    }

    /** Most visited spot and its count, or null. */
    public BlockPos favouriteSpot(int[] countOut) {
        ListTag l = list("spots", Tag.TAG_COMPOUND);
        CompoundTag best = null;
        for (int i = 0; i < l.size(); i++) {
            CompoundTag c = l.getCompound(i);
            if (best == null || c.getInt("n") > best.getInt("n")) {
                best = c;
            }
        }
        if (best == null) {
            return null;
        }
        if (countOut != null && countOut.length > 0) {
            countOut[0] = best.getInt("n");
        }
        return BlockPos.of(best.getLong("p"));
    }

    public void resetAll() {
        for (String k : new ArrayList<>(tag.getAllKeys())) {
            tag.remove(k);
        }
    }
}
