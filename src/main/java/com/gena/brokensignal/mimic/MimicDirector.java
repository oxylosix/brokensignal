package com.gena.brokensignal.mimic;

import com.mojang.logging.LogUtils;
import org.slf4j.Logger;
import com.gena.brokensignal.Config;
import com.gena.brokensignal.Director;
import com.gena.brokensignal.HorrorEvents;
import com.gena.brokensignal.HorrorState;
import com.gena.brokensignal.ModRegistry;
import com.gena.brokensignal.ext.Outside;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.util.Mth;
import net.minecraft.util.RandomSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Runs player-shaped mimics. A session is one "visit": the body arrives the way a person would,
 * spends time doing ordinary things near the observer and leaves.
 *
 * Rules enforced here:
 *  - STEALTH sessions never err, never stare, never make sounds of their own.
 *  - Most visits end in nothing. Betrayal is decided once at the start from context and then
 *    waits for an ordinary moment (player busy, close, not in danger).
 *  - A betrayal is one hit and a disappearance, never a fight. It never kills.
 *  - Only the observer receives the entity (see MimicEntity#broadcastToPlayer).
 */
public final class MimicDirector {
    public enum Kind { FRIEND, WORKER, SELF }

    public enum Mode { STEALTH, ALMOST }

    enum Task { ARRIVE, ACCOMPANY, WORK, FIGHT, REPLAY, LEAVE }

    static final class Session {
        final Kind kind;
        final Mode mode;
        final UUID observer;
        final UUID copied;
        final String name;
        final MimicEntity body;
        final long started;
        final long endsAt;
        final boolean betray;
        final float sprintRatio;
        final String trade;
        Task task = Task.ARRIVE;
        int taskTicks;
        BlockPos work;
        int workProgress;
        int swingIn;
        Habits.Clip clip;
        int clipIndex;
        boolean struck;
        int vanishIn = -1;
        int crouchReply;
        boolean lastObsCrouch;
        int obsCrouches;
        int errorCooldown = 20 * 90;
        int errorTicks;

        Session(Kind kind, Mode mode, UUID observer, UUID copied, String name, MimicEntity body, long now, long ends,
                boolean betray, float sprintRatio, String trade) {
            this.kind = kind;
            this.mode = mode;
            this.observer = observer;
            this.copied = copied;
            this.name = name;
            this.body = body;
            this.started = now;
            this.endsAt = ends;
            this.betray = betray;
            this.sprintRatio = sprintRatio;
            this.trade = trade;
        }
    }

    private static final Logger LOG = LogUtils.getLogger();
    private static final Map<UUID, Session> BY_BODY = new HashMap<>();
    private static final Map<UUID, Session> BY_OBSERVER = new HashMap<>();

    private MimicDirector() {}

    public static boolean drives(MimicEntity e) {
        return BY_BODY.containsKey(e.getUUID());
    }

    public static boolean busy(ServerPlayer p) {
        return BY_OBSERVER.containsKey(p.getUUID());
    }

    public static String describe(ServerPlayer p) {
        Session s = BY_OBSERVER.get(p.getUUID());
        if (s == null) {
            return "none";
        }
        return s.kind + "/" + s.mode + " as " + s.name + " task=" + s.task + " betray=" + s.betray
                + " left=" + (s.endsAt - HorrorState.of(p).now()) / 20 + "s";
    }

    public static void stop(ServerPlayer p) {
        Session s = BY_OBSERVER.get(p.getUUID());
        if (s != null) {
            end(s);
        }
    }

    // ------------------------------------------------------------------ starting

    /** Copy another online player who is far away or in another dimension. Multiplayer only. */
    public static String startFriend(ServerPlayer obs, boolean force) {
        if (busy(obs)) {
            return "already has a visitor";
        }
        ServerPlayer friend = null;
        for (ServerPlayer o : obs.server.getPlayerList().getPlayers()) {
            if (o == obs || o.isSpectator()) {
                continue;
            }
            boolean far = o.level() != obs.level() || o.distanceToSqr(obs) > 160 * 160;
            if (far && (friend == null || obs.getRandom().nextBoolean())) {
                friend = o;
            }
        }
        if (friend == null) {
            return "no other player is far enough away";
        }
        if (!force && (obs.serverLevel().isNight() || obs.getY() < obs.serverLevel().getSeaLevel() - 8)) {
            // the best moments are ordinary ones: daylight, on the surface
            return "not an ordinary moment";
        }
        int sim = Habits.similarity(friend);
        Mode mode = sim >= 90 || obs.getRandom().nextFloat() < 0.6F ? Mode.STEALTH : Mode.ALMOST;
        return spawn(obs, Kind.FRIEND, mode, friend.getUUID(), friend.getGameProfile().getName(), equipmentOf(friend),
                Habits.sprintRatio(friend), Habits.trade(friend), null, force);
    }

    /** A figure far away that works like a person. Its name tag is never close enough to read. */
    public static String startWorker(ServerPlayer obs, boolean force) {
        if (busy(obs)) {
            return "already has a visitor";
        }
        if (obs.serverLevel().isNight() && !force) {
            return "daytime only";
        }
        long seed = obs.serverLevel().getSeed();
        UUID look = new UUID(seed, seed ^ 0x5157L);
        ItemStack[] eq = new ItemStack[] {ItemStack.EMPTY, ItemStack.EMPTY, ItemStack.EMPTY, ItemStack.EMPTY,
                new ItemStack(obs.getRandom().nextBoolean() ? Items.STONE_AXE : Items.IRON_PICKAXE)};
        return spawn(obs, Kind.WORKER, Mode.STEALTH, look, null, eq, 0.1F, "chop", null, force);
    }

    /** You, doing what you did on an earlier day, at the place you did it. */
    public static String startSelf(ServerPlayer obs, boolean force) {
        if (busy(obs)) {
            return "already has a visitor";
        }
        Habits.Clip clip = Habits.olderClip(obs);
        if (clip == null) {
            return "no work recorded on an earlier day";
        }
        if (!force && clip.origin().distSqr(obs.blockPosition()) > 96 * 96) {
            return "not near yesterday's place";
        }
        return spawn(obs, Kind.SELF, Mode.STEALTH, obs.getUUID(), obs.getGameProfile().getName(), equipmentOf(obs),
                Habits.sprintRatio(obs), Habits.trade(obs), clip, force);
    }

    private static ItemStack[] equipmentOf(ServerPlayer p) {
        return new ItemStack[] {p.getItemBySlot(EquipmentSlot.HEAD).copy(), p.getItemBySlot(EquipmentSlot.CHEST).copy(),
                p.getItemBySlot(EquipmentSlot.LEGS).copy(), p.getItemBySlot(EquipmentSlot.FEET).copy(),
                p.getMainHandItem().copy()};
    }

    private static String spawn(ServerPlayer obs, Kind kind, Mode mode, UUID skin, String name, ItemStack[] eq,
            float sprint, String trade, Habits.Clip clip, boolean force) {
        ServerLevel lv = obs.serverLevel();
        RandomSource r = obs.getRandom();
        Vec3 at;
        if (clip != null) {
            at = Habits.pos(clip.samples().get(0));
            if (canSee(obs, at.add(0, 1.6, 0))) {
                at = null;
            }
        } else {
            int min = kind == Kind.WORKER ? 55 : 30;
            at = hiddenSpot(obs, min, min + 25);
        }
        if (at == null) {
            return "no hidden spot to arrive from";
        }
        MimicEntity m = ModRegistry.MIMIC.get().create(lv);
        if (m == null) {
            return "could not create body";
        }
        m.moveTo(at.x, at.y, at.z, r.nextFloat() * 360F, 0F);
        m.setSkinOwner(skin);
        m.setObserver(obs.getUUID());
        if (name != null) {
            m.setCustomName(Component.literal(name));
            m.setCustomNameVisible(true);
        }
        m.setItemSlot(EquipmentSlot.HEAD, eq[0]);
        m.setItemSlot(EquipmentSlot.CHEST, eq[1]);
        m.setItemSlot(EquipmentSlot.LEGS, eq[2]);
        m.setItemSlot(EquipmentSlot.FEET, eq[3]);
        m.setItemSlot(EquipmentSlot.MAINHAND, eq[4]);
        HorrorState st = HorrorState.of(obs);
        long now = st.now();
        int minutes = kind == Kind.WORKER ? 4 + r.nextInt(6) : kind == Kind.SELF ? 3 + r.nextInt(3) : 6 + r.nextInt(18);
        boolean betray = decideBetrayal(obs, st, kind, r, force);
        Session s = new Session(kind, mode, obs.getUUID(), skin, name == null ? "?" : name, m, now, now + 20L * 60 * minutes,
                betray, sprint, trade);
        s.clip = clip;
        s.task = clip != null ? Task.REPLAY : kind == Kind.WORKER ? Task.WORK : Task.ARRIVE;
        lv.addFreshEntity(m);
        BY_BODY.put(m.getUUID(), s);
        BY_OBSERVER.put(obs.getUUID(), s);
        st.inc("mimic_visits");
        if (Config.LOG_EVENTS.get()) {
            LOG.info("[mimic] {} for {}: {}", kind, obs.getGameProfile().getName(), describe(obs));
        }
        return null;
    }

    /**
     * Roughly: 1 in 10 visits ends in a hit, more often later in the game and never on the first
     * visit. The decision is invisible and made before anything happens.
     */
    private static boolean decideBetrayal(ServerPlayer obs, HorrorState st, Kind kind, RandomSource r, boolean force) {
        if (kind == Kind.WORKER || kind == Kind.SELF) {
            return false;
        }
        if (force) {
            return Config.DEBUG.get() || r.nextFloat() < 0.5F;
        }
        int visits = st.count("mimic_visits");
        if (visits < 1 || Director.phase(obs) < 4) {
            return false;
        }
        float p = 0.06F + 0.02F * Math.min(4, Director.phase(obs) - 4) + (st.count("mimic_betrayals") == 0 ? 0.05F : 0F);
        return r.nextFloat() < p * Config.MIMIC_BETRAYAL.get().floatValue();
    }

    private static Vec3 hiddenSpot(ServerPlayer obs, int min, int max) {
        ServerLevel lv = obs.serverLevel();
        RandomSource r = obs.getRandom();
        Vec3 look = obs.getViewVector(1F);
        for (int i = 0; i < 16; i++) {
            double ang = Math.atan2(-look.z, -look.x) + (r.nextDouble() - 0.5) * Math.PI * 1.2;
            double d = min + r.nextDouble() * (max - min);
            int x = Mth.floor(obs.getX() + Math.cos(ang) * d);
            int z = Mth.floor(obs.getZ() + Math.sin(ang) * d);
            if (!lv.hasChunkAt(new BlockPos(x, 0, z))) {
                continue;
            }
            BlockPos top = lv.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, new BlockPos(x, 0, z));
            if (!lv.getFluidState(top.below()).isEmpty() || Math.abs(top.getY() - obs.getY()) > 12) {
                continue;
            }
            Vec3 v = new Vec3(x + 0.5, top.getY(), z + 0.5);
            if (!canSee(obs, v.add(0, 1.6, 0))) {
                return v;
            }
        }
        return null;
    }

    public static boolean canSee(ServerPlayer p, Vec3 target) {
        Vec3 eye = p.getEyePosition();
        if (eye.distanceToSqr(target) > 128 * 128) {
            return false;
        }
        HitResult hit = p.level().clip(new ClipContext(eye, target, ClipContext.Block.VISUAL, ClipContext.Fluid.NONE, p));
        return hit.getType() == HitResult.Type.MISS;
    }

    public static boolean looksAt(ServerPlayer p, Vec3 target, double cos) {
        Vec3 to = target.subtract(p.getEyePosition()).normalize();
        return p.getViewVector(1F).dot(to) > cos && canSee(p, target);
    }

    // ------------------------------------------------------------------ ticking

    public static void tick(MinecraftServer server) {
        if (BY_BODY.isEmpty()) {
            return;
        }
        Iterator<Session> it = new java.util.ArrayList<>(BY_BODY.values()).iterator();
        while (it.hasNext()) {
            Session s = it.next();
            ServerPlayer obs = server.getPlayerList().getPlayer(s.observer);
            if (obs == null || s.body.isRemoved() || obs.level() != s.body.level() || !Config.ENABLED.get()) {
                end(s);
                continue;
            }
            try {
                step(s, obs);
            } catch (RuntimeException e) {
                LOG.error("[mimic] session failed, removing body", e);
                end(s);
            }
        }
    }

    private static void step(Session s, ServerPlayer obs) {
        MimicEntity m = s.body;
        RandomSource r = m.getRandom();
        long now = HorrorState.of(obs).now();
        double d2 = m.distanceToSqr(obs);
        s.taskTicks++;

        if (s.vanishIn >= 0) {
            if (s.vanishIn-- == 0 || !looksAt(obs, m.getEyePosition(), 0.5)) {
                end(s);
            }
            return;
        }
        // the real person is coming: the copy must not be there when they arrive
        if (s.kind == Kind.FRIEND) {
            ServerPlayer real = obs.server.getPlayerList().getPlayer(s.copied);
            if (real == null) {
                leave(s, obs);
            } else if (real.level() == obs.level() && real.distanceToSqr(obs) < 96 * 96) {
                s.vanishIn = 0;
                return;
            }
        }
        if (now > s.endsAt && s.task != Task.LEAVE) {
            leave(s, obs);
        }
        if (s.betray && !s.struck && strikeMoment(s, obs, now, d2, r)) {
            strike(s, obs);
            return;
        }
        greet(s, obs, d2);
        if (s.mode == Mode.ALMOST && error(s, obs, r)) {
            return;
        }
        switch (s.task) {
            case ARRIVE -> {
                walkTo(m, obs.position(), 1.0, s.sprintRatio > 0.3F && d2 > 400);
                if (d2 < 9 * 9) {
                    s.task = Task.ACCOMPANY;
                    s.taskTicks = 0;
                }
            }
            case ACCOMPANY -> accompany(s, obs, d2, r);
            case WORK -> work(s, obs, d2, r);
            case FIGHT -> fight(s, obs);
            case REPLAY -> replay(s, obs);
            case LEAVE -> {
                if (d2 > 26 * 26 && !canSee(obs, m.getEyePosition()) || s.taskTicks > 20 * 90) {
                    end(s);
                } else if (m.getNavigation().isDone()) {
                    Vec3 away = m.position().subtract(obs.position()).normalize().scale(40).add(m.position());
                    walkTo(m, away, 1.0, false);
                }
            }
        }
    }

    private static void leave(Session s, ServerPlayer obs) {
        s.task = Task.LEAVE;
        s.taskTicks = 0;
        s.body.getNavigation().stop();
        s.body.level().destroyBlockProgress(s.body.getId(), s.work == null ? BlockPos.ZERO : s.work, -1);
    }

    private static void walkTo(MimicEntity m, Vec3 to, double speed, boolean sprint) {
        m.setSprinting(sprint);
        if (m.getNavigation().isDone() || m.tickCount % 20 == 0) {
            m.getNavigation().moveTo(to.x, to.y, to.z, sprint ? speed * 1.3 : speed);
        }
    }

    private static void accompany(Session s, ServerPlayer obs, double d2, RandomSource r) {
        MimicEntity m = s.body;
        Monster threat = nearestMonster(obs, 10);
        if (threat != null) {
            s.task = Task.FIGHT;
            m.setTarget(threat);
            return;
        }
        if (d2 > 7 * 7) {
            walkTo(m, obs.position(), 1.0, d2 > 18 * 18 || obs.isSprinting());
        } else if (d2 < 2.5 * 2.5) {
            m.getNavigation().stop();
        }
        if (m.tickCount % 40 == 0 && r.nextFloat() < 0.3F) {
            m.getLookControl().setLookAt(obs.getX() + r.nextGaussian() * 6, obs.getEyeY(), obs.getZ() + r.nextGaussian() * 6);
        }
        // social camouflage: do what the player is doing, or what this person usually does
        if (s.taskTicks > 20 * 20 && m.tickCount % 20 == 0) {
            String doing = obs.swinging ? targetTrade(obs) : null;
            if (doing != null || r.nextFloat() < 0.04F) {
                BlockPos t = findWork(m, doing != null ? doing : s.trade, 10);
                if (t != null) {
                    s.work = t;
                    s.workProgress = 0;
                    s.task = Task.WORK;
                    s.taskTicks = 0;
                }
            }
        }
    }

    private static String targetTrade(ServerPlayer obs) {
        HitResult hit = obs.pick(5, 1F, false);
        if (hit instanceof net.minecraft.world.phys.BlockHitResult bh) {
            BlockState bs = obs.level().getBlockState(bh.getBlockPos());
            if (bs.is(BlockTags.LOGS)) {
                return "chop";
            }
            if (bs.is(BlockTags.MINEABLE_WITH_PICKAXE)) {
                return "dig";
            }
        }
        return null;
    }

    private static BlockPos findWork(MimicEntity m, String trade, int radius) {
        BlockPos c = m.blockPosition();
        BlockPos best = null;
        double bd = Double.MAX_VALUE;
        for (BlockPos p : BlockPos.betweenClosed(c.offset(-radius, -3, -radius), c.offset(radius, 4, radius))) {
            BlockState bs = m.level().getBlockState(p);
            boolean ok = trade.equals("chop") ? bs.is(BlockTags.LOGS)
                    : bs.is(BlockTags.BASE_STONE_OVERWORLD) && m.level().getBlockState(p.above()).isAir();
            if (ok) {
                double d = p.distSqr(c);
                if (d < bd) {
                    bd = d;
                    best = p.immutable();
                }
            }
        }
        return best;
    }

    private static void work(Session s, ServerPlayer obs, double d2, RandomSource r) {
        MimicEntity m = s.body;
        if (s.kind == Kind.WORKER) {
            worker(s, obs, d2, r);
        }
        if (s.work == null) {
            s.work = findWork(m, s.trade, 14);
            if (s.work == null) {
                s.task = s.kind == Kind.WORKER ? Task.LEAVE : Task.ACCOMPANY;
                return;
            }
        }
        Vec3 c = Vec3.atCenterOf(s.work);
        if (m.distanceToSqr(c) > 3.2 * 3.2) {
            walkTo(m, c, 1.0, false);
            if (s.taskTicks > 20 * 30) {
                s.work = null;
            }
            return;
        }
        m.getNavigation().stop();
        m.getLookControl().setLookAt(c.x, c.y, c.z);
        BlockState bs = m.level().getBlockState(s.work);
        boolean gone = bs.isAir();
        if (--s.swingIn <= 0) {
            m.swing(InteractionHand.MAIN_HAND);
            // human rhythm: about 4 swings a second with jitter, never metronomic
            s.swingIn = 4 + r.nextInt(3);
            if (!gone) {
                s.workProgress++;
                m.level().destroyBlockProgress(m.getId(), s.work, Math.min(9, s.workProgress * 10 / 14));
                if (s.workProgress % 4 == 0) {
                    m.level().playSound(null, s.work, bs.getSoundType().getHitSound(), SoundSource.BLOCKS, 0.4F, 0.5F);
                }
            }
        }
        if (!gone && s.workProgress >= 14) {
            m.level().destroyBlockProgress(m.getId(), s.work, -1);
            if (Config.WORLD_EDITS.get() && !nearHome(obs, s.work)) {
                m.level().destroyBlock(s.work, false, m);
            }
            s.work = s.trade.equals("chop") ? (m.level().getBlockState(s.work.above()).is(BlockTags.LOGS) ? s.work.above() : null) : null;
            s.workProgress = 0;
            if (s.work == null && s.kind != Kind.WORKER && r.nextFloat() < 0.5F) {
                s.task = Task.ACCOMPANY;
                s.taskTicks = 0;
            }
        } else if (gone && s.kind != Kind.SELF) {
            s.work = null;
        }
        if (s.kind != Kind.WORKER && d2 > 20 * 20) {
            s.task = Task.ACCOMPANY;
        }
    }

    private static boolean nearHome(ServerPlayer obs, BlockPos p) {
        BlockPos home = HorrorEvents.house(obs);
        return home != null && home.distSqr(p) < 24 * 24;
    }

    /** "Don't touch him": far away and busy. Approach and he is gone; ignore him and he may be closer later. */
    private static void worker(Session s, ServerPlayer obs, double d2, RandomSource r) {
        MimicEntity m = s.body;
        if (d2 < 28 * 28 && !looksAt(obs, m.getEyePosition(), 0.7)) {
            s.vanishIn = 0;
            return;
        }
        if (d2 < 22 * 22) {
            s.vanishIn = 6 + r.nextInt(10);
            return;
        }
        if (s.taskTicks % (20 * 50) == 0 && s.taskTicks > 0 && !looksAt(obs, m.getEyePosition(), 0.3) && r.nextFloat() < 0.45F) {
            Vec3 closer = m.position().add(obs.position().subtract(m.position()).scale(0.3));
            BlockPos top = m.level().getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, BlockPos.containing(closer));
            if (!canSee(obs, Vec3.atBottomCenterOf(top).add(0, 1.6, 0))) {
                m.moveTo(top.getX() + 0.5, top.getY(), top.getZ() + 0.5, m.getYRot(), 0F);
                s.work = null;
            }
        }
    }

    private static Monster nearestMonster(ServerPlayer obs, double r) {
        List<Monster> ms = obs.level().getEntitiesOfClass(Monster.class, new AABB(obs.blockPosition()).inflate(r),
                e -> e.isAlive() && !(e instanceof MimicEntity));
        Monster best = null;
        for (Monster e : ms) {
            if (best == null || e.distanceToSqr(obs) < best.distanceToSqr(obs)) {
                best = e;
            }
        }
        return best;
    }

    private static void fight(Session s, ServerPlayer obs) {
        MimicEntity m = s.body;
        if (!(m.getTarget() instanceof Monster t) || !t.isAlive() || t.distanceToSqr(obs) > 20 * 20) {
            m.setTarget(null);
            s.task = Task.ACCOMPANY;
            return;
        }
        walkTo(m, t.position(), 1.2, true);
        m.getLookControl().setLookAt(t, 30F, 30F);
        if (m.distanceToSqr(t) < 2.6 * 2.6 && --s.swingIn <= 0) {
            m.swing(InteractionHand.MAIN_HAND);
            m.doHurtTarget(t);
            s.swingIn = 11 + m.getRandom().nextInt(4);
        }
    }

    /** Plays an earlier day's clip at the place it happened. It does not notice anyone. */
    private static void replay(Session s, ServerPlayer obs) {
        MimicEntity m = s.body;
        List<Habits.Sample> ss = s.clip.samples();
        if (s.clipIndex >= ss.size()) {
            s.vanishIn = 20 * 3;
            return;
        }
        if (m.tickCount % Habits.SAMPLE != 0) {
            return;
        }
        Habits.Sample cur = ss.get(s.clipIndex++);
        m.getMoveControl().setWantedPosition(cur.x(), cur.y(), cur.z(), cur.sprint() ? 1.3 : 1.0);
        m.setSprinting(cur.sprint());
        m.setYHeadRot(cur.yaw());
        if (cur.action() == Habits.BREAK || cur.action() == Habits.PLACE) {
            BlockPos at = BlockPos.of(cur.actPos());
            m.getLookControl().setLookAt(Vec3.atCenterOf(at));
            m.swing(InteractionHand.MAIN_HAND);
            // the block it is hitting is usually gone: you took it yesterday. It keeps hitting air.
        }
        if (m.distanceToSqr(obs) < 3 * 3) {
            m.getNavigation().stop();
        }
    }

    // ------------------------------------------------------------------ social details

    /** Crouch twice at it and it crouches back, like players do. Almost-perfect copies sometimes don't. */
    private static void greet(Session s, ServerPlayer obs, double d2) {
        MimicEntity m = s.body;
        boolean c = obs.isCrouching();
        if (c && !s.lastObsCrouch && d2 < 12 * 12) {
            s.obsCrouches++;
            if (s.obsCrouches >= 2 && s.crouchReply <= 0) {
                boolean answers = s.mode == Mode.STEALTH || m.getRandom().nextFloat() < 0.6F;
                s.crouchReply = answers ? 30 + m.getRandom().nextInt(12) : -60;
                s.obsCrouches = 0;
            }
        }
        if (obs.tickCount % 40 == 0) {
            s.obsCrouches = 0;
        }
        s.lastObsCrouch = c;
        if (s.crouchReply > 0) {
            s.crouchReply--;
            int t = s.crouchReply;
            boolean down = (t > 22 && t < 27) || (t > 12 && t < 17);
            m.setShiftKeyDown(down);
            m.setPose(down ? Pose.CROUCHING : Pose.STANDING);
            if (t < 30) {
                m.getLookControl().setLookAt(obs, 30F, 30F);
            }
        } else if (s.crouchReply < 0) {
            s.crouchReply++;
        }
    }

    /** One small, deniable mistake every few minutes. Only for ALMOST copies. */
    private static boolean error(Session s, ServerPlayer obs, RandomSource r) {
        MimicEntity m = s.body;
        if (s.errorTicks > 0) {
            s.errorTicks--;
            return true;
        }
        if (--s.errorCooldown > 0 || s.task == Task.ARRIVE) {
            return false;
        }
        s.errorCooldown = 20 * (150 + r.nextInt(240));
        switch (r.nextInt(4)) {
            case 0 -> {
                // stops a step from a wall and stays facing it for a little too long
                m.getNavigation().stop();
                Vec3 f = m.position().add(m.getLookAngle().scale(1.5));
                m.getLookControl().setLookAt(f.x, m.getEyeY(), f.z);
                s.errorTicks = 50 + r.nextInt(30);
            }
            case 1 -> {
                // looks at you a second longer than a person would, then carries on
                m.getNavigation().stop();
                m.getLookControl().setLookAt(obs, 10F, 10F);
                s.errorTicks = 40 + r.nextInt(20);
            }
            case 2 -> {
                // the wrong tool for the job, for one block
                ItemStack keep = m.getMainHandItem().copy();
                m.setItemSlot(EquipmentSlot.MAINHAND, new ItemStack(s.trade.equals("chop") ? Items.WOODEN_SHOVEL : Items.WOODEN_HOE));
                obs.server.execute(() -> {
                    if (!m.isRemoved()) {
                        m.setItemSlot(EquipmentSlot.MAINHAND, keep);
                    }
                });
                s.errorTicks = 0;
                return false;
            }
            default -> {
                // turns its head towards something you just heard but it could not have
                Vec3 behind = obs.position().subtract(obs.getLookAngle().scale(8));
                m.getLookControl().setLookAt(behind.x, obs.getEyeY(), behind.z);
                s.errorTicks = 30;
            }
        }
        return true;
    }

    // ------------------------------------------------------------------ betrayal

    private static boolean strikeMoment(Session s, ServerPlayer obs, long now, double d2, RandomSource r) {
        long known = now - s.started;
        if (known < 20L * 60 * 3 || d2 > 3.2 * 3.2 || obs.getHealth() < 8 || nearestMonster(obs, 12) != null) {
            return false;
        }
        // ordinary moments weigh more: busy hands, a chest, a doorway, a calm sky
        float w = 0.002F;
        if (obs.swinging) {
            w += 0.006F;
        }
        if (obs.containerMenu != obs.inventoryMenu) {
            w += 0.02F;
        }
        if (!obs.serverLevel().isRaining() && obs.serverLevel().isDay()) {
            w += 0.002F;
        }
        if (known > s.endsAt - s.started - 20L * 40) {
            w += 0.01F;
        }
        return r.nextFloat() < w;
    }

    private static void strike(Session s, ServerPlayer obs) {
        MimicEntity m = s.body;
        s.struck = true;
        m.getLookControl().setLookAt(obs, 180F, 180F);
        m.lookAt(net.minecraft.commands.arguments.EntityAnchorArgument.Anchor.EYES, obs.getEyePosition());
        m.swing(InteractionHand.MAIN_HAND);
        float dmg = Math.min(6F, obs.getHealth() - 1F);
        if (dmg > 0) {
            obs.hurt(obs.damageSources().mobAttack(m), dmg);
        }
        Vec3 push = obs.position().subtract(m.position()).normalize();
        obs.knockback(0.9, -push.x, -push.z);
        obs.hurtMarked = true;
        ServerLevel lv = obs.serverLevel();
        lv.playSound(null, m.blockPosition(), ModRegistry.MIMIC_STINGER.get(), SoundSource.HOSTILE, 1.0F, 1.0F);
        lv.playSound(null, m.blockPosition(), SoundEvents.PLAYER_ATTACK_STRONG, SoundSource.HOSTILE, 1.0F, 0.6F);
        HorrorEvents.meta(obs, "flicker", "");
        HorrorState st = HorrorState.of(obs);
        st.inc("mimic_betrayals");
        st.setText("mimic_last", s.name);
        st.setPos("mimic_site", m.blockPosition());
        // consequences, later: the companion (if the player enabled it) and a quiet echo in the world
        st.schedule("mimic_echo", st.now() + 20L * 60 * (8 + obs.getRandom().nextInt(25)), s.name);
        Outside.maybe(obs, "after_betrayal", s.name);
        end(s);
    }

    /** Hit by a player: a real person would turn, step back, maybe crouch at you. */
    public static void onHit(MimicEntity m, net.minecraft.world.entity.player.Player p) {
        Session s = BY_BODY.get(m.getUUID());
        if (s == null) {
            return;
        }
        s.workProgress = 0;
        m.getNavigation().stop();
        m.getLookControl().setLookAt(p, 30F, 30F);
        int hits = HorrorState.of((ServerPlayer) p).count("mimic_hit_now") + 1;
        HorrorState.of((ServerPlayer) p).setCount("mimic_hit_now", hits);
        if (hits == 1) {
            s.crouchReply = 32;
        } else if (hits >= 3) {
            HorrorState.of((ServerPlayer) p).setCount("mimic_hit_now", 0);
            leave(s, (ServerPlayer) p);
        }
    }

    private static void end(Session s) {
        BY_BODY.remove(s.body.getUUID());
        BY_OBSERVER.remove(s.observer);
        if (s.work != null) {
            s.body.level().destroyBlockProgress(s.body.getId(), s.work, -1);
        }
        if (!s.body.isRemoved()) {
            s.body.discard();
        }
    }

    public static void clearAll() {
        for (Session s : new java.util.ArrayList<>(BY_BODY.values())) {
            end(s);
        }
    }
}
