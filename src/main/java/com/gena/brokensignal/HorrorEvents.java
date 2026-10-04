package com.gena.brokensignal;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
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

    public static boolean trigger(ServerPlayer p, String id) {
        ServerLevel lv = p.serverLevel();
        RandomSource r = p.getRandom();
        switch (id) {
            case "whisper" -> {
                sound(p, ModRegistry.WHISPER.get(), behind(p, 2.5, 0.6), 0.7F, 0.9F + r.nextFloat() * 0.2F);
                if (r.nextFloat() < 0.4F) {
                    p.displayClientMessage(Component.literal(pickS(r, WHISPERS))
                            .withStyle(ChatFormatting.DARK_GRAY, ChatFormatting.ITALIC), true);
                }
                return true;
            }
            case "footsteps" -> {
                int n = 5 + r.nextInt(4);
                for (int i = 0; i < n; i++) {
                    final double d = Math.max(2.0, 11.0 - i * 1.3);
                    later(p, 1 + i * 8, () -> {
                        Vec3 v = behind(p, d, 0.15);
                        BlockState below = lv.getBlockState(BlockPos.containing(v.x, p.getY() - 0.5, v.z));
                        SoundEvent step = below.isAir() ? SoundEvents.STONE_STEP : below.getSoundType().getStepSound();
                        sound(p, step, new Vec3(v.x, p.getY(), v.z), 0.6F, 0.9F + r.nextFloat() * 0.1F);
                    });
                }
                return true;
            }
            case "knock" -> {
                sound(p, ModRegistry.KNOCK.get(), behind(p, 4.0, 1.2), 0.9F, 1.0F);
                return true;
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
                return true;
            }
            case "join" -> {
                p.sendSystemMessage(Component.translatable("multiplayer.player.joined", FAKE).withStyle(ChatFormatting.YELLOW));
                if (r.nextBoolean()) {
                    later(p, 60 + r.nextInt(100), () -> p.sendSystemMessage(Component.literal("<" + FAKE + "> " + pickS(r, JOIN_LINES))));
                }
                later(p, 400 + r.nextInt(800), () -> p.sendSystemMessage(
                        Component.translatable("multiplayer.player.left", FAKE).withStyle(ChatFormatting.YELLOW)));
                return true;
            }
            case "door" -> {
                BlockPos c = p.blockPosition();
                BlockPos found = null;
                for (BlockPos bp : BlockPos.betweenClosed(c.offset(-12, -4, -12), c.offset(12, 4, 12))) {
                    BlockState st = lv.getBlockState(bp);
                    if (st.getBlock() instanceof DoorBlock && st.is(BlockTags.WOODEN_DOORS)
                            && st.getValue(DoorBlock.HALF) == DoubleBlockHalf.LOWER) {
                        found = bp.immutable();
                        break;
                    }
                }
                if (found == null) {
                    return false;
                }
                BlockState st = lv.getBlockState(found);
                ((DoorBlock) st.getBlock()).setOpen(null, lv, st, found, !st.getValue(DoorBlock.OPEN));
                return true;
            }
            case "drone" -> {
                sound(p, ModRegistry.DRONE.get(), p.position().add(0, 1, 0), 0.8F, 0.8F + r.nextFloat() * 0.3F);
                return true;
            }
            case "watcher" -> {
                return spawnWatcher(p, 22 + r.nextInt(18), WatcherEntity.Mode.STALK, 0.9) != null;
            }
            case "behind" -> {
                WatcherEntity w = spawnWatcher(p, 5, WatcherEntity.Mode.STALK, 0.25);
                if (w == null) {
                    return false;
                }
                w.setLookLimit(3);
                title(p, "ОБЕРНИСЬ", null);
                return true;
            }
            case "chase" -> {
                WatcherEntity w = spawnWatcher(p, 20, WatcherEntity.Mode.CHASE, 0.5);
                if (w == null) {
                    return false;
                }
                title(p, "БЕГИ", null);
                sound(p, ModRegistry.DRONE.get(), p.position(), 1.0F, 0.6F);
                return true;
            }
            case "darkness" -> {
                p.addEffect(new MobEffectInstance(MobEffects.DARKNESS, 220, 0, false, false));
                sound(p, ModRegistry.STATIC_BURST.get(), p.position(), 0.6F, 0.7F);
                later(p, 30, () -> title(p, "", "ты не один"));
                return true;
            }
            case "torches" -> {
                if (!Config.WORLD_EDITS.get()) {
                    return false;
                }
                BlockPos c = p.blockPosition();
                List<BlockPos> list = new ArrayList<>();
                for (BlockPos bp : BlockPos.betweenClosed(c.offset(-10, -4, -10), c.offset(10, 5, 10))) {
                    if (isTorch(lv.getBlockState(bp))) {
                        list.add(bp.immutable());
                    }
                }
                if (list.isEmpty()) {
                    return false;
                }
                Collections.shuffle(list);
                int n = Math.min(list.size(), 2 + r.nextInt(4));
                for (int i = 0; i < n; i++) {
                    final BlockPos bp = list.get(i);
                    later(p, 1 + i * 12, () -> {
                        if (isTorch(lv.getBlockState(bp))) {
                            lv.removeBlock(bp, false);
                            lv.playSound(null, bp, SoundEvents.FIRE_EXTINGUISH, SoundSource.BLOCKS, 0.4F, 1.6F);
                            lv.sendParticles(ParticleTypes.SMOKE, bp.getX() + 0.5, bp.getY() + 0.6, bp.getZ() + 0.5,
                                    6, 0.05, 0.05, 0.05, 0.01);
                        }
                    });
                }
                return true;
            }
            case "pillar" -> {
                if (!Config.WORLD_EDITS.get()) {
                    return false;
                }
                double a = r.nextDouble() * Math.PI * 2.0;
                int dist = 30 + r.nextInt(20);
                int x = Mth.floor(p.getX() + Math.cos(a) * dist);
                int z = Mth.floor(p.getZ() + Math.sin(a) * dist);
                if (!lv.isLoaded(new BlockPos(x, p.getBlockY(), z))) {
                    return false;
                }
                int y = lv.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, x, z);
                BlockPos base = new BlockPos(x, y, z);
                if (!lv.getFluidState(base.below()).isEmpty()) {
                    return false;
                }
                for (int i = 0; i < 3; i++) {
                    lv.setBlock(base.above(i), Blocks.OBSIDIAN.defaultBlockState(), 3);
                }
                lv.setBlock(base.above(3), Blocks.REDSTONE_TORCH.defaultBlockState(), 3);
                return true;
            }
            case "sign" -> {
                if (!Config.WORLD_EDITS.get()) {
                    return false;
                }
                Vec3 v = behind(p, 5.0, 0.5);
                BlockPos pos = stand(lv, v.x, v.z, p.getBlockY());
                if (pos == null) {
                    return false;
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
                    sign.setChanged();
                    lv.sendBlockUpdated(pos, st, st, 3);
                }
                return true;
            }
            default -> {
                return false;
            }
        }
    }

    private static boolean isTorch(BlockState st) {
        return st.is(Blocks.TORCH) || st.is(Blocks.WALL_TORCH) || st.is(Blocks.SOUL_TORCH) || st.is(Blocks.SOUL_WALL_TORCH);
    }

    private static WatcherEntity spawnWatcher(ServerPlayer p, double dist, WatcherEntity.Mode mode, double spread) {
        ServerLevel lv = p.serverLevel();
        if (!lv.getEntitiesOfClass(WatcherEntity.class, p.getBoundingBox().inflate(160.0)).isEmpty()) {
            return null;
        }
        for (int tries = 0; tries < 8; tries++) {
            Vec3 v = behind(p, dist, spread);
            BlockPos pos = stand(lv, v.x, v.z, p.getBlockY());
            if (pos == null) {
                continue;
            }
            WatcherEntity w = ModRegistry.WATCHER.get().create(lv);
            if (w == null) {
                return null;
            }
            float yaw = (float) (Mth.atan2(p.getZ() - (pos.getZ() + 0.5), p.getX() - (pos.getX() + 0.5)) * (180.0 / Math.PI)) - 90.0F;
            w.moveTo(pos.getX() + 0.5, pos.getY(), pos.getZ() + 0.5, yaw, 0.0F);
            w.setYHeadRot(yaw);
            w.setYBodyRot(yaw);
            w.setMode(mode);
            lv.addFreshEntity(w);
            return w;
        }
        return null;
    }

    /** Finds a free 1x2 spot with solid ground near the given column. */
    private static BlockPos stand(ServerLevel lv, double x, double z, int y0) {
        int bx = Mth.floor(x);
        int bz = Mth.floor(z);
        for (int dy = 0; dy <= 12; dy++) {
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

    /** Sound that only this player hears. */
    private static void sound(ServerPlayer p, SoundEvent event, Vec3 v, float volume, float pitch) {
        Holder<SoundEvent> holder = BuiltInRegistries.SOUND_EVENT.wrapAsHolder(event);
        p.connection.send(new ClientboundSoundPacket(holder, SoundSource.AMBIENT, v.x, v.y, v.z, volume, pitch,
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
