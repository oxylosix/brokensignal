package com.gena.brokensignal.mimic;

import com.gena.brokensignal.Config;
import com.gena.brokensignal.EventCtx;
import com.gena.brokensignal.HorrorEvents;
import com.gena.brokensignal.HorrorState;
import com.gena.brokensignal.ext.Outside;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.IntPredicate;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobSpawnType;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/**
 * Non-player imitation: animals the player knows, villagers that are not quite from here.
 * Each scene is a short script of "beats" ticked on the server; every scene ends by itself and
 * leaves the world as it was (the real animal goes back to being an animal).
 */
public final class Scenes {
    public static final String TAG = "brokensignal_mimic";

    private static final class Beat {
        final UUID player;
        final IntPredicate step;
        int t;

        Beat(UUID player, IntPredicate step) {
            this.player = player;
            this.step = step;
        }
    }

    private static final List<Beat> BEATS = new ArrayList<>();
    private static final Set<UUID> OURS = new HashSet<>();

    private Scenes() {}

    static void play(ServerPlayer p, IntPredicate step) {
        if (BEATS.size() < 32) {
            BEATS.add(new Beat(p.getUUID(), step));
        }
    }

    public static void tick(MinecraftServer server) {
        Iterator<Beat> it = BEATS.iterator();
        while (it.hasNext()) {
            Beat b = it.next();
            boolean go = server.getPlayerList().getPlayer(b.player) != null;
            try {
                go = go && b.step.test(b.t++);
            } catch (RuntimeException e) {
                com.mojang.logging.LogUtils.getLogger().error("[BrokenSignal] scene failed", e);
                go = false;
            }
            if (!go) {
                it.remove();
            }
        }
    }

    /** Tagged villagers from an earlier session (crash, restart) are not supposed to exist any more. */
    public static boolean stray(Entity e) {
        return e.getTags().contains(TAG) && !OURS.contains(e.getUUID());
    }

    // ------------------------------------------------------------------ the animal you feed

    /**
     * An animal you have fed many times looks up at you, waits, lunges once, and goes back to
     * grazing as if nothing happened.
     */
    public static String petTurn(ServerPlayer p, EventCtx c, boolean f) {
        Animal a = knownAnimal(p, f ? 1 : 3, 20);
        if (a == null) {
            return "no animal this player has fed nearby";
        }
        if (!f && p.serverLevel().isNight()) {
            return "only on an ordinary day";
        }
        final boolean[] hit = {false};
        int pause = 30 + p.getRandom().nextInt(40);
        play(p, t -> {
            if (!a.isAlive()) {
                return false;
            }
            if (t < pause) {
                a.getNavigation().stop();
                a.getLookControl().setLookAt(p, 10F, 10F);
                return true;
            }
            if (t == pause) {
                Vec3 d = p.position().subtract(a.position()).normalize();
                a.setDeltaMovement(d.x * 0.75, 0.38, d.z * 0.75);
                a.hasImpulse = true;
            }
            if (!hit[0] && t < pause + 12 && a.distanceToSqr(p) < 1.8 * 1.8) {
                hit[0] = true;
                p.hurt(p.damageSources().mobAttack(a), Math.min(3F, p.getHealth() - 1F));
                p.serverLevel().playSound(null, a.blockPosition(), SoundEvents.PLAYER_ATTACK_KNOCKBACK, SoundSource.NEUTRAL, 0.8F, 0.5F);
            }
            return t < pause + 14;
        });
        HorrorState.of(p).inc("pet_turns");
        return null;
    }

    /** A dozen animals around you turn their heads to you at the same tick, then back. Not scary. Just wrong. */
    public static String herdSync(ServerPlayer p, EventCtx c, boolean f) {
        List<Animal> herd = p.serverLevel().getEntitiesOfClass(Animal.class, new AABB(p.blockPosition()).inflate(18),
                a -> a.isAlive() && !a.isBaby());
        if (herd.size() < (f ? 1 : 4)) {
            return "no herd";
        }
        int hold = 50 + p.getRandom().nextInt(30);
        play(p, t -> {
            for (Animal a : herd) {
                if (a.isAlive()) {
                    a.getNavigation().stop();
                    a.getLookControl().setLookAt(p, 90F, 90F);
                }
            }
            return t < hold;
        });
        return null;
    }

    /** One animal by the house stands facing the door for the whole evening. */
    public static String watchesHouse(ServerPlayer p, EventCtx c, boolean f) {
        BlockPos home = HorrorEvents.house(p);
        if (home == null) {
            return "no house";
        }
        List<Animal> near = p.serverLevel().getEntitiesOfClass(Animal.class, new AABB(home).inflate(24), Entity::isAlive);
        if (near.isEmpty()) {
            return "no animal near the house";
        }
        Animal a = near.get(p.getRandom().nextInt(near.size()));
        Vec3 door = Vec3.atCenterOf(home);
        int len = 20 * (60 + p.getRandom().nextInt(120));
        play(p, t -> {
            if (!a.isAlive()) {
                return false;
            }
            a.getNavigation().stop();
            a.getLookControl().setLookAt(door.x, door.y, door.z);
            // if you walk up to it, it goes back to being a cow before you get there
            return t < len && a.distanceToSqr(p) > 5 * 5;
        });
        return null;
    }

    private static Animal knownAnimal(ServerPlayer p, int minFed, double r) {
        ServerLevel lv = p.serverLevel();
        for (UUID id : Habits.pets(p, minFed)) {
            Entity e = lv.getEntity(id);
            if (e instanceof Animal a && a.isAlive() && a.distanceToSqr(p) < r * r && MimicDirector.canSee(p, a.getEyePosition())) {
                return a;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ villagers

    /**
     * A villager who is not from this village. It doesn't work, it walks the way you walked a
     * minute ago, and when you get home it is already standing at your door. Rarely it jumps at you.
     */
    public static String villagerExtra(ServerPlayer p, EventCtx c, boolean f) {
        if (!f && c.place != EventCtx.Place.VILLAGE) {
            return "not in a village";
        }
        List<Habits.Sample> route = Habits.recent(p, 60);
        if (route.size() < 20) {
            return "no route yet";
        }
        ServerLevel lv = p.serverLevel();
        Villager v = EntityType.VILLAGER.create(lv);
        if (v == null) {
            return "could not create villager";
        }
        Vec3 start = Habits.pos(route.get(0));
        if (MimicDirector.canSee(p, start.add(0, 1.6, 0)) && !f) {
            return "start of route is in view";
        }
        v.moveTo(start.x, start.y, start.z, p.getRandom().nextFloat() * 360F, 0F);
        v.finalizeSpawn(lv, lv.getCurrentDifficultyAt(v.blockPosition()), MobSpawnType.EVENT, null);
        v.addTag(TAG);
        v.getBrain().removeAllBehaviors();
        lv.addFreshEntity(v);
        OURS.add(v.getUUID());
        boolean lunge = p.getRandom().nextFloat() < 0.15F;
        BlockPos home = HorrorEvents.house(p);
        int[] idx = {0};
        boolean[] done = {false};
        play(p, t -> {
            if (!v.isAlive() || t > 20 * 60 * 6 || v.distanceToSqr(p) > 120 * 120) {
                OURS.remove(v.getUUID());
                v.discard();
                return false;
            }
            if (t % 15 == 0 && idx[0] < route.size()) {
                Habits.Sample s = route.get(idx[0]);
                idx[0] += 3;
                v.getNavigation().moveTo(s.x(), s.y(), s.z(), 0.55);
            } else if (idx[0] >= route.size() && home != null && t % 40 == 0) {
                v.getNavigation().moveTo(home.getX() + 0.5, home.getY(), home.getZ() + 0.5, 0.6);
            }
            if (lunge && !done[0] && v.distanceToSqr(p) < 2.2 * 2.2 && t > 20 * 40) {
                done[0] = true;
                Vec3 d = p.position().subtract(v.position()).normalize();
                v.setDeltaMovement(d.x * 0.8, 0.3, d.z * 0.8);
                p.hurt(p.damageSources().mobAttack(v), Math.min(4F, p.getHealth() - 1F));
                lv.playSound(null, v.blockPosition(), SoundEvents.VILLAGER_HURT, SoundSource.NEUTRAL, 1F, 0.35F);
            }
            if (done[0] && !MimicDirector.looksAt(p, v.getEyePosition(), 0.6)) {
                OURS.remove(v.getUUID());
                v.discard();
                return false;
            }
            return true;
        });
        return null;
    }

    /** After you saw something, a real villager walks out to that exact place and stands there. */
    public static String villagerKnows(ServerPlayer p, EventCtx c, boolean f) {
        HorrorState st = HorrorState.of(p);
        BlockPos site = st.pos("mimic_site");
        if (site == null) {
            site = st.pos("sightingAt");
        }
        if (site == null) {
            return "nothing has happened anywhere yet";
        }
        List<Villager> vs = p.serverLevel().getEntitiesOfClass(Villager.class, new AABB(p.blockPosition()).inflate(32),
                v -> v.isAlive() && !v.getTags().contains(TAG));
        if (vs.isEmpty() || site.distSqr(p.blockPosition()) > 90 * 90) {
            return "no villager / site too far";
        }
        Villager v = vs.get(p.getRandom().nextInt(vs.size()));
        BlockPos target = site;
        play(p, t -> {
            if (!v.isAlive()) {
                return false;
            }
            if (t % 30 == 0) {
                v.getNavigation().moveTo(target.getX() + 0.5, target.getY(), target.getZ() + 0.5, 0.6);
            }
            if (v.blockPosition().distSqr(target) < 4) {
                v.getLookControl().setLookAt(Vec3.atCenterOf(target.above()));
            }
            return t < 20 * 90;
        });
        return null;
    }

    /** Keeps an echo armor stand until the player comes close or looks away for long; then it is gone. */
    public static void standUntilClose(ServerPlayer p, Entity stand) {
        play(p, t -> {
            if (!stand.isAlive()) {
                return false;
            }
            boolean close = stand.distanceToSqr(p) < 7 * 7;
            if ((close && !MimicDirector.looksAt(p, stand.getEyePosition(), 0.5)) || t > 20 * 60 * 10) {
                stand.discard();
                return false;
            }
            return true;
        });
    }

    /** Helper for consequences that need the companion to have a say. */
    public static void outside(ServerPlayer p, String cue, String arg) {
        if (Config.ENABLED.get()) {
            Outside.maybe(p, cue, arg);
        }
    }
}
