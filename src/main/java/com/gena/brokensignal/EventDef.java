package com.gena.brokensignal;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import net.minecraft.server.level.ServerPlayer;

/**
 * Declarative description of one event. The Director decides when; the action decides how.
 * Actions return null on success or a short reason string when the event could not happen
 * right now (no door nearby, worldEdits disabled...). A failed action never counts as fired.
 */
public final class EventDef {
    public enum Cat { SOUND, WORLD, PLAYER, MESSAGE, FIGURE, META, COMPUTER, SECRET }

    public enum Size { SMALL, MEDIUM, MAJOR }

    public enum Rarity {
        COMMON(1.0), UNCOMMON(0.45), RARE(0.14), SECRET(0.015);

        public final double factor;

        Rarity(double factor) {
            this.factor = factor;
        }
    }

    @FunctionalInterface
    public interface Action {
        String run(ServerPlayer p, EventCtx ctx, boolean force);
    }

    public record FollowUp(String id, int minDelay, int maxDelay, float chance) {}

    public final String id;
    public final Cat cat;
    public Size size = Size.SMALL;
    public Rarity rarity = Rarity.COMMON;
    public int minPhase = 1;
    public int maxPhase = 8;
    public int weight = 10;
    public int priority = 0;
    public int cooldown = 20 * 60 * 10;
    public int danger = 0;
    public int quietAfter = 0;
    public boolean once = false;
    public boolean needsEdits = false;
    public boolean needsMeta = false;
    public boolean needsVisuals = false;
    public boolean directorPick = true;
    public Predicate<EventCtx> cond = c -> true;
    public final Set<EventCtx.Place> places = EnumSet.noneOf(EventCtx.Place.class);
    public boolean placesOnly = false;
    public final List<FollowUp> follow = new ArrayList<>();
    public final Action action;

    public EventDef(String id, Cat cat, Action action) {
        this.id = id;
        this.cat = cat;
        this.action = action;
    }

    public EventDef size(Size s) {
        this.size = s;
        if (s == Size.MEDIUM && danger == 0) {
            danger = 1;
        } else if (s == Size.MAJOR && danger < 2) {
            danger = 3;
        }
        return this;
    }

    public EventDef rarity(Rarity r) {
        this.rarity = r;
        return this;
    }

    public EventDef phase(int min) {
        this.minPhase = min;
        return this;
    }

    public EventDef phase(int min, int max) {
        this.minPhase = min;
        this.maxPhase = max;
        return this;
    }

    public EventDef weight(int w) {
        this.weight = w;
        return this;
    }

    public EventDef priority(int p) {
        this.priority = p;
        return this;
    }

    /** Cooldown in minutes of the player's play time. */
    public EventDef cooldown(double minutes) {
        this.cooldown = (int) (minutes * 1200);
        return this;
    }

    public EventDef danger(int d) {
        this.danger = d;
        return this;
    }

    /** Forces a calm stretch (minutes) after this event: nothing else may start. */
    public EventDef quiet(double minutes) {
        this.quietAfter = (int) (minutes * 1200);
        return this;
    }

    public EventDef once() {
        this.once = true;
        return this;
    }

    public EventDef edits() {
        this.needsEdits = true;
        return this;
    }

    public EventDef meta() {
        this.needsMeta = true;
        return this;
    }

    public EventDef visual() {
        this.needsVisuals = true;
        return this;
    }

    /** Only reachable through chains, consequences, triggers or commands. */
    public EventDef manual() {
        this.directorPick = false;
        return this;
    }

    public EventDef when(Predicate<EventCtx> c) {
        this.cond = this.cond.and(c);
        return this;
    }

    /** Places where this event feels right (weight x2.5). */
    public EventDef at(EventCtx.Place... ps) {
        for (EventCtx.Place p : ps) {
            places.add(p);
        }
        return this;
    }

    /** Like at(), but the event is impossible anywhere else. */
    public EventDef only(EventCtx.Place... ps) {
        at(ps);
        placesOnly = true;
        return this;
    }

    /** After this event, maybe schedule another one (delays in minutes of play time). */
    public EventDef then(String next, double minMinutes, double maxMinutes, float chance) {
        follow.add(new FollowUp(next, (int) (minMinutes * 1200), (int) (maxMinutes * 1200), chance));
        return this;
    }
}
