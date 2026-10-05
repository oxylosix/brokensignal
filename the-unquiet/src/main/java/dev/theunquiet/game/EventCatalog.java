package dev.theunquiet.game;

import dev.theunquiet.core.api.EventCategory;
import dev.theunquiet.core.api.HorrorEvent;
import dev.theunquiet.core.api.HorrorEventRegistry;
import dev.theunquiet.core.api.Rarity;
import dev.theunquiet.ModEntities;
import dev.theunquiet.UnquietConfig;
import dev.theunquiet.entity.WitnessEntity;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.entity.npc.Villager;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.level.levelgen.Heightmap;

public final class EventCatalog {
    private static boolean registered;

    private EventCatalog() {
    }

    public static synchronized void register() {
        if (registered) {
            return;
        }
        registered = true;

        register(HorrorEvent.builder("distant_knock", EventCategory.ENVIRONMENTAL, Rarity.UNCOMMON)
                .cooldown(144_000)
                .when(context -> !context.level().isDay() && context.level().canSeeSky(context.player().blockPosition()))
                .doAction(context -> {
                    context.memory().setChainStage(context.player().getUUID(), "quiet_house", "heard_knock");
                    BlockPos origin = context.player().blockPosition().relative(context.player().getDirection().getOpposite(), 7);
                    if (UnquietConfig.AUDIO_EFFECTS.get()) {
                        context.level().playSound(null, origin, SoundEvents.WOODEN_DOOR_CLOSE,
                                SoundSource.AMBIENT, 0.28F, 0.72F);
                    }
                }).build());

        register(HorrorEvent.builder("footsteps_behind", EventCategory.SUBTLE, Rarity.COMMON)
                .cooldown(108_000)
                .when(context -> context.player().onGround() && context.level().isDay())
                .doAction(context -> {
                    Vec3 behind = context.player().position().subtract(context.player().getLookAngle().scale(5));
                    BlockPos pos = BlockPos.containing(behind);
                    if (UnquietConfig.AUDIO_EFFECTS.get()) {
                        context.level().playSound(null, pos, SoundEvents.GRASS_STEP,
                                SoundSource.AMBIENT, 0.22F, 0.9F);
                    }
                }).build());

        register(HorrorEvent.builder("village_stillness", EventCategory.VILLAGE, Rarity.RARE)
                .cooldown(288_000)
                .when(context -> !context.level().getEntitiesOfClass(
                        Villager.class, new AABB(context.player().blockPosition()).inflate(14)).isEmpty())
                .doAction(context -> {
                    if (UnquietConfig.AUDIO_EFFECTS.get()) {
                        context.level().playSound(null, context.player().blockPosition(),
                                SoundEvents.AMBIENT_CAVE, SoundSource.AMBIENT, 0.25F, 0.65F);
                    }
                    context.player().displayClientMessage(Component.translatable("event.theunquiet.village_stillness"), true);
                }).build());

        register(HorrorEvent.builder("night_echo", EventCategory.ENVIRONMENTAL, Rarity.UNCOMMON)
                .cooldown(144_000)
                .when(context -> context.memory().chainStage(context.player().getUUID(), "quiet_house")
                        .equals("heard_knock")
                        && context.level().isNight()
                        && context.level().canSeeSky(context.player().blockPosition()))
                .doAction(context -> {
                    context.memory().setChainStage(context.player().getUUID(), "quiet_house", "echo_returned");
                    BlockPos origin = context.player().blockPosition().offset(
                            context.random().nextInt(17) - 8, 0, context.random().nextInt(17) - 8);
                    if (UnquietConfig.AUDIO_EFFECTS.get()) {
                        context.level().playSound(null, origin, SoundEvents.AMBIENT_CAVE,
                                SoundSource.AMBIENT, 0.3F, 1.15F);
                    }
                }).build());

        register(HorrorEvent.builder("animal_watch", EventCategory.ANIMAL, Rarity.RARE)
                .cooldown(288_000)
                .when(context -> !context.level().getEntitiesOfClass(
                        Animal.class, new AABB(context.player().blockPosition()).inflate(9)).isEmpty())
                .doAction(context -> {
                    Animal animal = context.level().getEntitiesOfClass(
                                    Animal.class, new AABB(context.player().blockPosition()).inflate(9))
                            .stream().min((left, right) -> Double.compare(
                                    left.distanceToSqr(context.player()), right.distanceToSqr(context.player())))
                            .orElse(null);
                    if (animal != null) {
                        animal.getLookControl().setLookAt(context.player(), 10.0F, 10.0F);
                        context.player().displayClientMessage(Component.translatable("event.theunquiet.animal_watch"), true);
                    }
                }).build());

        register(HorrorEvent.builder("ash_in_air", EventCategory.SUBTLE, Rarity.UNCOMMON)
                .cooldown(180_000)
                .when(context -> context.level().isDay() && context.level().canSeeSky(context.player().blockPosition()))
                .doAction(context -> {
                    Vec3 center = context.player().position().add(context.player().getLookAngle().scale(8));
                    context.level().sendParticles(ParticleTypes.ASH,
                            center.x, center.y + 1.3, center.z, 5, 0.35, 0.4, 0.35, 0.002);
                    context.player().displayClientMessage(Component.translatable("event.theunquiet.ash_in_air"), true);
                }).build());

        register(HorrorEvent.builder("wrong_block", EventCategory.ENVIRONMENTAL, Rarity.VERY_RARE)
                .cooldown(576_000)
                .when(context -> context.level().getBlockState(context.player().blockPosition().below()).is(Blocks.GRASS_BLOCK))
                .doAction(context -> {
                    if (UnquietConfig.AUDIO_EFFECTS.get()) {
                        context.level().playSound(null, context.player().blockPosition(),
                                SoundEvents.SCULK_SENSOR_CLICKING, SoundSource.AMBIENT, 0.18F, 0.45F);
                    }
                }).build());

        register(HorrorEvent.builder("impossible_company", EventCategory.TEMPORAL, Rarity.SECRET)
                .afterPlayTicks(144_000)
                .cooldown(1_440_000)
                .when(context -> context.player().isAlive() && context.player().getHealth() > 8.0F)
                .doAction(context -> {
                    context.player().displayClientMessage(Component.translatable("event.theunquiet.night_echo"), true);
                    if (UnquietConfig.AUDIO_EFFECTS.get()) {
                        context.level().playSound(null, context.player().blockPosition().above(),
                                SoundEvents.AMETHYST_BLOCK_CHIME, SoundSource.AMBIENT, 0.18F, 0.5F);
                    }
                }).build());

        register(HorrorEvent.builder("the_witness", EventCategory.ENTITY, Rarity.VERY_RARE)
                .afterPlayTicks(144_000)
                .cooldown(576_000)
                .when(context -> context.memory().chainStage(context.player().getUUID(), "quiet_house")
                        .equals("echo_returned")
                        && context.level().isNight()
                        && context.level().canSeeSky(context.player().blockPosition()))
                .doAction(context -> {
                    BlockPos offset = context.player().blockPosition().offset(
                            context.random().nextInt(25) - 12, 0, context.random().nextInt(25) - 12);
                    BlockPos position = context.level().getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, offset);
                    if (position.distSqr(context.player().blockPosition()) < 12 * 12) {
                        return;
                    }
                    WitnessEntity witness = ModEntities.WITNESS.get().create(context.level());
                    if (witness == null) {
                        return;
                    }
                    witness.setObserver(context.player().getUUID());
                    witness.moveTo(position.getX() + 0.5, position.getY(), position.getZ() + 0.5,
                            context.random().nextFloat() * 360.0F, 0.0F);
                    if (context.level().noCollision(witness)) {
                        context.level().addFreshEntity(witness);
                    }
                }).build());
    }

    private static void register(HorrorEvent event) {
        HorrorEventRegistry.register(event);
    }
}
