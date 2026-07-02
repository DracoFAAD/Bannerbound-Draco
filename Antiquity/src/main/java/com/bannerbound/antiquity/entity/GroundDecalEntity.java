package com.bannerbound.antiquity.entity;

import java.util.OptionalDouble;

import com.bannerbound.antiquity.BannerboundAntiquity;
import com.bannerbound.antiquity.Config;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.syncher.EntityDataAccessor;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * A purely-cosmetic ground decal for the hunting tracker — either a blood splat (a bleeding wound)
 * or a footprint track (a walking animal). It lies flat on the ground and fades after a lifetime.
 * Each decal remembers the {@code heading} the animal was moving; right-clicking it stamps a
 * {@code revealTick}, and the renderer draws a translucent white "search cone" pointing that way
 * (Hunter: Call of the Wild–style), fading client-side over ~3s and re-armed by another click.
 *
 * <p>Decals never float: on spawn they clamp down to the top of the nearest solid block in their
 * column (and simply don't spawn if there's none within range). Not saved to disk; no collision or
 * gravity, but pickable so the cursor can right-click it.
 */
public class GroundDecalEntity extends Entity {
    public static final int KIND_BLOOD = 0;
    public static final int KIND_TRACK = 1;
    private static final int MAX_DROP = 16; // how far down we'll search for a surface
    private static final int HEADING_SETTLE_TICKS = 40; // watch ~2s of travel for the true bearing
    private static final double MIN_TRACK_DIST = 0.5;   // animal must move this far to read a direction

    // Server-side only: the animal we read the heading from for the first couple of seconds. Mob
    // facing twitches/spins, so we instead track its net displacement away from the decal.
    private int sourceAnimalId = -1;

    private static final EntityDataAccessor<Integer> DATA_KIND =
        SynchedEntityData.defineId(GroundDecalEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<Integer> DATA_VARIANT =
        SynchedEntityData.defineId(GroundDecalEntity.class, EntityDataSerializers.INT);
    private static final EntityDataAccessor<String> DATA_SPECIES =
        SynchedEntityData.defineId(GroundDecalEntity.class, EntityDataSerializers.STRING);
    private static final EntityDataAccessor<Float> DATA_DIRECTION =
        SynchedEntityData.defineId(GroundDecalEntity.class, EntityDataSerializers.FLOAT);
    private static final EntityDataAccessor<Integer> DATA_REVEAL_TICK =
        SynchedEntityData.defineId(GroundDecalEntity.class, EntityDataSerializers.INT);
    // Grouping key so the tracker can tell which tracks belong to the same animal: the source
    // animal's entity id (stable for its lifetime; unique among loaded entities). -1 = unknown.
    private static final EntityDataAccessor<Integer> DATA_GROUP_ID =
        SynchedEntityData.defineId(GroundDecalEntity.class, EntityDataSerializers.INT);

    public GroundDecalEntity(EntityType<? extends GroundDecalEntity> type, Level level) {
        super(type, level);
        this.noPhysics = true;
        this.setNoGravity(true);
    }

    /** Blood splat from {@code source}: {@code variant} seeds the texture pick + rotation. */
    public static void spawnBlood(Level level, double x, double y, double z, int variant, Entity source) {
        spawn(level, x, y, z, source, KIND_BLOOD, variant, "");
    }

    /** Footprint track for {@code species} (e.g. "cow") left by {@code source}. */
    public static void spawnTrack(Level level, double x, double y, double z, String species, Entity source) {
        spawn(level, x, y, z, source, KIND_TRACK, 0, species);
    }

    private static void spawn(Level level, double x, double y, double z, Entity source,
                              int kind, int variant, String species) {
        OptionalDouble surface = groundSurface(level, x, y, z);
        if (surface.isEmpty()) {
            return; // no ground beneath — don't leave a floating decal
        }
        GroundDecalEntity decal = new GroundDecalEntity(BannerboundAntiquity.GROUND_DECAL.get(), level);
        decal.setPos(x, surface.getAsDouble() + 0.02, z);
        decal.entityData.set(DATA_KIND, kind);
        decal.entityData.set(DATA_VARIANT, variant);
        decal.entityData.set(DATA_SPECIES, species);
        decal.entityData.set(DATA_DIRECTION, source.getYRot()); // provisional; refined from travel over ~2s
        decal.entityData.set(DATA_GROUP_ID, source.getId()); // groups every track left by this one animal
        decal.sourceAnimalId = source.getId();
        level.addFreshEntity(decal);
    }

    /** Top surface Y of the nearest solid block at or below {@code startY} in this column. */
    private static OptionalDouble groundSurface(Level level, double x, double startY, double z) {
        BlockPos.MutableBlockPos pos =
            new BlockPos.MutableBlockPos(Mth.floor(x), Mth.floor(startY), Mth.floor(z));
        for (int i = 0; i <= MAX_DROP; i++) {
            BlockState state = level.getBlockState(pos);
            VoxelShape shape = state.getCollisionShape(level, pos);
            if (!shape.isEmpty()) {
                return OptionalDouble.of(pos.getY() + shape.max(Direction.Axis.Y));
            }
            pos.move(Direction.DOWN);
        }
        return OptionalDouble.empty();
    }

    public int getKind() {
        return this.entityData.get(DATA_KIND);
    }

    public int getVariant() {
        return this.entityData.get(DATA_VARIANT);
    }

    public String getSpecies() {
        return this.entityData.get(DATA_SPECIES);
    }

    /** Yaw (degrees) the animal was heading when it left this decal. */
    public float getHeading() {
        return this.entityData.get(DATA_DIRECTION);
    }

    /** Entity tick at which the cone was last revealed, or -1 if never. Drives the client-side fade. */
    public int getRevealTick() {
        return this.entityData.get(DATA_REVEAL_TICK);
    }

    /** Id of the animal that left this decal — shared by every track from the same animal, -1 if unknown. */
    public int getGroupId() {
        return this.entityData.get(DATA_GROUP_ID);
    }

    @Override
    protected void defineSynchedData(SynchedEntityData.Builder builder) {
        builder.define(DATA_KIND, KIND_BLOOD);
        builder.define(DATA_VARIANT, 0);
        builder.define(DATA_SPECIES, "");
        builder.define(DATA_DIRECTION, 0.0F);
        builder.define(DATA_REVEAL_TICK, -1);
        builder.define(DATA_GROUP_ID, -1);
    }

    /**
     * Right-click to "examine the track": (re)arm the direction cone at full opacity. On the client,
     * examining a footprint also lights up every other active track left by the same animal in cyan —
     * but only once the player's settlement has researched {@code hunting_instincts} (the highlight is a
     * purely-cosmetic client effect, so it's triggered and gated client-side).
     */
    @Override
    public InteractionResult interact(Player player, InteractionHand hand) {
        if (!this.level().isClientSide) {
            this.entityData.set(DATA_REVEAL_TICK, this.tickCount);
        } else if (getKind() == KIND_TRACK
                && net.neoforged.fml.loading.FMLEnvironment.dist.isClient()) {
            com.bannerbound.antiquity.client.FootprintHighlight.examine(this);
        }
        return InteractionResult.sidedSuccess(this.level().isClientSide);
    }

    @Override
    public void tick() {
        super.tick(); // advances tickCount (renderer uses it for both the lifetime fade and cone fade)
        if (this.level() instanceof ServerLevel server) {
            settleHeading(server);
            int lifetime = getKind() == KIND_TRACK
                ? Config.FOOTPRINT_LIFETIME_TICKS.get()
                : Config.BLOOD_SPLAT_LIFETIME_TICKS.get();
            if (this.tickCount >= lifetime) {
                this.discard();
            }
        }
    }

    /**
     * For the first {@link #HEADING_SETTLE_TICKS} ticks, point the heading along the animal's net
     * displacement away from the decal (where it actually went), not its twitchy instantaneous
     * facing — then lock it. Mob yaw spins on sharp turns; the decal→animal vector doesn't.
     */
    private void settleHeading(ServerLevel server) {
        if (sourceAnimalId < 0) {
            return;
        }
        Entity source = server.getEntity(sourceAnimalId);
        if (source == null || !source.isAlive()) {
            sourceAnimalId = -1; // lost it — keep the last good heading
            return;
        }
        double dx = source.getX() - this.getX();
        double dz = source.getZ() - this.getZ();
        if (dx * dx + dz * dz >= MIN_TRACK_DIST * MIN_TRACK_DIST) {
            // Yaw whose forward vector (-sin, cos) points from the decal toward the animal.
            this.entityData.set(DATA_DIRECTION, (float) (Mth.atan2(-dx, dz) * (180.0 / Math.PI)));
        }
        if (this.tickCount >= HEADING_SETTLE_TICKS) {
            sourceAnimalId = -1; // settle: lock the heading in
        }
    }

    @Override
    public boolean isPickable() {
        return !this.isRemoved(); // targetable by the cursor so it can be right-clicked
    }

    @Override
    public boolean isAttackable() {
        return false; // left-click shouldn't destroy a decal
    }

    @Override
    public boolean shouldBeSaved() {
        return false; // ephemeral decoration — gone on reload
    }

    @Override
    protected void readAdditionalSaveData(CompoundTag tag) {
        this.entityData.set(DATA_KIND, tag.getInt("Kind"));
        this.entityData.set(DATA_VARIANT, tag.getInt("Variant"));
        this.entityData.set(DATA_SPECIES, tag.getString("Species"));
        this.entityData.set(DATA_DIRECTION, tag.getFloat("Direction"));
        this.entityData.set(DATA_REVEAL_TICK, tag.contains("RevealTick") ? tag.getInt("RevealTick") : -1);
        this.entityData.set(DATA_GROUP_ID, tag.contains("GroupId") ? tag.getInt("GroupId") : -1);
    }

    @Override
    protected void addAdditionalSaveData(CompoundTag tag) {
        tag.putInt("Kind", getKind());
        tag.putInt("Variant", getVariant());
        tag.putString("Species", getSpecies());
        tag.putFloat("Direction", getHeading());
        tag.putInt("RevealTick", getRevealTick());
        tag.putInt("GroupId", getGroupId());
    }
}
