package com.gena.brokensignal;

import com.gena.brokensignal.pc.ComputerService;
import com.gena.brokensignal.pc.Vfs;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LightningBolt;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.AbstractFurnaceBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * The v4 event content. Each method is one event with its own mechanic; metadata
 * (phase, rarity, cooldown, place, conditions, follow-ups) lives in EventCatalog.
 * Methods return null on success or a short reason why nothing happened.
 */
public final class ExtraEvents {
    private ExtraEvents() {}

    /** Fallback used by HorrorEvents.run for ids that are not legacy events. */
    public static String run(ServerPlayer p, String id, boolean force) {
        EventDef def = EventCatalog.get(id);
        if (def == null) {
            return "unknown event";
        }
        return def.action.run(p, EventCtx.of(p), force);
    }

    // ================================================================== helpers

    static Vec3 dir(ServerPlayer p, double dist, boolean behind, double spread) {
        double a = (behind ? HorrorEvents.back(p, spread) : HorrorEvents.front(p, spread));
        return new Vec3(p.getX() + Math.cos(a) * dist, p.getEyeY(), p.getZ() + Math.sin(a) * dist);
    }

    static void snd(ServerPlayer p, net.minecraft.sounds.SoundEvent s, Vec3 at, float vol, float pitch) {
        HorrorEvents.sound(p, s, at, Config.vol(vol), pitch);
    }

    static void snd(ServerPlayer p, net.minecraft.core.Holder<net.minecraft.sounds.SoundEvent> s, Vec3 at, float vol, float pitch) {
        HorrorEvents.sound(p, s, at, Config.vol(vol), pitch);
    }

    /** Client-side visual effect; respects the visualEffects config. */
    static String client(ServerPlayer p, String action, String arg) {
        if (!Config.VISUAL_EFFECTS.get()) {
            return "visual effects disabled";
        }
        PacketDistributor.sendToPlayer(p, new MetaPayload(action, arg));
        return null;
    }

    static boolean edits(HorrorState st) {
        return Chains.canEdit(st);
    }

    static BlockPos home(ServerPlayer p) {
        return p.getRespawnPosition();
    }

    /** Small cube scan; only called when an event fires, never per tick. */
    static List<BlockPos> scan(ServerLevel lv, BlockPos c, int r, java.util.function.Predicate<BlockState> test, int max) {
        List<BlockPos> out = new ArrayList<>();
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int dx = -r; dx <= r && out.size() < max; dx++) {
            for (int dz = -r; dz <= r && out.size() < max; dz++) {
                for (int dy = -3; dy <= 4 && out.size() < max; dy++) {
                    m.set(c.getX() + dx, c.getY() + dy, c.getZ() + dz);
                    if (lv.isLoaded(m) && test.test(lv.getBlockState(m))) {
                        out.add(m.immutable());
                    }
                }
            }
        }
        return out;
    }

    static List<BlockEntity> blockEntities(ServerLevel lv, BlockPos c, int r) {
        List<BlockEntity> out = new ArrayList<>();
        int cx = c.getX() >> 4;
        int cz = c.getZ() >> 4;
        int cr = (r >> 4) + 1;
        for (int x = cx - cr; x <= cx + cr; x++) {
            for (int z = cz - cr; z <= cz + cr; z++) {
                if (lv.hasChunk(x, z)) {
                    for (BlockEntity be : lv.getChunk(x, z).getBlockEntities().values()) {
                        if (be.getBlockPos().distSqr(c) <= (double) r * r) {
                            out.add(be);
                        }
                    }
                }
            }
        }
        return out;
    }

    // ================================================================== SMALL (sound / detail)

    public static String distantChest(ServerPlayer p, EventCtx c, boolean f) {
        Vec3 at = dir(p, 14, true, 1.2);
        snd(p, SoundEvents.CHEST_OPEN, at, 0.35F, 0.9F);
        HorrorEvents.later(p, 30 + p.getRandom().nextInt(30), () -> snd(p, SoundEvents.CHEST_CLOSE, at, 0.35F, 0.9F));
        return null;
    }

    public static String glass(ServerPlayer p, EventCtx c, boolean f) {
        snd(p, SoundEvents.GLASS_BREAK, dir(p, 28, true, 1.0), 0.25F, 0.8F);
        return null;
    }

    public static String stepEcho(ServerPlayer p, EventCtx c, boolean f) {
        if (p.getDeltaMovement().horizontalDistanceSqr() < 0.001) {
            return f ? null : "player is not walking";
        }
        for (int i = 0; i < 4; i++) {
            HorrorEvents.later(p, 8 + i * 7, () -> snd(p, p.level().getBlockState(p.blockPosition().below()).getSoundType().getStepSound(), dir(p, 3.5, true, 0.2), 0.3F, 1.0F));
        }
        return null;
    }

    public static String eating(ServerPlayer p, EventCtx c, boolean f) {
        Vec3 at = dir(p, 4, true, 0.6);
        for (int i = 0; i < 6; i++) {
            HorrorEvents.later(p, i * 4, () -> snd(p, SoundEvents.GENERIC_EAT, at, 0.3F, 0.85F + p.getRandom().nextFloat() * 0.2F));
        }
        return null;
    }

    public static String farDoor(ServerPlayer p, EventCtx c, boolean f) {
        Vec3 at = dir(p, 22, true, 1.4);
        snd(p, SoundEvents.WOODEN_DOOR_OPEN, at, 0.5F, 0.95F);
        HorrorEvents.later(p, 50 + p.getRandom().nextInt(40), () -> snd(p, SoundEvents.WOODEN_DOOR_CLOSE, at, 0.5F, 0.95F));
        return null;
    }

    public static String bell(ServerPlayer p, EventCtx c, boolean f) {
        snd(p, SoundEvents.BELL_BLOCK, dir(p, 60, false, Math.PI), 0.4F, 0.6F);
        return null;
    }

    public static String anvil(ServerPlayer p, EventCtx c, boolean f) {
        snd(p, SoundEvents.ANVIL_LAND, dir(p, 30, true, 1.0), 0.15F, 0.5F);
        return null;
    }

    public static String frame(ServerPlayer p, EventCtx c, boolean f) {
        snd(p, SoundEvents.ITEM_FRAME_ROTATE_ITEM, dir(p, 3, true, 0.5), 0.5F, 1.0F);
        return null;
    }

    public static String pickup(ServerPlayer p, EventCtx c, boolean f) {
        snd(p, SoundEvents.ITEM_PICKUP, dir(p, 2.5, true, 0.4), 0.25F, 1.3F);
        return null;
    }

    public static String notes(ServerPlayer p, EventCtx c, boolean f) {
        Vec3 at = dir(p, 25, false, Math.PI);
        float[] pitch = {1.2F, 1.0F, 0.8F};
        for (int i = 0; i < 3; i++) {
            final float pt = pitch[i];
            HorrorEvents.later(p, i * 12, () -> snd(p, SoundEvents.NOTE_BLOCK_HARP, at, 0.3F, pt));
        }
        return null;
    }

    public static String drip(ServerPlayer p, EventCtx c, boolean f) {
        Vec3 at = dir(p, 4, true, 1.0);
        HorrorEvents.repeat(p, 5, 18, () -> snd(p, SoundEvents.POINTED_DRIPSTONE_DRIP_WATER, at, 0.4F, 1.0F));
        return null;
    }

    public static String scrape(ServerPlayer p, EventCtx c, boolean f) {
        Vec3 at = dir(p, 9, true, 0.8);
        HorrorEvents.repeat(p, 4, 15, () -> snd(p, SoundEvents.STONE_HIT, at, 0.3F, 0.5F));
        return null;
    }

    public static String bedCreak(ServerPlayer p, EventCtx c, boolean f) {
        BlockPos bed = home(p);
        if (bed == null || bed.distSqr(p.blockPosition()) > 20 * 20) {
            return "not near the bed";
        }
        snd(p, SoundEvents.WOOL_STEP, Vec3.atCenterOf(bed), 0.5F, 0.7F);
        HorrorEvents.later(p, 14, () -> snd(p, SoundEvents.WOOL_STEP, Vec3.atCenterOf(bed), 0.4F, 0.6F));
        return null;
    }

    public static String lever(ServerPlayer p, EventCtx c, boolean f) {
        Vec3 at = dir(p, 6, true, 1.0);
        snd(p, SoundEvents.LEVER_CLICK, at, 0.35F, 0.6F);
        HorrorEvents.later(p, 40, () -> snd(p, SoundEvents.LEVER_CLICK, at, 0.35F, 0.5F));
        return null;
    }

    public static String ladder(ServerPlayer p, EventCtx c, boolean f) {
        Vec3 at = p.position().add(0, 4, 0);
        HorrorEvents.repeat(p, 5, 6, () -> snd(p, SoundEvents.LADDER_STEP, at, 0.35F, 1.0F));
        return null;
    }

    public static String button(ServerPlayer p, EventCtx c, boolean f) {
        snd(p, SoundEvents.STONE_BUTTON_CLICK_ON, dir(p, 5, true, 1.0), 0.35F, 0.9F);
        return null;
    }

    public static String campfireSound(ServerPlayer p, EventCtx c, boolean f) {
        Vec3 at = dir(p, 10, true, 0.8);
        HorrorEvents.repeat(p, 6, 9, () -> snd(p, SoundEvents.CAMPFIRE_CRACKLE, at, 0.4F, 1.0F));
        return null;
    }

    public static String xp(ServerPlayer p, EventCtx c, boolean f) {
        snd(p, SoundEvents.EXPERIENCE_ORB_PICKUP, p.position(), 0.2F, 0.5F);
        return null;
    }

    public static String bucket(ServerPlayer p, EventCtx c, boolean f) {
        snd(p, SoundEvents.BUCKET_FILL, dir(p, 5, true, 0.8), 0.35F, 0.9F);
        return null;
    }

    public static String furnaceSound(ServerPlayer p, EventCtx c, boolean f) {
        Vec3 at = dir(p, 6, true, 1.0);
        HorrorEvents.repeat(p, 3, 25, () -> snd(p, SoundEvents.FURNACE_FIRE_CRACKLE, at, 0.4F, 1.0F));
        return null;
    }

    public static String flicker(ServerPlayer p, EventCtx c, boolean f) {
        return client(p, "flicker", "");
    }

    public static String hudGone(ServerPlayer p, EventCtx c, boolean f) {
        return client(p, "hud", String.valueOf(50 + p.getRandom().nextInt(40)));
    }

    public static String chunkErr(ServerPlayer p, EventCtx c, boolean f) {
        return client(p, "chunkerr", (p.getBlockX() >> 4) + " " + (p.getBlockZ() >> 4));
    }

    public static String fakeSaving(ServerPlayer p, EventCtx c, boolean f) {
        return client(p, "saving", "");
    }

    public static String wrongName(ServerPlayer p, EventCtx c, boolean f) {
        if (p.getMainHandItem().isEmpty()) {
            return "empty hand";
        }
        return client(p, "wrongname", "");
    }

    public static String fog(ServerPlayer p, EventCtx c, boolean f) {
        return client(p, "fog", String.valueOf(600 + p.getRandom().nextInt(600)));
    }

    public static String loginTurn(ServerPlayer p, EventCtx c, boolean f) {
        p.connection.teleport(p.getX(), p.getY(), p.getZ(), p.getYRot() + 180F, p.getXRot());
        return null;
    }

    public static String torchExtra(ServerPlayer p, EventCtx c, boolean f) {
        if (!edits(c.st)) {
            return "world edits disabled";
        }
        BlockPos h = home(p) != null ? home(p) : p.blockPosition();
        ServerLevel lv = p.serverLevel();
        RandomSource r = p.getRandom();
        for (int t = 0; t < 20; t++) {
            BlockPos at = h.offset(r.nextInt(9) - 4, r.nextInt(3), r.nextInt(9) - 4);
            if (lv.getBlockState(at).isAir() && lv.getBlockState(at.below()).isFaceSturdy(lv, at.below(), Direction.UP)
                    && at.distSqr(p.blockPosition()) > 9) {
                lv.setBlock(at, Blocks.TORCH.defaultBlockState(), 3);
                c.st.inc("world_changes");
                return null;
            }
        }
        return "no free spot";
    }

    public static String flower(ServerPlayer p, EventCtx c, boolean f) {
        if (!edits(c.st)) {
            return "world edits disabled";
        }
        Vec3 b = dir(p, 3, true, 0.3);
        BlockPos at = HorrorEvents.stand(p.serverLevel(), b.x, b.z, p.getBlockY(), 2);
        if (at == null || !p.serverLevel().getBlockState(at.below()).is(net.minecraft.tags.BlockTags.DIRT)) {
            return "no grass behind the player";
        }
        p.serverLevel().setBlock(at, (p.getRandom().nextBoolean() ? Blocks.POPPY : Blocks.OXEYE_DAISY).defaultBlockState(), 3);
        c.st.inc("world_changes");
        return null;
    }

    public static String hotbarShift(ServerPlayer p, EventCtx c, boolean f) {
        Inventory inv = p.getInventory();
        List<Integer> used = new ArrayList<>();
        for (int i = 0; i < 9; i++) {
            if (i != inv.selected && !inv.getItem(i).isEmpty()) {
                used.add(i);
            }
        }
        if (used.size() < 2) {
            return "hotbar too empty";
        }
        int a = used.remove(p.getRandom().nextInt(used.size()));
        int b = used.get(p.getRandom().nextInt(used.size()));
        ItemStack sa = inv.getItem(a);
        inv.setItem(a, inv.getItem(b));
        inv.setItem(b, sa);
        return null;
    }

    public static String mobStare(ServerPlayer p, EventCtx c, boolean f) {
        List<Animal> mobs = p.serverLevel().getEntitiesOfClass(Animal.class, p.getBoundingBox().inflate(16));
        if (mobs.isEmpty()) {
            return "no animals around";
        }
        Animal a = mobs.get(p.getRandom().nextInt(mobs.size()));
        a.getNavigation().stop();
        HorrorEvents.repeat(p, 60, 2, () -> {
            if (a.isAlive()) {
                a.getNavigation().stop();
                a.getLookControl().setLookAt(p, 30F, 30F);
            }
        });
        return null;
    }

    // ================================================================== MEDIUM

    public static String animalsFace(ServerPlayer p, EventCtx c, boolean f) {
        List<Animal> mobs = p.serverLevel().getEntitiesOfClass(Animal.class, p.getBoundingBox().inflate(24));
        if (mobs.size() < 3) {
            return "not enough animals";
        }
        Vec3 point = dir(p, 40, true, 0.3);
        HorrorEvents.repeat(p, 100, 2, () -> {
            for (Animal a : mobs) {
                if (a.isAlive()) {
                    a.getNavigation().stop();
                    a.getLookControl().setLookAt(point.x, point.y, point.z);
                }
            }
        });
        return null;
    }

    public static String villageStare(ServerPlayer p, EventCtx c, boolean f) {
        List<Villager> vs = p.serverLevel().getEntitiesOfClass(Villager.class, p.getBoundingBox().inflate(32));
        if (vs.isEmpty()) {
            return "no villagers";
        }
        client(p, "silence", "200");
        HorrorEvents.repeat(p, 100, 2, () -> {
            for (Villager v : vs) {
                if (v.isAlive()) {
                    v.getNavigation().stop();
                    v.getLookControl().setLookAt(p, 30F, 30F);
                }
            }
        });
        return null;
    }

    public static String pets(ServerPlayer p, EventCtx c, boolean f) {
        List<TamableAnimal> pets = p.serverLevel().getEntitiesOfClass(TamableAnimal.class, p.getBoundingBox().inflate(32), t -> t.isOwnedBy(p));
        if (pets.isEmpty()) {
            return "no pets";
        }
        Vec3 point = dir(p, 30, true, 0.2);
        for (TamableAnimal t : pets) {
            t.setOrderedToSit(true);
            t.setInSittingPose(true);
        }
        HorrorEvents.repeat(p, 120, 2, () -> {
            for (TamableAnimal t : pets) {
                if (t.isAlive()) {
                    t.getLookControl().setLookAt(point.x, point.y, point.z);
                }
            }
        });
        return null;
    }

    public static String timeSkip(ServerPlayer p, EventCtx c, boolean f) {
        ServerLevel lv = p.serverLevel();
        if (lv.players().size() > 1 && !f) {
            return "other players online";
        }
        lv.setDayTime(lv.getDayTime() + 600 + p.getRandom().nextInt(900));
        return null;
    }

    public static String homeChanged(ServerPlayer p, EventCtx c, boolean f) {
        BlockPos h = home(p);
        if (h == null || !edits(c.st)) {
            return "no home or edits disabled";
        }
        ServerLevel lv = p.serverLevel();
        List<BlockPos> doors = scan(lv, h, 8, s -> s.getBlock() instanceof DoorBlock && s.getValue(DoorBlock.HALF) == net.minecraft.world.level.block.state.properties.DoubleBlockHalf.LOWER, 4);
        if (doors.isEmpty()) {
            return "no door at home";
        }
        BlockPos d = doors.get(p.getRandom().nextInt(doors.size()));
        BlockState s = lv.getBlockState(d);
        lv.setBlock(d, s.setValue(DoorBlock.OPEN, !s.getValue(DoorBlock.OPEN)), 10);
        c.st.inc("world_changes");
        return null;
    }

    public static String furnaceLit(ServerPlayer p, EventCtx c, boolean f) {
        ServerLevel lv = p.serverLevel();
        List<BlockPos> fs = scan(lv, p.blockPosition(), 10, s -> s.getBlock() instanceof AbstractFurnaceBlock && !s.getValue(AbstractFurnaceBlock.LIT), 3);
        if (fs.isEmpty()) {
            return "no cold furnace";
        }
        BlockPos at = fs.get(0);
        lv.setBlock(at, lv.getBlockState(at).setValue(AbstractFurnaceBlock.LIT, true), 3);
        HorrorEvents.later(p, 200 + p.getRandom().nextInt(200), () -> {
            BlockState s = lv.getBlockState(at);
            if (s.getBlock() instanceof AbstractFurnaceBlock) {
                lv.setBlock(at, s.setValue(AbstractFurnaceBlock.LIT, false), 3);
            }
        });
        return null;
    }

    public static String trapdoor(ServerPlayer p, EventCtx c, boolean f) {
        ServerLevel lv = p.serverLevel();
        List<BlockPos> ts = scan(lv, p.blockPosition(), 8, s -> s.getBlock() instanceof TrapDoorBlock && !s.getValue(TrapDoorBlock.OPEN), 3);
        if (ts.isEmpty()) {
            return "no trapdoor";
        }
        BlockPos at = ts.get(0);
        lv.setBlock(at, lv.getBlockState(at).setValue(TrapDoorBlock.OPEN, true), 3);
        snd(p, SoundEvents.WOODEN_TRAPDOOR_OPEN, Vec3.atCenterOf(at), 0.6F, 0.9F);
        return null;
    }

    public static String signRewrite(ServerPlayer p, EventCtx c, boolean f) {
        String last = c.st.text("lastChat");
        if (last.isEmpty()) {
            return "player never chatted";
        }
        for (BlockEntity be : blockEntities(p.serverLevel(), p.blockPosition(), 24)) {
            if (be instanceof SignBlockEntity sign && be.getBlockPos().distSqr(p.blockPosition()) > 16) {
                String t = last.length() > 15 ? last.substring(0, 15) : last;
                sign.setText(sign.getFrontText().setMessage(1, Component.literal(t)), true);
                sign.setChanged();
                p.serverLevel().sendBlockUpdated(be.getBlockPos(), be.getBlockState(), be.getBlockState(), 3);
                return null;
            }
        }
        return "no sign nearby";
    }

    public static String cropsRow(ServerPlayer p, EventCtx c, boolean f) {
        if (!edits(c.st)) {
            return "world edits disabled";
        }
        ServerLevel lv = p.serverLevel();
        List<BlockPos> crops = scan(lv, p.blockPosition(), 12, s -> s.getBlock() instanceof CropBlock cb && cb.isMaxAge(s), 64);
        if (crops.size() < 6) {
            return "no grown field";
        }
        BlockPos first = crops.get(0);
        int n = 0;
        for (BlockPos at : crops) {
            if (at.getX() == first.getX() && n < 8) {
                BlockState s = lv.getBlockState(at);
                lv.setBlock(at, ((CropBlock) s.getBlock()).getStateForAge(0), 3);
                n++;
            }
        }
        c.st.inc("world_changes");
        return n > 0 ? null : "no row";
    }

    public static String ghostBlock(ServerPlayer p, EventCtx c, boolean f) {
        BlockPos at = c.st.pos("lastPlacedPos");
        ServerLevel lv = p.serverLevel();
        if (at == null || !lv.isLoaded(at) || at.distSqr(p.blockPosition()) > 24 * 24 || lv.getBlockState(at).isAir()) {
            return "no recently placed block";
        }
        BlockState s = lv.getBlockState(at);
        if (lv.getBlockEntity(at) != null) {
            return "block has data";
        }
        lv.setBlock(at, Blocks.AIR.defaultBlockState(), 2);
        HorrorEvents.later(p, 60 + p.getRandom().nextInt(80), () -> {
            if (lv.getBlockState(at).isAir()) {
                lv.setBlock(at, s, 2);
            }
        });
        return null;
    }

    public static String mirrorBlock(ServerPlayer p, EventCtx c, boolean f) {
        BlockPos at = c.st.pos("lastPlacedPos");
        ServerLevel lv = p.serverLevel();
        if (at == null || !edits(c.st) || !lv.isLoaded(at)) {
            return "nothing to copy";
        }
        BlockState s = lv.getBlockState(at);
        if (s.isAir() || lv.getBlockEntity(at) != null) {
            return "nothing to copy";
        }
        BlockPos mirror = new BlockPos(2 * p.getBlockX() - at.getX(), at.getY(), 2 * p.getBlockZ() - at.getZ());
        if (!lv.isLoaded(mirror) || !lv.getBlockState(mirror).isAir()) {
            return "mirror spot taken";
        }
        lv.setBlock(mirror, s, 3);
        c.st.inc("world_changes");
        return null;
    }

    public static String pathTrail(ServerPlayer p, EventCtx c, boolean f) {
        BlockPos h = home(p);
        if (h == null || !edits(c.st) || h.distSqr(p.blockPosition()) < 20 * 20) {
            return "too close to home or edits disabled";
        }
        ServerLevel lv = p.serverLevel();
        Vec3 d = Vec3.atCenterOf(h).subtract(p.position()).multiply(1, 0, 1).normalize();
        int placed = 0;
        for (int i = 3; i < 14; i++) {
            int x = (int) Math.floor(p.getX() + d.x * i);
            int z = (int) Math.floor(p.getZ() + d.z * i);
            BlockPos top = lv.getHeightmapPos(Heightmap.Types.WORLD_SURFACE, new BlockPos(x, 0, z)).below();
            if (lv.getBlockState(top).is(Blocks.GRASS_BLOCK) && lv.getBlockState(top.above()).isAir()) {
                lv.setBlock(top, Blocks.DIRT_PATH.defaultBlockState(), 3);
                placed++;
            }
        }
        if (placed > 0) {
            c.st.inc("world_changes");
        }
        return placed > 0 ? null : "no grass to mark";
    }

    public static String followSteps(ServerPlayer p, EventCtx c, boolean f) {
        // steps behind you that only exist while you move
        HorrorEvents.repeat(p, 40, 8, () -> {
            if (p.getDeltaMovement().horizontalDistanceSqr() > 0.002) {
                snd(p, SoundEvents.GRAVEL_STEP, dir(p, 5, true, 0.15), 0.25F, 0.9F);
            }
        });
        return null;
    }

    public static String favouriteSpot(ServerPlayer p, EventCtx c, boolean f) {
        int[] n = new int[1];
        BlockPos spot = c.st.favouriteSpot(n);
        if (spot == null || n[0] < 6 || !edits(c.st)) {
            return "no favourite spot yet";
        }
        ServerLevel lv = p.serverLevel();
        BlockPos at = HorrorEvents.stand(lv, spot.getX() + 0.5, spot.getZ() + 0.5, spot.getY(), 4);
        if (at == null || at.distSqr(p.blockPosition()) < 100) {
            return "player is at the spot";
        }
        lv.setBlock(at, Blocks.REDSTONE_TORCH.defaultBlockState(), 3);
        c.st.inc("world_changes");
        c.st.remember("sites", at, 8);
        return null;
    }

    public static String stillWatch(ServerPlayer p, EventCtx c, boolean f) {
        if (!c.st.flag("was_still") && !f) {
            return "player was not idle";
        }
        c.st.setFlag("was_still", false);
        return HorrorEvents.spawnWatcher(p, 26, WatcherEntity.Mode.STALK, () -> HorrorEvents.front(p, 0.4), f, null);
    }

    public static String homecoming(ServerPlayer p, EventCtx c, boolean f) {
        if (!c.st.flag("came_home") && !f) {
            return "did not just come home";
        }
        c.st.setFlag("came_home", false);
        RandomSource r = p.getRandom();
        if (r.nextBoolean()) {
            String res = homeChanged(p, c, f);
            if (res == null) {
                return null;
            }
        }
        // a torch is gone and the smoke is still there
        ServerLevel lv = p.serverLevel();
        List<BlockPos> torches = scan(lv, p.blockPosition(), 7, HorrorEvents::isTorch, 8);
        if (torches.isEmpty() || !edits(c.st)) {
            return "nothing changed at home";
        }
        HorrorEvents.snuff(lv, torches.get(r.nextInt(torches.size())));
        c.st.inc("world_changes");
        return null;
    }

    public static String afterSleep(ServerPlayer p, EventCtx c, boolean f) {
        if (!c.st.flag("woke") && !f) {
            return "did not just wake up";
        }
        c.st.setFlag("woke", false);
        RandomSource r = p.getRandom();
        if (c.st.flag("lied_sleep")) {
            c.st.setFlag("lied_sleep", false);
            ComputerService.msg(p, "гость", "ты не спал.", null, null);
        }
        return switch (r.nextInt(4)) {
            case 0 -> homeChanged(p, c, f);
            case 1 -> torchExtra(p, c, f);
            case 2 -> hotbarShift(p, c, f);
            default -> {
                Vec3 bed = Vec3.atCenterOf(home(p) != null ? home(p) : p.blockPosition());
                snd(p, SoundEvents.WOOD_STEP, bed.add(2, 0, 0), 0.3F, 0.9F);
                yield null;
            }
        };
    }

    public static String loadingTerrain(ServerPlayer p, EventCtx c, boolean f) {
        return client(p, "terrain", "");
    }

    // ================================================================== MAJOR

    public static String blackout(ServerPlayer p, EventCtx c, boolean f) {
        ServerLevel lv = p.serverLevel();
        List<BlockPos> torches = scan(lv, p.blockPosition(), 12, HorrorEvents::isTorch, 24);
        if (torches.size() < 3) {
            return "not enough light sources";
        }
        torches.sort((a, b) -> Double.compare(b.distSqr(p.blockPosition()), a.distSqr(p.blockPosition())));
        List<BlockState> saved = new ArrayList<>();
        for (BlockPos t : torches) {
            saved.add(lv.getBlockState(t));
        }
        for (int i = 0; i < torches.size(); i++) {
            BlockPos t = torches.get(i);
            HorrorEvents.later(p, i * 6, () -> {
                if (HorrorEvents.isTorch(lv.getBlockState(t))) {
                    lv.setBlock(t, Blocks.AIR.defaultBlockState(), 3);
                    snd(p, SoundEvents.FIRE_EXTINGUISH, Vec3.atCenterOf(t), 0.2F, 1.6F);
                }
            });
        }
        HorrorEvents.later(p, torches.size() * 6 + 600 + p.getRandom().nextInt(400), () -> {
            for (int i = 0; i < torches.size(); i++) {
                if (lv.getBlockState(torches.get(i)).isAir()) {
                    lv.setBlock(torches.get(i), saved.get(i), 3);
                }
            }
        });
        return null;
    }

    public static String stormFigure(ServerPlayer p, EventCtx c, boolean f) {
        ServerLevel lv = p.serverLevel();
        Vec3 at = dir(p, 45, false, 0.5);
        BlockPos ground = lv.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, BlockPos.containing(at));
        LightningBolt bolt = EntityType.LIGHTNING_BOLT.create(lv);
        if (bolt == null) {
            return "could not create lightning";
        }
        bolt.setVisualOnly(true);
        bolt.moveTo(Vec3.atBottomCenterOf(ground));
        lv.addFreshEntity(bolt);
        return HorrorEvents.spawnWatcher(p, 42, WatcherEntity.Mode.STALK, () -> HorrorEvents.front(p, 0.15), f, w -> w.setMaxLife(30));
    }

    public static String longNight(ServerPlayer p, EventCtx c, boolean f) {
        ServerLevel lv = p.serverLevel();
        if (lv.players().size() > 1 && !f) {
            return "other players online";
        }
        long fixed = lv.getDayTime();
        client(p, "silence", "2400");
        HorrorEvents.repeat(p, 120, 20, () -> lv.setDayTime(fixed));
        return null;
    }

    public static String wrongWorld(ServerPlayer p, EventCtx c, boolean f) {
        ServerLevel lv = p.serverLevel();
        List<BlockPos> torches = scan(lv, p.blockPosition(), 8, s -> s.is(Blocks.TORCH), 16);
        if (torches.isEmpty()) {
            return "no torches to change";
        }
        client(p, "terrain", "");
        HorrorEvents.later(p, 10, () -> {
            for (BlockPos t : torches) {
                if (lv.getBlockState(t).is(Blocks.TORCH)) {
                    lv.setBlock(t, Blocks.REDSTONE_TORCH.defaultBlockState(), 3);
                }
            }
        });
        HorrorEvents.later(p, 900 + p.getRandom().nextInt(600), () -> {
            for (BlockPos t : torches) {
                if (lv.getBlockState(t).is(Blocks.REDSTONE_TORCH)) {
                    lv.setBlock(t, Blocks.TORCH.defaultBlockState(), 3);
                }
            }
        });
        return null;
    }

    public static String visitor(ServerPlayer p, EventCtx c, boolean f) {
        ServerLevel lv = p.serverLevel();
        List<BlockPos> doors = scan(lv, p.blockPosition(), 8, s -> s.getBlock() instanceof DoorBlock && s.getValue(DoorBlock.HALF) == net.minecraft.world.level.block.state.properties.DoubleBlockHalf.LOWER && !s.getValue(DoorBlock.OPEN), 4);
        if (doors.isEmpty()) {
            return "no closed door nearby";
        }
        BlockPos d = doors.get(0);
        Vec3 at = Vec3.atCenterOf(d);
        for (int i = 0; i < 3; i++) {
            HorrorEvents.later(p, i * 12, () -> snd(p, SoundEvents.WOOD_HIT, at, 0.7F, 0.6F));
        }
        HorrorEvents.later(p, 160, () -> {
            BlockState s = lv.getBlockState(d);
            if (s.getBlock() instanceof DoorBlock) {
                lv.setBlock(d, s.setValue(DoorBlock.OPEN, true), 10);
                snd(p, SoundEvents.WOODEN_DOOR_OPEN, at, 0.7F, 0.9F);
            }
        });
        for (int i = 0; i < 5; i++) {
            final int k = i;
            HorrorEvents.later(p, 190 + i * 9, () -> snd(p, SoundEvents.WOOD_STEP, at.add(p.position().subtract(at).scale(k / 6.0)), 0.4F, 0.9F));
        }
        HorrorEvents.later(p, 300, () -> {
            BlockState s = lv.getBlockState(d);
            if (s.getBlock() instanceof DoorBlock && s.getValue(DoorBlock.OPEN)) {
                lv.setBlock(d, s.setValue(DoorBlock.OPEN, false), 10);
                snd(p, SoundEvents.WOODEN_DOOR_CLOSE, at, 0.7F, 0.9F);
            }
        });
        return null;
    }

    public static String meet(ServerPlayer p, EventCtx c, boolean f) {
        BlockPos d = c.st.pos("death");
        if (d == null || d.distSqr(p.blockPosition()) > 48 * 48 || d.distSqr(p.blockPosition()) < 12 * 12) {
            return "not near the last death place";
        }
        double a = Math.atan2(d.getZ() + 0.5 - p.getZ(), d.getX() + 0.5 - p.getX());
        double dist = Math.sqrt(d.distSqr(p.blockPosition()));
        return HorrorEvents.spawnWatcher(p, dist, WatcherEntity.Mode.STALK, () -> a, f, null);
    }

    public static String caveCall(ServerPlayer p, EventCtx c, boolean f) {
        ServerLevel lv = p.serverLevel();
        Vec3 look = p.getLookAngle().multiply(1, 0, 1).normalize();
        for (int i = 0; i < 6; i++) {
            final int k = i;
            HorrorEvents.later(p, i * 9, () -> snd(p, SoundEvents.STONE_STEP, p.position().add(look.scale(16 - k)), 0.4F, 0.9F));
        }
        if (!edits(c.st)) {
            return null;
        }
        HorrorEvents.later(p, 80, () -> {
            for (int i = 14; i < 22; i++) {
                BlockPos at = BlockPos.containing(p.position().add(look.scale(i)));
                BlockPos spot = HorrorEvents.stand(lv, at.getX() + 0.5, at.getZ() + 0.5, at.getY(), 3);
                if (spot != null && lv.getBlockState(spot).isAir() && lv.getBrightness(net.minecraft.world.level.LightLayer.BLOCK, spot) < 4) {
                    lv.setBlock(spot, Blocks.TORCH.defaultBlockState(), 3);
                    c.st.inc("world_changes");
                    c.st.remember("sites", spot, 8);
                    return;
                }
            }
        });
        return null;
    }

    public static String chestTorch(ServerPlayer p, EventCtx c, boolean f) {
        int n = 0;
        for (BlockEntity be : blockEntities(p.serverLevel(), p.blockPosition(), 16)) {
            if (be instanceof ChestBlockEntity chest) {
                for (int i = 0; i < chest.getContainerSize(); i++) {
                    if (chest.getItem(i).isEmpty()) {
                        chest.setItem(i, new ItemStack(Items.TORCH));
                        n++;
                        break;
                    }
                }
            }
        }
        return n > 0 ? null : "no chests";
    }

    public static String lightFar(ServerPlayer p, EventCtx c, boolean f) {
        if (!edits(c.st)) {
            return "world edits disabled";
        }
        ServerLevel lv = p.serverLevel();
        Vec3 at = dir(p, 55 + p.getRandom().nextInt(20), false, 0.6);
        BlockPos spot = HorrorEvents.stand(lv, at.x, at.z, lv.getHeight(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, (int) at.x, (int) at.z), 4);
        if (spot == null) {
            return "no spot for the fire";
        }
        lv.setBlock(spot, Blocks.CAMPFIRE.defaultBlockState(), 3);
        c.st.inc("world_changes");
        c.st.setPos("campfire", spot);
        // gone before you get there
        HorrorEvents.repeat(p, 120, 40, () -> {
            BlockPos fire = c.st.pos("campfire");
            if (fire != null && p.blockPosition().distSqr(fire) < 18 * 18 && lv.getBlockState(fire).is(Blocks.CAMPFIRE)) {
                lv.setBlock(fire, Blocks.AIR.defaultBlockState(), 3);
                c.st.remember("sites", fire, 8);
                c.st.setPos("campfire", null);
            }
        });
        return null;
    }

    // ================================================================== COMPUTER

    static Vfs pc(ServerPlayer p) {
        return ComputerService.vfs(p);
    }

    static String anyFile(Vfs v, RandomSource r, String prefix) {
        List<String> paths = new ArrayList<>();
        for (String s : v.paths()) {
            if (s.startsWith(prefix) && !s.startsWith("/system/")) {
                paths.add(s);
            }
        }
        return paths.isEmpty() ? null : paths.get(r.nextInt(paths.size()));
    }

    private static final String[] ODD_NAMES = {"новый файл (3).txt", "не_открывай.txt", "..txt", "копия копия.txt", "untitled.txt", "0.txt", "ты.txt"};

    public static String pcFileNew(ServerPlayer p, EventCtx c, boolean f) {
        RandomSource r = p.getRandom();
        String name = ODD_NAMES[r.nextInt(ODD_NAMES.length)];
        String text = switch (r.nextInt(4)) {
            case 0 -> "";
            case 1 -> "тут было что-то длинное.";
            case 2 -> c.p.getBlockX() + " " + c.p.getBlockY() + " " + c.p.getBlockZ();
            default -> "не забудь закрыть.";
        };
        ComputerService.file(p, "/desktop/" + name, text);
        return null;
    }

    public static String pcRename(ServerPlayer p, EventCtx c, boolean f) {
        Vfs v = pc(p);
        String path = anyFile(v, p.getRandom(), "/");
        if (path == null) {
            return "no files";
        }
        String name = Vfs.name(path);
        int dot = name.lastIndexOf('.');
        String renamed = (dot > 0 ? name.substring(0, dot) : name) + "_" + p.getGameProfile().getName().toLowerCase(Locale.ROOT) + (dot > 0 ? name.substring(dot) : "");
        v.rename(path, Vfs.parent(path) + "/" + renamed);
        return null;
    }

    public static String pcDrift(ServerPlayer p, EventCtx c, boolean f) {
        Vfs v = pc(p);
        String path = anyFile(v, p.getRandom(), "/d");
        CompoundTag file = path == null ? null : v.get(path);
        if (file == null || file.getString("c").length() < 8) {
            return "nothing to drift";
        }
        String text = file.getString("c");
        String[] words = text.split(" ");
        int i = p.getRandom().nextInt(words.length);
        String[] repl = {"тихо", "здесь", "тоже", "снова", "нет"};
        words[i] = repl[p.getRandom().nextInt(repl.length)];
        file.putString("c", String.join(" ", words));
        return null;
    }

    public static String pcBadDate(ServerPlayer p, EventCtx c, boolean f) {
        Vfs v = pc(p);
        String path = anyFile(v, p.getRandom(), "/");
        if (path == null) {
            return "no files";
        }
        String[] bad = {"день 0, 25:71", "день -1, 03:00", ComputerService.clock(p.serverLevel(), 24000L * 2), "день ?, 00:00"};
        v.get(path).putString("m", bad[p.getRandom().nextInt(bad.length)]);
        return null;
    }

    public static String pcNewFolder(ServerPlayer p, EventCtx c, boolean f) {
        String[] dirs = {"/archive/old", "/downloads/новая папка", "/documents/" + p.getGameProfile().getName(), "/unknown"};
        String d = dirs[p.getRandom().nextInt(dirs.length)];
        Vfs v = pc(p);
        if (v.isDir(d)) {
            return "already exists";
        }
        v.mkdir(d);
        c.st.setText("emptyDir", d);
        return null;
    }

    public static String pcFillFolder(ServerPlayer p, EventCtx c, boolean f) {
        String d = c.st.text("emptyDir");
        Vfs v = pc(p);
        if (d.isEmpty() || !v.isDir(d) || !v.children(d).isEmpty()) {
            return "no empty folder";
        }
        v.put(d + "/" + c.lv.getBiome(p.blockPosition()).unwrapKey().map(k -> k.location().getPath()).orElse("место") + ".txt",
                "температура: " + String.format(Locale.ROOT, "%.1f", c.lv.getBiome(p.blockPosition()).value().getBaseTemperature()) + "\nосвещение: " + c.blockLight + "\nты там был.", ComputerService.clock(p));
        return null;
    }

    public static String pcClock(ServerPlayer p, EventCtx c, boolean f) {
        Vfs v = pc(p);
        int off = v.clockOffset();
        v.setClockOffset(off == 0 ? -(600 + p.getRandom().nextInt(2400)) : 0);
        return null;
    }

    public static String pcAutotype(ServerPlayer p, EventCtx c, boolean f) {
        String[] lines = {"ls -a", "cd /archive", "cat список.txt", "здесь", "whoami"};
        ComputerService.fx(p, "autotype:" + lines[p.getRandom().nextInt(lines.length)]);
        return null;
    }

    public static String pcFlicker(ServerPlayer p, EventCtx c, boolean f) {
        ComputerService.fx(p, "flicker");
        return null;
    }

    public static String pcFreeze(ServerPlayer p, EventCtx c, boolean f) {
        ComputerService.fx(p, "freeze");
        return null;
    }

    public static String pcShuffle(ServerPlayer p, EventCtx c, boolean f) {
        ComputerService.fx(p, "shuffle");
        return null;
    }

    public static String pcUiWrong(ServerPlayer p, EventCtx c, boolean f) {
        ComputerService.fx(p, "wrong");
        return null;
    }

    public static String pcCursor(ServerPlayer p, EventCtx c, boolean f) {
        ComputerService.fx(p, "cursor");
        return null;
    }

    public static String pcUnknownMsg(ServerPlayer p, EventCtx c, boolean f) {
        String place = ComputerService.placeName(c.place);
        String[] texts = {
                "ты долго был в " + place + ".",
                "слышал?",
                "закрой дверь, когда уходишь.",
                c.st.text("lastPlaced").isEmpty() ? "ты ничего не строишь." : "зачем там " + c.st.text("lastPlaced") + "?"
        };
        ComputerService.msg(p, "—", texts[p.getRandom().nextInt(texts.length)], null, null);
        client(p, "pctoast", "");
        return null;
    }

    public static String pcSituation(ServerPlayer p, EventCtx c, boolean f) {
        String w = c.thunder ? "гроза" : c.rain ? "дождь" : "ясно";
        ComputerService.file(p, "/desktop/сейчас.txt", "погода: " + w + "\nвысота: " + p.getBlockY() + "\nздоровье: " + (int) p.getHealth() + "\nрядом: " + (c.dark() ? "темно" : "светло"));
        return null;
    }

    public static String pcGhostCmd(ServerPlayer p, EventCtx c, boolean f) {
        String[] cmds = {"cd /", "ls", "cat /desktop/список.txt", "rm /documents/readme.txt", "ping home"};
        ComputerService.addHistoryGhost(p, cmds[p.getRandom().nextInt(cmds.length)]);
        return null;
    }

    public static String pcNightFile(ServerPlayer p, EventCtx c, boolean f) {
        CompoundTag file = pc(p).put("/documents/ночь.txt", "его видно только сейчас.", ComputerService.clock(p));
        file.putBoolean("n", true);
        return null;
    }

    public static String pcVanish(ServerPlayer p, EventCtx c, boolean f) {
        CompoundTag file = pc(p).put("/desktop/прочти.txt", "ты его уже читал.", ComputerService.clock(p));
        file.putBoolean("v", true);
        return null;
    }

    public static String pcOn(ServerPlayer p, EventCtx c, boolean f) {
        if (c.pc == null) {
            return "no computer in range";
        }
        ComputerService.setLit(c.lv, c.pc, true);
        snd(p, SoundEvents.UI_BUTTON_CLICK, Vec3.atCenterOf(c.pc), 0.2F, 0.5F);
        ComputerService.log(p, "включение (не пользователем)");
        BlockPos pcPos = c.pc;
        HorrorEvents.later(p, 400, () -> {
            if (!ComputerService.isOpen(p) && !c.st.flag("pc_keep_on")) {
                ComputerService.setLit(c.lv, pcPos, false);
            }
        });
        return null;
    }

    public static String pcToast(ServerPlayer p, EventCtx c, boolean f) {
        ComputerService.msg(p, "—", "", null, null);
        return client(p, "pctoast", "");
    }

    public static String pcListenBack(ServerPlayer p, EventCtx c, boolean f) {
        c.st.setFlag("killed_listen", false);
        ComputerService.log(p, "listen: запущен");
        return null;
    }

    public static String pcRestore(ServerPlayer p, EventCtx c, boolean f) {
        String path = c.st.text("arg");
        if (path.isEmpty()) {
            return "nothing to restore";
        }
        String key = "restore_" + Integer.toHexString(path.hashCode());
        String text = c.st.text(key);
        pc(p).put(path, text + "\n\nне удаляй.", ComputerService.clock(p)).putBoolean("lock", true);
        c.st.setText(key, "");
        return null;
    }

    public static String pcNotesAnswer(ServerPlayer p, EventCtx c, boolean f) {
        Vfs v = pc(p);
        v.setNotes(v.notes() + "\n\nсосед.");
        return null;
    }

    public static String pcNotesLine(ServerPlayer p, EventCtx c, boolean f) {
        Vfs v = pc(p);
        if (v.notes().isBlank()) {
            return "notes are empty";
        }
        v.setNotes(v.notes() + "\nпроверить " + (c.st.places("sites").isEmpty() ? "подвал" : c.st.places("sites").get(0).getX() + " " + c.st.places("sites").get(0).getZ()));
        return null;
    }

    // ================================================================== SECRETS

    public static String sSelfJoin(ServerPlayer p, EventCtx c, boolean f) {
        p.sendSystemMessage(Component.translatable("multiplayer.player.joined", p.getGameProfile().getName()).withStyle(net.minecraft.ChatFormatting.YELLOW));
        return null;
    }

    public static String sRevisit(ServerPlayer p, EventCtx c, boolean f) {
        int[] n = new int[1];
        BlockPos spot = c.st.favouriteSpot(n);
        if (spot == null || n[0] < 30 || p.blockPosition().distSqr(spot) > 36) {
            return "not at the spot often enough";
        }
        if (!edits(c.st)) {
            return "world edits disabled";
        }
        BlockPos at = HorrorEvents.stand(p.serverLevel(), spot.getX() + 0.5, spot.getZ() + 0.5, spot.getY(), 3);
        if (at == null || at.equals(p.blockPosition())) {
            return "spot occupied";
        }
        p.serverLevel().setBlock(at, Blocks.STONE_PRESSURE_PLATE.defaultBlockState(), 3);
        c.st.inc("world_changes");
        return null;
    }

    public static String sNightOwl(ServerPlayer p, EventCtx c, boolean f) {
        ComputerService.file(p, "/archive/ночи.txt", "ночью: " + c.st.count("min_night") + " мин\nднём: " + c.st.count("min_day") + " мин\n\nмне тоже больше нравится ночью.");
        return null;
    }

    public static String sBeside(ServerPlayer p, EventCtx c, boolean f) {
        return HorrorEvents.spawnWatcher(p, 3, WatcherEntity.Mode.STALK, () -> HorrorEvents.back(p, 0.3), f, w -> w.setMaxLife(200));
    }

    public static String sAllChains(ServerPlayer p, EventCtx c, boolean f) {
        Vfs v = pc(p);
        if (!v.unlock("unknown")) {
            return "already unlocked";
        }
        v.raw().putString("unk", "всё, что ты нашёл, было оставлено для тебя.\nне всё — мной.");
        return null;
    }

    public static String sFirstPlace(ServerPlayer p, EventCtx c, boolean f) {
        BlockPos first = c.st.pos("first");
        if (first == null || !edits(c.st)) {
            return "first position unknown";
        }
        ServerLevel lv = p.serverLevel();
        BlockPos at = HorrorEvents.stand(lv, p.getX() + 2, p.getZ(), p.getBlockY(), 3);
        if (at == null) {
            return "no spot";
        }
        HorrorEvents.placeSign(lv, p, at, new String[] {"", first.getX() + " " + first.getZ(), "", ""});
        c.st.inc("world_changes");
        return null;
    }

    static boolean isAir(Block b) {
        return b == Blocks.AIR;
    }
}
