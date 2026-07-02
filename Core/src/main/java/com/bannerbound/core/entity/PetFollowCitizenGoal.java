package com.bannerbound.core.entity;

import java.util.EnumSet;
import java.util.UUID;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.animal.Wolf;

/**
 * Makes a citizen-bonded {@link Wolf} follow its owning {@link CitizenEntity}. Mirrors vanilla's
 * follow-owner goal, but the owner is a citizen (not a player), so vanilla's player-keyed lookup
 * never fires for these pets — this fills that gap.
 *
 * <p>The owner is resolved by UUID each time the goal starts (not held as a hard reference) so
 * the goal survives a reload: {@code PetEvents} re-attaches it to bonded wolves on entity-join,
 * and the lookup just re-finds the (loaded) citizen. If the owner is offline/unloaded or dead,
 * the goal idles.
 */
@ApiStatus.Internal
public class PetFollowCitizenGoal extends Goal {
    private static final double START_FOLLOW_DIST_SQ = 10.0 * 10.0;
    private static final double STOP_FOLLOW_DIST = 3.0;
    private static final double TELEPORT_DIST_SQ = 24.0 * 24.0;
    private static final double SPEED = 1.1;
    private static final int REPATH_INTERVAL = 10;

    private final Wolf pet;
    @Nullable private CitizenEntity owner;
    private int repath;

    public PetFollowCitizenGoal(Wolf pet) {
        this.pet = pet;
        this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Nullable
    private CitizenEntity resolveOwner() {
        if (!(pet.level() instanceof ServerLevel sl)) return null;
        UUID id = pet.getOwnerUUID();
        if (id == null) return null;
        return sl.getEntity(id) instanceof CitizenEntity c ? c : null;
    }

    @Override
    public boolean canUse() {
        if (pet.isOrderedToSit()) return false;
        CitizenEntity o = resolveOwner();
        if (o == null || !o.isAlive()) return false;
        if (pet.distanceToSqr(o) < START_FOLLOW_DIST_SQ) return false;
        this.owner = o;
        return true;
    }

    @Override
    public boolean canContinueToUse() {
        if (pet.getNavigation().isDone()) return false;
        if (pet.isOrderedToSit()) return false;
        return owner != null && owner.isAlive()
            && pet.distanceToSqr(owner) > STOP_FOLLOW_DIST * STOP_FOLLOW_DIST;
    }

    @Override
    public void start() {
        repath = 0;
    }

    @Override
    public void stop() {
        owner = null;
        pet.getNavigation().stop();
    }

    @Override
    public void tick() {
        if (owner == null) return;
        pet.getLookControl().setLookAt(owner, 10.0F, (float) pet.getMaxHeadXRot());
        if (--repath > 0) return;
        repath = REPATH_INTERVAL;
        // Teleport if hopelessly far (path budget can't keep up across long gaps).
        if (pet.distanceToSqr(owner) >= TELEPORT_DIST_SQ) {
            pet.moveTo(owner.getX(), owner.getY(), owner.getZ(), pet.getYRot(), pet.getXRot());
            CitizenEntity.tagDeliberateTeleport(pet);
            pet.getNavigation().stop();
            return;
        }
        pet.getNavigation().moveTo(owner, SPEED);
    }
}
