package com.gena.brokensignal;

import com.gena.brokensignal.pc.ComputerBlock;
import com.gena.brokensignal.pc.ComputerService;
import com.gena.brokensignal.pc.Vfs;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.LodestoneTracker;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;

/**
 * Persistent multi-step scenarios (state lives in HorrorState "chains", so they survive relogs).
 * Each chain is a small state machine; steps are advanced either by time (tick) or by
 * player interaction hooks (computer reads, replies, commands, sightings, deaths).
 *
 * Most chains cross the WORLD <-> COMPUTER boundary in both directions.
 */
public final class Chains {
    public static final List<String> IDS = List.of(
            "delivery", "unsent", "camera", "sector7", "listener", "prophecy", "deathlog",
            "echo", "dialog", "night_msg", "shutdown", "house_pc", "copies");

    private static final int MAX_ACTIVE = 2;

    private Chains() {}

    private static long min(double m) {
        return (long) (m * 1200);
    }

    private static void next(HorrorState st, String id, int step, long delay) {
        st.setChain(id, step, st.now() + delay);
    }

    private static void hold(HorrorState st, String id, int step) {
        st.setChain(id, step, st.now() + 600);
    }

    private static void done(ServerPlayer p, HorrorState st, String id) {
        st.finishChain(id);
        st.addAwareness(2);
        if (Config.LOG_EVENTS.get()) {
            Director.LOG.info("[BrokenSignal] chain '{}' finished for {}", id, p.getGameProfile().getName());
        }
    }

    private static boolean available(HorrorState st, String id) {
        return !st.chainDone(id) && st.chainStep(id) < 0;
    }

    public static boolean start(ServerPlayer p, HorrorState st, String id) {
        if (!IDS.contains(id)) {
            return false;
        }
        st.resetChain(id);
        st.setChain(id, 0, st.now());
        st.setTime("chain_" + id, st.now());
        return true;
    }

    static boolean canEdit(HorrorState st) {
        return Config.WORLD_EDITS.get() && st.count("world_changes") < Config.MAX_WORLD_CHANGES.get();
    }

    private static boolean hasPc(HorrorState st) {
        return !st.places("pcs").isEmpty();
    }

    // ------------------------------------------------------------------ tick

    public static void tick(ServerPlayer p, HorrorState st, long now) {
        if (!Config.COMPUTER_EVENTS.get()) {
            return;
        }
        if (now % 600 < 20) {
            maybeStart(p, st);
        }
        for (String id : st.activeChains()) {
            if (st.chainDue(id) <= now) {
                try {
                    step(p, st, id, st.chainStep(id));
                } catch (RuntimeException e) {
                    Director.LOG.error("[BrokenSignal] chain '{}' step {} failed for {}; chain stopped", id, st.chainStep(id), p.getGameProfile().getName(), e);
                    st.finishChain(id);
                }
            }
        }
    }

    private static void maybeStart(ServerPlayer p, HorrorState st) {
        if (st.activeChains().size() >= MAX_ACTIVE) {
            return;
        }
        int ph = Director.phase(p);
        RandomSource r = p.getRandom();
        boolean pc = hasPc(st);
        int opens = st.count("pc_opens");
        String id = null;
        if (!pc && ph >= 2 && available(st, "delivery") && canEdit(st)) {
            id = "delivery";
        } else if (pc && r.nextFloat() < 0.35F) {
            String[] order = {"copies", "unsent", "dialog", "listener", "sector7", "echo", "prophecy", "night_msg", "house_pc"};
            int startAt = r.nextInt(order.length);
            for (int i = 0; i < order.length && id == null; i++) {
                String c = order[(startAt + i) % order.length];
                if (!available(st, c)) {
                    continue;
                }
                boolean ok = switch (c) {
                    case "copies" -> ph >= 3;
                    case "unsent" -> ph >= 3 && opens >= 2;
                    case "dialog", "listener" -> ph >= 4;
                    case "sector7" -> ph >= 4 && opens >= 3;
                    case "echo" -> ph >= 4 && st.count("chats") >= 3;
                    case "prophecy" -> ph >= 5 && opens >= 4;
                    case "night_msg" -> ph >= 6 && isNight(p) && st.chainDone("dialog");
                    case "house_pc" -> ph >= 4 && HorrorEvents.house(p) != null && canEdit(st);
                    default -> false;
                };
                if (ok) {
                    id = c;
                }
            }
        }
        if (id != null) {
            start(p, st, id);
        }
    }

    static boolean isNight(ServerPlayer p) {
        long d = p.serverLevel().getDayTime() % 24000L;
        return d >= 13000 && d < 23000;
    }

    // ------------------------------------------------------------------ steps

    private static void step(ServerPlayer p, HorrorState st, String id, int s) {
        ServerLevel lv = p.serverLevel();
        RandomSource r = p.getRandom();
        Vfs v = ComputerService.vfs(p);
        switch (id) {
            case "delivery" -> {
                // a computer appears next to the bed, discovered after sleeping
                if (st.time("wokeAt") <= st.time("chain_delivery")) {
                    hold(st, id, 0);
                    return;
                }
                BlockPos bed = p.getRespawnPosition();
                BlockPos spot = bed == null ? null : freeSpotNear(lv, bed, 2);
                if (spot == null) {
                    st.setTime("chain_delivery", st.now());
                    hold(st, id, 0);
                    return;
                }
                placeComputer(p, st, spot, Direction.Plane.HORIZONTAL.getRandomDirection(r), false);
                done(p, st, id);
            }
            case "unsent" -> {
                switch (s) {
                    case 0 -> {
                        CompoundTag f = v.put("/documents/unsent.txt",
                                "я не помню, когда поставил этот дом.\nдверь была с другой стороны.\nсегодня утром я считал факелы, их было на один больше.\n\nесли будешь читать это после меня, проверь то, что под землёй. там",
                                ComputerService.clock(lv, -min(40)));
                        f.putBoolean("v", true);
                        hold(st, id, 1);
                    }
                    case 1 -> hold(st, id, 1); // advanced by onVanish
                    case 2 -> {
                        BlockPos room = canEdit(st) ? buildRoom(p, st) : null;
                        String tail = room == null
                                ? "там ничего нет. я проверял дважды."
                                : "там\n\n" + room.getX() + " " + room.getY() + " " + room.getZ();
                        v.put("/archive/unsent (2).txt", "...считал факелы, их было на один больше.\n\nпроверь то, что под землёй. " + tail, ComputerService.clock(lv, -min(41)));
                        ComputerService.log(p, "архив: восстановлен 1 файл");
                        if (room == null) {
                            done(p, st, id);
                        } else {
                            hold(st, id, 3);
                        }
                    }
                    default -> hold(st, id, 3); // finished in onOpen at the buried computer
                }
            }
            case "camera" -> {
                BlockPos at = st.pos("sighting");
                if (s == 0) {
                    if (v.unlock("cam")) {
                        ComputerService.msg(p, "система", "подключено устройство: камера (1).\nприложение cam доступно.", null, null);
                    }
                    next(st, id, 1, 600);
                } else if (s == 1) {
                    if (at != null && p.blockPosition().distSqr(at) < 36 && canEdit(st)) {
                        BlockPos sign = HorrorEvents.stand(lv, at.getX() + 0.5, at.getZ() + 0.5, at.getY(), 6);
                        if (sign != null && lv.getBlockState(sign).isAir()) {
                            String when = ComputerService.clock(lv, -(st.now() - st.time("sightingAt")));
                            int comma = when.indexOf(',');
                            HorrorEvents.placeSign(lv, p, sign, new String[] {"", when.substring(0, comma), when.substring(comma + 2), ""});
                            st.inc("world_changes");
                        }
                        next(st, id, 2, min(4 + r.nextInt(5)));
                    } else if (st.now() - st.time("chain_camera") > min(60)) {
                        next(st, id, 2, 0);
                    } else {
                        hold(st, id, 1);
                    }
                } else {
                    ComputerService.log(p, "камера: запись удалена пользователем гость");
                    done(p, st, id);
                }
            }
            case "sector7" -> {
                if (s == 0) {
                    String key = String.valueOf(1000 + r.nextInt(9000));
                    BlockPos home = p.getRespawnPosition() != null ? p.getRespawnPosition() : p.blockPosition();
                    double ang = r.nextDouble() * Math.PI * 2;
                    int cx = home.getX() + (int) (Math.cos(ang) * (16 + r.nextInt(10)));
                    int cz = home.getZ() + (int) (Math.sin(ang) * (16 + r.nextInt(10)));
                    int cy = lv.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, cx, cz) - 3;
                    BlockPos chest = new BlockPos(cx, cy, cz);
                    st.setPos("s7chest", chest);
                    CompoundTag f = v.put("/archive/s7.dat", "s7 " + key.hashCode(), ComputerService.clock(lv, -24000L * 6));
                    f.putBoolean("k", true);
                    f.putString("key", key);
                    f.putString("plain", "сектор 7\n\n" + cx + " " + cz + "\nглубина: 3");
                    ComputerService.addHistoryGhost(p, "decrypt /archive/s7.dat ????");
                    BlockPos signPos = null;
                    if (canEdit(st)) {
                        double a2 = r.nextDouble() * Math.PI * 2;
                        int d = 8 + r.nextInt(7);
                        signPos = HorrorEvents.stand(lv, home.getX() + Math.cos(a2) * d, home.getZ() + Math.sin(a2) * d, home.getY(), 8);
                        if (signPos != null && lv.getBlockState(signPos).isAir()) {
                            HorrorEvents.placeSign(lv, p, signPos, new String[] {"", key, "", ""});
                            st.inc("world_changes");
                            ComputerService.log(p, "резервная копия ключа: снаружи, ~" + d + " м от кровати");
                        } else {
                            signPos = null;
                        }
                    }
                    if (signPos == null) {
                        v.put("/system/.key", key, ComputerService.clock(lv, -24000L * 6));
                    }
                    hold(st, id, 1);
                } else {
                    hold(st, id, 1); // advanced by onCmd(decrypt)
                }
            }
            case "listener" -> {
                if (s == 0) {
                    v.mkdir("/system/.listen");
                    v.put("/system/.listen/readme", "приложение не установлено.\nзапуск: open listen", ComputerService.clock(lv, -24000L));
                    st.setFlag("listen_hint", true);
                }
                hold(st, id, 1); // advanced by onListen / kill
            }
            case "prophecy" -> {
                if (s == 0) {
                    int[] at = {3 + r.nextInt(2), 7 + r.nextInt(3), 12 + r.nextInt(4)};
                    String[] ev = {"knock", "far_door", "silence"};
                    String[] word = {"стук", "дверь", "тишина"};
                    StringBuilder b = new StringBuilder();
                    for (int i = 0; i < 3; i++) {
                        b.append(ComputerService.clock(lv, min(at[i]))).append("   ").append(word[i]).append('\n');
                        st.schedule(ev[i], st.now() + min(at[i]), "");
                    }
                    v.put("/documents/расписание.log", b.toString().stripTrailing(), ComputerService.clock(lv, min(at[2] + 1)));
                    next(st, id, 1, min(at[2] + 1));
                } else {
                    CompoundTag f = v.get("/documents/расписание.log");
                    if (f != null) {
                        f.putString("c", f.getString("c") + "\n\nверно.");
                    }
                    done(p, st, id);
                }
            }
            case "deathlog" -> {
                BlockPos d = st.pos("death");
                if (s == 0) {
                    CompoundTag f = v.get("/logs/death.log");
                    if (f != null && d != null) {
                        f.putString("c", f.getString("c") + "\n" + ComputerService.clock(lv, 24000L) + "   " + d.getX() + " " + d.getY() + " " + d.getZ());
                    }
                    next(st, id, 1, 600);
                } else if (d != null && p.blockPosition().distSqr(d) < 64 && canEdit(st)) {
                    BlockPos at = HorrorEvents.stand(lv, d.getX() + 0.5, d.getZ() + 0.5, d.getY(), 5);
                    if (at != null && lv.getBlockState(at).isAir()) {
                        lv.setBlock(at, Blocks.CANDLE.defaultBlockState().setValue(net.minecraft.world.level.block.CandleBlock.LIT, true), 3);
                        st.inc("world_changes");
                    }
                    done(p, st, id);
                } else if (st.now() - st.time("chain_deathlog") > min(120)) {
                    done(p, st, id);
                } else {
                    hold(st, id, 1);
                }
            }
            case "echo" -> {
                String last = st.text("lastChat");
                if (!last.isEmpty()) {
                    appendFile(p, v, "/logs/chat.log", ComputerService.clock(lv, min(2 + r.nextInt(3))) + "  <" + p.getGameProfile().getName() + "> " + last);
                }
                done(p, st, id);
            }
            case "dialog" -> {
                if (s == 0) {
                    ComputerService.msg(p, "гость", "это ты оставил свет у двери?", "да|нет|кто это", "dlg1");
                    next(st, id, 1, min(15));
                } else {
                    ComputerService.msg(p, "гость", "ладно.", null, null);
                    done(p, st, id);
                }
            }
            case "night_msg" -> {
                if (s == 0) {
                    ComputerService.msg(p, "гость", "не спишь?", "нет|сплю", "night1");
                    next(st, id, 1, min(20));
                } else {
                    done(p, st, id);
                }
            }
            case "shutdown" -> {
                BlockPos pc = EventCtx.nearest(st.places("pcs"), p.blockPosition(), 256);
                if (s == 0) {
                    if (!isNight(p) || pc == null || !lv.isLoaded(pc)) {
                        hold(st, id, 0);
                        return;
                    }
                    st.setFlag("pc_keep_on", true);
                    ComputerService.setLit(lv, pc, true);
                    lv.playSound(null, pc, SoundEvents.UI_BUTTON_CLICK.value(), net.minecraft.sounds.SoundSource.BLOCKS, Config.vol(0.2F), 0.5F);
                    ComputerService.log(p, "включение: удалённое");
                    v.queueFx("autotype:не выключай");
                    next(st, id, 1, min(6));
                } else {
                    st.setFlag("pc_keep_on", false);
                    if (pc != null && lv.isLoaded(pc) && !ComputerService.isOpen(p)) {
                        ComputerService.setLit(lv, pc, false);
                    }
                    done(p, st, id);
                }
            }
            case "house_pc" -> {
                if (s == 0) {
                    BlockPos house = HorrorEvents.house(p);
                    BlockPos spot = house == null ? null : freeSpotNear(lv, house, 3);
                    if (spot == null) {
                        done(p, st, id);
                        return;
                    }
                    placeComputer(p, st, spot, Direction.Plane.HORIZONTAL.getRandomDirection(r), false);
                    st.setPos("house_pc", spot);
                }
                hold(st, id, 1); // finished in onOpen
            }
            case "copies" -> {
                if (s == 0) {
                    v.put("/desktop/список.txt\u200b", "уголь\nжелезо — 12\nфакелы\nпочинить дверь\nне спускаться в подвал", ComputerService.clock(lv, -24000L * 3 - 4100));
                    next(st, id, 1, min(20 + r.nextInt(20)));
                } else {
                    CompoundTag f = v.get("/desktop/список.txt");
                    if (f != null) {
                        f.putString("c", f.getString("c").replace("починить дверь", "дверь больше не закрывать"));
                    }
                    done(p, st, id);
                }
            }
            default -> st.finishChain(id);
        }
    }

    // ------------------------------------------------------------------ hooks (computer -> world)

    public static void onOpen(ServerPlayer p, HorrorState st, Vfs v, BlockPos pos) {
        if (st.chainStep("unsent") == 3 && pos.equals(st.pos("unsent_pc"))) {
            v.put("/documents/unsent.txt", "я не помню, когда поставил этот дом.\nдверь была с другой стороны.\nсегодня утром я считал факелы, их было на один больше.\n\nесли будешь читать это после меня, проверь то, что под землёй. там тот же компьютер.\nон включён. я его не включал.", ComputerService.clock(p.serverLevel(), -min(40)));
            done(p, st, "unsent");
        }
        if (st.chainStep("house_pc") == 1 && pos.equals(st.pos("house_pc"))) {
            v.put("/documents/дом.txt", "последний вход: день 0, 00:00\nпользователь: " + p.getGameProfile().getName(), "день 0, 00:00");
            done(p, st, "house_pc");
        }
        // secret: opened exactly at midnight several times
        long d = p.serverLevel().getDayTime() % 24000L;
        if (Config.SECRETS.get() && d >= 17800 && d <= 18200 && st.inc("midnight_opens") == 3 && v.unlock("unknown")) {
            v.raw().putString("unk", "—\n\nты открываешь его в одно и то же время.\nя тоже.");
        }
    }

    public static void onVanish(ServerPlayer p, String path) {
        HorrorState st = HorrorState.of(p);
        if (path.equals("/documents/unsent.txt") && st.chainStep("unsent") == 1) {
            next(st, "unsent", 2, min(10 + p.getRandom().nextInt(12)));
        }
    }

    public static void onRead(ServerPlayer p, HorrorState st, Vfs v, String path, CompoundTag f) {
        if (path.equals("/system/.listen/readme")) {
            st.setFlag("listen_hint", true);
        }
        if (path.startsWith("/documents/") && f.getInt("r") == 3 && Config.SECRETS.get() && !f.getBoolean("k")) {
            // a file read too many times starts reading back
            f.putString("c", f.getString("c") + "\n\n(прочитано: 3)");
        }
    }

    public static void onDelete(ServerPlayer p, HorrorState st, Vfs v, String path, CompoundTag f) {
        if (path.startsWith("/documents/") && Director.phase(p) >= 5 && p.getRandom().nextFloat() < 0.3F) {
            // it comes back later, slightly different
            st.schedule("pc_restore", st.now() + min(8 + p.getRandom().nextInt(10)), path);
            st.setText("restore_" + Integer.toHexString(path.hashCode()), f.getString("c"));
        }
    }

    public static void onCmd(ServerPlayer p, HorrorState st, Vfs v, String cmd, String arg) {
        switch (cmd) {
            case "decrypt" -> {
                if (arg.equals("/archive/s7.dat") && st.chainStep("sector7") == 1) {
                    BlockPos c = st.pos("s7chest");
                    if (c != null && canEdit(st)) {
                        ServerLevel lv = p.serverLevel();
                        lv.setBlock(c, Blocks.CHEST.defaultBlockState(), 3);
                        if (lv.getBlockEntity(c) instanceof ChestBlockEntity chest) {
                            ItemStack paper = new ItemStack(Items.PAPER);
                            paper.set(DataComponents.CUSTOM_NAME, Component.literal("сектор 7"));
                            chest.setItem(4, paper);
                            BlockPos home = p.getRespawnPosition();
                            if (home != null) {
                                ItemStack compass = new ItemStack(Items.COMPASS);
                                compass.set(DataComponents.LODESTONE_TRACKER, new LodestoneTracker(Optional.of(GlobalPos.of(p.getRespawnDimension(), home)), false));
                                compass.set(DataComponents.CUSTOM_NAME, Component.literal("обратно"));
                                chest.setItem(13, compass);
                            }
                        }
                        st.inc("world_changes");
                        st.remember("sites", c, 8);
                    }
                    st.schedule("far_door", st.now() + min(5 + p.getRandom().nextInt(6)), "");
                    done(p, st, "sector7");
                }
            }
            case "kill" -> {
                if ("listen".equals(arg)) {
                    ComputerService.log(p, "listen: процесс завершён");
                    st.schedule("pc_listen_back", st.now() + min(40 + p.getRandom().nextInt(40)), "");
                    if (st.chainStep("listener") >= 0) {
                        done(p, st, "listener");
                    }
                }
            }
            case "shutdown" -> {
                if (Director.phase(p) >= 3 && available(st, "shutdown")) {
                    start(p, st, "shutdown");
                }
            }
            case "find" -> {
                if (Config.SECRETS.get() && (arg.equals("гость") || arg.equals("guest")) && !st.flag("found_guest")) {
                    st.setFlag("found_guest", true);
                    v.put("/archive/.guest/входы.log", visits(p, st), ComputerService.clock(p));
                    v.term("/archive/.guest/входы.log");
                }
            }
            default -> {
                if (Config.SECRETS.get() && cmd.equals(ComputerService.hostname(p)) && v.unlock("unknown")) {
                    v.raw().putString("unk", "ты назвал его по имени.\n\nничего не произошло.");
                    v.term("установлено: ?");
                }
            }
        }
    }

    private static String visits(ServerPlayer p, HorrorState st) {
        int[] count = new int[1];
        BlockPos spot = st.favouriteSpot(count);
        StringBuilder b = new StringBuilder();
        b.append("входов: ").append(st.count("logins")).append('\n');
        b.append("снов: ").append(st.count("sleeps")).append('\n');
        if (spot != null) {
            b.append("чаще всего здесь: ").append(spot.getX()).append(' ').append(spot.getZ()).append(" (").append(count[0]).append(")\n");
        }
        b.append("ночью: ").append(st.count("min_night")).append(" мин, днём: ").append(st.count("min_day")).append(" мин");
        return b.toString();
    }

    public static void onReply(ServerPlayer p, HorrorState st, Vfs v, String node, String choice) {
        long now = st.now();
        RandomSource r = p.getRandom();
        switch (node) {
            case "dlg1" -> {
                switch (choice) {
                    case "да" -> {
                        ComputerService.msg(p, "гость", "хорошо. я не буду его трогать.", null, null);
                        st.schedule("torch_extra", now + min(2 + r.nextInt(4)), "");
                    }
                    case "нет" -> {
                        ComputerService.msg(p, "гость", "значит, не ты.", null, null);
                        st.schedule("far_door", now + min(3 + r.nextInt(5)), "");
                    }
                    default -> {
                        ComputerService.msg(p, "гость", "сосед.", null, null);
                        v.put("/documents/сосед.txt", visits(p, st), ComputerService.clock(p));
                    }
                }
                if (st.chainStep("dialog") >= 0) {
                    done(p, st, "dialog");
                }
            }
            case "night1" -> {
                if (choice.equals("сплю")) {
                    ComputerService.msg(p, "гость", "хорошо.", null, null);
                    st.setFlag("lied_sleep", true);
                } else {
                    ComputerService.msg(p, "гость", "я тоже.", null, null);
                    st.schedule("knock", now + min(1 + r.nextInt(3)), "");
                }
                if (st.chainStep("night_msg") >= 0) {
                    done(p, st, "night_msg");
                }
            }
            default -> Director.LOG.debug("[BrokenSignal] reply to unknown node '{}'", node);
        }
    }

    public static void onNotes(ServerPlayer p, HorrorState st, Vfs v, String text) {
        st.setCount("notes_len", text.length());
        if (Config.SECRETS.get() && text.toLowerCase(java.util.Locale.ROOT).contains("кто ты") && !st.flag("notes_asked")) {
            st.setFlag("notes_asked", true);
            st.schedule("pc_notes_answer", st.now() + min(6 + p.getRandom().nextInt(10)), "");
        }
    }

    public static void onApp(ServerPlayer p, HorrorState st, Vfs v, String app) {
        if ("cam".equals(app) && st.count("app_cam") == 5 && Director.phase(p) >= 5) {
            st.schedule("behind", st.now() + 200, "");
        }
    }

    public static void onListen(ServerPlayer p, HorrorState st, Vfs v) {
        if (!v.appUnlocked("listen")) {
            return;
        }
        int n = st.inc("listens");
        BlockPos home = p.getRespawnPosition();
        boolean away = home != null && home.distSqr(p.blockPosition()) > 40 * 40;
        Vec3 ear = p.position().add(p.getLookAngle().scale(-1.5));
        if (n == 1) {
            for (int i = 0; i < 5; i++) {
                final int k = i;
                HorrorEvents.later(p, 20 + i * 11, () -> HorrorEvents.sound(p, SoundEvents.WOOD_STEP, ear, Config.vol(0.25F), 0.9F + k * 0.02F));
            }
            ComputerService.log(p, "listen: запись 1, " + (away ? "дом" : "эта комната"));
            if (away) {
                st.schedule("home_changed", st.now() + min(3 + p.getRandom().nextInt(4)), "");
            }
        } else if (n == 2) {
            for (int i = 0; i < 3; i++) {
                HorrorEvents.later(p, 30 + i * 14, () -> HorrorEvents.sound(p, SoundEvents.WOOD_HIT, ear, Config.vol(0.35F), 0.7F));
            }
            ComputerService.log(p, "listen: запись 2");
            if (st.chainStep("listener") >= 0) {
                done(p, st, "listener");
            }
        } else {
            ComputerService.log(p, "listen: тишина");
        }
    }

    // ------------------------------------------------------------------ hooks (world -> computer)

    public static void onSighting(ServerPlayer p, HorrorState st) {
        if (hasPc(st) && Director.phase(p) >= 3 && available(st, "camera") && st.activeChains().size() < MAX_ACTIVE + 1) {
            start(p, st, "camera");
        }
    }

    public static void onDeath(ServerPlayer p, HorrorState st, String deathText) {
        if (!hasPc(st) || !Config.COMPUTER_EVENTS.get()) {
            return;
        }
        Vfs v = ComputerService.vfs(p);
        BlockPos d = p.blockPosition();
        appendFile(p, v, "/logs/death.log", ComputerService.clock(p) + "   " + d.getX() + " " + d.getY() + " " + d.getZ() + "   " + deathText);
        if (Director.phase(p) >= 4 && available(st, "deathlog")) {
            start(p, st, "deathlog");
            st.setChain("deathlog", 0, st.now() + min(10 + p.getRandom().nextInt(10)));
        }
    }

    public static void onChat(ServerPlayer p, HorrorState st, String text) {
        if (!hasPc(st) || !Config.COMPUTER_EVENTS.get()) {
            return;
        }
        String t = text.length() > 120 ? text.substring(0, 120) : text;
        appendFile(p, ComputerService.vfs(p), "/logs/chat.log", ComputerService.clock(p) + "  <" + p.getGameProfile().getName() + "> " + t);
    }

    // ------------------------------------------------------------------ world helpers

    static void appendFile(ServerPlayer p, Vfs v, String path, String line) {
        CompoundTag f = v.get(path);
        String old = f == null ? "" : f.getString("c");
        String[] lines = (old.isEmpty() ? line : old + "\n" + line).split("\n");
        int from = Math.max(0, lines.length - 30);
        v.put(path, String.join("\n", java.util.Arrays.copyOfRange(lines, from, lines.length)), ComputerService.clock(p));
    }

    static BlockPos freeSpotNear(ServerLevel lv, BlockPos c, int r) {
        for (int dy = 0; dy <= 1; dy++) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    BlockPos at = c.offset(dx, dy, dz);
                    if ((dx != 0 || dz != 0) && lv.getBlockState(at).isAir() && lv.getBlockState(at.above()).isAir()
                            && lv.getBlockState(at.below()).isFaceSturdy(lv, at.below(), Direction.UP)) {
                        return at;
                    }
                }
            }
        }
        return null;
    }

    public static void placeComputer(ServerPlayer p, HorrorState st, BlockPos at, Direction facing, boolean lit) {
        BlockState s = ModRegistry.COMPUTER.get().defaultBlockState().setValue(ComputerBlock.FACING, facing).setValue(ComputerBlock.LIT, lit);
        p.serverLevel().setBlock(at, s, 3);
        ComputerService.registerPc(p, at);
        st.inc("world_changes");
    }

    /** A small sealed stone room 8-10 blocks under the surface, with a computer that is already on. */
    private static BlockPos buildRoom(ServerPlayer p, HorrorState st) {
        ServerLevel lv = p.serverLevel();
        RandomSource r = p.getRandom();
        BlockPos base = p.getRespawnPosition() != null ? p.getRespawnPosition() : p.blockPosition();
        double a = r.nextDouble() * Math.PI * 2;
        int x = base.getX() + (int) (Math.cos(a) * (22 + r.nextInt(14)));
        int z = base.getZ() + (int) (Math.sin(a) * (22 + r.nextInt(14)));
        if (!lv.isLoaded(new BlockPos(x, base.getY(), z))) {
            return null;
        }
        int y = lv.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z) - 10;
        if (y < lv.getMinBuildHeight() + 6) {
            return null;
        }
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                for (int dy = 0; dy <= 3; dy++) {
                    BlockPos at = new BlockPos(x + dx, y + dy, z + dz);
                    boolean shell = Math.abs(dx) == 2 || Math.abs(dz) == 2 || dy == 0 || dy == 3;
                    lv.setBlock(at, shell ? (r.nextInt(6) == 0 ? Blocks.CRACKED_STONE_BRICKS : Blocks.STONE_BRICKS).defaultBlockState() : Blocks.AIR.defaultBlockState(), 2);
                }
            }
        }
        BlockPos pc = new BlockPos(x, y + 1, z - 1);
        placeComputer(p, st, pc, Direction.SOUTH, true);
        st.setPos("unsent_pc", pc);
        st.remember("sites", pc, 8);
        return new BlockPos(x, y + 1, z);
    }
}
