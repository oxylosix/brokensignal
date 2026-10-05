package com.gena.brokensignal;

import com.gena.brokensignal.pc.ComputerService;
import com.mojang.logging.LogUtils;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import org.slf4j.Logger;

/**
 * Central decision maker: WHEN (dynamic intervals + pacing), WHAT (weighted, context-aware
 * selection with anti-repetition), WHERE (location affinity) and HOW HARD (tension budget).
 *
 * Called once per second per player from HorrorEvents.onPlayerTick, never every tick.
 * Hidden pacing cycle: calm -> build -> peak -> after -> calm. The player never sees it.
 */
public final class Director {
    public static final Logger LOG = LogUtils.getLogger();
    /** Progression points needed for each hidden phase 0..8. */
    private static final int[] THRESHOLDS = {0, 6, 15, 26, 40, 56, 76, 100, 135};
    /** Average minutes between director decisions per phase. */
    private static final double[] BASE_GAP = {12, 8, 6.5, 5.5, 5, 4.5, 4, 3.6, 3.2};

    private Director() {}

    // ------------------------------------------------------------------ progression

    public static int phase(ServerPlayer p) {
        HorrorState st = HorrorState.of(p);
        int forced = st.forcedPhase();
        if (forced >= 0) {
            return Math.min(8, forced);
        }
        double minutes = st.now() / 1200.0 * Config.PHASE_SPEED.get();
        double grace = Config.GRACE_MINUTES.get();
        if (minutes < grace) {
            return 0;
        }
        double points = (minutes - grace)
                + st.awareness() * 0.6
                + Math.min(30.0, st.count("pc_opens") * 1.5)
                + st.chainsFinished() * 4.0;
        int ph = 0;
        for (int i = 0; i < THRESHOLDS.length; i++) {
            if (points >= THRESHOLDS[i]) {
                ph = i;
            }
        }
        if (ph > st.maxPhase()) {
            st.setMaxPhase(ph);
        }
        return Math.max(ph, st.maxPhase());
    }

    /** Legacy 0..3 stage used by the original events. */
    public static int stage(ServerPlayer p) {
        int ph = phase(p);
        return ph <= 1 ? 0 : ph <= 3 ? 1 : ph <= 5 ? 2 : 3;
    }

    // ------------------------------------------------------------------ tick

    public static void tick(ServerPlayer p) {
        if (!Config.ENABLED.get() || p.isSpectator() || (p.isCreative() && !Config.AFFECT_CREATIVE.get())) {
            return;
        }
        HorrorState st = HorrorState.of(p);
        long now = st.now();
        observe(p, st, now);
        st.setTension(st.tension() * 0.996F);

        for (HorrorState.Scheduled s : st.popDue(now)) {
            EventDef def = EventCatalog.get(s.id());
            if (def != null) {
                st.setText("arg", s.arg());
                String err = fire(p, def, EventCtx.of(p), false, "consequence");
                if (err != null && Config.DEBUG.get()) {
                    LOG.debug("[BrokenSignal] consequence {} skipped: {}", s.id(), err);
                }
            }
        }
        Chains.tick(p, st, now);

        if (st.nextEventAt() == 0L) {
            st.setNextEventAt(now + gap(p, st, phase(p)));
            return;
        }
        if (now < st.nextEventAt()) {
            return;
        }
        EventCtx ctx = EventCtx.of(p);
        EventDef def = pick(ctx);
        long gap = gap(p, st, ctx.phase);
        if (def == null) {
            gap = 20 * 45; // nothing fits right now: deliberately stay quiet a little longer
        } else {
            String err = fire(p, def, ctx, false, "director");
            if (err != null) {
                gap = 20 * 20;
            } else {
                gap = Math.max(gap, def.quietAfter);
            }
        }
        st.setNextEventAt(now + gap);
    }

    /** Cheap behaviour memory: movement, stillness, hot spots, time away from home. */
    private static void observe(ServerPlayer p, HorrorState st, long now) {
        BlockPos pos = p.blockPosition();
        BlockPos last = st.pos("lastPos");
        if (last != null && last.distManhattan(pos) == 0) {
            int still = st.inc("still");
            if (still == 300) {
                st.setFlag("was_still", true);
            }
        } else {
            if (st.count("still") > 20 && st.count("walked") > 30) {
                st.setFlag("just_stopped", false);
            }
            st.setCount("still", 0);
            st.inc("walked");
        }
        st.setPos("lastPos", pos);
        if (now % 600 < 20) {
            st.visit(pos);
            long day = p.serverLevel().getDayTime() % 24000L;
            st.inc(day >= 13000 && day < 23000 ? "min_night" : "min_day");
        }
        BlockPos home = p.getRespawnPosition();
        if (home != null && p.getRespawnDimension() == p.level().dimension()) {
            double d = home.distSqr(pos);
            if (d > 80 * 80) {
                st.inc("away");
            } else if (d < 16 * 16) {
                if (st.count("away") > 900) {
                    st.setFlag("came_home", true);
                    st.setNextEventAt(Math.min(st.nextEventAt(), now + 200));
                }
                st.setCount("away", 0);
            }
        }
    }

    private static long gap(ServerPlayer p, HorrorState st, int phase) {
        RandomSource r = p.getRandom();
        double minutes = BASE_GAP[Math.min(8, phase)] * (0.55 + r.nextDouble() * 1.05);
        if (r.nextFloat() < 0.16F) {
            minutes *= 2.4; // long, uneventful stretch: calm is part of it
        }
        if ("after".equals(st.pace())) {
            minutes *= 1.8;
        }
        minutes /= Math.max(0.1, Config.INTENSITY.get());
        long ticks = (long) (minutes * 1200);
        return Math.max(ticks, Config.MIN_GAP_SECONDS.get() * 20L);
    }

    // ------------------------------------------------------------------ selection

    public static boolean allowed(EventDef d, EventCtx c, StringBuilder why) {
        if (c.phase < d.minPhase || c.phase > d.maxPhase) {
            return note(why, "phase " + d.minPhase + "-" + d.maxPhase);
        }
        if (d.once && c.st.onceDone(d.id)) {
            return note(why, "already happened");
        }
        if (c.now - c.st.lastFired(d.id) < d.cooldown) {
            return note(why, "cooldown " + (d.cooldown - (c.now - c.st.lastFired(d.id))) / 20 + "s");
        }
        if (d.needsEdits && !Config.WORLD_EDITS.get()) {
            return note(why, "worldEdits off");
        }
        if (d.needsMeta && !Config.COMPUTER_EVENTS.get()) {
            return note(why, "computerEvents off");
        }
        if (d.needsVisuals && !Config.VISUAL_EFFECTS.get()) {
            return note(why, "visualEffects off");
        }
        if (d.rarity == EventDef.Rarity.SECRET && !Config.SECRETS.get()) {
            return note(why, "secrets off");
        }
        if (d.placesOnly && !d.places.contains(c.place)) {
            return note(why, "wrong place " + c.place);
        }
        if (!d.cond.test(c)) {
            return note(why, "conditions not met");
        }
        return true;
    }

    private static boolean note(StringBuilder why, String s) {
        if (why != null) {
            why.append(s);
        }
        return false;
    }

    private static double paceFactor(String pace, EventDef.Size s) {
        return switch (pace) {
            case "build" -> s == EventDef.Size.SMALL ? 1.0 : s == EventDef.Size.MEDIUM ? 1.4 : 0.25;
            case "peak" -> s == EventDef.Size.SMALL ? 0.5 : s == EventDef.Size.MEDIUM ? 1.1 : 2.6;
            case "after" -> s == EventDef.Size.SMALL ? 0.7 : 0.0;
            default -> s == EventDef.Size.SMALL ? 1.6 : s == EventDef.Size.MEDIUM ? 0.45 : 0.04;
        };
    }

    static EventDef pick(EventCtx c) {
        List<EventDef> pool = new ArrayList<>();
        List<Double> weights = new ArrayList<>();
        int bestPriority = Integer.MIN_VALUE;
        List<String> hist = c.st.history();
        String lastCat = c.st.lastCategoryName();
        String pace = c.st.pace();
        double rare = Config.RARE_CHANCE.get();
        for (EventDef d : EventCatalog.all()) {
            if (!d.directorPick || !allowed(d, c, null)) {
                continue;
            }
            double w = d.weight * d.rarity.factor;
            if (d.rarity != EventDef.Rarity.COMMON) {
                w *= rare;
            }
            w *= paceFactor(pace, d.size);
            if (d.places.contains(c.place)) {
                w *= 2.5;
            }
            int recent = 0;
            for (int i = Math.max(0, hist.size() - 8); i < hist.size(); i++) {
                if (hist.get(i).equals(d.id)) {
                    recent++;
                }
            }
            w /= 1.0 + recent * 3.0;
            if (d.cat.name().equals(lastCat)) {
                w *= 0.3;
            }
            if (c.now - c.st.lastCategory(d.cat.name()) < 20 * 60 * 4) {
                w *= 0.6;
            }
            if (d.cat == EventDef.Cat.COMPUTER) {
                w *= 1.0 + Math.min(3.0, c.st.count("pc_opens") / 5.0);
            }
            if (d.danger >= 2 && c.st.tension() > 4.0F) {
                w *= 0.15;
            }
            if (c.phase - d.minPhase <= 1) {
                w *= 1.3; // freshly unlocked content is a little more likely
            }
            if (c.st.timesFired(d.id) == 0) {
                w *= 1.2;
            }
            if (w <= 0.0) {
                continue;
            }
            if (d.priority > bestPriority) {
                bestPriority = d.priority;
            }
            pool.add(d);
            weights.add(w);
        }
        if (pool.isEmpty()) {
            return null;
        }
        double total = 0.0;
        for (int i = 0; i < pool.size(); i++) {
            if (pool.get(i).priority == bestPriority) {
                total += weights.get(i);
            }
        }
        double roll = c.p.getRandom().nextDouble() * total;
        for (int i = 0; i < pool.size(); i++) {
            if (pool.get(i).priority != bestPriority) {
                continue;
            }
            roll -= weights.get(i);
            if (roll <= 0.0) {
                return pool.get(i);
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ firing

    /** Command / legacy entry point. Returns null on success or a reason. */
    public static String trigger(ServerPlayer p, String id, boolean force) {
        EventDef def = EventCatalog.get(id);
        if (def == null) {
            return "unknown event: " + id;
        }
        return fire(p, def, EventCtx.of(p), force, "command");
    }

    public static String fire(ServerPlayer p, EventDef def, EventCtx ctx, boolean force, String source) {
        if (!force && !allowed(def, ctx, null)) {
            StringBuilder why = new StringBuilder();
            allowed(def, ctx, why);
            return why.toString();
        }
        String err;
        try {
            err = def.action.run(p, ctx, force);
        } catch (RuntimeException e) {
            LOG.error("[BrokenSignal] event '{}' failed for {} ({}, {})", def.id, p.getGameProfile().getName(), source, ctx, e);
            ctx.st.record(def); // cooldown applies, so a broken event cannot spam the log
            return "internal error, see log";
        }
        if (err != null) {
            return err;
        }
        HorrorState st = ctx.st;
        st.record(def);
        st.setTension(st.tension() + def.danger);
        if (def.danger > 0) {
            st.addAwareness(def.danger);
        }
        if (def.cat == EventDef.Cat.WORLD || def.cat == EventDef.Cat.FIGURE) {
            st.remember("sites", p.blockPosition(), 8);
            st.setTime("lastWorldEvent", st.now());
        }
        advancePace(st, def, p.getRandom());
        for (EventDef.FollowUp f : def.follow) {
            if (p.getRandom().nextFloat() < f.chance()) {
                long delay = f.minDelay() + (f.maxDelay() > f.minDelay() ? p.getRandom().nextInt(f.maxDelay() - f.minDelay()) : 0);
                st.schedule(f.id(), st.now() + delay, def.id);
            }
        }
        ComputerService.onWorldEvent(p, def, ctx);
        if (Config.LOG_EVENTS.get()) {
            LOG.info("[BrokenSignal] {} <- {} ({}) {}", p.getGameProfile().getName(), def.id, source, ctx);
        }
        return null;
    }

    private static void advancePace(HorrorState st, EventDef def, RandomSource r) {
        int n = st.inc("pace_n");
        String pace = st.pace();
        String next = pace;
        switch (pace) {
            case "calm" -> {
                if (n >= 2 + r.nextInt(2)) {
                    next = "build";
                }
            }
            case "build" -> {
                if (n >= 3 + r.nextInt(3) || st.tension() > 3.5F) {
                    next = "peak";
                }
            }
            case "peak" -> {
                if (def.size == EventDef.Size.MAJOR || n >= 3) {
                    next = "after";
                }
            }
            default -> next = "calm";
        }
        if (!next.equals(pace)) {
            st.setPace(next);
            st.setCount("pace_n", 0);
        }
    }

    // ------------------------------------------------------------------ hooks

    public static void onWake(ServerPlayer p) {
        HorrorState st = HorrorState.of(p);
        st.inc("sleeps");
        st.setFlag("woke", true);
        st.setTime("wokeAt", st.now());
        st.setNextEventAt(Math.min(st.nextEventAt(), st.now() + 60));
    }

    public static void onLogin(ServerPlayer p) {
        HorrorState st = HorrorState.of(p);
        st.inc("logins");
        st.setFlag("logged_in", true);
        if (st.nextEventAt() > 0 && st.nextEventAt() < st.now() + 20 * 90) {
            st.setNextEventAt(st.now() + 20 * 90); // never ambush the loading screen
        }
    }
}
