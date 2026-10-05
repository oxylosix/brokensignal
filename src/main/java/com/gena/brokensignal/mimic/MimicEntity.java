package com.gena.brokensignal.mimic;

import java.util.Optional;
import java.util.UUID;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.PathfinderMob;
import net.minecraft.world.entity.ai.attributes.AttributeSupplier;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.goal.FloatGoal;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;

/**
 * A body that looks like a player (skin of {@link #skinOwner()}, real name tag, real equipment)
 * and behaves like one. It has no goals of its own: {@link MimicDirector} drives it through a
 * {@link MimicDirector.Session}. It is never saved with the world and is only sent to the one
 * player it is meant for, so nobody else can see it and the real person never meets it.
 */
public class MimicEntity extends PathfinderMob {
    private static final EntityDataAccessor<Optional<UUID>> SKIN =
            SynchedEntityData.defineId(MimicEntity.class, EntityDataSerializers.OPTIONAL_UUID);

    /** Who may see this body. Null = everyone (never used by the director, only by debug). */
    private UUID observer;

    public MimicEntity(EntityType<? extends MimicEntity> type, Level level) {
        super(type, level);
        this.setPersistenceRequired();
        for (EquipmentSlot s : EquipmentSlot.values()) {
            this.setDropChance(s, 0.0F);
        }
        this.setCanPickUpLoot(false);
    }

    public static AttributeSupplier.Builder createAttributes() {
        // speed 0.31: mob velocity scales with speed squared, this matches a walking player
        return PathfinderMob.createMobAttributes()
                .add(Attributes.MAX_HEALTH, 20.0)
                .add(Attributes.MOVEMENT_SPEED, 0.31)
                .add(Attributes.FOLLOW_RANGE, 64.0)
                .add(Attributes.ATTACK_DAMAGE, 3.0)
                .add(Attributes.STEP_HEIGHT, 0.6);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(SKIN, Optional.empty());
    }

    @Override
    protected void registerGoals() {
        this.goalSelector.addGoal(0, new FloatGoal(this));
    }

    public void setSkinOwner(UUID id) {
        this.entityData.set(SKIN, Optional.ofNullable(id));
    }

    public UUID skinOwner() {
        return this.entityData.get(SKIN).orElse(this.getUUID());
    }

    public void setObserver(UUID id) {
        this.observer = id;
    }

    public UUID observer() {
        return observer;
    }

    @Override
    public boolean broadcastToPlayer(ServerPlayer player) {
        return (observer == null || observer.equals(player.getUUID())) && super.broadcastToPlayer(player);
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
    public void tick() {
        super.tick();
        if (!this.level().isClientSide && !MimicDirector.drives(this)) {
            // a body without a session (debug leftovers, crash recovery) just stops existing
            this.discard();
        }
    }

    @Override
    public boolean hurt(DamageSource source, float amount) {
        if (this.level().isClientSide) {
            return super.hurt(source, amount);
        }
        if (source.getEntity() instanceof Player p) {
            MimicDirector.onHit(this, p);
        }
        // it reacts like a hit player (red flash, knockback) but cannot be killed
        boolean r = super.hurt(source, Math.min(amount, 1.0F));
        this.setHealth(this.getMaxHealth());
        return r;
    }

    @Override
    public boolean isPushable() {
        return true;
    }

    @Override
    protected boolean shouldDespawnInPeaceful() {
        return false;
    }
}
