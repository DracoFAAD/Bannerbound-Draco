package com.bannerbound.antiquity.entity;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.bannerbound.antiquity.BannerboundAntiquity;
import com.bannerbound.antiquity.Config;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MoverType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;

/**
 * The "catch" left behind when a thrown spear kills a fish (see {@code SpearFishingEvents}): a
 * free-floating object that renders the spear angled in the water with the fish impaled on its tip
 * (see {@code SpearedFishEntityRenderer}), bobs at the surface, and — on walk-over pickup — grants
 * the {@link #payload}: the spear plus everything the fish would have dropped, in one go.
 *
 * <p>It is a standalone entity that follows nothing (the fish is dead), so — unlike a spear stuck in
 * a LIVING mob, which must be a render layer on the body — a dedicated entity is correct here. The
 * float behaviour mirrors {@code FisherBobber}: snap to the water surface on first contact, then a
 * gentle sin-bob; out of water it falls under gravity and rests like a dropped item.
 */
public class SpearedFishEntity extends Entity {
    /** Gravity per tick while airborne / out of water (matches FisherBobber's mild arc). */
    private static final float GRAVITY = 0.04F;
    /** Pickup grace after spawning so a fresh catch isn't grabbed before it surfaces. */
    private static final int DEFAULT_PICKUP_DELAY = 40;
    /** Rest this far below the water's block-top. Keeps blockPosition() inside the water block so the
     *  bob never dips into the air block above — which would flip the in-water test each tick and
     *  re-fire the surface splash sound. */
    private static final double SURFACE_SINK = 0.12;

    /** The spear item — synced so the renderer draws the right spear model. */
    private static final EntityDataAccessor<ItemStack> DATA_SPEAR =
        SynchedEntityData.defineId(SpearedFishEntity.class, EntityDataSerializers.ITEM_STACK);
    /** Registry id of the speared fish's EntityType (e.g. "minecraft:cod") — picks the model/texture. */
    private static final EntityDataAccessor<String> DATA_FISH_TYPE =
        SynchedEntityData.defineId(SpearedFishEntity.class, EntityDataSerializers.STRING);
    /** Tropical-fish packed variant (0 for non-tropical). Reserved for full variant rendering. */
    private static final EntityDataAccessor<Integer> DATA_FISH_VARIANT =
        SynchedEntityData.defineId(SpearedFishEntity.class, EntityDataSerializers.INT);
    /** Whether a plant rope still connects this catch to its thrower — synced so the renderer draws
     *  the green rope back to that player's hand (see {@code RopeRenderer}). */
    private static final EntityDataAccessor<Boolean> DATA_TETHERED =
        SynchedEntityData.defineId(SpearedFishEntity.class, EntityDataSerializers.BOOLEAN);
    /** The thrower (rope holder) — synced so the client can resolve their hand for the rope. */
    private static final EntityDataAccessor<Optional<UUID>> DATA_OWNER =
        SynchedEntityData.defineId(SpearedFishEntity.class, EntityDataSerializers.OPTIONAL_UUID);
    /** The thrower's network entity id — synced so the CLIENT can resolve the owner (player OR a citizen
     *  NPC) via {@code level.getEntity(int)} to draw the rope back to its hand. {@code -1} = none. UUID
     *  alone isn't enough: the client can only look up players by UUID, not citizens. */
    private static final EntityDataAccessor<Integer> DATA_OWNER_ENTITY_ID =
        SynchedEntityData.defineId(SpearedFishEntity.class, EntityDataSerializers.INT);
    /** Being reeled in — SYNCED so the client homes the catch too (otherwise the client keeps
     *  floating it and the pull-in never shows). */
    private static final EntityDataAccessor<Boolean> DATA_REELING =
        SynchedEntityData.defineId(SpearedFishEntity.class, EntityDataSerializers.BOOLEAN);
    /** Flight heading/pitch the spear had when it pierced the fish — synced so the renderer angles the
     *  whole speared-fish the way it actually struck, not a fixed planted pose. */
    private static final EntityDataAccessor<Float> DATA_PIERCE_YAW =
        SynchedEntityData.defineId(SpearedFishEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Float> DATA_PIERCE_PITCH =
        SynchedEntityData.defineId(SpearedFishEntity.class, EntityDataSerializers.FLOAT);

    /** Speed (blocks/tick) the catch flies back to the owner while being reeled in. */
    private static final double REEL_SPEED = 1.2;

    /** Server-only: the exact items handed over on pickup (spear copy + the fish's drops). */
    private final List<ItemStack> payload = new ArrayList<>();
    private int pickupDelay;
    /** Once we've snapped to the water surface, don't keep re-snapping every tick. */
    private boolean snappedToSurface;

    public SpearedFishEntity(EntityType<? extends SpearedFishEntity> type, Level level) {
        super(type, level);
    }

    /** Build a catch at {@code (x,y,z)} carrying the spear, the fish's registry id/variant, and the
     *  fish's drops. The payload granted on pickup = the spear + every (non-empty) drop. */
    public SpearedFishEntity(Level level, double x, double y, double z, ItemStack spear,
                             String fishType, int fishVariant, List<ItemStack> drops) {
        this(BannerboundAntiquity.SPEARED_FISH.get(), level);
        this.setPos(x, y, z);
        this.entityData.set(DATA_SPEAR, spear.copy());
        this.entityData.set(DATA_FISH_TYPE, fishType);
        this.entityData.set(DATA_FISH_VARIANT, fishVariant);
        this.payload.add(spear.copy());
        for (ItemStack drop : drops) {
            if (!drop.isEmpty()) {
                this.payload.add(drop.copy());
            }
        }
        this.pickupDelay = DEFAULT_PICKUP_DELAY;
        // Heading is set from the pierce direction via setPierce(); default 0 until then.
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        builder.define(DATA_SPEAR, new ItemStack(BannerboundAntiquity.WOODEN_SPEAR.get()));
        builder.define(DATA_FISH_TYPE, "minecraft:cod");
        builder.define(DATA_FISH_VARIANT, 0);
        builder.define(DATA_TETHERED, false);
        builder.define(DATA_OWNER, Optional.empty());
        builder.define(DATA_OWNER_ENTITY_ID, -1);
        builder.define(DATA_REELING, false);
        builder.define(DATA_PIERCE_YAW, 0.0F);
        builder.define(DATA_PIERCE_PITCH, 0.0F);
    }

    /** Orient the catch the way the spear was travelling when it pierced the fish. */
    public void setPierce(float yaw, float pitch) {
        this.entityData.set(DATA_PIERCE_YAW, yaw);
        this.entityData.set(DATA_PIERCE_PITCH, pitch);
        this.setYRot(yaw);
    }

    public float getPierceYaw() {
        return this.entityData.get(DATA_PIERCE_YAW);
    }

    public float getPiercePitch() {
        return this.entityData.get(DATA_PIERCE_PITCH);
    }

    public boolean isReeling() {
        return this.entityData.get(DATA_REELING);
    }

    /** Mark this catch as rope-tethered to {@code owner} (the thrower) — draws the rope + reelable.
     *  Stores both the UUID (server reel + NBT) and the live entity id (client rope resolution, which
     *  needs an id to find a citizen NPC owner the client can't look up by UUID). */
    public void setTether(Entity owner) {
        this.entityData.set(DATA_TETHERED, true);
        this.entityData.set(DATA_OWNER, Optional.ofNullable(owner == null ? null : owner.getUUID()));
        this.entityData.set(DATA_OWNER_ENTITY_ID, owner == null ? -1 : owner.getId());
    }

    /** True if a rope still connects this catch to its thrower. */
    public boolean isTethered() {
        return this.entityData.get(DATA_TETHERED);
    }

    /** The rope holder's UUID, if tethered. */
    public Optional<UUID> getOwnerUUID() {
        return this.entityData.get(DATA_OWNER);
    }

    /** True if {@code player} is the thrower this catch is tethered to. */
    public boolean isTetheredTo(Player player) {
        return isTethered() && getOwnerUUID().map(id -> id.equals(player.getUUID())).orElse(false);
    }

    /** Begin reeling this catch back to its thrower (synced so the client shows the pull-in). */
    public void startReeling() {
        this.entityData.set(DATA_REELING, true);
    }

    /** Resolve the tether owner as any entity — a player (the original feature) OR a citizen NPC (the
     *  spear-fisher reusing this catch). Server-side uses the full entity lookup so a {@code CitizenEntity}
     *  owner resolves; client-side only players are needed (for the rope render) so a player lookup is fine. */
    private Entity getOwnerEntity() {
        // Prefer the synced entity id — it resolves on BOTH sides (incl. a citizen NPC on the client,
        // which can't be found by UUID). Fall back to the UUID for older catches / post-reload.
        int eid = this.entityData.get(DATA_OWNER_ENTITY_ID);
        if (eid != -1) {
            Entity e = this.level().getEntity(eid);
            if (e != null && e.isAlive()) return e;
        }
        Optional<UUID> id = getOwnerUUID();
        if (id.isEmpty()) return null;
        if (this.level() instanceof ServerLevel sl) return sl.getEntity(id.get());
        return this.level().getPlayerByUUID(id.get());
    }

    /** The tether owner (player or citizen) resolved for rendering the rope, or null. */
    public Entity getTetherOwner() {
        return getOwnerEntity();
    }

    /** The spear item — for the renderer (and the first payload entry). */
    public ItemStack getSpearItem() {
        return this.entityData.get(DATA_SPEAR);
    }

    /** Registry id of the impaled fish's EntityType (e.g. "minecraft:salmon"). */
    public String getFishType() {
        return this.entityData.get(DATA_FISH_TYPE);
    }

    /** Tropical-fish packed variant (0 if not a tropical fish). */
    public int getFishVariant() {
        return this.entityData.get(DATA_FISH_VARIANT);
    }

    @Override
    public void tick() {
        super.tick();
        // Capture last-tick position for smooth client interpolation (we move below).
        this.xo = this.getX();
        this.yo = this.getY();
        this.zo = this.getZ();
        if (this.pickupDelay > 0) {
            this.pickupDelay--;
        }

        if (isReeling() && reelTowardOwner()) {
            return; // being pulled in (both sides home so the client shows it) — skip float/bob
        }

        Vec3 velocity = this.getDeltaMovement();
        BlockPos pos = this.blockPosition();
        boolean inWater = this.level().getFluidState(pos).is(FluidTags.WATER);
        if (inWater) {
            boolean submerged = this.level().getFluidState(pos.above()).is(FluidTags.WATER);
            if (submerged) {
                // Buoyant: a speared fish floats — rise toward the surface, dampening drift.
                this.snappedToSurface = false;
                this.setDeltaMovement(velocity.x * 0.85, 0.08, velocity.z * 0.85);
            } else {
                // Top water block reached → settle just UNDER the waterline (SURFACE_SINK) so the
                // bob stays inside the water block and never re-triggers the splash sound.
                double surfaceY = Math.floor(this.getY()) + 1.0;
                double restY = surfaceY - SURFACE_SINK;
                if (!this.snappedToSurface) {
                    this.snappedToSurface = true; // surfaced once → splash, then stay quiet
                    if (!this.level().isClientSide) {
                        this.level().playSound(null, this.getX(), this.getY(), this.getZ(),
                            SoundEvents.FISHING_BOBBER_SPLASH, SoundSource.NEUTRAL, 0.3F,
                            1.0F + (this.random.nextFloat() - this.random.nextFloat()) * 0.4F);
                    }
                }
                // Spring toward the rest line + a gentle sin-bob; dampen horizontal drift.
                double spring = (restY - this.getY()) * 0.2;
                double bob = Math.sin(this.tickCount * 0.1) * 0.008;
                this.setDeltaMovement(velocity.x * 0.6, spring + bob, velocity.z * 0.6);
                if (this.level() instanceof ServerLevel serverLevel) {
                    spawnSurfaceBubbles(serverLevel, surfaceY);
                }
            }
        } else {
            this.snappedToSurface = false; // out of water (e.g. on land) → fall and rest like an item
            this.setDeltaMovement(velocity.x * 0.98, velocity.y - GRAVITY, velocity.z * 0.98);
        }
        this.move(MoverType.SELF, this.getDeltaMovement());
        if (this.onGround()) {
            // Rest like a dropped item on land — kill horizontal slide.
            Vec3 grounded = this.getDeltaMovement();
            this.setDeltaMovement(grounded.x * 0.6, grounded.y, grounded.z * 0.6);
        }

        if (!this.level().isClientSide) {
            if (this.pickupDelay == 0) {
                tryPickup();
            }
            if (this.tickCount >= Config.SPEAR_CATCH_LIFETIME_TICKS.get()) {
                this.discard(); // don't litter forever
            }
        }
    }

    /**
     * Home toward the thrower. Runs on BOTH sides — the client moves the catch so the pull-in is
     * visible (it would otherwise keep floating it); only the server grants the catch + ends the
     * reel on arrival. Returns false (so the caller falls back to float physics) if there's no owner.
     */
    private boolean reelTowardOwner() {
        Entity owner = getOwnerEntity();
        if (owner == null || !owner.isAlive()) {
            if (!this.level().isClientSide) {
                this.entityData.set(DATA_REELING, false); // lost the owner → resume floating
            }
            return false;
        }
        Vec3 toOwner = owner.position().add(0.0, owner.getBbHeight() * 0.5, 0.0).subtract(this.position());
        if (!this.level().isClientSide) {
            this.pickupDelay = 0;
            if (toOwner.length() < 1.2) {
                // Player throw → straight to inventory (original feature). Spear-fisher NPC → deposit
                // the fish into its job drop-off. Any other owner type: just stop reeling and float.
                if (owner instanceof Player player) {
                    grantTo(player);
                } else if (owner instanceof com.bannerbound.core.entity.CitizenEntity citizen) {
                    grantToDepot(citizen);
                } else {
                    this.entityData.set(DATA_REELING, false);
                    return false;
                }
                return true;
            }
        }
        Vec3 velocity = toOwner.normalize().scale(REEL_SPEED);
        this.setDeltaMovement(velocity);
        this.move(MoverType.SELF, velocity);
        return true;
    }

    /** The usual floating-bobber surface fizz — a few bubbles (and the odd splash) at the waterline. */
    private void spawnSurfaceBubbles(ServerLevel level, double surfaceY) {
        if (this.tickCount % 5 != 0) {
            return;
        }
        level.sendParticles(ParticleTypes.BUBBLE, this.getX(), surfaceY, this.getZ(),
            2, 0.15, 0.0, 0.15, 0.0);
        if (this.random.nextFloat() < 0.3F) {
            level.sendParticles(ParticleTypes.SPLASH, this.getX(), surfaceY, this.getZ(),
                2, 0.15, 0.0, 0.15, 0.1);
        }
    }

    /** Walk-over pickup: a nearby living player collects the whole catch (server-authoritative). */
    private void tryPickup() {
        for (Player player : this.level().getEntitiesOfClass(Player.class,
                this.getBoundingBox().inflate(0.6, 0.4, 0.6))) {
            if (player.isAlive() && !player.isSpectator()) {
                grantTo(player);
                return;
            }
        }
    }

    private void grantTo(Player player) {
        for (ItemStack stack : this.payload) {
            ItemStack give = stack.copy();
            if (!player.getInventory().add(give)) {
                player.drop(give, false); // inventory full → drop at the player so nothing's lost
            }
        }
        this.level().playSound(null, this.getX(), this.getY(), this.getZ(),
            SoundEvents.ITEM_PICKUP, SoundSource.PLAYERS, 0.2F,
            ((this.random.nextFloat() - this.random.nextFloat()) * 0.7F + 1.0F) * 2.0F);
        this.discard();
    }

    /**
     * Reel-in arrival for a spear-fisher NPC: deposit the fish drops into the citizen's job drop-off.
     * Skips {@code payload[0]} — that's the spear, which for an NPC is a reusable equipped tool that
     * never left {@code getJobTool()} (the thrown spear was only a copy). Depositing it would duplicate
     * a spear every catch, so only the fish drops are stored; overflow spills at the catch.
     */
    private void grantToDepot(com.bannerbound.core.entity.CitizenEntity citizen) {
        net.minecraft.world.Container depot = com.bannerbound.core.entity.DropOffContainers.resolveJobDepot(citizen);
        int fishCount = 0;
        for (int i = 1; i < this.payload.size(); i++) {   // i=0 is the spear (the reusable tool) — skip it
            ItemStack give = this.payload.get(i).copy();
            if (give.isEmpty()) continue;
            fishCount += give.getCount();
            ItemStack leftover = depot == null ? give
                : com.bannerbound.core.entity.DropOffContainers.insert(depot, give);
            if (!leftover.isEmpty()) this.spawnAtLocation(leftover);
        }
        // Credit the "fishing" food-production source (mirrors the rod FisherWorkGoal) so the Starvation
        // crisis's spear-fishing path and the Town Hall food stats see spear-caught fish as income —
        // without this, food_sustained's "is fishing actively producing?" check never trips for spears.
        com.bannerbound.core.api.settlement.Settlement settlement = citizen.getSettlement();
        if (settlement != null && fishCount > 0) {
            settlement.addFoodProduced("fishing", fishCount);
        }
        this.level().playSound(null, this.getX(), this.getY(), this.getZ(),
            SoundEvents.ITEM_PICKUP, SoundSource.PLAYERS, 0.2F,
            ((this.random.nextFloat() - this.random.nextFloat()) * 0.7F + 1.0F) * 2.0F);
        this.discard();
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
        this.entityData.set(DATA_SPEAR, loadStack(tag, "Spear"));
        this.entityData.set(DATA_FISH_TYPE, tag.getString("FishType"));
        this.entityData.set(DATA_FISH_VARIANT, tag.getInt("FishVariant"));
        this.entityData.set(DATA_TETHERED, tag.getBoolean("Tethered"));
        this.entityData.set(DATA_OWNER,
            tag.hasUUID("Owner") ? Optional.of(tag.getUUID("Owner")) : Optional.empty());
        this.pickupDelay = tag.getInt("PickupDelay");
        this.payload.clear();
        ListTag list = tag.getList("Payload", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            ItemStack.parse(this.registryAccess(), list.getCompound(i)).ifPresent(this.payload::add);
        }
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
        ItemStack spear = getSpearItem();
        if (!spear.isEmpty()) {
            tag.put("Spear", spear.save(this.registryAccess(), new CompoundTag()));
        }
        tag.putString("FishType", getFishType());
        tag.putInt("FishVariant", getFishVariant());
        tag.putBoolean("Tethered", isTethered());
        getOwnerUUID().ifPresent(id -> tag.putUUID("Owner", id));
        tag.putInt("PickupDelay", this.pickupDelay);
        ListTag list = new ListTag();
        for (ItemStack stack : this.payload) {
            if (!stack.isEmpty()) {
                list.add(stack.save(this.registryAccess(), new CompoundTag()));
            }
        }
        tag.put("Payload", list);
    }

    private ItemStack loadStack(CompoundTag tag, String key) {
        if (tag.contains(key)) {
            return ItemStack.parse(this.registryAccess(), tag.getCompound(key)).orElse(ItemStack.EMPTY);
        }
        return ItemStack.EMPTY;
    }

    @Override
    public boolean isAttackable() {
        return false; // it's a pickup, not a punching bag
    }
}
