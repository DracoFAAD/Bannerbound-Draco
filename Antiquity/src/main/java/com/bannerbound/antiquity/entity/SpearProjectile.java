package com.bannerbound.antiquity.entity;

import java.util.ArrayList;
import java.util.List;

import com.bannerbound.antiquity.BannerboundAntiquity;
import com.bannerbound.antiquity.Config;

import com.bannerbound.antiquity.client.SpearProjectileRenderer;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.animal.AbstractFish;
import net.minecraft.world.entity.projectile.AbstractArrow;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;

/**
 * A thrown spear. It flies and sticks in blocks (pickup-able) like an arrow. When it hits a mob it
 * deals damage and — vanilla-arrow style — records itself as a {@link StuckSpear} on the victim
 * (the {@code BannerboundAntiquity.STUCK_SPEARS} attachment), then discards the projectile entity.
 * A render layer draws the stuck spear as part of the mob, so it tracks the body smoothly with no
 * follow-entity, and the stored spear ItemStack drops on the mob's death (see StuckSpearDropEvents).
 * Throwing consumes the held spear; recovering the projectile (from a block, or the death-drop)
 * returns the same item.
 */
public class SpearProjectile extends AbstractArrow {
    /** Max spears that can be embedded in one mob (matches the wire cap in StuckSpear). */
    private static final int MAX_STUCK = 8;
    /** Fraction to pull the impact point toward the mob's center, so the spear head sinks INTO the
     *  body (no surface gap / floating) while staying on the side it struck. 0 = at the surface,
     *  1 = at the body center. Tunable ~0.4–0.7. */
    private static final double BURY_FRACTION = 0.5;
    /** Extra upward lift (blocks) for the stuck point so the spear sits in the body rather than
     *  down by the legs (the long shaft otherwise hangs low). Tunable. */
    private static final double STUCK_LIFT = 0.45;

    /** The spear item this projectile is — synced so the client renderer draws the right model,
     *  and used as the pickup / death-drop item. */
    private static final EntityDataAccessor<ItemStack> DATA_SPEAR =
        SynchedEntityData.defineId(SpearProjectile.class, EntityDataSerializers.ITEM_STACK);
    /** Whether this spear was thrown with a plant rope attached — synced so the renderer draws the
     *  green rope back to the thrower's hand (see {@code RopeRenderer}). */
    private static final EntityDataAccessor<Boolean> DATA_ROPE_TETHERED =
        SynchedEntityData.defineId(SpearProjectile.class, EntityDataSerializers.BOOLEAN);
    /** Being reeled in — SYNCED so the client homes the spear too (skipping the normal arrow tick),
     *  otherwise the client keeps recomputing rotation from the homing velocity → stuttering spin. */
    private static final EntityDataAccessor<Boolean> DATA_REELING =
        SynchedEntityData.defineId(SpearProjectile.class, EntityDataSerializers.BOOLEAN);

    /** Speed (blocks/tick) the spear flies back to the owner while being reeled in. */
    private static final double REEL_SPEED = 1.4;

    public SpearProjectile(EntityType<? extends SpearProjectile> type, Level level) {
        super(type, level);
    }

    public SpearProjectile(Level level, LivingEntity shooter, ItemStack spear, double damage) {
        super(BannerboundAntiquity.SPEAR_PROJECTILE.get(), shooter, level, spear.copy(), null);
        this.entityData.set(DATA_SPEAR, spear.copy());

        this.setBaseDamage(damage);
        // Recoverable as the same spear once it lands in a block.
        this.pickup = AbstractArrow.Pickup.ALLOWED;
    }

    /** When thrown in creative, only a creative player may pick the landed spear back up. */
    public void setCreativeOnlyPickup() {
        this.pickup = AbstractArrow.Pickup.CREATIVE_ONLY;
    }

    /** Whether this spear can ever become a recoverable item. An NPC spear-fisher/hunter throws a
     *  COPY of its reusable equipped tool, so its spear must NEVER yield an item (block pickup, mob
     *  death-drop, or a stuck-spear pull) — recovering it would duplicate the citizen's tool. */
    private boolean dropsRecoverable = true;

    /** How long a throwaway NPC spear stays visually stuck in a mob before vanishing — mirrors a
     *  landed arrow's ~60 s despawn. */
    private static final int NPC_STUCK_DESPAWN_TICKS = 1200;

    /** Mark this as a throwaway NPC spear: no pickup and no item recovery of any kind. It still
     *  EMBEDS in the mob it hits (the stuck-spear visual + the wounded read), but as a cosmetic-only
     *  entry: players can't pull it out, it never death-drops, and it despawns off the mob after
     *  {@link #NPC_STUCK_DESPAWN_TICKS} like a landed arrow — so the citizen's tool can't be duped. */
    public void markNoRecovery() {
        this.dropsRecoverable = false;
        this.pickup = AbstractArrow.Pickup.DISALLOWED;
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        super.defineSynchedData(builder);
        builder.define(DATA_SPEAR, new ItemStack(BannerboundAntiquity.WOODEN_SPEAR.get()));
        builder.define(DATA_ROPE_TETHERED, false);
        builder.define(DATA_REELING, false);
    }

    public boolean isReeling() {
        return this.entityData.get(DATA_REELING);
    }

    /** The spear item — for the renderer + the drop. */
    public ItemStack getSpearItem() {
        return this.entityData.get(DATA_SPEAR);
    }

    /** True if this spear was thrown with a plant rope attached (renders the green rope, reelable). */
    public boolean isRopeTethered() {
        return this.entityData.get(DATA_ROPE_TETHERED);
    }

    public void setRopeTethered(boolean tethered) {
        this.entityData.set(DATA_ROPE_TETHERED, tethered);
    }

    /** Begin reeling this (still-flying or ground-stuck) spear back to its thrower (synced). */
    public void startReeling() {
        this.entityData.set(DATA_REELING, true);
    }

    @Override
    public void tick() {
        if (isReeling()) {
            reelTowardOwner();
            return; // both sides home (so the client doesn't re-spin from velocity) — skip arrow tick
        }
        super.tick();
    }

    /**
     * Home toward the throwing player, on BOTH sides so the client shows a smooth glide. Rotation is
     * left frozen (we skip the arrow tick that recomputes it from velocity), which is what removes the
     * reeling stutter. Only the server hands back the spear + discards on arrival.
     */
    private void reelTowardOwner() {
        // Owner is any LivingEntity — a player (original feature) or a spear-fisher citizen NPC.
        if (!(this.getOwner() instanceof LivingEntity owner) || !owner.isAlive()) {
            if (!this.level().isClientSide) {
                this.entityData.set(DATA_REELING, false); // lost the owner → resume as a normal spear
            }
            return;
        }
        this.inGround = false;
        Vec3 toOwner = owner.position().add(0.0, owner.getBbHeight() * 0.5, 0.0).subtract(this.position());
        if (!this.level().isClientSide && toOwner.length() < 1.2) {
            if (owner instanceof net.minecraft.world.entity.player.Player player) {
                // Player: hand the spear back to the inventory (the throw consumed it).
                ItemStack give = getSpearItem().copy();
                if (!player.getInventory().add(give)) {
                    player.drop(give, false);
                }
            }
            // Citizen / other owner: the spear is a reusable equipped tool that never left
            // getJobTool() — the in-flight projectile was only a copy, so there's nothing to return;
            // it just vanishes on arrival (whether it hit a fish or missed). No arrival sound — the
            // spear_reel cue already played when the pull-in started.
            this.discard();
            return;
        }
        Vec3 velocity = toOwner.normalize().scale(REEL_SPEED);
        this.setDeltaMovement(velocity);
        this.setPos(this.getX() + velocity.x, this.getY() + velocity.y, this.getZ() + velocity.z);
    }

    @Override
    protected ItemStack getDefaultPickupItem() {
        return new ItemStack(BannerboundAntiquity.WOODEN_SPEAR.get());
    }

    /** Glide through water like a trident (0.99) instead of stalling like an arrow (0.6) — so a
     *  thrown spear actually reaches a fish underwater. */
    @Override
    protected float getWaterInertia() {
        return 0.99F;
    }

    /** The thunk when the spear sticks in a block (played by AbstractArrow on landing, with its own
     *  pitch jitter). Mob hits use SPEAR_HIT_FLESH instead (see onHitEntity). */
    @Override
    protected net.minecraft.sounds.SoundEvent getDefaultHitGroundSoundEvent() {
        return BannerboundAntiquity.SPEAR_HIT_BLOCK_SOUND.get();
    }

    /**
     * Enlarge the frustum-culling box. {@link SpearProjectileRenderer} anchors the spear model's
     * TIP at the entity origin, so the ~2.6-block shaft extends far outside the tiny arrow hitbox.
     * Vanilla culls on that hitbox inflated by only ~0.5, so once the spear is stuck and its hitbox
     * sits just off-screen (e.g. looking down the shaft, tip below the view), the whole on-screen
     * shaft gets culled and vanishes — the "invisible but still pick-up-able" bug. Inflating by the
     * model's full reach keeps it drawn whenever any part of the model is visible.
     */
    @Override
    public AABB getBoundingBoxForCulling() {
        return super.getBoundingBoxForCulling().inflate(3.0);
    }

    /**
     * Cosmetic ground decals (footprint tracks / blood splats) are {@code isPickable()} so the cursor
     * can right-click them — but that also makes the arrow's entity-collision raytrace treat them as a
     * solid target, so a spear thrown across tracks would "hit" one, get discarded and drop. Skip them
     * here so the spear flies straight through and only stops on a real block or mob.
     */
    @Override
    protected boolean canHitEntity(Entity target) {
        return super.canHitEntity(target) && !(target instanceof GroundDecalEntity);
    }

    @Override
    protected void onHitEntity(EntityHitResult result) {
        Entity target = result.getEntity();
        Entity owner = this.getOwner();
        DamageSource source = this.damageSources().arrow(this, owner == null ? this : owner);
        boolean hurt = target.hurt(source, (float) this.getBaseDamage());
        if (!this.level().isClientSide) {
            if (hurt && target instanceof LivingEntity living) {
                // Wet thud of the spear sinking into flesh.
                Vec3 fleshHit = result.getLocation();
                this.level().playSound(null, fleshHit.x, fleshHit.y, fleshHit.z,
                    BannerboundAntiquity.SPEAR_HIT_FLESH_SOUND.get(), net.minecraft.sounds.SoundSource.PLAYERS,
                    0.9F, 0.8F + this.random.nextFloat() * 0.4F);
                if (Config.BLEED_ENABLED.get()) {
                    HuntingFear.applyBleed(living, Config.BLEED_DURATION_TICKS.get(), owner); // wound bleeds
                }
                if (living instanceof AbstractFish && this.level() instanceof ServerLevel serverLevel) {
                    // Spear fishing: spit blood at the wound, matching the hunting feel.
                    Vec3 hit = result.getLocation();
                    serverLevel.sendParticles(BannerboundAntiquity.BLOOD_DROP.get(),
                        hit.x, hit.y, hit.z, 16, 0.15, 0.1, 0.15, 0.15);
                }
                if (living.isAlive()) {
                    // Survived → embed. Use the precise ray-trace impact point, NOT this.position()
                    // — by now the projectile has moved to/past the mob, so this.position() is off
                    // the body and would make the spear float.
                    addStuckSpear(living, result.getLocation());
                } else if (living instanceof AbstractFish && Config.SPEAR_FISHING_ENABLED.get()) {
                    // KILLING blow on a fish → the spear is bundled into the floating catch by
                    // SpearFishingEvents (which fired during the hurt above). Don't drop it here, or
                    // the player would get two spears. Remove the dead fish at once so its death
                    // flop/flash doesn't play next to the catch's impaled fish ("two fish hurt").
                    living.discard();
                } else if (dropsRecoverable) {
                    // KILLING blow — the mob's death-drop already fired before we recorded the
                    // spear, so drop it here or it's lost. (NPC spears never drop — no duplication.)
                    this.spawnAtLocation(getSpearItem());
                }
            } else if (dropsRecoverable) {
                // Non-living / undamaged: drop the spear so it's not lost. (NPC spears never drop.)
                this.spawnAtLocation(getSpearItem());
            }
        }
        this.discard();
    }

    /**
     * Record this spear on {@code host} (server side) so a render layer can draw it stuck in the
     * mob. The hit point is captured in the renderer's MODEL-SPACE frame so it lines up when the
     * layer runs: LivingEntityRenderer applies {@code mulPose(YP, 180 - yBodyRot)} then
     * {@code scale(-1,-1,1)} then {@code translate(0,-1.501,0)}. So from the world-space hit
     * delta we (1) rotate by {@code -yBodyRot} to make it body-relative, (2) negate X and Y for the
     * {@code -1,-1,1} flip, (3) lift Y by 1.501 to the model pivot. Yaw is stored body-relative so
     * the spear keeps pointing the struck direction as the mob turns.
     */
    private void addStuckSpear(LivingEntity host, Vec3 hitPos) {
        List<StuckSpear> current = host.getData(BannerboundAntiquity.STUCK_SPEARS.get());
        if (current.size() >= MAX_STUCK) {
            return; // already bristling with spears; ignore further hits
        }
        // Sink the head INTO the body: pull the impact point toward the mob's centre so there's no
        // surface gap (the "floating" look) and it hugs the body as the mob turns.
        Vec3 center = host.position().add(0.0, host.getBbHeight() * 0.5, 0.0);
        Vec3 buried = hitPos.lerp(center, BURY_FRACTION).add(0.0, STUCK_LIFT, 0.0);
        Vec3 world = buried.subtract(host.position());
        // Invert the renderer's model-space transform (it applies YP(180 - yBodyRot) then
        // scale(-1,-1,1) then translate(0,-1.501,0)). Solving that for the local point gives:
        //   localX =  cos*wx + sin*wz
        //   localY =  1.501 - wy
        //   localZ =  sin*wx - cos*wz       (cos/sin of yBodyRot)
        double rad = Math.toRadians(host.yBodyRot);
        double cos = Math.cos(rad);
        double sin = Math.sin(rad);
        // Horizontal placement is negated (180° about Y): without this the spear lands on the
        // OPPOSITE side it struck (front↔back / left↔right). Height + orientation are unaffected.
        float localX = (float) -(world.x * cos + world.z * sin);
        float localY = (float) (1.501 - world.y);
        float localZ = (float) -(world.x * sin - world.z * cos);
        float bodyYaw = this.getYRot() - host.yBodyRot;
        // NPC spears embed as COSMETIC-ONLY: never pullable/droppable (no tool dup) and timed out
        // like a landed arrow (StuckSpearEvents prunes them).
        long expireAt = dropsRecoverable ? -1L
            : this.level().getGameTime() + NPC_STUCK_DESPAWN_TICKS;
        List<StuckSpear> next = new ArrayList<>(current);
        next.add(new StuckSpear(getSpearItem().copy(), localX, localY, localZ, bodyYaw, this.getXRot(),
            dropsRecoverable, expireAt));
        // A NEW immutable list via setData is what triggers the native client sync — never mutate
        // the existing list in place (that wouldn't sync).
        host.setData(BannerboundAntiquity.STUCK_SPEARS.get(), List.copyOf(next));
        // (The flesh-hit sound already played in onHitEntity for this hit — no extra thunk here.)
    }

    @Override
    public void addAdditionalSaveData(CompoundTag tag) {
        super.addAdditionalSaveData(tag);
        tag.put("Spear", getSpearItem().save(this.registryAccess(), new CompoundTag()));
        tag.putBoolean("RopeTethered", isRopeTethered());
    }

    @Override
    public void readAdditionalSaveData(CompoundTag tag) {
        super.readAdditionalSaveData(tag);
        if (tag.contains("Spear")) {
            ItemStack.parse(this.registryAccess(), tag.getCompound("Spear"))
                .ifPresent(s -> this.entityData.set(DATA_SPEAR, s));
        }
        this.entityData.set(DATA_ROPE_TETHERED, tag.getBoolean("RopeTethered"));
    }
}
