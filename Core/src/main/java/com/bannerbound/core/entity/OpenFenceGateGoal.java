package com.bannerbound.core.entity;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.Iterator;
import java.util.List;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.BannerboundCore;

import net.minecraft.core.BlockPos;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.entity.ai.goal.Goal;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.gameevent.GameEvent;
import net.minecraft.world.level.pathfinder.Path;
import net.minecraft.world.phys.AABB;

/**
 * Opens fence gates a moving citizen actually intends to cross, then closes them behind it — the
 * fence-gate counterpart of vanilla {@code OpenDoorGoal}. Handles vanilla AND modded gates (rope gate),
 * matched by the {@code minecraft:fence_gates} tag, and any number of gates per enclosure.
 * <p>
 * <b>Intent-based, not proximity-based.</b> A gate is opened only when the citizen's CURRENT PATH crosses
 * it (or it's right against it, a jam fallback) — NOT merely because the citizen is standing near it. This
 * is what stops a worker that rests/works a couple of blocks from a gate (e.g. the herder by its pen) from
 * holding it open so the penned animals wander out. A gate is also held open while a herded animal is
 * crossing it, so the herder's trailing flock isn't cut off. {@link CitizenNodeEvaluator} guarantees the
 * pathfinder routes through a closed gate; this goal makes it open by the time the citizen reaches it.
 * <p>
 * Flagless: never claims MOVE/LOOK, so it runs alongside the work and patrol goals.
 */
@ApiStatus.Internal
public class OpenFenceGateGoal extends Goal {
    /** Horizontal block radius scanned around the citizen for gates. */
    private static final int DETECT_RADIUS = 3;
    /** Squared distance within which a gate is "right at" the citizen (jam fallback / held while crossing). */
    private static final double AT_GATE_SQ = 2.25; // 1.5 blocks
    /** How many upcoming path nodes count as "intends to cross". */
    private static final int LOOKAHEAD = 8;
    private static final int TIMEOUT_TICKS = 400;

    private final CitizenEntity citizen;
    /** Gates this goal has opened and still needs to close once intent ends. */
    private final List<BlockPos> openedGates = new ArrayList<>();
    private int age;

    public OpenFenceGateGoal(CitizenEntity citizen) {
        this.citizen = citizen;
        this.setFlags(EnumSet.noneOf(Flag.class));
    }

    @Override
    public boolean canUse() {
        return hasGateToOpen();
    }

    @Override
    public boolean canContinueToUse() {
        if (age >= TIMEOUT_TICKS) return false;
        return !openedGates.isEmpty() || hasGateToOpen();
    }

    @Override
    public void start() {
        age = 0;
    }

    @Override
    public void tick() {
        age++;
        long now = citizen.level().getGameTime();
        BlockPos center = citizen.blockPosition();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        // Open every nearby gate the citizen currently intends to cross (handles gate clusters / multi-gate pens).
        for (int dx = -DETECT_RADIUS; dx <= DETECT_RADIUS; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -DETECT_RADIUS; dz <= DETECT_RADIUS; dz++) {
                    cursor.set(center.getX() + dx, center.getY() + dy, center.getZ() + dz);
                    BlockState state = citizen.level().getBlockState(cursor);
                    // Defer to a gate someone is actively HOLDING (e.g. a herder's pen gate) — fighting it
                    // every tick was the open/close flicker.
                    if (isOpenableGate(state) && !GateHolds.isHeld(cursor, now) && shouldBeOpen(cursor)) {
                        BlockPos gate = cursor.immutable();
                        setGate(gate, state, true);
                        if (!openedGates.contains(gate)) openedGates.add(gate);
                    }
                }
            }
        }
        // Close any gate we opened that's no longer wanted open (intent ended, citizen through, no flock at it).
        Iterator<BlockPos> it = openedGates.iterator();
        while (it.hasNext()) {
            BlockPos gate = it.next();
            BlockState state = citizen.level().getBlockState(gate);
            if (!isOpenableGate(state)) { it.remove(); continue; }
            if (GateHolds.isHeld(gate, now)) { it.remove(); continue; } // a holder owns it now — relinquish, don't close
            if (!shouldBeOpen(gate)) {
                setGate(gate, state, false);
                it.remove();
            }
        }
    }

    @Override
    public void stop() {
        for (BlockPos gate : openedGates) {
            BlockState state = citizen.level().getBlockState(gate);
            if (isOpenableGate(state)) setGate(gate, state, false);
        }
        openedGates.clear();
        age = 0;
    }

    /** A gate should be open iff the citizen's path crosses it, it's right against the citizen (jam/crossing
     *  fallback), or a herded animal is at it (so the herder's trailing flock isn't cut off mid-crossing). */
    private boolean shouldBeOpen(BlockPos gate) {
        if (pathCrossesGate(gate)) return true;       // the citizen is walking through it
        if (herdedAnimalAt(gate)) return true;        // a led flock is crossing it (even if the herder is posted, still)
        // Jam fallback: pressed against a closed gate WHILE trying to move (not merely idling beside one,
        // which would hold gates open all over — that's why this clause requires an active path).
        return !citizen.getNavigation().isDone()
            && citizen.distanceToSqr(gate.getX() + 0.5, gate.getY() + 0.5, gate.getZ() + 0.5) <= AT_GATE_SQ;
    }

    /** True if the citizen's current navigation path threads {@code gate} within the next {@link #LOOKAHEAD}
     *  nodes — i.e. it actually intends to walk through it (not just happens to be near it). */
    private boolean pathCrossesGate(BlockPos gate) {
        Path path = citizen.getNavigation().getPath();
        if (path == null) return false;
        int end = Math.min(path.getNodeCount(), path.getNextNodeIndex() + LOOKAHEAD);
        for (int i = path.getNextNodeIndex(); i < end; i++) {
            var n = path.getNode(i);
            if (n.x == gate.getX() && n.z == gate.getZ() && Math.abs(n.y - gate.getY()) <= 1) return true;
        }
        return false;
    }

    /** Is an animal currently being herded (claimed) right at this gate? Keeps it open while a led flock crosses. */
    private boolean herdedAnimalAt(BlockPos gate) {
        AABB box = new AABB(gate).inflate(1.5);
        for (Animal a : citizen.level().getEntitiesOfClass(Animal.class, box)) {
            Integer h = a.getExistingDataOrNull(BannerboundCore.HERDED_BY.get());
            if (h != null && h != 0) return true;
        }
        return false;
    }

    /** Whether any nearby gate currently wants managing (for canUse/canContinue): a CLOSED gate the
     *  citizen intends to cross, or an ALREADY-OPEN gate its path crosses — adopting the latter is
     *  what closes a gate leaked open by a chunk unload mid-crossing (unload skips {@code stop()}, and
     *  the fresh goal's {@link #openedGates} list is empty, so nobody else would ever close it). */
    private boolean hasGateToOpen() {
        long now = citizen.level().getGameTime();
        BlockPos center = citizen.blockPosition();
        BlockPos.MutableBlockPos cursor = new BlockPos.MutableBlockPos();
        for (int dx = -DETECT_RADIUS; dx <= DETECT_RADIUS; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -DETECT_RADIUS; dz <= DETECT_RADIUS; dz++) {
                    cursor.set(center.getX() + dx, center.getY() + dy, center.getZ() + dz);
                    BlockState state = citizen.level().getBlockState(cursor);
                    if (!isOpenableGate(state) || GateHolds.isHeld(cursor, now)) continue;
                    boolean closed = !state.getValue(BlockStateProperties.OPEN);
                    if (closed ? shouldBeOpen(cursor) : pathCrossesGate(cursor)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /** Any fence gate — vanilla or the modded rope gate — identified by tag + the OPEN property. */
    private static boolean isOpenableGate(BlockState state) {
        return state.is(BlockTags.FENCE_GATES) && state.hasProperty(BlockStateProperties.OPEN);
    }

    private void setGate(BlockPos pos, BlockState state, boolean open) {
        if (!isOpenableGate(state)) return;
        if (state.getValue(BlockStateProperties.OPEN) == open) return;
        citizen.level().setBlock(pos, state.setValue(BlockStateProperties.OPEN, open), 10);
        citizen.level().playSound(null, pos,
            open ? SoundEvents.FENCE_GATE_OPEN : SoundEvents.FENCE_GATE_CLOSE,
            SoundSource.BLOCKS, 1.0f, 1.0f);
        citizen.level().gameEvent(citizen,
            open ? GameEvent.BLOCK_OPEN : GameEvent.BLOCK_CLOSE, pos);
    }
}
