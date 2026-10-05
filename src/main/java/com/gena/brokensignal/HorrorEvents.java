package com.gena.brokensignal;

import com.gena.brokensignal.pc.ComputerService;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.DoubleSupplier;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.GlobalPos;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSoundPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.component.LodestoneTracker;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.StandingSignBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.RotationSegment;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.ServerChatEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.level.BlockEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Server-side "director". The rule of this mod: almost no words. Things look like bugs,
 * lag or normal game messages, and the world slowly stops being yours.
 */
public final class HorrorEvents {
    public static final List<String> EVENTS = List.of(
            // sounds
            "whisper", "footsteps", "knock", "drone", "cave", "creeper", "mining",
            // world
            "door", "torches", "tunnel", "leaves", "pillar", "sign", "gift", "house", "chest", "rearrange",
            // player
            "lag", "shuffle", "wake", "stare", "darkness",
            // fake game messages
            "chat", "join", "mimic", "deathmsg", "advancement",
            // the figure
            "watcher", "behind", "turn", "chase",
            // client / computer
            "title", "silence", "pause", "lost", "static", "screenshot", "user", "note");

    /** Heavy events that the director runs at most once per session. */
    private static final Set<String> ONCE = Set.of("house", "lost", "screenshot", "pause", "note", "user", "static");

    private static final String KEY = "brokensignal_ticks";
    private static final String HOUSE = "brokensignal_house";
    private static final String HOUSE_DIM = "brokensignal_house_dim";
    private static final String HOUSE_SEEN = "brokensignal_house_seen";
    private static final String FAKE = "s1gnal";
    private static final Map<UUID, State> STATES = new HashMap<>();

    // Short, lowercase, no drama. Most of the time it says nothing at all.
    private static final String[] CHAT = {
            "%name%", ".", "%hx% %hy% %hz%", "ты оставил дверь открытой", "здесь кто-то был",
            "не копай вниз", "ты долго спал", "это не твой дом", "мне здесь нравится", "тут тихо"
    };
    private static final String[] MIMIC = {"привет", "я здесь", "кто это", "ок", ".", "не отвечайте"};
    private static final String[] REPLIES = {".", "%name%", "нет", "здесь", "..."};
    private static final String[] DEATH_KEYS = {
            "death.attack.fall", "death.attack.drown", "death.attack.inWall",
            "death.attack.outOfWorld", "death.attack.generic", "death.attack.player"
    };
    private static final String[] ADVANCEMENTS = {
            "advancements.adventure.sleep_in_bed.title", "advancements.story.mine_stone.title",
            "advancements.adventure.kill_a_mob.title", "advancements.story.root.title"
    };

    private static final class Task {
        int delay;
        final Runnable run;

        Task(int delay, Runnable run) {
            this.delay = delay;
            this.run = run;
        }
    }

    private static final class State {
        boolean slept;
        int cooldown = 20 * 90;
        int clock;
        final List<Task> tasks = new ArrayList<>();
        final Set<String> done = new HashSet<>();
        final ArrayDeque<Vec3> history = new ArrayDeque<>();
    }

    private HorrorEvents() {}

    // ------------------------------------------------------------------ lifecycle

    /** Keep horror progress (and the house) after death / returning from the End. */
    public static void onClone(PlayerEvent.Clone event) {
        CompoundTag old = event.getOriginal().getPersistentData();
        CompoundTag now = event.getEntity().getPersistentData();
        for (String k : new String[] {KEY, HOUSE, HOUSE_DIM, HOUSE_SEEN, HorrorState.ROOT, "brokensignal_pc"}) {
            if (old.contains(k)) {
                now.put(k, old.get(k).copy());
            }
        }
    }

    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        STATES.remove(event.getEntity().getUUID());
        if (event.getEntity() instanceof ServerPlayer p) {
            ComputerService.forget(p);
        }
    }

    /** Sometimes someone else "joins" a little after you. */
    public static void onLogin(PlayerEvent.PlayerLoggedInEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer p) || !Config.ENABLED.get()) {
            return;
        }
        HorrorState hs = HorrorState.of(p);
        if (hs.pos("first") == null) {
            hs.setPos("first", p.blockPosition());
        }
        Director.onLogin(p);
        if (stage(p) < 1) {
            return;
        }
        if (p.getRandom().nextFloat() < 0.3F) {
            later(p, 200 + p.getRandom().nextInt(400), () -> run(p, "join", false));
        }
    }

    public static void onPlayerTick(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        State state = state(player);
        runTasks(player, state);
        if (!Config.ENABLED.get() || player.isSpectator()) {
            return;
        }
        if (player.isCreative() && !Config.AFFECT_CREATIVE.get()) {
            return;
        }
        state.clock++;
        if (state.clock % 10 == 0 && player.onGround() && !player.isPassenger()) {
            state.history.addLast(player.position());
            while (state.history.size() > 6) {
                state.history.removeFirst();
            }
        }
        CompoundTag data = player.getPersistentData();
        int ticks = data.getInt(KEY) + 1;
        data.putInt(KEY, ticks);
        if (ticks < Config.GRACE_MINUTES.get() * 1200) {
            return;
        }
        if (state.clock % 20 == 0) {
            checkHouse(player);
        }
        if (player.isSleeping() && player.getSleepTimer() == 40 && stage(player) >= 1
                && player.getRandom().nextFloat() < 0.3F) {
            trigger(player, "wake");
        }
        if (player.isSleeping()) {
            state.slept = true;
        } else if (state.slept) {
            state.slept = false;
            Director.onWake(player);
        }
        Director.tick(player);
    }

    private static void runTasks(ServerPlayer player, State state) {
        if (state.tasks.isEmpty()) {
            return;
        }
        List<Task> due = new ArrayList<>();
        for (Task t : state.tasks) {
            if (--t.delay <= 0) {
                due.add(t);
            }
        }
        state.tasks.removeAll(due);
        for (Task t : due) {
            if (player.isAlive() && !player.hasDisconnected()) {
                t.run.run();
            }
        }
    }

    // ------------------------------------------------------------------ interactions

    /** Talking in chat is not a good idea. */
    public static void onChat(ServerChatEvent event) {
        ServerPlayer p = event.getPlayer();
        if (!Config.ENABLED.get() || p.isSpectator() || stage(p) < 1) {
            return;
        }
        String raw = event.getRawText();
        HorrorState hs = HorrorState.of(p);
        hs.setText("lastChat", raw.length() > 120 ? raw.substring(0, 120) : raw);
        hs.inc("chats");
        Chains.onChat(p, hs, raw);
        String low = raw.toLowerCase(Locale.ROOT);
        boolean called = low.contains("s1gnal") || low.contains("сигнал") || low.contains("кто ты")
                || low.contains("кто здесь") || low.contains("кто тут") || low.contains("who are you");
        RandomSource r = p.getRandom();
        String reply;
        if (called && r.nextFloat() < 0.5F) {
            reply = pickS(r, REPLIES).replace("%name%", p.getName().getString());
        } else if (stage(p) >= 2 && r.nextFloat() < 0.08F) {
            reply = low; // it just repeats you
        } else {
            return;
        }
        final String text = reply;
        p.server.execute(() -> later(p, 60 + r.nextInt(120), () -> fakeChat(p, text)));
    }

    public static void onDeath(LivingDeathEvent event) {
        if (event.getEntity() instanceof ServerPlayer dp && Config.ENABLED.get()) {
            HorrorState hs = HorrorState.of(dp);
            hs.setPos("death", dp.blockPosition());
            Chains.onDeath(dp, hs, event.getSource().getLocalizedDeathMessage(dp).getString());
        }
        if (event.getEntity() instanceof ServerPlayer p && Config.ENABLED.get() && stage(p) >= 2
                && p.getRandom().nextFloat() < 0.3F) {
            fakeChat(p, ".");
        }
    }

    /** Rarely, a block you mined grows back. */
    public static void onBreak(BlockEvent.BreakEvent event) {
        if (!(event.getPlayer() instanceof ServerPlayer p) || !Config.ENABLED.get() || !Config.WORLD_EDITS.get()) {
            return;
        }
        BlockState st = event.getState();
        if (stage(p) < 2 || !carvable(st) || p.getRandom().nextFloat() > 0.012F) {
            return;
        }
        ServerLevel lv = p.serverLevel();
        BlockPos pos = event.getPos().immutable();
        later(p, 80 + p.getRandom().nextInt(200), () -> {
            if (p.serverLevel() == lv && lv.getBlockState(pos).isAir() && p.distanceToSqr(Vec3.atCenterOf(pos)) > 4.0) {
                lv.setBlock(pos, st, 3);
            }
        });
    }

    /** Rarely, a torch you just placed goes out. */
    public static void onPlace(BlockEvent.EntityPlaceEvent event) {
        if (!(event.getEntity() instanceof ServerPlayer p) || !Config.ENABLED.get()) {
            return;
        }
        HorrorState hs = HorrorState.of(p);
        hs.setPos("lastPlacedPos", event.getPos().immutable());
        hs.setText("lastPlaced", event.getPlacedBlock().getBlock().getName().getString());
        if (!Config.WORLD_EDITS.get()) {
            return;
        }
        if (stage(p) < 1 || !isTorch(event.getPlacedBlock()) || p.getRandom().nextFloat() > 0.04F) {
            return;
        }
        ServerLevel lv = p.serverLevel();
        BlockPos pos = event.getPos().immutable();
        later(p, 60 + p.getRandom().nextInt(120), () -> {
            if (p.serverLevel() == lv) {
                snuff(lv, pos);
            }
        });
    }

    // ------------------------------------------------------------------ progression

    public static int minutes(ServerPlayer player) {
        return player.getPersistentData().getInt(KEY) / 1200;
    }

    public static void setMinutes(ServerPlayer player, int minutes) {
        player.getPersistentData().putInt(KEY, minutes * 1200);
    }

    /** 0: <15 min, 1: <35, 2: <60, 3: 60+ */
    public static int stage(ServerPlayer player) {
        int m = minutes(player);
        return m < 15 ? 0 : m < 35 ? 1 : m < 60 ? 2 : 3;
    }

    private static String pick(ServerPlayer p, int stage) {
        boolean under = underground(p);
        boolean meta = Config.COMPUTER_EVENTS.get();
        boolean edits = Config.WORLD_EDITS.get();
        List<String> pool = new ArrayList<>();
        add(pool, "footsteps", 3);
        add(pool, "cave", 3);
        add(pool, "knock", 2);
        add(pool, "door", 1);
        add(pool, "whisper", 1);
        add(pool, "drone", 1);
        if (under) {
            add(pool, "mining", 3);
        }
        if (stage >= 1) {
            add(pool, "watcher", 4);
            add(pool, "stare", 2);
            add(pool, "lag", 2);
            add(pool, "chest", 2);
            add(pool, "chat", 2);
            add(pool, "join", 1);
            add(pool, "mimic", 1);
            add(pool, "deathmsg", 1);
            add(pool, "advancement", 1);
            add(pool, "shuffle", 1);
            if (edits) {
                add(pool, "torches", 2);
                add(pool, "pillar", 1);
                if (under) {
                    add(pool, "tunnel", 2);
                } else {
                    add(pool, "leaves", 1);
                }
            }
            if (meta) {
                add(pool, "title", 1);
            }
        }
        if (stage >= 2) {
            add(pool, "behind", 2);
            add(pool, "turn", 2);
            add(pool, "darkness", 1);
            add(pool, "rearrange", 1);
            if (edits) {
                add(pool, "house", 3);
                add(pool, "sign", 2);
                add(pool, "gift", 1);
            }
            if (meta) {
                add(pool, "silence", 1);
                add(pool, "lost", 1);
                add(pool, "screenshot", 1);
                add(pool, "user", 1);
                add(pool, "pause", 1);
                add(pool, "note", 1);
            }
        }
        if (stage >= 3) {
            add(pool, "creeper", 1);
            if (meta) {
                add(pool, "static", 1);
            }
            if (p.level().isNight()) {
                add(pool, "chase", 2);
            }
        }
        return pool.get(p.getRandom().nextInt(pool.size()));
    }

    private static void add(List<String> list, String id, int weight) {
        for (int i = 0; i < weight; i++) {
            list.add(id);
        }
    }

    private static State state(ServerPlayer player) {
        return STATES.computeIfAbsent(player.getUUID(), k -> new State());
    }

    public static void later(ServerPlayer player, int delay, Runnable run) {
        state(player).tasks.add(new Task(delay, run));
    }

    public static void repeat(ServerPlayer player, int times, int every, Runnable run) {
        for (int i = 0; i < times; i++) {
            later(player, 1 + i * every, run);
        }
    }

    private static String pickS(RandomSource r, String[] arr) {
        return arr[r.nextInt(arr.length)];
    }

    /** Used by the automatic director. */
    public static boolean trigger(ServerPlayer p, String id) {
        return run(p, id, false) == null;
    }

    // ------------------------------------------------------------------ events

    /** Runs an event. Returns null on success, otherwise a short reason why it could not run. */
    public static String run(ServerPlayer p, String id, boolean force) {
        ServerLevel lv = p.serverLevel();
        RandomSource r = p.getRandom();
        switch (id) {
            case "whisper" -> {
                sound(p, ModRegistry.WHISPER, behind(p, 2.0, 0.5), 0.8F, 0.9F + r.nextFloat() * 0.2F);
                return null;
            }
            case "footsteps" -> {
                Vec3 look = p.getLookAngle();
                final double base = Math.atan2(-look.z, -look.x) + (r.nextDouble() - 0.5) * 0.5;
                final double ox = p.getX();
                final double oz = p.getZ();
                final double oy = p.getY();
                int n = 6 + r.nextInt(5);
                for (int i = 0; i < n; i++) {
                    final double d = Math.max(2.5, 14.0 - i * 1.3);
                    final boolean left = i % 2 == 0;
                    later(p, 1 + i * 8, () -> {
                        double side = left ? 0.25 : -0.25;
                        double x = ox + Math.cos(base) * d + Math.cos(base + Math.PI / 2) * side;
                        double z = oz + Math.sin(base) * d + Math.sin(base + Math.PI / 2) * side;
                        BlockState below = lv.getBlockState(BlockPos.containing(x, oy - 0.5, z));
                        SoundEvent step = below.isAir() ? SoundEvents.STONE_STEP : below.getSoundType().getStepSound();
                        sound(p, step, new Vec3(x, oy, z), 0.9F, 0.85F + r.nextFloat() * 0.1F);
                    });
                }
                return null; // and then nothing. it just stops close to you.
            }
            case "knock" -> {
                BlockPos door = findOpenable(p, 16, true);
                Vec3 at = door != null ? Vec3.atCenterOf(door) : behind(p, 4.0, 1.2);
                sound(p, ModRegistry.KNOCK, at, 1.0F, 1.0F);
                if (r.nextFloat() < 0.4F || force) {
                    later(p, 50 + r.nextInt(40), () -> sound(p, ModRegistry.KNOCK, at, 1.0F, 0.85F));
                }
                return null;
            }
            case "drone" -> {
                sound(p, ModRegistry.DRONE, p.position().add(0, 1, 0), 0.8F, 0.8F + r.nextFloat() * 0.3F);
                return null;
            }
            case "cave" -> {
                sound(p, SoundEvents.AMBIENT_CAVE, behind(p, 6.0, 1.5), 1.0F, 0.8F + r.nextFloat() * 0.3F);
                return null;
            }
            case "creeper" -> {
                sound(p, SoundEvents.CREEPER_PRIMED, behind(p, 1.5, 0.3), 1.0F, 0.5F);
                return null;
            }
            case "mining" -> {
                BlockPos wall = findWall(p, 6, 12);
                if (wall == null) {
                    return "no stone wall nearby";
                }
                mine(p, wall, Direction.Plane.HORIZONTAL.getRandomDirection(r), 3 + r.nextInt(3));
                return null;
            }
            case "door" -> {
                BlockPos found = findOpenable(p, 16, false);
                if (found == null) {
                    return "no wooden door, trapdoor or fence gate within 16 blocks";
                }
                toggle(lv, found);
                if (r.nextFloat() < 0.5F || force) {
                    later(p, 30 + r.nextInt(30), () -> toggle(lv, found));
                }
                return null;
            }
            case "torches" -> {
                if (!Config.WORLD_EDITS.get()) {
                    return "worldEdits is disabled in config";
                }
                BlockPos c = p.blockPosition();
                List<BlockPos> list = new ArrayList<>();
                for (BlockPos bp : BlockPos.betweenClosed(c.offset(-20, -6, -20), c.offset(20, 8, 20))) {
                    if (isTorch(lv.getBlockState(bp))) {
                        list.add(bp.immutable());
                    }
                }
                if (list.isEmpty()) {
                    return "no torches within 20 blocks";
                }
                // farthest first: the dark comes towards you
                list.sort(Comparator.comparingDouble((BlockPos b) -> -b.distSqr(c)));
                int n = Math.min(list.size(), 3 + r.nextInt(6));
                for (int i = 0; i < n; i++) {
                    final BlockPos bp = list.get(i);
                    later(p, 1 + i * 10, () -> snuff(lv, bp));
                }
                return null;
            }
            case "tunnel" -> {
                if (!Config.WORLD_EDITS.get()) {
                    return "worldEdits is disabled in config";
                }
                if (!underground(p) && !force) {
                    return "you have to be underground";
                }
                Direction dir = Direction.Plane.HORIZONTAL.getRandomDirection(r);
                BlockPos start = p.blockPosition().relative(dir.getClockWise(), 4 + r.nextInt(3));
                int len = 24 + r.nextInt(24);
                if (!canCarve(lv, start) && !canCarve(lv, start.above())) {
                    return "no solid stone next to you";
                }
                // first you hear it, then it is there
                mine(p, start, dir, 4);
                later(p, 20 * 9, () -> carveTunnel(lv, start.relative(dir, -len / 2), dir, len));
                return null;
            }
            case "leaves" -> {
                if (!Config.WORLD_EDITS.get()) {
                    return "worldEdits is disabled in config";
                }
                BlockPos leaf = findLeaves(p, 10, 28);
                if (leaf == null) {
                    return "no natural tree 10-28 blocks away";
                }
                stripTree(lv, leaf);
                return null;
            }
            case "pillar" -> {
                if (!Config.WORLD_EDITS.get()) {
                    return "worldEdits is disabled in config";
                }
                // a plain 1x1 tower, as if someone pillared up to look around
                for (int tries = 0; tries < 10; tries++) {
                    double a = front(p, 0.9);
                    int dist = 18 + r.nextInt(16);
                    BlockPos pos = stand(lv, p.getX() + Math.cos(a) * dist, p.getZ() + Math.sin(a) * dist, p.getBlockY(), 5);
                    if (pos == null || !lv.getFluidState(pos.below()).isEmpty()) {
                        continue;
                    }
                    BlockState block = lv.getBlockState(pos.below()).is(BlockTags.DIRT)
                            ? Blocks.DIRT.defaultBlockState() : Blocks.COBBLESTONE.defaultBlockState();
                    int h = 5 + r.nextInt(5);
                    for (int i = 0; i < h; i++) {
                        if (!lv.getBlockState(pos.above(i)).isAir()) {
                            break;
                        }
                        lv.setBlock(pos.above(i), block, 3);
                    }
                    return null;
                }
                return "no free spot for a pillar in front of you";
            }
            case "sign" -> {
                if (!Config.WORLD_EDITS.get()) {
                    return "worldEdits is disabled in config";
                }
                BlockPos pos = null;
                for (int tries = 0; tries < 10 && pos == null; tries++) {
                    Vec3 v = behind(p, 3.0 + r.nextDouble() * 3.0, 0.7);
                    pos = stand(lv, v.x, v.z, p.getBlockY(), 4);
                }
                if (pos == null) {
                    return "no free spot for a sign behind you";
                }
                BlockPos house = house(p);
                String[] lines = switch (r.nextInt(house != null ? 4 : 3)) {
                    case 0 -> new String[] {"", "", "", ""};
                    case 1 -> new String[] {"", p.getName().getString(), "", ""};
                    case 2 -> new String[] {"", "здесь", "", ""};
                    default -> new String[] {"", house.getX() + " " + house.getY() + " " + house.getZ(), "", ""};
                };
                placeSign(lv, p, pos, lines);
                return null;
            }
            case "gift" -> {
                if (!Config.WORLD_EDITS.get()) {
                    return "worldEdits is disabled in config";
                }
                BlockPos pos = null;
                for (int tries = 0; tries < 10 && pos == null; tries++) {
                    Vec3 v = behind(p, 3.0 + r.nextDouble() * 3.0, 0.7);
                    pos = stand(lv, v.x, v.z, p.getBlockY(), 3);
                }
                if (pos == null) {
                    return "no free spot for a chest behind you";
                }
                lv.setBlock(pos, Blocks.CHEST.defaultBlockState(), 3);
                if (lv.getBlockEntity(pos) instanceof ChestBlockEntity chest) {
                    ItemStack stack = giftFor(p);
                    if (!stack.isEmpty()) {
                        chest.setItem(13, stack);
                    }
                    chest.setChanged();
                }
                return null;
            }
            case "house" -> {
                return buildHouse(p, force);
            }
            case "chest" -> {
                BlockPos pos = findChest(p, 16);
                if (pos == null) {
                    return "no chest within 16 blocks";
                }
                BlockState st = lv.getBlockState(pos);
                lv.blockEvent(pos, st.getBlock(), 1, 1);
                lv.playSound(null, pos, SoundEvents.CHEST_OPEN, SoundSource.BLOCKS, 0.5F, 0.9F);
                later(p, 40 + r.nextInt(40), () -> {
                    if (lv.getBlockState(pos).is(st.getBlock())) {
                        lv.blockEvent(pos, st.getBlock(), 1, 0);
                        lv.playSound(null, pos, SoundEvents.CHEST_CLOSE, SoundSource.BLOCKS, 0.5F, 0.9F);
                    }
                });
                return null;
            }
            case "rearrange" -> {
                BlockPos pos = findChest(p, 24);
                if (pos == null || !(lv.getBlockEntity(pos) instanceof ChestBlockEntity chest)) {
                    return "no chest within 24 blocks";
                }
                List<ItemStack> items = new ArrayList<>();
                int filled = 0;
                for (int i = 0; i < chest.getContainerSize(); i++) {
                    ItemStack s = chest.getItem(i);
                    if (!s.isEmpty()) {
                        filled++;
                    }
                    items.add(s.copy());
                }
                if (filled < 2) {
                    return "the chest is almost empty";
                }
                Collections.shuffle(items);
                for (int i = 0; i < items.size(); i++) {
                    chest.setItem(i, items.get(i));
                }
                chest.setChanged();
                return null;
            }
            case "lag" -> {
                State s = state(p);
                Vec3 old = s.history.peekFirst();
                if (old == null || old.distanceTo(p.position()) < 3.0 || p.isPassenger()) {
                    return "walk around for a few seconds first";
                }
                p.teleportTo(old.x, old.y, old.z);
                s.history.clear();
                return null;
            }
            case "shuffle" -> {
                Inventory inv = p.getInventory();
                List<Integer> filled = new ArrayList<>();
                for (int i = 0; i < 9; i++) {
                    if (!inv.getItem(i).isEmpty()) {
                        filled.add(i);
                    }
                }
                if (filled.size() < 2) {
                    return "hotbar has fewer than 2 items";
                }
                Collections.shuffle(filled);
                int a = filled.get(0);
                int b = filled.get(1);
                ItemStack tmp = inv.getItem(a);
                inv.setItem(a, inv.getItem(b));
                inv.setItem(b, tmp);
                p.inventoryMenu.broadcastChanges();
                return null;
            }
            case "wake" -> {
                if (!p.isSleeping()) {
                    return "you are not sleeping";
                }
                p.stopSleepInBed(true, true);
                p.displayClientMessage(Component.translatable("block.minecraft.bed.not_safe"), true);
                later(p, 30, () -> sound(p, ModRegistry.KNOCK, behind(p, 3.0, 1.0), 0.8F, 0.9F));
                return null;
            }
            case "stare" -> {
                List<Mob> mobs = lv.getEntitiesOfClass(Mob.class, p.getBoundingBox().inflate(24.0),
                        m -> m.isAlive() && !(m instanceof WatcherEntity) && !m.isNoAi());
                if (mobs.isEmpty()) {
                    return "no mobs within 24 blocks";
                }
                // every mob around stops and looks at you for 8 seconds
                repeat(p, 160, 1, () -> {
                    for (Mob m : mobs) {
                        if (m.isAlive()) {
                            m.getNavigation().stop();
                            m.setDeltaMovement(0.0, m.getDeltaMovement().y, 0.0);
                            m.getLookControl().setLookAt(p, 180.0F, 180.0F);
                        }
                    }
                });
                return null;
            }
            case "darkness" -> {
                p.addEffect(new MobEffectInstance(MobEffects.DARKNESS, 200, 0, false, false));
                return null;
            }
            case "chat" -> {
                BlockPos h = house(p);
                BlockPos c = h != null ? h : p.blockPosition();
                fakeChat(p, pickS(r, CHAT)
                        .replace("%name%", p.getName().getString())
                        .replace("%hx%", String.valueOf(c.getX()))
                        .replace("%hy%", String.valueOf(c.getY()))
                        .replace("%hz%", String.valueOf(c.getZ())));
                return null;
            }
            case "join" -> {
                p.sendSystemMessage(Component.translatable("multiplayer.player.joined", FAKE).withStyle(ChatFormatting.YELLOW));
                if (force || r.nextFloat() < 0.3F) {
                    later(p, 100 + r.nextInt(200), () -> fakeChat(p, "."));
                }
                later(p, force ? 400 : 600 + r.nextInt(1200), () -> p.sendSystemMessage(
                        Component.translatable("multiplayer.player.left", FAKE).withStyle(ChatFormatting.YELLOW)));
                return null;
            }
            case "mimic" -> {
                // a message from you that you never wrote
                p.sendSystemMessage(Component.literal("<" + p.getName().getString() + "> " + pickS(r, MIMIC)));
                return null;
            }
            case "deathmsg" -> {
                String key = pickS(r, DEATH_KEYS);
                p.sendSystemMessage(key.equals("death.attack.player")
                        ? Component.translatable(key, FAKE, p.getDisplayName())
                        : Component.translatable(key, FAKE));
                return null;
            }
            case "advancement" -> {
                Component adv = Component.literal("[").append(Component.translatable(pickS(r, ADVANCEMENTS)))
                        .append("]").withStyle(ChatFormatting.GREEN);
                p.sendSystemMessage(Component.translatable("chat.type.advancement.task", FAKE, adv));
                return null;
            }
            case "watcher" -> {
                return spawnWatcher(p, 22 + r.nextInt(14), WatcherEntity.Mode.STALK, () -> front(p, 1.1), force, null);
            }
            case "behind" -> {
                String err = spawnWatcher(p, 3.0, WatcherEntity.Mode.STALK, () -> back(p, 0.3), force, w -> {
                    w.setLookLimit(2);
                    w.setVanishDistance(1.0);
                    w.setMaxLife(20 * 15);
                });
                if (err == null) {
                    sound(p, ModRegistry.WHISPER, behind(p, 1.5, 0.2), 0.6F, 0.7F);
                }
                return err;
            }
            case "turn" -> {
                WatcherEntity[] ref = new WatcherEntity[1];
                String err = spawnWatcher(p, 10, WatcherEntity.Mode.STALK, () -> back(p, 0.4), force, w -> {
                    w.setLookLimit(14);
                    w.setVanishDistance(3.0);
                    w.setMaxLife(20 * 12);
                    ref[0] = w;
                });
                if (err != null) {
                    return err;
                }
                final WatcherEntity target = ref[0];
                later(p, 20, () -> {
                    if (target == null || target.isRemoved()) {
                        return;
                    }
                    double dx = target.getX() - p.getX();
                    double dz = target.getZ() - p.getZ();
                    double dy = target.getEyeY() - p.getEyeY();
                    float yaw = (float) (Mth.atan2(dz, dx) * (180.0 / Math.PI)) - 90.0F;
                    float pitch = (float) (-Mth.atan2(dy, Math.sqrt(dx * dx + dz * dz)) * (180.0 / Math.PI));
                    p.connection.teleport(p.getX(), p.getY(), p.getZ(), yaw, pitch);
                });
                return null;
            }
            case "chase" -> {
                String err = spawnWatcher(p, 18, WatcherEntity.Mode.CHASE, () -> back(p, 0.6), force, null);
                if (err == null) {
                    sound(p, ModRegistry.DRONE, p.position(), 1.0F, 0.6F);
                }
                return err;
            }
            case "title" -> {
                return meta(p, "title", r.nextInt(3) == 0 ? "lan" : "other");
            }
            case "silence" -> {
                String err = meta(p, "silence", "");
                if (err == null) {
                    later(p, 20 * 12, () -> sound(p, ModRegistry.KNOCK, behind(p, 2.0, 0.4), 1.0F, 0.9F));
                }
                return err;
            }
            case "pause", "lost", "static" -> {
                return meta(p, id, "");
            }
            case "screenshot", "user" -> {
                // v4: these used to touch the real computer (screenshots folder, OS user name).
                // They now stay inside the game: a short static burst instead.
                return meta(p, "static", "");
            }
            case "note" -> {
                // v4: the note is written to the in-game computer, never to a real file.
                BlockPos h = house(p);
                BlockPos c = h != null ? h : p.blockPosition();
                if (EventCtx.of(p).pc == null) {
                    return "no in-game computer";
                }
                ComputerService.file(p, "/desktop/прочти.txt", c.getX() + " " + c.getY() + " " + c.getZ());
                return null;
            }
            default -> {
                return ExtraEvents.run(p, id, force);
            }
        }
    }

    // ------------------------------------------------------------------ the house

    public static BlockPos house(ServerPlayer p) {
        CompoundTag d = p.getPersistentData();
        if (!d.contains(HOUSE) || !p.serverLevel().dimension().location().toString().equals(d.getString(HOUSE_DIM))) {
            return null;
        }
        return BlockPos.of(d.getLong(HOUSE));
    }

    /** A small dark hut that you did not build. Its coordinates find their way to you. */
    private static String buildHouse(ServerPlayer p, boolean force) {
        if (!Config.WORLD_EDITS.get()) {
            return "worldEdits is disabled in config";
        }
        CompoundTag d = p.getPersistentData();
        if (d.contains(HOUSE) && !force) {
            return "there is already a house";
        }
        ServerLevel lv = p.serverLevel();
        RandomSource r = p.getRandom();
        for (int tries = 0; tries < 24; tries++) {
            double a = r.nextDouble() * Math.PI * 2.0;
            int dist = 36 + r.nextInt(30);
            int cx = Mth.floor(p.getX() + Math.cos(a) * dist);
            int cz = Mth.floor(p.getZ() + Math.sin(a) * dist);
            if (!lv.isLoaded(new BlockPos(cx, p.getBlockY(), cz))) {
                continue;
            }
            int min = Integer.MAX_VALUE;
            int max = Integer.MIN_VALUE;
            boolean ok = true;
            for (int dx = -2; dx <= 2 && ok; dx++) {
                for (int dz = -2; dz <= 2; dz++) {
                    int h = lv.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, cx + dx, cz + dz);
                    BlockPos g = new BlockPos(cx + dx, h - 1, cz + dz);
                    if (!lv.getFluidState(g).isEmpty() || !lv.getFluidState(g.above()).isEmpty()) {
                        ok = false;
                        break;
                    }
                    min = Math.min(min, h);
                    max = Math.max(max, h);
                }
            }
            if (!ok || max - min > 2 || Math.abs(max - p.getBlockY()) > 30) {
                continue;
            }
            BlockPos center = new BlockPos(cx, max, cz);
            Direction door = Direction.getNearest(p.getX() - cx, 0.0, p.getZ() - cz);
            if (door.getAxis().isVertical()) {
                door = Direction.NORTH;
            }
            buildHut(lv, center, door, r, giftFor(p));
            d.putLong(HOUSE, center.asLong());
            d.putString(HOUSE_DIM, lv.dimension().location().toString());
            d.putBoolean(HOUSE_SEEN, false);
            deliverCoords(p, center);
            return null;
        }
        return "no flat dry place 36-66 blocks away";
    }

    private static void buildHut(ServerLevel lv, BlockPos c, Direction door, RandomSource r, ItemStack loot) {
        BlockState planks = Blocks.OAK_PLANKS.defaultBlockState();
        BlockState log = Blocks.OAK_LOG.defaultBlockState();
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                // foundation down to the ground
                for (int y = 1; y <= 4; y++) {
                    BlockPos f = c.offset(dx, -y, dz);
                    if (lv.getBlockState(f).isFaceSturdy(lv, f, Direction.UP)) {
                        break;
                    }
                    lv.setBlock(f, Blocks.COBBLESTONE.defaultBlockState(), 3);
                }
                lv.setBlock(c.offset(dx, -1, dz), r.nextInt(4) == 0
                        ? Blocks.MOSSY_COBBLESTONE.defaultBlockState() : Blocks.COBBLESTONE.defaultBlockState(), 3);
                boolean edge = Math.abs(dx) == 2 || Math.abs(dz) == 2;
                boolean corner = Math.abs(dx) == 2 && Math.abs(dz) == 2;
                for (int y = 0; y < 3; y++) {
                    lv.setBlock(c.offset(dx, y, dz), corner ? log : edge ? planks : Blocks.AIR.defaultBlockState(), 3);
                }
                lv.setBlock(c.offset(dx, 3, dz), planks, 3);
                lv.setBlock(c.offset(dx, 4, dz), Blocks.AIR.defaultBlockState(), 3);
            }
        }
        // empty doorway facing where you were standing
        BlockPos gap = c.relative(door, 2);
        lv.setBlock(gap, Blocks.AIR.defaultBlockState(), 3);
        lv.setBlock(gap.above(), Blocks.AIR.defaultBlockState(), 3);
        // one small window on the side
        lv.setBlock(c.relative(door.getClockWise(), 2).above(), Blocks.GLASS_PANE.defaultBlockState(), 3);

        Direction back = door.getOpposite();
        BlockPos foot = c;
        BlockPos head = c.relative(back);
        lv.setBlock(head, Blocks.WHITE_BED.defaultBlockState()
                .setValue(BedBlock.FACING, back).setValue(BedBlock.PART, BedPart.HEAD), 18);
        lv.setBlock(foot, Blocks.WHITE_BED.defaultBlockState()
                .setValue(BedBlock.FACING, back).setValue(BedBlock.PART, BedPart.FOOT), 18);

        BlockPos chestPos = c.relative(back).relative(door.getClockWise());
        lv.setBlock(chestPos, Blocks.CHEST.defaultBlockState()
                .setValue(BlockStateProperties.HORIZONTAL_FACING, door), 3);
        if (lv.getBlockEntity(chestPos) instanceof ChestBlockEntity chest && !loot.isEmpty()) {
            chest.setItem(r.nextInt(chest.getContainerSize()), loot);
            chest.setChanged();
        }
        lv.setBlock(c.relative(back).relative(door.getCounterClockWise()), Blocks.CRAFTING_TABLE.defaultBlockState(), 3);
    }

    private static void deliverCoords(ServerPlayer p, BlockPos c) {
        String coords = c.getX() + " " + c.getY() + " " + c.getZ();
        int way = p.getRandom().nextInt(Config.COMPUTER_EVENTS.get() ? 3 : 2);
        if (way == 0) {
            later(p, 200 + p.getRandom().nextInt(400), () -> fakeChat(p, coords));
        } else if (way == 1) {
            later(p, 100, () -> run(p, "sign", true));
        } else {
            later(p, 200, () -> meta(p, "note", coords));
        }
    }

    /** When you finally step inside, someone is standing in the doorway. */
    private static void checkHouse(ServerPlayer p) {
        BlockPos h = house(p);
        CompoundTag d = p.getPersistentData();
        if (h == null || d.getBoolean(HOUSE_SEEN)) {
            return;
        }
        double dx = p.getX() - (h.getX() + 0.5);
        double dz = p.getZ() - (h.getZ() + 0.5);
        if (dx * dx + dz * dz > 4.0 || Math.abs(p.getY() - h.getY()) > 2.5) {
            return;
        }
        d.putBoolean(HOUSE_SEEN, true);
        later(p, 60, () -> sound(p, ModRegistry.KNOCK, Vec3.atCenterOf(h).add(0, 1, 0), 0.7F, 0.8F));
        later(p, 140, () -> {
            for (Direction dir : Direction.Plane.HORIZONTAL) {
                BlockPos gap = h.relative(dir, 2);
                if (p.serverLevel().getBlockState(gap).isAir() && p.serverLevel().getBlockState(gap.above()).isAir()) {
                    BlockPos out = h.relative(dir, 4);
                    double ang = Math.atan2(out.getZ() + 0.5 - p.getZ(), out.getX() + 0.5 - p.getX());
                    double dist = Math.sqrt(p.distanceToSqr(Vec3.atCenterOf(out)));
                    spawnWatcher(p, dist, WatcherEntity.Mode.STALK, () -> ang, true, w -> {
                        w.setLookLimit(30);
                        w.setVanishDistance(2.0);
                        w.setMaxLife(20 * 40);
                    });
                    return;
                }
            }
        });
    }

    // ------------------------------------------------------------------ helpers

    public static String meta(ServerPlayer p, String action, String arg) {
        if (!Config.COMPUTER_EVENTS.get()) {
            return "computerEvents is disabled in config";
        }
        PacketDistributor.sendToPlayer(p, new MetaPayload(action, arg));
        return null;
    }

    private static void fakeChat(ServerPlayer p, String text) {
        p.sendSystemMessage(Component.literal("<" + FAKE + "> " + text));
    }

    /** A copy of something you own, or a compass that points to the house, or nothing. */
    private static ItemStack giftFor(ServerPlayer p) {
        RandomSource r = p.getRandom();
        BlockPos h = house(p);
        if (h != null && r.nextInt(3) == 0) {
            ItemStack compass = new ItemStack(Items.COMPASS);
            compass.set(DataComponents.LODESTONE_TRACKER,
                    new LodestoneTracker(Optional.of(GlobalPos.of(p.serverLevel().dimension(), h)), false));
            return compass;
        }
        Inventory inv = p.getInventory();
        List<ItemStack> own = new ArrayList<>();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            if (!inv.getItem(i).isEmpty()) {
                own.add(inv.getItem(i));
            }
        }
        if (own.isEmpty() || r.nextInt(4) == 0) {
            return ItemStack.EMPTY;
        }
        return own.get(r.nextInt(own.size())).copyWithCount(1);
    }

    public static boolean underground(ServerPlayer p) {
        return p.getY() < 60 && !p.serverLevel().canSeeSky(p.blockPosition().above());
    }

    private static boolean carvable(BlockState st) {
        return st.is(BlockTags.BASE_STONE_OVERWORLD) || st.is(BlockTags.DIRT) || st.is(Blocks.GRAVEL);
    }

    private static boolean canCarve(ServerLevel lv, BlockPos pos) {
        if (!lv.isLoaded(pos) || !carvable(lv.getBlockState(pos))) {
            return false;
        }
        for (Direction d : Direction.values()) {
            if (!lv.getFluidState(pos.relative(d)).isEmpty()) {
                return false;
            }
        }
        return true;
    }

    private static void carveTunnel(ServerLevel lv, BlockPos from, Direction dir, int len) {
        for (int i = 0; i < len; i++) {
            BlockPos b = from.relative(dir, i);
            for (int y = 0; y < 2; y++) {
                BlockPos q = b.above(y);
                if (canCarve(lv, q)) {
                    lv.setBlock(q, Blocks.AIR.defaultBlockState(), 2);
                }
            }
        }
    }

    /** Pickaxe sounds moving through the rock. */
    private static void mine(ServerPlayer p, BlockPos start, Direction dir, int blocks) {
        ServerLevel lv = p.serverLevel();
        for (int b = 0; b < blocks; b++) {
            final BlockPos at = start.relative(dir, b);
            int t0 = b * 30;
            for (int hit = 0; hit < 4; hit++) {
                later(p, 1 + t0 + hit * 5, () -> {
                    BlockState st = lv.getBlockState(at);
                    SoundEvent s = st.isAir() ? SoundEvents.STONE_HIT : st.getSoundType().getHitSound();
                    sound(p, s, Vec3.atCenterOf(at), 0.6F, 0.6F);
                });
            }
            later(p, 1 + t0 + 22, () -> {
                BlockState st = lv.getBlockState(at);
                SoundEvent s = st.isAir() ? SoundEvents.STONE_BREAK : st.getSoundType().getBreakSound();
                sound(p, s, Vec3.atCenterOf(at), 1.0F, 0.8F);
            });
        }
    }

    private static BlockPos findWall(ServerPlayer p, int min, int max) {
        ServerLevel lv = p.serverLevel();
        RandomSource r = p.getRandom();
        for (int tries = 0; tries < 20; tries++) {
            double a = r.nextDouble() * Math.PI * 2.0;
            int d = min + r.nextInt(max - min + 1);
            BlockPos b = BlockPos.containing(p.getX() + Math.cos(a) * d, p.getY() + 1 + r.nextInt(3) - 1, p.getZ() + Math.sin(a) * d);
            if (lv.isLoaded(b) && carvable(lv.getBlockState(b))) {
                return b;
            }
        }
        return null;
    }

    private static BlockPos findLeaves(ServerPlayer p, int min, int max) {
        ServerLevel lv = p.serverLevel();
        BlockPos c = p.blockPosition();
        BlockPos best = null;
        double bestD = Double.MAX_VALUE;
        for (BlockPos bp : BlockPos.betweenClosed(c.offset(-max, -4, -max), c.offset(max, 16, max))) {
            double d = bp.distSqr(c);
            if (d < min * min || d > max * max || d >= bestD) {
                continue;
            }
            BlockState st = lv.getBlockState(bp);
            if (st.is(BlockTags.LEAVES) && st.hasProperty(LeavesBlock.PERSISTENT) && !st.getValue(LeavesBlock.PERSISTENT)) {
                bestD = d;
                best = bp.immutable();
            }
        }
        return best;
    }

    /** Removes the leaves of one tree, leaving a bare trunk. */
    private static void stripTree(ServerLevel lv, BlockPos start) {
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        Set<BlockPos> seen = new HashSet<>();
        queue.add(start);
        seen.add(start);
        int removed = 0;
        while (!queue.isEmpty() && removed < 400) {
            BlockPos b = queue.poll();
            BlockState st = lv.getBlockState(b);
            boolean leaf = st.is(BlockTags.LEAVES);
            boolean wood = st.is(BlockTags.LOGS);
            if (!leaf && !wood) {
                continue;
            }
            if (leaf) {
                lv.setBlock(b, Blocks.AIR.defaultBlockState(), 18);
                removed++;
            }
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        BlockPos n = b.offset(dx, dy, dz);
                        if (n.distManhattan(start) < 12 && seen.add(n)) {
                            queue.add(n);
                        }
                    }
                }
            }
        }
    }

    private static BlockPos findChest(ServerPlayer p, int radius) {
        ServerLevel lv = p.serverLevel();
        BlockPos c = p.blockPosition();
        BlockPos best = null;
        double bestD = Double.MAX_VALUE;
        for (BlockPos bp : BlockPos.betweenClosed(c.offset(-radius, -5, -radius), c.offset(radius, 5, radius))) {
            BlockEntity be = lv.getBlockEntity(bp);
            if (be instanceof ChestBlockEntity) {
                double d = bp.distSqr(c);
                if (d < bestD) {
                    bestD = d;
                    best = bp.immutable();
                }
            }
        }
        return best;
    }

    public static void placeSign(ServerLevel lv, ServerPlayer p, BlockPos pos, String[] lines) {
        float yaw = (float) (Mth.atan2(pos.getZ() + 0.5 - p.getZ(), pos.getX() + 0.5 - p.getX()) * (180.0 / Math.PI)) - 90.0F;
        BlockState st = Blocks.OAK_SIGN.defaultBlockState()
                .setValue(StandingSignBlock.ROTATION, RotationSegment.convertToSegment(yaw + 180.0F));
        lv.setBlock(pos, st, 3);
        if (lv.getBlockEntity(pos) instanceof SignBlockEntity sign) {
            SignText text = new SignText();
            for (int i = 0; i < 4; i++) {
                text = text.setMessage(i, Component.literal(lines[i]));
            }
            sign.setText(text, true);
            sign.setText(text, false);
            sign.setWaxed(true);
            sign.setChanged();
            lv.sendBlockUpdated(pos, st, st, 3);
        }
    }

    public static void snuff(ServerLevel lv, BlockPos bp) {
        if (isTorch(lv.getBlockState(bp))) {
            lv.removeBlock(bp, false);
            lv.playSound(null, bp, SoundEvents.FIRE_EXTINGUISH, SoundSource.BLOCKS, 0.4F, 1.6F);
            lv.sendParticles(ParticleTypes.SMOKE, bp.getX() + 0.5, bp.getY() + 0.6, bp.getZ() + 0.5, 6, 0.05, 0.05, 0.05, 0.01);
        }
    }

    private static boolean isOpenable(BlockState st) {
        if (st.getBlock() instanceof DoorBlock) {
            return st.is(BlockTags.WOODEN_DOORS) && st.getValue(DoorBlock.HALF) == DoubleBlockHalf.LOWER;
        }
        return (st.is(BlockTags.WOODEN_TRAPDOORS) || st.is(BlockTags.FENCE_GATES))
                && st.hasProperty(BlockStateProperties.OPEN);
    }

    /** Nearest wooden door / trapdoor / fence gate. doorsOnly limits the search to doors. */
    private static BlockPos findOpenable(ServerPlayer p, int radius, boolean doorsOnly) {
        ServerLevel lv = p.serverLevel();
        BlockPos c = p.blockPosition();
        BlockPos best = null;
        double bestD = Double.MAX_VALUE;
        for (BlockPos bp : BlockPos.betweenClosed(c.offset(-radius, -5, -radius), c.offset(radius, 5, radius))) {
            BlockState st = lv.getBlockState(bp);
            if (!isOpenable(st) || (doorsOnly && !(st.getBlock() instanceof DoorBlock))) {
                continue;
            }
            double d = bp.distSqr(c);
            if (d < bestD) {
                bestD = d;
                best = bp.immutable();
            }
        }
        return best;
    }

    private static void toggle(ServerLevel lv, BlockPos pos) {
        BlockState st = lv.getBlockState(pos);
        if (!isOpenable(st)) {
            return;
        }
        boolean open = !st.getValue(BlockStateProperties.OPEN);
        if (st.getBlock() instanceof DoorBlock door) {
            door.setOpen(null, lv, st, pos, open);
            return;
        }
        lv.setBlock(pos, st.setValue(BlockStateProperties.OPEN, open), 10);
        SoundEvent s = st.is(BlockTags.FENCE_GATES)
                ? (open ? SoundEvents.FENCE_GATE_OPEN : SoundEvents.FENCE_GATE_CLOSE)
                : (open ? SoundEvents.WOODEN_TRAPDOOR_OPEN : SoundEvents.WOODEN_TRAPDOOR_CLOSE);
        lv.playSound(null, pos, s, SoundSource.BLOCKS, 1.0F, 0.9F);
    }

    public static boolean isTorch(BlockState st) {
        return st.is(Blocks.TORCH) || st.is(Blocks.WALL_TORCH) || st.is(Blocks.SOUL_TORCH) || st.is(Blocks.SOUL_WALL_TORCH);
    }

    public static String spawnWatcher(ServerPlayer p, double dist, WatcherEntity.Mode mode, DoubleSupplier angle,
            boolean force, Consumer<WatcherEntity> setup) {
        ServerLevel lv = p.serverLevel();
        List<WatcherEntity> existing = lv.getEntitiesOfClass(WatcherEntity.class, p.getBoundingBox().inflate(160.0));
        if (!existing.isEmpty()) {
            if (!force) {
                return "a watcher is already nearby";
            }
            for (WatcherEntity old : existing) {
                old.discard();
            }
        }
        for (int tries = 0; tries < 16; tries++) {
            double a = angle.getAsDouble();
            double d = dist * (tries < 8 ? 1.0 : 0.7);
            BlockPos pos = stand(lv, p.getX() + Math.cos(a) * d, p.getZ() + Math.sin(a) * d, p.getBlockY(), 8);
            if (pos == null) {
                continue;
            }
            WatcherEntity w = ModRegistry.WATCHER.get().create(lv);
            if (w == null) {
                return "could not create entity";
            }
            float yaw = (float) (Mth.atan2(p.getZ() - (pos.getZ() + 0.5), p.getX() - (pos.getX() + 0.5)) * (180.0 / Math.PI)) - 90.0F;
            w.moveTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, yaw, 0.0F);
            w.setYHeadRot(yaw);
            w.setYBodyRot(yaw);
            w.setMode(mode);
            if (setup != null) {
                setup.accept(w);
            }
            lv.addFreshEntity(w);
            return null;
        }
        return "no free spot to spawn the watcher";
    }

    /** Random angle (radians, world XZ) roughly in the player's view direction. */
    public static double front(ServerPlayer p, double spread) {
        Vec3 look = p.getLookAngle();
        return Math.atan2(look.z, look.x) + (p.getRandom().nextDouble() - 0.5) * 2.0 * spread;
    }

    public static double back(ServerPlayer p, double spread) {
        return front(p, spread) + Math.PI;
    }

    /** Finds a free 1x2 spot with solid ground near the given column. */
    public static BlockPos stand(ServerLevel lv, double x, double z, int y0, int maxDy) {
        int bx = Mth.floor(x);
        int bz = Mth.floor(z);
        for (int dy = 0; dy <= maxDy; dy++) {
            for (int sign = 1; sign >= -1; sign -= 2) {
                if (dy == 0 && sign == -1) {
                    continue;
                }
                BlockPos pos = new BlockPos(bx, y0 + dy * sign, bz);
                if (!lv.isLoaded(pos)) {
                    return null;
                }
                BlockPos below = pos.below();
                if (lv.getBlockState(below).isFaceSturdy(lv, below, Direction.UP)
                        && lv.getBlockState(pos).getCollisionShape(lv, pos).isEmpty()
                        && lv.getBlockState(pos.above()).getCollisionShape(lv, pos.above()).isEmpty()
                        && lv.getFluidState(pos).isEmpty()) {
                    return pos;
                }
            }
        }
        return null;
    }

    public static Vec3 behind(ServerPlayer p, double dist, double spread) {
        Vec3 look = p.getLookAngle();
        double a = Math.atan2(-look.z, -look.x) + (p.getRandom().nextDouble() - 0.5) * 2.0 * spread;
        return new Vec3(p.getX() + Math.cos(a) * dist, p.getEyeY(), p.getZ() + Math.sin(a) * dist);
    }

    /** Sound that only this player hears. */
    public static void sound(ServerPlayer p, Holder<SoundEvent> holder, Vec3 v, float volume, float pitch) {
        p.connection.send(new ClientboundSoundPacket(holder, SoundSource.HOSTILE, v.x, v.y, v.z, volume, pitch,
                p.getRandom().nextLong()));
    }

    public static void sound(ServerPlayer p, SoundEvent event, Vec3 v, float volume, float pitch) {
        sound(p, BuiltInRegistries.SOUND_EVENT.wrapAsHolder(event), v, volume, pitch);
    }
}
