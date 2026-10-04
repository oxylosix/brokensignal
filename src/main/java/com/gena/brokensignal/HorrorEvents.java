package com.gena.brokensignal;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.DoubleSupplier;
import java.util.function.Consumer;
import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.Holder;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetSubtitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket;
import net.minecraft.network.protocol.game.ClientboundSetTitlesAnimationPacket;
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
import net.minecraft.world.item.DyeColor;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.StandingSignBlock;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.RotationSegment;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;
import net.neoforged.neoforge.event.tick.PlayerTickEvent;

/** Server-side "director": slowly escalating horror events per player. */
public final class HorrorEvents {
    public static final List<String> EVENTS = List.of(
            "whisper", "footsteps", "knock", "chat", "door", "drone", "watcher", "join",
            "torches", "pillar", "sign", "darkness", "behind", "chase");

    private static final String KEY = "brokensignal_ticks";
    private static final String FAKE = "s1gnal";
    private static final Map<UUID, State> STATES = new HashMap<>();

    private static final String[] WHISPERS = {
            "...здесь...", "...не оборачивайся...", "...я рядом...", "...слышишь?...", "...тише..."
    };
    private static final String[] CHAT = {
            "ты слышишь меня?",
            "%name%",
            "%name%, почему ты здесь один?",
            "я вижу тебя. %x% %y% %z%",
            "это не твой мир",
            "ты оставил дверь открытой",
            "сигнал потерян",
            "не выходи ночью",
            "зачем ты вернулся",
            "[сообщение удалено]",
            "мы уже встречались",
            "обернись"
    };
    private static final String[] JOIN_LINES = {"привет", "...", "нашёл тебя", "тут темно"};
    private static final String[][] SIGNS = {
            {"", "ТЫ НЕ", "ОДИН", ""},
            {"Я ВИДЕЛ", "КАК ТЫ", "СПИШЬ", ""},
            {"", "НЕ", "ОБОРАЧИВАЙСЯ", ""},
            {"", "%name%", "", ""},
            {"ЗДЕСЬ", "БЫЛ", "КТО-ТО", "ЕЩЁ"},
            {"СИГНАЛ", "ПОТЕРЯН", "", ""}
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
        int cooldown = 20 * 90;
        final List<Task> tasks = new ArrayList<>();
    }

    private HorrorEvents() {}

    /** Keep horror progress after death / returning from the End. */
    public static void onClone(PlayerEvent.Clone event) {
        CompoundTag old = event.getOriginal().getPersistentData();
        if (old.contains(KEY)) {
            event.getEntity().getPersistentData().putInt(KEY, old.getInt(KEY));
        }
    }

    public static void onLogout(PlayerEvent.PlayerLoggedOutEvent event) {
        STATES.remove(event.getEntity().getUUID());
    }

    public static void onPlayerTick(PlayerTickEvent.Post event) {
        if (!(event.getEntity() instanceof ServerPlayer player)) {
            return;
        }
        State state = state(player);
        if (!state.tasks.isEmpty()) {
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
        if (!Config.ENABLED.get() || player.isSpectator()) {
            return;
        }
        if (player.isCreative() && !Config.AFFECT_CREATIVE.get()) {
            return;
        }
        CompoundTag data = player.getPersistentData();
        int ticks = data.getInt(KEY) + 1;
        data.putInt(KEY, ticks);
        if (ticks < Config.GRACE_MINUTES.get() * 1200) {
            return;
        }
        if (--state.cooldown > 0) {
            return;
        }
        int stage = stage(player);
        RandomSource r = player.getRandom();
        double k = Math.max(0.1, Config.INTENSITY.get()) * (1.0 + stage * 0.4);
        state.cooldown = Math.max(100, (int) ((20 * 60 + r.nextInt(20 * 150)) / k));
        for (int i = 0; i < 4; i++) {
            if (trigger(player, pick(player, stage))) {
                break;
            }
        }
    }

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

    private static String pick(ServerPlayer player, int stage) {
        List<String> pool = new ArrayList<>();
        add(pool, "whisper", 4);
        add(pool, "footsteps", 4);
        add(pool, "knock", 3);
        add(pool, "chat", 3);
        add(pool, "door", 2);
        add(pool, "drone", 2);
        if (stage >= 1) {
            add(pool, "watcher", 5);
            add(pool, "join", 2);
            add(pool, "torches", 2);
            add(pool, "pillar", 1);
        }
        if (stage >= 2) {
            add(pool, "sign", 2);
            add(pool, "darkness", 2);
            add(pool, "behind", 2);
            add(pool, "watcher", 2);
        }
        if (stage >= 3 && player.level().isNight()) {
            add(pool, "chase", 2);
        }
        return pool.get(player.getRandom().nextInt(pool.size()));
    }

    private static void add(List<String> list, String id, int weight) {
        for (int i = 0; i < weight; i++) {
            list.add(id);
        }
    }

    private static State state(ServerPlayer player) {
        return STATES.computeIfAbsent(player.getUUID(), k -> new State());
    }

    private static void later(ServerPlayer player, int delay, Runnable run) {
        state(player).tasks.add(new Task(delay, run));
    }

    private static String pickS(RandomSource r, String[] arr) {
        return arr[r.nextInt(arr.length)];
    }

    /** Used by the automatic director. */
    public static boolean trigger(ServerPlayer p, String id) {
        return run(p, id, false) == null;
    }

    /** Runs an event. Returns null on success, otherwise a short reason why it could not run. */
    public static String run(ServerPlayer p, String id, boolean force) {
        ServerLevel lv = p.serverLevel();
        RandomSource r = p.getRandom();
        switch (id) {
            case "whisper" -> {
                sound(p, ModRegistry.WHISPER, behind(p, 2.0, 0.5), 1.0F, 0.9F + r.nextFloat() * 0.2F);
                if (force || r.nextFloat() < 0.5F) {
                    p.displayClientMessage(Component.literal(pickS(r, WHISPERS))
                            .withStyle(ChatFormatting.DARK_GRAY, ChatFormatting.ITALIC), true);
                }
                return null;
            }
            case "footsteps" -> {
                Vec3 look = p.getLookAngle();
                final double base = Math.atan2(-look.z, -look.x) + (r.nextDouble() - 0.5) * 0.5;
                final double ox = p.getX();
                final double oz = p.getZ();
                int n = 6 + r.nextInt(4);
                for (int i = 0; i < n; i++) {
                    final double d = Math.max(1.8, 12.0 - i * 1.4);
                    final boolean left = i % 2 == 0;
                    later(p, 1 + i * 7, () -> {
                        double side = left ? 0.25 : -0.25;
                        double x = ox + Math.cos(base) * d + Math.cos(base + Math.PI / 2) * side;
                        double z = oz + Math.sin(base) * d + Math.sin(base + Math.PI / 2) * side;
                        BlockState below = lv.getBlockState(BlockPos.containing(x, p.getY() - 0.5, z));
                        SoundEvent step = below.isAir() ? SoundEvents.STONE_STEP : below.getSoundType().getStepSound();
                        sound(p, step, new Vec3(x, p.getY(), z), 1.0F, 0.85F + r.nextFloat() * 0.1F);
                    });
                }
                // the steps stop right behind you... and then a whisper
                later(p, 1 + n * 7 + 25, () -> sound(p, ModRegistry.WHISPER, behind(p, 1.2, 0.2), 0.8F, 0.8F));
                return null;
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
            case "chat" -> {
                String msg = pickS(r, CHAT)
                        .replace("%name%", p.getName().getString())
                        .replace("%x%", String.valueOf(p.getBlockX()))
                        .replace("%y%", String.valueOf(p.getBlockY()))
                        .replace("%z%", String.valueOf(p.getBlockZ()));
                p.sendSystemMessage(Component.literal("<")
                        .append(Component.literal("??????").withStyle(ChatFormatting.OBFUSCATED))
                        .append("> ")
                        .append(Component.literal(msg)));
                return null;
            }
            case "join" -> {
                p.sendSystemMessage(Component.translatable("multiplayer.player.joined", FAKE).withStyle(ChatFormatting.YELLOW));
                if (force || r.nextBoolean()) {
                    later(p, 60 + r.nextInt(100), () -> p.sendSystemMessage(Component.literal("<" + FAKE + "> " + pickS(r, JOIN_LINES))));
                }
                later(p, force ? 300 : 400 + r.nextInt(800), () -> p.sendSystemMessage(
                        Component.translatable("multiplayer.player.left", FAKE).withStyle(ChatFormatting.YELLOW)));
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
            case "drone" -> {
                sound(p, ModRegistry.DRONE, p.position().add(0, 1, 0), 1.0F, 0.8F + r.nextFloat() * 0.3F);
                return null;
            }
            case "watcher" -> {
                // far away, in front-ish so the player can actually notice it
                return spawnWatcher(p, 20 + r.nextInt(14), WatcherEntity.Mode.STALK, () -> front(p, 1.1), force, null);
            }
            case "behind" -> {
                String err = spawnWatcher(p, 3.0, WatcherEntity.Mode.STALK, () -> back(p, 0.3), force, w -> {
                    w.setLookLimit(2);
                    w.setVanishDistance(1.0);
                    w.setMaxLife(20 * 15);
                });
                if (err != null) {
                    return err;
                }
                sound(p, ModRegistry.WHISPER, behind(p, 1.5, 0.2), 1.0F, 0.7F);
                later(p, 10, () -> title(p, "ОБЕРНИСЬ", null));
                return null;
            }
            case "chase" -> {
                String err = spawnWatcher(p, 18, WatcherEntity.Mode.CHASE, () -> back(p, 0.6), force, null);
                if (err != null) {
                    return err;
                }
                title(p, "БЕГИ", null);
                sound(p, ModRegistry.DRONE, p.position(), 1.0F, 0.6F);
                return null;
            }
            case "darkness" -> {
                p.addEffect(new MobEffectInstance(MobEffects.DARKNESS, 220, 0, false, false));
                sound(p, ModRegistry.STATIC_BURST, p.position(), 0.8F, 0.7F);
                later(p, 30, () -> title(p, "", "ты не один"));
                return null;
            }
            case "torches" -> {
                if (!Config.WORLD_EDITS.get()) {
                    return "worldEdits is disabled in config";
                }
                BlockPos c = p.blockPosition();
                List<BlockPos> list = new ArrayList<>();
                for (BlockPos bp : BlockPos.betweenClosed(c.offset(-16, -6, -16), c.offset(16, 8, 16))) {
                    if (isTorch(lv.getBlockState(bp))) {
                        list.add(bp.immutable());
                    }
                }
                if (list.isEmpty()) {
                    return "no torches within 16 blocks";
                }
                Collections.shuffle(list);
                int n = Math.min(list.size(), 2 + r.nextInt(5));
                for (int i = 0; i < n; i++) {
                    final BlockPos bp = list.get(i);
                    later(p, 1 + i * 12, () -> {
                        if (isTorch(lv.getBlockState(bp))) {
                            lv.removeBlock(bp, false);
                            lv.playSound(null, bp, SoundEvents.FIRE_EXTINGUISH, SoundSource.BLOCKS, 0.5F, 1.6F);
                            lv.sendParticles(ParticleTypes.SMOKE, bp.getX() + 0.5, bp.getY() + 0.6, bp.getZ() + 0.5,
                                    8, 0.05, 0.05, 0.05, 0.01);
                        }
                    });
                }
                return null;
            }
            case "pillar" -> {
                if (!Config.WORLD_EDITS.get()) {
                    return "worldEdits is disabled in config";
                }
                for (int tries = 0; tries < 10; tries++) {
                    double a = front(p, 0.8);
                    int dist = 16 + r.nextInt(14);
                    int x = Mth.floor(p.getX() + Math.cos(a) * dist);
                    int z = Mth.floor(p.getZ() + Math.sin(a) * dist);
                    BlockPos pos = stand(lv, x + 0.5, z + 0.5, p.getBlockY(), 4);
                    if (pos == null || !lv.getFluidState(pos.below()).isEmpty()) {
                        continue;
                    }
                    for (int i = 0; i < 3; i++) {
                        lv.setBlock(pos.above(i), Blocks.OBSIDIAN.defaultBlockState(), 3);
                    }
                    lv.setBlock(pos.above(3), Blocks.REDSTONE_TORCH.defaultBlockState(), 3);
                    sound(p, ModRegistry.STATIC_BURST, Vec3.atCenterOf(pos), 1.0F, 0.6F);
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
                float yaw = (float) (Mth.atan2(pos.getZ() + 0.5 - p.getZ(), pos.getX() + 0.5 - p.getX()) * (180.0 / Math.PI)) - 90.0F;
                BlockState st = Blocks.OAK_SIGN.defaultBlockState()
                        .setValue(StandingSignBlock.ROTATION, RotationSegment.convertToSegment(yaw + 180.0F));
                lv.setBlock(pos, st, 3);
                if (lv.getBlockEntity(pos) instanceof SignBlockEntity sign) {
                    String[] lines = SIGNS[r.nextInt(SIGNS.length)];
                    SignText text = new SignText().setColor(DyeColor.RED).setHasGlowingText(true);
                    for (int i = 0; i < 4; i++) {
                        text = text.setMessage(i, Component.literal(lines[i].replace("%name%", p.getName().getString())));
                    }
                    sign.setText(text, true);
                    sign.setText(text, false);
                    sign.setWaxed(true);
                    sign.setChanged();
                    lv.sendBlockUpdated(pos, st, st, 3);
                }
                sound(p, ModRegistry.KNOCK, Vec3.atCenterOf(pos), 0.7F, 1.3F);
                return null;
            }
            default -> {
                return "unknown event";
            }
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

    private static boolean isTorch(BlockState st) {
        return st.is(Blocks.TORCH) || st.is(Blocks.WALL_TORCH) || st.is(Blocks.SOUL_TORCH) || st.is(Blocks.SOUL_WALL_TORCH);
    }

    private static String spawnWatcher(ServerPlayer p, double dist, WatcherEntity.Mode mode, DoubleSupplier angle,
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
    private static double front(ServerPlayer p, double spread) {
        Vec3 look = p.getLookAngle();
        return Math.atan2(look.z, look.x) + (p.getRandom().nextDouble() - 0.5) * 2.0 * spread;
    }

    /** Random angle roughly behind the player. */
    private static double back(ServerPlayer p, double spread) {
        return front(p, spread) + Math.PI;
    }

    /** Finds a free 1x2 spot with solid ground near the given column. */
    private static BlockPos stand(ServerLevel lv, double x, double z, int y0, int maxDy) {
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

    private static Vec3 behind(ServerPlayer p, double dist, double spread) {
        Vec3 look = p.getLookAngle();
        double a = Math.atan2(-look.z, -look.x) + (p.getRandom().nextDouble() - 0.5) * 2.0 * spread;
        return new Vec3(p.getX() + Math.cos(a) * dist, p.getEyeY(), p.getZ() + Math.sin(a) * dist);
    }

    private static void sound(ServerPlayer p, Holder<SoundEvent> holder, Vec3 v, float volume, float pitch) {
        p.connection.send(new ClientboundSoundPacket(holder, SoundSource.HOSTILE, v.x, v.y, v.z, volume, pitch,
                p.getRandom().nextLong()));
    }

    /** Sound that only this player hears. */
    private static void sound(ServerPlayer p, SoundEvent event, Vec3 v, float volume, float pitch) {
        Holder<SoundEvent> holder = BuiltInRegistries.SOUND_EVENT.wrapAsHolder(event);
        p.connection.send(new ClientboundSoundPacket(holder, SoundSource.HOSTILE, v.x, v.y, v.z, volume, pitch,
                p.getRandom().nextLong()));
    }

    private static void title(ServerPlayer p, String title, String subtitle) {
        p.connection.send(new ClientboundSetTitlesAnimationPacket(4, 30, 16));
        p.connection.send(new ClientboundSetSubtitleTextPacket(
                Component.literal(subtitle == null ? "" : subtitle).withStyle(ChatFormatting.GRAY)));
        p.connection.send(new ClientboundSetTitleTextPacket(
                Component.literal(title).withStyle(ChatFormatting.DARK_RED, ChatFormatting.BOLD)));
    }
}
