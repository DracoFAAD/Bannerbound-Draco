package com.bannerbound.antiquity.entity;

import javax.annotation.Nullable;

import com.bannerbound.antiquity.BannerboundAntiquity;
import com.bannerbound.antiquity.item.ArrowParts;

import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;

/**
 * The modular arrow in flight. ONE {@link AbstractArrow} class backs every part combination. The parts
 * live on the pickup stack's {@code ARROW_TIP/SHAFT/BACK} data components (copied from the fired ammo),
 * but {@code AbstractArrow.pickupItemStack} is NOT synced to clients — so the stack is mirrored into a
 * synced {@link #DATA_ARROW} that the {@link com.bannerbound.antiquity.client.CompositeArrowRenderer
 * renderer} reads (via {@link ArrowParts}) to draw the three data-driven projectile layers. Heavier
 * metal parts also drop a bit faster (trajectory). A recovered arrow keeps its exact parts (and quality,
 * and any poison) via the stored pickup stack; base damage is set from the parts × craftsmanship in
 * {@link com.bannerbound.antiquity.item.CompositeArrowItem#createArrow}.
 */
public class CompositeArrowEntity extends AbstractArrow {

    /** A copy of the fired ammo (its part components) mirrored to clients for rendering. */
    private static final EntityDataAccessor<ItemStack> DATA_ARROW =
        SynchedEntityData.defineId(CompositeArrowEntity.class, EntityDataSerializers.ITEM_STACK);

    public CompositeArrowEntity(EntityType<? extends CompositeArrowEntity> type, Level level) {
        super(type, level);
    }

    public CompositeArrowEntity(Level level, LivingEntity shooter, ItemStack pickup,
                                @Nullable ItemStack weapon) {
        super(BannerboundAntiquity.ARROW_ENTITY.get(), shooter, level, pickup.copyWithCount(1), weapon);
        this.entityData.set(DATA_ARROW, pickup.copyWithCount(1));
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(DATA_ARROW, new ItemStack(BannerboundAntiquity.ARROW.get()));
    }

    /** The fired arrow stack (with part components), valid on both sides. */
    public ItemStack arrowStack() {
        return this.entityData.get(DATA_ARROW);
    }

    public String tip()   { return ArrowParts.tip(arrowStack()); }
    public String shaft() { return ArrowParts.shaft(arrowStack()); }
    public String back()  { return ArrowParts.back(arrowStack()); }

    @Override
    public void readAdditionalSaveData(net.minecraft.nbt.CompoundTag compound) {
        super.readAdditionalSaveData(compound);
        // The pickup stack (with components) is restored by AbstractArrow; mirror it into the synced slot.
        this.entityData.set(DATA_ARROW, getPickupItemStackOrigin().copyWithCount(1));
    }

    /** Heavier metal parts fall faster → a flatter, shorter-range arc (trajectory by weight). */
    @Override
    protected double getDefaultGravity() {
        return ArrowParts.gravityFor(arrowStack());
    }

    @Override
    protected ItemStack getDefaultPickupItem() {
        return new ItemStack(BannerboundAntiquity.ARROW.get());
    }
}
