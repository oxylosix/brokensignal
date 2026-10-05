package com.gena.brokensignal;

import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.BiomeTags;
import net.minecraft.world.level.LightLayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.levelgen.Heightmap;

/**
 * Snapshot of the situation an event would happen in. Built only when the Director actually has
 * to decide something (at most once per second per player), never every tick.
 */
public final class EventCtx {
    public enum Time { DAWN, DAY, DUSK, NIGHT, MIDNIGHT }

    public enum Place { HOME, BASEMENT, CAVE, FOREST, VILLAGE, WATER, OPEN, PC_ROOM, HOUSE_SITE, EVENT_SITE, NETHER }

    public final ServerPlayer p;
    public final ServerLevel lv;
    public final HorrorState st;
    public final int phase;
    public final long now;
    public final Time time;
    public final boolean rain;
    public final boolean thunder;
    public final Place place;
    public final BlockPos pc;
    public final boolean underground;
    public final boolean indoors;
    public final int blockLight;

    private EventCtx(ServerPlayer p, int phase) {
        this.p = p;
        this.lv = p.serverLevel();
        this.st = HorrorState.of(p);
        this.phase = phase;
        this.now = st.now();
        long t = lv.getDayTime() % 24000L;
        if (t < 1000 || t >= 23000) {
            time = Time.DAWN;
        } else if (t < 11500) {
            time = Time.DAY;
        } else if (t < 13500) {
            time = Time.DUSK;
        } else if (t >= 17000 && t < 19000) {
            time = Time.MIDNIGHT;
        } else {
            time = Time.NIGHT;
        }
        rain = lv.isRaining();
        thunder = lv.isThundering();
        BlockPos pos = p.blockPosition();
        underground = HorrorEvents.underground(p);
        indoors = !lv.canSeeSky(pos.above());
        blockLight = lv.getBrightness(LightLayer.BLOCK, pos);
        pc = nearest(st.places("pcs"), pos, 12);
        place = detect(pos);
    }

    public static EventCtx of(ServerPlayer p) {
        return new EventCtx(p, Director.phase(p));
    }

    private Place detect(BlockPos pos) {
        if (lv.dimension() != Level.OVERWORLD) {
            return Place.NETHER;
        }
        if (p.isInWater()) {
            return Place.WATER;
        }
        if (pc != null && indoors) {
            return Place.PC_ROOM;
        }
        BlockPos house = HorrorEvents.house(p);
        if (house != null && house.distSqr(pos) < 12 * 12) {
            return Place.HOUSE_SITE;
        }
        BlockPos home = p.getRespawnPosition();
        if (home != null && p.getRespawnDimension() == lv.dimension() && home.distSqr(pos) < 24 * 24) {
            if (indoors && pos.getY() < home.getY() - 3) {
                return Place.BASEMENT;
            }
            if (indoors || home.distSqr(pos) < 10 * 10) {
                return Place.HOME;
            }
        }
        if (underground) {
            return Place.CAVE;
        }
        if (nearest(st.places("sites"), pos, 10) != null) {
            return Place.EVENT_SITE;
        }
        if (lv.isVillage(pos)) {
            return Place.VILLAGE;
        }
        if (lv.getBiome(pos).is(BiomeTags.IS_FOREST)
                || lv.getHeight(Heightmap.Types.MOTION_BLOCKING, pos.getX(), pos.getZ())
                        - lv.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, pos.getX(), pos.getZ()) > 2) {
            return Place.FOREST;
        }
        return Place.OPEN;
    }

    public static BlockPos nearest(List<BlockPos> list, BlockPos pos, int radius) {
        BlockPos best = null;
        double bd = (double) radius * radius;
        for (BlockPos b : list) {
            double d = b.distSqr(pos);
            if (d <= bd) {
                bd = d;
                best = b;
            }
        }
        return best;
    }

    public boolean night() {
        return time == Time.NIGHT || time == Time.MIDNIGHT;
    }

    public boolean dark() {
        return night() || underground || (indoors && blockLight < 6);
    }

    public boolean hasPc() {
        return !st.places("pcs").isEmpty();
    }

    public boolean at(Place... ps) {
        for (Place x : ps) {
            if (place == x) {
                return true;
            }
        }
        return false;
    }

    @Override
    public String toString() {
        return "phase=" + phase + " time=" + time + " place=" + place + (rain ? " rain" : "") + (thunder ? " storm" : "")
                + (pc != null ? " pc@" + pc.toShortString() : "");
    }
}
