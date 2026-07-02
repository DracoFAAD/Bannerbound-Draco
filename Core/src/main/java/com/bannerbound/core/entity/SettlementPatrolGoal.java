package com.bannerbound.core.entity;

import org.jetbrains.annotations.ApiStatus;

import javax.annotation.Nullable;

import com.bannerbound.core.api.settlement.Settlement;

import net.minecraft.core.BlockPos;
import net.minecraft.util.RandomSource;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.ai.navigation.PathNavigation;
import net.minecraft.world.entity.ai.util.DefaultRandomPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.phys.Vec3;

import java.util.EnumSet;

/**
 * Picks a random walk target inside the citizen's settlement claimed chunks and strolls there.
 * Falls back to a stroll toward the town hall when no in-territory target can be found, which
 * also serves as the "come home" behavior if the citizen got pushed out.
 */
@ApiStatus.Internal
public class SettlementPatrolGoal extends Goal {
    private static final int TARGET_TRIES = 16;
    private static final int IDLE_TICKS_BETWEEN_PATROLS = 60;
    /** Anchor the idle stroll on the outpost only once the worker is within this far of it; beyond
     *  it, {@link OutpostCommuteGoal} owns the long walk and patrol stays out of the way. Must
     *  comfortably exceed the commute's hand-off distance so there's no dead zone between them. */
    private static final double OUTPOST_IDLE_RADIUS = 24.0;

    private final CitizenEntity citizen;
    private final double speedModifier;
    private double targetX, targetY, targetZ;
    private int cooldown;

    public SettlementPatrolGoal(CitizenEntity citizen, double speedModifier) {
        this.citizen = citizen;
        this.speedModifier = speedModifier;
        this.setFlags(EnumSet.of(Flag.MOVE));
    }

    @Override
    public boolean canUse() {
        // Activation tier: with no player nearby, don't pick targets or pathfind — just idle. The
        // citizen stays a real loaded entity; it resumes patrolling the moment a player approaches.
        if (!citizen.isAiActive()) return false;
        if (cooldown > 0) {
            cooldown--;
            return false;
        }
        // Stagger A*: a new patrol segment (and its pathfind) only starts on this citizen's think
        // tick, so the fleet's path searches spread across ticks instead of clustering into a spike.
        if (!citizen.isThinkTick()) return false;
        // Pick order depends on government:
        //   - Anarchy (NONE): wander first, fall back to town hall. Citizens don't loiter on
        //     the campfire — they roam the territory (or near it) and only swing back to the
        //     campfire incidentally, mostly when AnarchyWorkGoal delivers loot.
        //   - Post-laws (Council / Chiefdom): loiter near the campfire / home as the
        //     primary, fall back to wandering if no anchor. Matches the existing "village
        //     square" feel for organised settlements.
        Settlement s = citizen.getSettlement();
        // Outpost workers live ON SITE: idle strolls anchor on the outpost, never the settlement —
        // an assigned miner spends the whole rotation out there (work, idle, sleep) instead of
        // commuting home between shifts. Falls through to normal patrol once the site is gone.
        Vec3 outpost = outpostAnchorVec(s);
        if (outpost != null) {
            // Far from the site, the long walk belongs to OutpostCommuteGoal (priority 2) — don't
            // issue a doomed cross-map patrol target or, worse, wander the worker back toward the
            // town hall. Just idle here until the commute brings them in; only anchor the idle
            // stroll once they're actually ON SITE.
            if (citizen.distanceToSqr(outpost.x, outpost.y, outpost.z)
                    > OUTPOST_IDLE_RADIUS * OUTPOST_IDLE_RADIUS) {
                return false;
            }
            // Tight loiter (±3), not the ±8 town stroll: an outpost worker waiting out the vein's
            // regen wave (up to ~8000 ticks of idle) should read as "standing at its post by the
            // rock", not wandering off across the hillside — which looked like it had abandoned the
            // job. The work goal reclaims it the instant a face refreshes.
            RandomSource orng = citizen.getRandom();
            this.targetX = outpost.x + orng.nextDouble() * 6 - 3;
            this.targetY = outpost.y;
            this.targetZ = outpost.z + orng.nextDouble() * 6 - 3;
            return true;
        }
        boolean anarchy = s != null
            && s.governmentType() == Settlement.Government.NONE;
        Vec3 target;
        if (anarchy) {
            target = pickWanderTarget();
            if (target == null) target = pickTownHallFallback();
        } else {
            target = pickTownHallFallback();
            if (target == null) target = pickInTerritoryTarget();
        }
        if (target == null) {
            return false;
        }
        this.targetX = target.x;
        this.targetY = target.y;
        this.targetZ = target.z;
        return true;
    }

    /** Wide-area wander target for anarchy patrol — a random point within
     *  {@link #ANARCHY_WANDER_RADIUS} of the citizen's current position, with a soft pull
     *  back toward the town hall when the citizen has drifted far away (so they don't
     *  steadily migrate out of the territory over many trips). */
    @Nullable
    private Vec3 pickWanderTarget() {
        RandomSource rng = citizen.getRandom();
        Vec3 candidate = DefaultRandomPos.getPos(citizen, ANARCHY_WANDER_RADIUS, 4);
        if (candidate != null) {
            Vec3 home = townHallVec();
            if (home != null) {
                double distSq = candidate.distanceToSqr(home);
                if (distSq > ANARCHY_LEASH_RADIUS * ANARCHY_LEASH_RADIUS) {
                    // Citizen drifted too far — bias the next target HALF the way home so we
                    // don't snap them back instantly (looks unnatural) but they trend back.
                    double mx = (home.x - citizen.getX()) * 0.5 + rng.nextDouble() * 6 - 3;
                    double mz = (home.z - citizen.getZ()) * 0.5 + rng.nextDouble() * 6 - 3;
                    return citizen.position().add(mx, 0, mz);
                }
            }
            return candidate;
        }
        return null;
    }

    /** Wander step radius for anarchy patrol. ~16 blocks ≈ a chunk-wide step, much wider than
     *  the tight {@link #REST_RADIUS} cluster around the campfire. */
    private static final int ANARCHY_WANDER_RADIUS = 16;
    /** Soft leash distance from town hall — if a wander pick is further than this, the next
     *  target is biased halfway home to keep citizens roughly within the settlement area. */
    private static final double ANARCHY_LEASH_RADIUS = 32.0;

    @Override
    public void start() {
        PathNavigation nav = citizen.getNavigation();
        nav.moveTo(targetX, targetY, targetZ, speedModifier);
    }

    @Override
    public boolean canContinueToUse() {
        return !citizen.getNavigation().isDone();
    }

    @Override
    public void stop() {
        cooldown = IDLE_TICKS_BETWEEN_PATROLS + citizen.getRandom().nextInt(80);
    }

    @Nullable
    private Vec3 pickInTerritoryTarget() {
        Settlement s = citizen.getSettlement();
        RandomSource rng = citizen.getRandom();
        for (int i = 0; i < TARGET_TRIES; i++) {
            Vec3 candidate = DefaultRandomPos.getPos(citizen, 10, 4);
            if (candidate == null) continue;
            if (s == null) {
                return candidate;
            }
            long packed = new ChunkPos(BlockPos.containing(candidate)).toLong();
            if (s.claimedChunks().contains(packed)) {
                return candidate;
            }
            // Bias subsequent picks slightly toward the town hall when outside territory.
            if (i > TARGET_TRIES / 2) {
                Vec3 home = townHallVec();
                if (home != null) {
                    double mx = (home.x - citizen.getX()) * 0.5 + rng.nextDouble() * 4 - 2;
                    double mz = (home.z - citizen.getZ()) * 0.5 + rng.nextDouble() * 4 - 2;
                    return citizen.position().add(mx, 0, mz);
                }
            }
        }
        return null;
    }

    /** Base loiter radius around the campfire / town hall for a tiny settlement. */
    private static final double REST_RADIUS = 7.0;
    /** Cap on the population-scaled spread so even a huge settlement stays a believable town, not
     *  a continent-wide sprinkle. */
    private static final double MAX_SPREAD_RADIUS = 40.0;

    /** Loiter/spread radius around the town hall, GROWN with population: a Hearth keeps its cozy
     *  ±7 campfire cluster, but a 30-citizen Village fans out to ±~25 so they read as an ambient
     *  town instead of all jostling on the campfire. */
    private double spreadRadius() {
        Settlement s = citizen.getSettlement();
        int pop = (s == null) ? 0 : s.population();
        return Math.min(MAX_SPREAD_RADIUS, REST_RADIUS + pop * 0.6);
    }

    @Nullable
    private Vec3 pickTownHallFallback() {
        // Pregnant women loiter around home instead of the campfire — the player asked for it
        // explicitly and it matches the "nesting" intuition. Pregnant women without a home (rare
        // edge case) fall back to the town hall like everyone else.
        if (citizen.isPregnant()) {
            Vec3 homeVec = homeVecForCitizen();
            if (homeVec != null) {
                RandomSource rngHome = citizen.getRandom();
                double dxh = rngHome.nextDouble() * (REST_RADIUS * 2) - REST_RADIUS;
                double dzh = rngHome.nextDouble() * (REST_RADIUS * 2) - REST_RADIUS;
                return new Vec3(homeVec.x + dxh, homeVec.y, homeVec.z + dzh);
            }
        }
        Vec3 v = townHallVec();
        if (v == null) return null;
        RandomSource rng = citizen.getRandom();
        double r = spreadRadius();   // grows with population so big settlements fan out
        double dx = rng.nextDouble() * (r * 2) - r;
        double dz = rng.nextDouble() * (r * 2) - r;
        return new Vec3(v.x + dx, v.y, v.z + dz);
    }

    @Nullable
    private Vec3 townHallVec() {
        Settlement s = citizen.getSettlement();
        if (s == null || s.townHallPos() == null) return null;
        BlockPos thp = s.townHallPos();
        return new Vec3(thp.getX() + 0.5, thp.getY(), thp.getZ() + 0.5);
    }

    /** The citizen's outpost work site, while it's still a live working claim of its settlement —
     *  the patrol anchor for outpost-assigned workers. Null = patrol normally. */
    @Nullable
    private Vec3 outpostAnchorVec(Settlement s) {
        BlockPos site = citizen.getOutpostSite();
        if (site == null || s == null) return null;
        if (!s.workingClaims().contains(new net.minecraft.world.level.ChunkPos(site).toLong())) {
            return null;   // outpost fell / marker moved home — resume settlement patrol
        }
        return new Vec3(site.getX() + 0.5, site.getY(), site.getZ() + 0.5);
    }

    /** Centre of the citizen's home block, or {@code null} if homeless. Used by pregnant women
     *  to bias their loiter target away from the campfire. */
    @Nullable
    private Vec3 homeVecForCitizen() {
        Settlement s = citizen.getSettlement();
        if (s == null) return null;
        com.bannerbound.core.api.settlement.Home home = s.getHomeFor(citizen.getUUID());
        if (home == null) return null;
        BlockPos p = home.pos();
        return new Vec3(p.getX() + 0.5, p.getY(), p.getZ() + 0.5);
    }
}
