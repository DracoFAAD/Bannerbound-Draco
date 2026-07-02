package com.bannerbound.antiquity;

import java.util.List;
import java.util.Set;

import net.minecraft.core.BlockPos;

/**
 * A block entity that can host rope ties — implemented by both the rope-fence post (one slot) and the
 * rope fence gate (two: left/right upright). {@link RopeTies} drives the linking, breaking, collision
 * fillers and rendering generically through this interface, so the post and gate share one rope system.
 */
public interface RopeTieHost {
    /** Number of tie points on this block (post = 1, gate = 2). */
    int slotCount();

    /** The anchors this host's {@code slot} is roped to (the far ends). */
    Set<RopeAnchor> connections(int slot);

    boolean addConnection(int slot, RopeAnchor other);

    boolean removeConnection(int slot, RopeAnchor other);

    /** Collision-filler cells this host placed for the rope to {@code other} (it owns that rope). */
    List<BlockPos> getFillers(RopeAnchor other);

    /** Record (empty list clears) the filler cells for the rope to {@code other}. */
    void putFillers(RopeAnchor other, List<BlockPos> cells);

    default boolean isConnected(int slot) {
        return !connections(slot).isEmpty();
    }
}
