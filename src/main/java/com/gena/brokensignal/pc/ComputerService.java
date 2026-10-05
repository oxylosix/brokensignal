package com.gena.brokensignal.pc;

import com.gena.brokensignal.Chains;
import com.gena.brokensignal.Config;
import com.gena.brokensignal.Director;
import com.gena.brokensignal.EventCtx;
import com.gena.brokensignal.EventDef;
import com.gena.brokensignal.HorrorState;
import com.gena.brokensignal.ModRegistry;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.nbt.Tag;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * ComputerCore: server-authoritative simulation of the in-game computer.
 * The client only renders what it receives and sends back simple actions; every command,
 * file and message is processed here against the virtual file system. No real files,
 * programs or system data are ever accessed.
 *
 * Also the EventBridge: world events write into the computer (onWorldEvent / recordSighting),
 * computer interactions feed back into the world through Chains and the Director.
 */
public final class ComputerService {
    private static final Map<UUID, BlockPos> SESSIONS = new HashMap<>();

    private ComputerService() {}

    // ------------------------------------------------------------------ time

    public static String clock(ServerLevel lv, long offsetTicks) {
        long t = lv.getDayTime() + offsetTicks;
        long day = Math.floorDiv(t, 24000L) + 1;
        long in = Math.floorMod(t, 24000L);
        int h = (int) ((in / 1000 + 6) % 24);
        int m = (int) (in % 1000 * 60 / 1000);
        return String.format(Locale.ROOT, "день %d, %02d:%02d", day, h, m);
    }

    public static String clock(ServerPlayer p) {
        return clock(p.serverLevel(), Vfs.of(p).clockOffset());
    }

    public static String hostname(ServerPlayer p) {
        return "station-" + (Math.abs(p.getUUID().hashCode()) % 900 + 100);
    }

    // ------------------------------------------------------------------ setup

    public static Vfs vfs(ServerPlayer p) {
        Vfs v = Vfs.of(p);
        if (!v.initialized()) {
            String now = clock(p.serverLevel(), 0);
            String old = clock(p.serverLevel(), -24000L * 3 - 4100);
            for (String d : new String[] {"/desktop", "/documents", "/downloads", "/system", "/logs", "/archive"}) {
                v.mkdir(d);
            }
            v.put("/documents/readme.txt", "добро пожаловать.\n\nфайлы хранятся в /documents.\nне выключайте питание во время записи.\nкоманды терминала: help", old);
            v.put("/desktop/список.txt", "уголь\nжелезо — 12\nфакелы\nпочинить дверь\nне забыть про подвал", old);
            v.put("/system/boot.log", "загрузка... ок\nдиск... ок\nсеть... нет соединения\nдатчики... 3 из 4\nвход: " + p.getGameProfile().getName(), now);
            v.put("/system/hostname", hostname(p), old);
            v.log(now + "  система запущена");
            v.markInitialized();
        }
        return v;
    }

    public static boolean isOpen(ServerPlayer p) {
        return SESSIONS.containsKey(p.getUUID());
    }

    public static BlockPos session(ServerPlayer p) {
        return SESSIONS.get(p.getUUID());
    }

    public static void forget(ServerPlayer p) {
        SESSIONS.remove(p.getUUID());
    }

    public static void registerPc(ServerPlayer p, BlockPos pos) {
        HorrorState.of(p).remember("pcs", pos.immutable(), 6);
    }

    /** Drops remembered computers that no longer exist (only checks loaded chunks). */
    public static void prune(ServerPlayer p, HorrorState st) {
        ServerLevel lv = p.serverLevel();
        for (BlockPos pos : st.places("pcs")) {
            if (lv.isLoaded(pos) && !lv.getBlockState(pos).is(ModRegistry.COMPUTER.get())) {
                st.forget("pcs", pos);
            }
        }
    }

    public static void setLit(ServerLevel lv, BlockPos pos, boolean lit) {
        BlockState s = lv.getBlockState(pos);
        if (s.is(ModRegistry.COMPUTER.get()) && s.getValue(BlockStateProperties.LIT) != lit) {
            lv.setBlock(pos, s.setValue(BlockStateProperties.LIT, lit), 3);
        }
    }

    // ------------------------------------------------------------------ open / close

    public static void open(ServerPlayer p, BlockPos pos) {
        if (!Config.COMPUTER_EVENTS.get()) {
            p.displayClientMessage(net.minecraft.network.chat.Component.translatable("block.brokensignal.computer.off"), true);
            return;
        }
        registerPc(p, pos);
        HorrorState st = HorrorState.of(p);
        st.inc("pc_opens");
        st.setTime("pcOpenedAt", st.now());
        Vfs v = vfs(p);
        SESSIONS.put(p.getUUID(), pos.immutable());
        setLit(p.serverLevel(), pos, true);
        p.serverLevel().playSound(null, pos, SoundEvents.UI_BUTTON_CLICK.value(), SoundSource.BLOCKS, Config.vol(0.25F), 0.6F);
        Chains.onOpen(p, st, v, pos);
        List<String> fx = new ArrayList<>();
        for (Tag t : v.strings("fxq")) {
            fx.add(t.getAsString());
        }
        v.strings("fxq").clear();
        sync(p, String.join(";", fx));
    }

    private static void close(ServerPlayer p, Vfs v) {
        BlockPos pos = SESSIONS.remove(p.getUUID());
        // files that only exist while you look at them
        for (String path : v.paths()) {
            CompoundTag f = v.get(path);
            if (f != null && f.getBoolean("v") && f.getInt("r") > 0) {
                v.remove(path);
                Chains.onVanish(p, path);
            }
        }
        v.raw().putBoolean("unread", false);
        if (pos != null && p.level().isLoaded(pos) && !HorrorState.of(p).flag("pc_keep_on")) {
            setLit(p.serverLevel(), pos, false);
        }
    }

    // ------------------------------------------------------------------ sync

    public static void sync(ServerPlayer p, String fx) {
        BlockPos pos = SESSIONS.get(p.getUUID());
        if (pos == null) {
            return;
        }
        Vfs v = vfs(p);
        CompoundTag out = new CompoundTag();
        out.putString("host", hostname(p));
        out.putString("clock", clock(p));
        out.putString("cwd", v.raw().getString("cwd").isEmpty() ? "/" : v.raw().getString("cwd"));
        out.putString("fx", fx == null ? "" : fx);
        long day = p.serverLevel().getDayTime() % 24000L;
        boolean night = day >= 13000 && day < 23000;
        CompoundTag files = new CompoundTag();
        for (String path : v.paths()) {
            CompoundTag f = v.get(path);
            if (f == null || (f.getBoolean("n") && !night)) {
                continue;
            }
            CompoundTag c = new CompoundTag();
            c.putString("c", f.getBoolean("k") ? corrupt(f.getString("c"), path.hashCode()) : f.getString("c"));
            c.putString("m", f.getString("m"));
            c.putBoolean("h", f.getBoolean("h") || Vfs.name(path).startsWith("."));
            files.put(path, c);
        }
        out.put("files", files);
        out.put("dirs", v.strings("dirs").copy());
        out.put("msgs", v.compounds("msgs").copy());
        out.put("logs", v.strings("logs").copy());
        out.put("term", v.strings("term").copy());
        out.put("apps", v.strings("apps").copy());
        out.putString("notes", v.notes());
        out.putBoolean("sHidden", v.setting("hidden"));
        out.putBoolean("sCrt", v.setting("crt"));
        out.putBoolean("sQuiet", v.setting("quiet"));
        if (v.appUnlocked("cam")) {
            out.putString("cam", camFrame(p));
        }
        out.putString("unk", v.raw().getString("unk"));
        PacketDistributor.sendToPlayer(p, new ComputerSyncPayload(pos, out));
    }

    static String corrupt(String s, int seed) {
        RandomSource r = RandomSource.create(seed);
        StringBuilder b = new StringBuilder(s.length());
        for (char c : s.toCharArray()) {
            b.append(c != '\n' && r.nextInt(5) == 0 ? "▒░#?".charAt(r.nextInt(4)) : c);
        }
        return b.toString();
    }

    // ------------------------------------------------------------------ actions

    public static void handle(ComputerActionPayload msg, IPayloadContext ctx) {
        ctx.enqueueWork(() -> {
            if (!(ctx.player() instanceof ServerPlayer p)) {
                return;
            }
            BlockPos pos = SESSIONS.get(p.getUUID());
            if (pos == null) {
                return;
            }
            if (p.distanceToSqr(Vec3.atCenterOf(pos)) > 64.0 || !p.serverLevel().getBlockState(pos).is(ModRegistry.COMPUTER.get())) {
                close(p, vfs(p));
                return;
            }
            String arg = msg.arg().length() > 4000 ? msg.arg().substring(0, 4000) : msg.arg();
            act(p, msg.action(), arg);
        });
    }

    private static void act(ServerPlayer p, String action, String arg) {
        Vfs v = vfs(p);
        HorrorState st = HorrorState.of(p);
        switch (action) {
            case "close" -> close(p, v);
            case "cmd" -> {
                String fx = Terminal.run(p, v, st, arg);
                sync(p, fx);
            }
            case "read" -> {
                CompoundTag f = v.get(arg);
                if (f != null) {
                    f.putInt("r", f.getInt("r") + 1);
                    st.inc("pc_reads");
                    Chains.onRead(p, st, v, Vfs.norm(arg), f);
                }
                sync(p, "");
            }
            case "reply" -> {
                int sep = arg.indexOf(':');
                if (sep > 0) {
                    try {
                        int idx = Integer.parseInt(arg.substring(0, sep));
                        ListTag msgs = v.compounds("msgs");
                        if (idx >= 0 && idx < msgs.size()) {
                            CompoundTag m = msgs.getCompound(idx);
                            if (!m.getString("ch").isEmpty() && !m.getBoolean("a")) {
                                String choice = arg.substring(sep + 1);
                                m.putBoolean("a", true);
                                m.putString("ans", choice);
                                Chains.onReply(p, st, v, m.getString("n"), choice);
                            }
                        }
                    } catch (NumberFormatException e) {
                        Director.LOG.warn("[BrokenSignal] bad reply payload '{}' from {}", arg, p.getGameProfile().getName());
                    }
                }
                sync(p, "");
            }
            case "notes" -> {
                v.setNotes(arg);
                Chains.onNotes(p, st, v, arg);
            }
            case "app" -> {
                st.inc("app_" + arg);
                if ("listen".equals(arg) || "cam".equals(arg) || "unknown".equals(arg)) {
                    Chains.onApp(p, st, v, arg);
                }
                if ("msgs".equals(arg)) {
                    v.raw().putBoolean("unread", false);
                }
                sync(p, "");
            }
            case "listen" -> {
                Chains.onListen(p, st, v);
                sync(p, "");
            }
            case "setting" -> {
                int eq = arg.indexOf('=');
                if (eq > 0) {
                    String k = arg.substring(0, eq);
                    if (k.equals("hidden") || k.equals("crt") || k.equals("quiet")) {
                        v.setSetting(k, Boolean.parseBoolean(arg.substring(eq + 1)));
                        if (k.equals("hidden")) {
                            st.inc("saw_hidden");
                        }
                    }
                }
                sync(p, "");
            }
            default -> Director.LOG.warn("[BrokenSignal] unknown computer action '{}' from {}", action, p.getGameProfile().getName());
        }
    }

    // ------------------------------------------------------------------ helpers for events / chains

    public static void file(ServerPlayer p, String path, String content) {
        vfs(p).put(path, content, clock(p));
    }

    public static CompoundTag fileDated(ServerPlayer p, String path, String content, long offsetTicks) {
        return vfs(p).put(path, content, clock(p.serverLevel(), offsetTicks));
    }

    public static void msg(ServerPlayer p, String from, String text, String choices, String node) {
        vfs(p).msg(from, text, clock(p), choices, node);
        notifyIfOpen(p, "ping");
    }

    public static void log(ServerPlayer p, String line) {
        vfs(p).log(clock(p) + "  " + line);
    }

    /** Plays an effect now if the computer is open, otherwise the next time it is opened. */
    public static void fx(ServerPlayer p, String fx) {
        if (isOpen(p)) {
            sync(p, fx);
        } else {
            vfs(p).queueFx(fx);
        }
    }

    public static void notifyIfOpen(ServerPlayer p, String fx) {
        if (isOpen(p)) {
            sync(p, fx);
        }
    }

    // ------------------------------------------------------------------ bridge: world -> computer

    public static void onWorldEvent(ServerPlayer p, EventDef def, EventCtx ctx) {
        if (!Config.COMPUTER_EVENTS.get() || !ctx.hasPc() || def.cat == EventDef.Cat.COMPUTER) {
            return;
        }
        RandomSource r = p.getRandom();
        String dir = direction(p);
        String line = switch (def.cat) {
            case FIGURE -> "датчик " + (1 + r.nextInt(4)) + ": движение, ~" + (16 + r.nextInt(40)) + " м, " + dir;
            case WORLD -> "датчик " + (1 + r.nextInt(4)) + ": изменение среды, " + dir;
            case SOUND -> r.nextFloat() < 0.5F ? "микрофон: шум " + (20 + r.nextInt(30)) + " дБ" : null;
            case MESSAGE -> "сеть: входящее подключение (1)";
            case META -> "ошибка видеовыхода";
            default -> null;
        };
        if (line != null) {
            log(p, line);
        }
        // occasionally the computer already "knew": a short note dated a few minutes BEFORE it happened
        if ((def.cat == EventDef.Cat.FIGURE || def.size != EventDef.Size.SMALL) && ctx.phase >= 4 && r.nextFloat() < 0.25F) {
            String text = switch (def.cat) {
                case FIGURE -> "кто-то стоял " + dir + " от тебя.\nты не обернулся сразу.";
                case WORLD -> "рядом с " + p.getBlockX() + " " + p.getBlockZ() + " что-то изменится.\nты заметишь не сразу.";
                default -> "ты был в " + placeName(ctx.place) + ".\nбыло тихо.";
            };
            fileDated(p, "/documents/" + String.format(Locale.ROOT, "%04d", r.nextInt(10000)) + ".txt", text, -1200L * (2 + r.nextInt(6)));
        }
        notifyIfOpen(p, "flicker");
    }

    public static void recordSighting(ServerPlayer p, Vec3 where) {
        HorrorState st = HorrorState.of(p);
        st.setPos("sighting", BlockPos.containing(where));
        st.setTime("sightingAt", st.now());
        st.inc("sightings");
        if (!st.places("pcs").isEmpty()) {
            log(p, "камера: объект в кадре (" + (int) where.x + " " + (int) where.z + ")");
        }
        Chains.onSighting(p, st);
    }

    public static String direction(ServerPlayer p) {
        String[] dirs = {"на юге", "на западе", "на севере", "на востоке"};
        int i = Math.floorMod(Math.round((p.getYRot() + 180F) / 90F), 4);
        return dirs[i];
    }

    public static String placeName(EventCtx.Place place) {
        return switch (place) {
            case HOME -> "доме";
            case BASEMENT -> "подвале";
            case CAVE -> "пещере";
            case FOREST -> "лесу";
            case VILLAGE -> "деревне";
            case WATER -> "воде";
            case PC_ROOM -> "комнате с компьютером";
            case HOUSE_SITE -> "чужом доме";
            case EVENT_SITE -> "том же месте";
            case NETHER -> "другом месте";
            default -> "поле";
        };
    }

    /** 21x11 top-down sketch of the area around the last sighting. ~231 heightmap reads, only on demand. */
    public static String camFrame(ServerPlayer p) {
        HorrorState st = HorrorState.of(p);
        BlockPos mark = st.pos("sighting");
        BlockPos c = mark != null ? mark : p.blockPosition();
        ServerLevel lv = p.serverLevel();
        int cy = lv.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, c.getX(), c.getZ());
        StringBuilder b = new StringBuilder();
        for (int z = -5; z <= 5; z++) {
            for (int x = -10; x <= 10; x++) {
                int wx = c.getX() + x * 2;
                int wz = c.getZ() + z * 2;
                char ch;
                if (Math.abs(p.getBlockX() - wx) <= 1 && Math.abs(p.getBlockZ() - wz) <= 1) {
                    ch = '@';
                } else if (mark != null && x == 0 && z == 0) {
                    ch = '?';
                } else if (!lv.isLoaded(new BlockPos(wx, cy, wz))) {
                    ch = ' ';
                } else {
                    int h = lv.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, wx, wz);
                    int hl = lv.getHeight(Heightmap.Types.MOTION_BLOCKING, wx, wz);
                    BlockState top = lv.getBlockState(new BlockPos(wx, h - 1, wz));
                    if (!top.getFluidState().isEmpty()) {
                        ch = '~';
                    } else if (hl > h + 1) {
                        ch = 'T';
                    } else if (top.is(Blocks.COBBLESTONE) || top.is(net.minecraft.tags.BlockTags.PLANKS) || top.is(Blocks.STONE_BRICKS)) {
                        ch = '#';
                    } else if (h - cy > 4) {
                        ch = '^';
                    } else if (cy - h > 4) {
                        ch = '.';
                    } else {
                        ch = ',';
                    }
                }
                b.append(ch);
            }
            b.append('\n');
        }
        long ago = mark == null ? -1 : (st.now() - st.time("sightingAt")) / 1200;
        b.append(mark == null ? "нет записей" : "кадр: " + ago + " мин назад");
        return b.toString();
    }

    public static void addHistoryGhost(ServerPlayer p, String cmd) {
        Vfs v = vfs(p);
        v.history(cmd);
        v.strings("ghost").add(StringTag.valueOf(cmd));
    }
}
