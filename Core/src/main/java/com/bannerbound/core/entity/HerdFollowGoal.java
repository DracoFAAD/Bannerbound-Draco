package com.bannerbound.core.entity;

import java.util.EnumSet;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

import com.bannerbound.core.BannerboundCore;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.animal.Animal;

/**
 * Makes a herded animal FOLLOW the herder that claims it, exactly like vanilla {@code TemptGoal} makes an
 * animal follow a player holding its food — using the animal's OWN navigation. This is the whole design:
 * we don't overwrite or replace the animal's pathfinding (every attempt to do that failed), we just make the
 * animal WANT to walk to the herder and let its own vanilla nav do the work. A player proved a wheat-tempted
 * cow walks straight through an open rope gate into a pen; the herder is simply a stand-in for that player
 * (vanilla TemptGoal only targets real players, so we re-create it here pointed at the herder).
 *
 * <p>Lives in the animal's goalSelector (added on claim) with {@code MOVE}+{@code LOOK} so it beats the
 * wander goals. Yields the instant the animal isn't claimed, so a released / penned animal behaves normally.</p>
 */
@ApiStatus.Internal
public class HerdFollowGoal extends Goal {
    private static final double STOP_DIST_SQ = 2.5 * 2.5;   // within ~2.5 blocks of the herder → close enough
    private static final double SPEED = 1.15;               // a gentle, tempt-like follow pace
    private static final int REPATH_INTERVAL = 5;

    private final Animal animal;
    @Nullable private CitizenEntity herder;
    private int repath;

    public HerdFollowGoal(Animal animal) {
        this.animal = animal;
        this.setFlags(EnumSet.of(Flag.MOVE, Flag.LOOK));
    }

    @Nullable
    private CitizenEntity resolveHerder() {
        if (!(animal.level() instanceof ServerLevel sl)) return null;
        Integer id = animal.getExistingDataOrNull(BannerboundCore.HERDED_BY.get());
        if (id == null || id == 0) return null;
        return sl.getEntity(id) instanceof CitizenEntity c && c.isAlive() ? c : null;
    }

    @Override
    public boolean canUse() {
        this.herder = resolveHerder();
        return herder != null;
    }

    @Override
    public boolean canContinueToUse() {
        this.herder = resolveHerder();
        return herder != null;
    }

    @Override
    public boolean requiresUpdateEveryTick() {
        return true;
    }

    @Override
    public void start() {
        repath = 0;
    }

    @Override
    public void stop() {
        // Freed (herder gone) → make sure we don't linger claimed with no herder.
        if (herder == null) animal.removeData(BannerboundCore.HERDED_BY.get());
        herder = null;
        animal.getNavigation().stop();
    }

    @Override
    public void tick() {
        if (herder == null) return;
        animal.getLookControl().setLookAt(herder, 30.0F, (float) animal.getMaxHeadXRot());
        if (animal.distanceToSqr(herder) <= STOP_DIST_SQ) {
            animal.getNavigation().stop();   // arrived — wait by the herder (it releases us once we're inside)
            return;
        }
        if (--repath > 0) return;
        repath = REPATH_INTERVAL;
        animal.getNavigation().moveTo(herder, SPEED);   // the animal's OWN vanilla nav — walks through the gate
    }
}
