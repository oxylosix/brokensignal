package com.gena.brokensignal.ext;

import com.gena.brokensignal.Config;
import com.gena.brokensignal.EventCtx;
import com.gena.brokensignal.HorrorEvents;
import com.gena.brokensignal.HorrorState;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.Container;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Consequences: events that only exist because something happened earlier — in the world or
 * outside the game window. They are scheduled, never picked at random by the director.
 */
public final class Echoes {
    private Echoes() {}

    private static BlockPos find(ServerPlayer p, BlockPos c, int r, java.util.function.Predicate<BlockState> ok) {
        if (c == null) {
            return null;
        }
        for (BlockPos q : BlockPos.betweenClosed(c.offset(-r, -3, -r), c.offset(r, 3, r))) {
            if (ok.test(p.level().getBlockState(q))) {
                return q.immutable();
            }
        }
        return null;
    }

    private static BlockPos homeOrHere(ServerPlayer p) {
        BlockPos h = HorrorEvents.house(p);
        return h != null ? h : p.blockPosition();
    }

    /** You were on the desktop for a while. When you come back, the door of your house is open. */
    public static String whileAway(ServerPlayer p, EventCtx c, boolean f) {
        BlockPos home = HorrorEvents.house(p);
        BlockPos door = find(p, home, 10, s -> s.getBlock() instanceof DoorBlock && !s.getValue(DoorBlock.OPEN));
        if (door == null) {
            return "no closed door at home";
        }
        BlockState s = p.level().getBlockState(door);
        ((DoorBlock) s.getBlock()).setOpen(null, p.level(), s, door, true);
        return null;
    }

    /** You closed the companion window before it finished. A door near you closes too. */
    public static String closedDoor(ServerPlayer p, EventCtx c, boolean f) {
        BlockPos door = find(p, p.blockPosition(), 14, s -> s.getBlock() instanceof DoorBlock && s.getValue(DoorBlock.OPEN));
        if (door == null) {
            p.serverLevel().playSound(null, p.blockPosition().offset(6, 0, -4), SoundEvents.WOODEN_DOOR_CLOSE, SoundSource.BLOCKS, 0.7F, 0.9F);
            return null;
        }
        BlockState s = p.level().getBlockState(door);
        ((DoorBlock) s.getBlock()).setOpen(null, p.level(), s, door, false);
        return null;
    }

    /** What you typed into the companion notepad turns up on a sign near your house. Nobody signed it. */
    public static String typedBack(ServerPlayer p, EventCtx c, boolean f) {
        String txt = HorrorState.of(p).text("arg");
        if (txt == null || txt.isBlank()) {
            return "nothing typed";
        }
        if (!Config.WORLD_EDITS.get()) {
            return "world edits disabled";
        }
        BlockPos at = find(p, homeOrHere(p), 12, s -> false);
        BlockPos base = homeOrHere(p);
        for (int i = 0; i < 24 && at == null; i++) {
            BlockPos q = base.offset(p.getRandom().nextInt(13) - 6, 0, p.getRandom().nextInt(13) - 6);
            q = p.level().getHeightmapPos(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, q);
            if (p.level().getBlockState(q).isAir() && p.level().getBlockState(q.below()).isSolid()) {
                at = q;
            }
        }
        if (at == null) {
            return "no space for a sign";
        }
        p.level().setBlock(at, net.minecraft.world.level.block.Blocks.OAK_SIGN.defaultBlockState(), 3);
        BlockEntity be = p.level().getBlockEntity(at);
        if (be instanceof SignBlockEntity sign) {
            String[] w = txt.length() > 60 ? txt.substring(0, 60).split(" ") : txt.split(" ");
            SignText st = new SignText();
            StringBuilder line = new StringBuilder();
            int row = 0;
            for (String word : w) {
                if (line.length() + word.length() > 14 && row < 3) {
                    st = st.setMessage(row++, Component.literal(line.toString().trim()));
                    line.setLength(0);
                }
                line.append(word).append(' ');
            }
            st = st.setMessage(row, Component.literal(line.toString().trim()));
            sign.setText(st, true);
            sign.setChanged();
        }
        HorrorState.of(p).inc("world_changes");
        return null;
    }

    /** You deleted a file the companion left in its folder. A sheet of paper with its name lies on your floor. */
    public static String fileGone(ServerPlayer p, EventCtx c, boolean f) {
        String name = HorrorState.of(p).text("arg");
        BlockPos at = homeOrHere(p);
        ItemStack paper = new ItemStack(Items.PAPER);
        paper.set(DataComponents.CUSTOM_NAME, Component.literal(name == null || name.isBlank() ? "untitled.txt" : name));
        ItemEntity e = new ItemEntity(p.level(), at.getX() + 0.5, at.getY() + 0.2, at.getZ() + 0.5, paper, 0, 0, 0);
        e.setPickUpDelay(10);
        p.level().addFreshEntity(e);
        return null;
    }

    /** Where the copy hit you, later: an armor stand facing the spot where you stood. Gone when you come close. */
    public static String mimicEcho(ServerPlayer p, EventCtx c, boolean f) {
        BlockPos site = HorrorState.of(p).pos("mimic_site");
        if (site == null || !Config.WORLD_EDITS.get()) {
            return "no site / world edits off";
        }
        if (site.distSqr(p.blockPosition()) > 60 * 60 || site.distSqr(p.blockPosition()) < 16 * 16) {
            return "wrong distance to site";
        }
        ServerLevel lv = p.serverLevel();
        ArmorStand a = new ArmorStand(lv, site.getX() + 0.5, site.getY(), site.getZ() + 0.5);
        a.setYRot(p.getRandom().nextFloat() * 360F);
        a.addTag(com.gena.brokensignal.mimic.Scenes.TAG + "_stand");
        lv.addFreshEntity(a);
        com.gena.brokensignal.mimic.Scenes.standUntilClose(p, a);
        return null;
    }

    /** Every 13th time you open the same chest, its contents are sorted. Only that chest, only the 13th time. */
    public static void onOpen(ServerPlayer p, BlockPos pos, Container c) {
        HorrorState st = HorrorState.of(p);
        String key = "open_" + pos.asLong();
        int n = st.inc(key);
        if (n % 13 != 0 || n > 13 * 4 || !Config.WORLD_EDITS.get() || c.getContainerSize() > 54) {
            return;
        }
        java.util.List<ItemStack> items = new java.util.ArrayList<>();
        for (int i = 0; i < c.getContainerSize(); i++) {
            if (!c.getItem(i).isEmpty()) {
                items.add(c.getItem(i).copy());
            }
        }
        items.sort(java.util.Comparator.comparing((ItemStack s) -> s.getHoverName().getString()).thenComparing(s -> -s.getCount()));
        for (int i = 0; i < c.getContainerSize(); i++) {
            c.setItem(i, i < items.size() ? items.get(i) : ItemStack.EMPTY);
        }
        c.setChanged();
        st.inc("chest_sorted");
    }
}
