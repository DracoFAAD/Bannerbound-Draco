package com.bannerbound.antiquity.entity;

import com.bannerbound.antiquity.BannerboundAntiquity;
import com.bannerbound.antiquity.poison.PoisonType;
import com.bannerbound.antiquity.poison.Poisons;

import net.minecraft.core.particles.ColorParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.EntityHitResult;

/**
 * A thrown blowdart. Flies like an arrow but deals almost no impact damage — its job is to deliver a
 * {@link PoisonType} on a hit (the real threat is the poison, not the prick). Always consumed (never
 * recoverable) like the no-pickup hunting arrows. The carried poison decides which coating it applies,
 * so all poisons reuse this one projectile; it's SYNCED so the client renderer's tinted tip and the
 * coloured in-flight trail match the actual poison (not the default).
 */
public class BlowdartProjectile extends AbstractArrow {
    private static final EntityDataAccessor<String> DATA_POISON =
        SynchedEntityData.defineId(BlowdartProjectile.class, EntityDataSerializers.STRING);

    private PoisonType poison = PoisonType.WOLFSBANE;

    public BlowdartProjectile(EntityType<? extends BlowdartProjectile> type, Level level) {
        super(type, level);
    }

    public BlowdartProjectile(Level level, LivingEntity shooter, PoisonType poison) {
        super(BannerboundAntiquity.BLOWDART_PROJECTILE.get(), shooter, level,
            new ItemStack(BannerboundAntiquity.WOLFSBANE_DART.get()), null);
        this.poison = poison;
        this.entityData.set(DATA_POISON, poison.id()); // sync to clients for the tip colour + trail
        this.pickup = Pickup.DISALLOWED; // darts are spent on use, never picked back up
        this.setBaseDamage(1.0);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(DATA_POISON, PoisonType.WOLFSBANE.id());
    }

    @Override
    protected ItemStack getDefaultPickupItem() {
        return getDartItem();
    }

    /** Trails coloured particles in flight (and a slow drip once stuck) the way a tipped arrow does,
     *  using the carried poison's tint so the dart reads as that poison mid-air. Client-side only. */
    @Override
    public void tick() {
        super.tick();
        if (this.level().isClientSide) {
            if (this.inGround) {
                if (this.inGroundTime % 5 == 0) {
                    spawnPoisonTrail(1);
                }
            } else {
                spawnPoisonTrail(2);
            }
        }
    }

    private void spawnPoisonTrail(int count) {
        int c = getPoison().tintColor();
        float r = (c >> 16 & 0xFF) / 255.0F;
        float g = (c >> 8 & 0xFF) / 255.0F;
        float b = (c & 0xFF) / 255.0F;
        for (int i = 0; i < count; i++) {
            this.level().addParticle(
                ColorParticleOption.create(ParticleTypes.ENTITY_EFFECT, r, g, b),
                this.getRandomX(0.5), this.getRandomY(), this.getRandomZ(0.5), 0.0, 0.0, 0.0);
        }
    }

    /** The poison this dart carries — read from the SYNCED data so the client renderer/trail get the
     *  right poison (the {@code poison} field is only populated server-side). */
    public PoisonType getPoison() {
        PoisonType t = PoisonType.fromId(this.entityData.get(DATA_POISON));
        return t == null ? PoisonType.WOLFSBANE : t;
    }

    /** The dart item this projectile represents — drives the in-flight renderer (per poison). Uses
     *  {@code ==} (not a switch) because AbstractArrow's super-constructor calls this BEFORE the
     *  {@code poison} field is assigned, and a switch on a null enum NPEs on {@code ordinal()}. */
    public ItemStack getDartItem() {
        return new ItemStack(poison == PoisonType.BELLADONNA
            ? BannerboundAntiquity.NIGHTSHADE_DART.get()
            : poison == PoisonType.CURARE
            ? BannerboundAntiquity.CURARE_DART.get()
            : poison == PoisonType.OLEANDER
            ? BannerboundAntiquity.OLEANDER_DART.get()
            : BannerboundAntiquity.WOLFSBANE_DART.get());
    }

    @Override
    protected void onHitEntity(EntityHitResult result) {
        Entity target = result.getEntity();
        Entity owner = this.getOwner();
        DamageSource source = this.damageSources().arrow(this, owner == null ? this : owner);
        target.hurt(source, (float) this.getBaseDamage());
        if (!this.level().isClientSide && target instanceof LivingEntity living && living.isAlive()) {
            Poisons.applyPoison(living, poison, owner); // recorded so the eventual poison kill credits the shooter
        }
        this.discard();
    }

    @Override
    public void addAdditionalSaveData(net.minecraft.nbt.CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.putString("Poison", poison.id());
    }

    @Override
    public void readAdditionalSaveData(net.minecraft.nbt.CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        PoisonType t = PoisonType.fromId(tag.getString("Poison"));
        if (t != null) {
            poison = t;
            this.entityData.set(DATA_POISON, poison.id());
        }
    }
}
