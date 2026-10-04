package com.gena.brokensignal;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.DamageTypeTags;
import net.minecraft.util.Mth;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.EntitySelector;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Monster;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/** Faceless figure. STALK: stands and stares, vanishes when watched. CHASE: hunts the player. */
public class WatcherEntity extends Monster {
    public enum Mode { STALK, CHASE }

    private Mode mode = Mode.STALK;
    private int life;
    private int lookTicks;
    private int lookLimit = 14;
    private int attackCd;
    private int maxLife = 20 * 120;

    public WatcherEntity(EntityType<? extends Monster> type, Level level) {
        super(type, level);
    }

    public static AttributeSupplier.Builder createAttributes() {
        return Monster.createMonsterAttributes()
                .add(Attributes.MAX_HEALTH, 100.0)
                .add(Attributes.MOVEMENT_SPEED, 0.32)
                .add(Attributes.ATTACK_DAMAGE, 6.0)
                .add(Attributes.FOLLOW_RANGE, 64.0)
                .add(Attributes.KNOCKBACK_RESISTANCE, 1.0);
    }

    public void setMode(Mode mode) {
        this.mode = mode;
        this.maxLife = mode == Mode.STALK ? 20 * 120 : 20 * 30;
    }

    public void setLookLimit(int ticks) {
        this.lookLimit = ticks;
    }

    @Override
    protected void registerGoals() {
    }

    @Override
    public boolean shouldBeSaved() {
        return false;
    }

    @Override
    public boolean removeWhenFarAway(double distance) {
        return false;
    }

    @Override
    public boolean isPushable() {
        return false;
    }

    @Override
    protected void playStepSound(BlockPos pos, BlockState state) {
        if (mode == Mode.CHASE) {
            super.playStepSound(pos, state);
        }
    }

    @Override
    public void tick() {
        super.tick();
        if (level().isClientSide) {
            return;
        }
        Player p = level().getNearestPlayer(getX(), getY(), getZ(), 160.0, EntitySelector.NO_SPECTATORS);
        if (p == null || ++life > maxLife) {
            vanish();
            return;
        }
        double dx = p.getX() - getX();
        double dz = p.getZ() - getZ();
        double dist = Math.sqrt(dx * dx + dz * dz);

        if (mode == Mode.STALK) {
            getNavigation().stop();
            setDeltaMovement(0.0, getDeltaMovement().y, 0.0);
            float yaw = (float) (Mth.atan2(dz, dx) * (180.0 / Math.PI)) - 90.0F;
            setYRot(yaw);
            setYHeadRot(yaw);
            setYBodyRot(yaw);
            getLookControl().setLookAt(p, 30.0F, 30.0F);
            if (isLookedAtBy(p)) {
                lookTicks++;
            } else if (lookTicks > 0) {
                lookTicks--;
            }
            if (lookTicks >= lookLimit || dist < 9.0) {
                if (random.nextFloat() < 0.3F) {
                    p.addEffect(new MobEffectInstance(MobEffects.DARKNESS, 80, 0, false, false));
                }
                vanish();
            }
        } else {
            getNavigation().moveTo(p, 1.5);
            getLookControl().setLookAt(p, 30.0F, 30.0F);
            if (attackCd > 0) {
                attackCd--;
            }
            if (dist < 2.0 && Math.abs(p.getY() - getY()) < 2.5 && attackCd == 0 && !p.isCreative()) {
                doHurtTarget(p);
                attackCd = 20;
            }
        }
    }

    private boolean isLookedAtBy(Player p) {
        Vec3 view = p.getViewVector(1.0F).normalize();
        Vec3 to = new Vec3(getX() - p.getX(), getEyeY() - p.getEyeY(), getZ() - p.getZ());
        double d = to.length();
        if (d < 0.001) {
            return true;
        }
        to = to.normalize();
        return view.dot(to) > 1.0 - 0.025 / d && p.hasLineOfSight(this);
    }

    @Override
    public boolean hurt(DamageSource source, float amount) {
        if (source.is(DamageTypeTags.BYPASSES_INVULNERABILITY)) {
            return super.hurt(source, amount);
        }
        if (!level().isClientSide && source.getEntity() instanceof Player) {
            vanish();
        }
        return false;
    }

    public void vanish() {
        if (isRemoved()) {
            return;
        }
        if (level() instanceof ServerLevel sl) {
            sl.playSound(null, getX(), getY(), getZ(), ModRegistry.STATIC_BURST.get(), SoundSource.HOSTILE, 1.0F, 1.0F);
            sl.sendParticles(ParticleTypes.LARGE_SMOKE, getX(), getY() + 1.0, getZ(), 25, 0.3, 0.8, 0.3, 0.02);
        }
        discard();
    }
}
