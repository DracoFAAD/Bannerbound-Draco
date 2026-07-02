package com.bannerbound.core.entity;

import com.bannerbound.core.BannerboundCore;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.projectile.Projectile;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

/**
 * Custom bobber projectile owned by a {@link CitizenEntity} while the fisher work goal is in
 * its CAST/WAIT phase. Lightweight on purpose — server-side it tracks position + a "in water"
 * flag so {@link com.bannerbound.core.client.FisherBobberRenderer} can decide whether to
 * draw a bob or a falling sprite. Client-side it gets the citizen owner id from sync data so it
 * can render the fishing line back to the citizen's hand.
 * <p>
 * Vanilla {@link net.minecraft.world.entity.projectile.FishingHook} is tightly coupled to
 * {@code Player} (constructor + owner field + retract sync). We don't try to reuse it.
 */
public class FisherBobber extends Projectile {
    /** Entity id of the owning citizen — used by the client renderer to anchor the fishing line. */
    private static final EntityDataAccessor<Integer> OWNER_ID =
        SynchedEntityData.defineId(FisherBobber.class, EntityDataSerializers.INT);

    private static final float GRAVITY = 0.03f;
    /** Flag flipped true the first tick the bobber enters water, so the surface-snap fires
     *  exactly once per cast (otherwise we'd keep snapping every tick and the bob oscillation
     *  would cancel out). Not synced — server-side state only; client sees the snap via the
     *  position-sync packet. */
    private boolean snappedToSurface;
    /** Vanilla-style fishing simulation, all server-side (clients see it via position + particle
     *  sync). {@code timeUntilLured}: idle wait before a fish takes interest; {@code timeUntilHooked}:
     *  the fish swimming in — a {@link ParticleTypes#FISHING} wake trails toward the float;
     *  {@code nibble}: the bite window (the float dips) during which {@link FisherWorkGoal} reels in;
     *  {@code fishAngle}: the approach heading. Mirrors {@code FishingHook.catchingFish}. */
    private int timeUntilLured;
    private int timeUntilHooked;
    private int nibble;
    private float fishAngle;

    public FisherBobber(EntityType<? extends FisherBobber> type, Level level) {
        super(type, level);
        this.noPhysics = false;
    }

    /** Server-side spawn helper: positions the bobber at the citizen's hand and binds it. */
    public FisherBobber(Level level, CitizenEntity owner, Vec3 initialVelocity) {
        super(BannerboundCore.FISHER_BOBBER.get(), level);
        this.setOwner(owner);
        this.entityData.set(OWNER_ID, owner.getId());
        // Spawn a touch above the citizen's eye so the parabolic arc has clearance over their head.
        Vec3 origin = new Vec3(owner.getX(), owner.getEyeY() - 0.1, owner.getZ());
        this.setPos(origin.x, origin.y, origin.z);
        this.setDeltaMovement(initialVelocity);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        builder.define(OWNER_ID, -1);
    }

    public int getOwnerCitizenId() {
        return this.entityData.get(OWNER_ID);
    }

    /** True during the bite window — {@link FisherWorkGoal} reels in (rolls a catch) when this flips
     *  true. The bobber drives the whole lure→approach→bite sequence itself (see {@link #catchingFish}). */
    public boolean isHooked() {
        return this.nibble > 0;
    }

    /** Resolves the citizen entity from {@link #OWNER_ID}, or null if the owner is no longer in
     *  the same level. Used by the client renderer to anchor the fishing line. */
    public CitizenEntity getOwnerCitizen(Level level) {
        Entity e = level.getEntity(getOwnerCitizenId());
        return e instanceof CitizenEntity c ? c : null;
    }

    @Override
    public void tick() {
        super.tick();
        // Defense-in-depth: if the owner citizen is gone (despawned, exiled, killed, or this
        // bobber somehow outlived a save/reload), kill the bobber so it doesn't orphan in the
        // world. {@link #shouldBeSaved} below also prevents the save-survive case directly.
        if (!this.level().isClientSide && getOwnerCitizen(this.level()) == null) {
            this.discard();
            return;
        }
        Vec3 velocity = this.getDeltaMovement();

        boolean inWater = this.level().getFluidState(this.blockPosition()).is(FluidTags.WATER);
        if (inWater) {
            // First tick in water: snap Y to the surface (block top) so the bobber sprite sits
            // visibly on top of the water rather than half-submerged at whatever Y the
            // ballistic trajectory put it at.
            if (!snappedToSurface) {
                this.setPos(this.getX(), Math.floor(this.getY()) + 1.0, this.getZ());
                snappedToSurface = true;
                // Splash sound on first water contact — matches vanilla FishingHook's landing
                // audio (same SoundEvent, volume, and pitch-jitter formula). Server-side only;
                // playSound(null, ...) broadcasts to nearby clients.
                if (!this.level().isClientSide) {
                    this.level().playSound(null, this.getX(), this.getY(), this.getZ(),
                        net.minecraft.sounds.SoundEvents.FISHING_BOBBER_SPLASH,
                        net.minecraft.sounds.SoundSource.NEUTRAL, 0.25f,
                        1.0f + (this.level().getRandom().nextFloat()
                              - this.level().getRandom().nextFloat()) * 0.4f);
                }
            }
            // Run the vanilla-style lure/approach/bite simulation server-side (spawns the wake
            // particles + bite splash). It may set a downward bite velocity, so re-read it after.
            if (this.level() instanceof ServerLevel sl) {
                catchingFish(sl);
            }
            velocity = this.getDeltaMovement();
            if (nibble > 0) {
                // Hooked: keep the dip the bite imparted, just dampen horizontal drift.
                this.setDeltaMovement(velocity.x * 0.4, velocity.y, velocity.z * 0.4);
            } else {
                // Steady bob — sin oscillation gives a gentle vertical wobble while we wait.
                double bob = Math.sin(this.tickCount * 0.1) * 0.02;
                this.setDeltaMovement(velocity.x * 0.4, bob, velocity.z * 0.4);
            }
        } else {
            // Airborne arc: apply gravity, mild drag.
            this.setDeltaMovement(velocity.x * 0.98, velocity.y - GRAVITY, velocity.z * 0.98);
        }

        this.move(net.minecraft.world.entity.MoverType.SELF, this.getDeltaMovement());
    }

    /**
     * One tick of the fishing simulation while the float sits in water. Adapted from vanilla
     * {@code FishingHook.catchingFish}: an idle {@code timeUntilLured} wait with occasional far
     * "interest" splashes, then a {@code timeUntilHooked} approach where a {@link ParticleTypes#FISHING}
     * V-wake homes in on the float, then the bite — a splash burst + downward yank that opens the
     * {@code nibble} window {@link FisherWorkGoal} reels during.
     */
    private void catchingFish(ServerLevel sl) {
        if (nibble > 0) {
            nibble--;
            return;
        }
        if (timeUntilHooked > 0) {
            timeUntilHooked--;
            if (timeUntilHooked > 0) {
                fishAngle += (float) random.triangle(0.0, 9.188);
                float rad = fishAngle * ((float) Math.PI / 180.0F);
                float sin = Mth.sin(rad);
                float cos = Mth.cos(rad);
                double px = this.getX() + sin * timeUntilHooked * 0.1F;
                double py = Math.floor(this.getY()) + 1.0;
                double pz = this.getZ() + cos * timeUntilHooked * 0.1F;
                if (sl.getBlockState(BlockPos.containing(px, py - 1.0, pz)).is(Blocks.WATER)) {
                    if (random.nextFloat() < 0.15F) {
                        sl.sendParticles(ParticleTypes.BUBBLE, px, py - 0.1, pz, 1, sin, 0.1, cos, 0.0);
                    }
                    float fx = sin * 0.04F;
                    float fz = cos * 0.04F;
                    sl.sendParticles(ParticleTypes.FISHING, px, py, pz, 0, fz, 0.01, -fx, 1.0);
                    sl.sendParticles(ParticleTypes.FISHING, px, py, pz, 0, -fz, 0.01, fx, 1.0);
                }
            } else {
                // Bite! Yank the float down and burst splash particles; open the bite window.
                this.setDeltaMovement(this.getDeltaMovement().x,
                    -0.4F * Mth.nextFloat(random, 0.6F, 1.0F), this.getDeltaMovement().z);
                this.playSound(SoundEvents.FISHING_BOBBER_SPLASH, 0.25F,
                    1.0F + (random.nextFloat() - random.nextFloat()) * 0.4F);
                double py = this.getY() + 0.5;
                sl.sendParticles(ParticleTypes.BUBBLE, this.getX(), py, this.getZ(), 8, 0.2, 0.0, 0.2, 0.2);
                sl.sendParticles(ParticleTypes.FISHING, this.getX(), py, this.getZ(), 6, 0.2, 0.0, 0.2, 0.2);
                nibble = Mth.nextInt(random, 20, 40);
            }
        } else if (timeUntilLured > 0) {
            timeUntilLured--;
            float chance = 0.15F;
            if (timeUntilLured < 20) chance += (20 - timeUntilLured) * 0.05F;
            else if (timeUntilLured < 40) chance += (40 - timeUntilLured) * 0.02F;
            else if (timeUntilLured < 60) chance += (60 - timeUntilLured) * 0.01F;
            if (random.nextFloat() < chance) {
                float a = Mth.nextFloat(random, 0.0F, 360.0F) * ((float) Math.PI / 180.0F);
                float dist = Mth.nextFloat(random, 25.0F, 60.0F);
                double px = this.getX() + Mth.sin(a) * dist * 0.1;
                double pz = this.getZ() + Mth.cos(a) * dist * 0.1;
                if (sl.getBlockState(BlockPos.containing(px, Math.floor(this.getY()), pz)).is(Blocks.WATER)) {
                    sl.sendParticles(ParticleTypes.SPLASH, px, Math.floor(this.getY()) + 1.0, pz,
                        2 + random.nextInt(2), 0.1, 0.0, 0.1, 0.0);
                }
            }
            if (timeUntilLured <= 0) {
                fishAngle = Mth.nextFloat(random, 0.0F, 360.0F);
                // Deep water bites faster: the approach (and the lure wait below) is scaled by depth,
                // up to 2× faster in deep water — so a fisher who reaches a deep lake out-fishes the shore.
                timeUntilHooked = Math.max(1, (int) (Mth.nextInt(random, 40, 160) * depthBiteFactor(sl)));
            }
        } else {
            // Base rate halved from the original 60–200 — NPC fishing was too fast across the board.
            // Deep water halves it again (depthBiteFactor), landing roughly at the old shallow rate.
            timeUntilLured = Math.max(1, (int) (Mth.nextInt(random, 120, 400) * depthBiteFactor(sl)));
        }
    }

    /** Bite-time multiplier from the water depth under the float: 1.0 in shallow (1-block) water,
     *  easing down to 0.5 (twice as fast) once the water is {@link #DEEP_WATER} or deeper. Deeper lakes
     *  spawn the lure/wake particles and the bite about twice as quickly. */
    private double depthBiteFactor(ServerLevel sl) {
        int depth = 0;
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos().set(this.blockPosition());
        while (depth < DEEP_WATER && sl.getFluidState(p).is(FluidTags.WATER)) {
            depth++;
            p.move(0, -1, 0);
        }
        float t = Mth.clamp((depth - 1) / (float) (DEEP_WATER - 1), 0.0F, 1.0F);
        return Mth.lerp(t, 1.0, 0.5);
    }

    /** Water this deep (blocks below the float) gives the full 2× bite speed. */
    private static final int DEEP_WATER = 4;

    @Override
    protected void readAdditionalSaveData(net.minecraft.nbt.CompoundTag tag) {
        // No persistence — the fisher goal recreates the bobber on next cast.
    }

    @Override
    protected void addAdditionalSaveData(net.minecraft.nbt.CompoundTag tag) {
        // intentionally empty
    }

    /** The bobber is transient — its owner-citizen synced-id can't be safely round-tripped
     *  through a save (synced data isn't persisted, and the citizen's runtime entity id changes
     *  on reload). Returning false here means the bobber is dropped on world save instead of
     *  being recreated next launch as an orphan with no fishing line. */
    @Override
    public boolean shouldBeSaved() {
        return false;
    }

    @Override
    public boolean shouldRenderAtSqrDistance(double distSq) {
        return distSq < 4096.0;
    }

    @Override
    public net.minecraft.network.protocol.Packet<net.minecraft.network.protocol.game.ClientGamePacketListener> getAddEntityPacket(
            net.minecraft.server.level.ServerEntity serverEntity) {
        return new net.minecraft.network.protocol.game.ClientboundAddEntityPacket(this, serverEntity);
    }

    @Override
    public void recreateFromPacket(net.minecraft.network.protocol.game.ClientboundAddEntityPacket packet) {
        super.recreateFromPacket(packet);
    }
}
