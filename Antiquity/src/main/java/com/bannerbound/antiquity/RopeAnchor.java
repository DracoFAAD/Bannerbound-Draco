package com.bannerbound.antiquity;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.antiquity.block.RopeFenceGateBlock;
import com.bannerbound.antiquity.block.RopeFencePostBlock;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * One end of a rope: a block position plus a <b>slot</b> identifying which tie point on that block.
 * A rope-fence post has a single tie point (slot 0, its centre); a rope fence gate has two (slot 0 =
 * the left upright, slot 1 = the right upright). Modelling a tie point this way lets any tie point
 * rope to any other uniformly — post↔post, post↔gate, gate↔gate.
 */
@ApiStatus.Internal
public record RopeAnchor(BlockPos pos, int slot) implements Comparable<RopeAnchor> {
    /** Height up the post/upright where the rope ties on — top of the coil (13/16). */
    public static final double TIE_Y = 13.0 / 16.0;

    public RopeAnchor immutable() {
        return new RopeAnchor(pos.immutable(), slot);
    }

    public CompoundTag toTag() {
        CompoundTag t = new CompoundTag();
        t.putLong("P", pos.asLong());
        t.putInt("S", slot);
        return t;
    }

    public static RopeAnchor fromTag(CompoundTag t) {
        return new RopeAnchor(BlockPos.of(t.getLong("P")), t.getInt("S"));
    }

    /** A total order over anchors, so each rope is owned/drawn by exactly one of its two ends. */
    @Override
    public int compareTo(RopeAnchor o) {
        int c = Long.compareUnsigned(pos.asLong(), o.pos.asLong());
        return c != 0 ? c : Integer.compare(slot, o.slot);
    }

    /** The world point where this anchor's rope ties on, or {@code null} if it's not a tie host. */
    public static Vec3 worldTie(BlockGetter level, RopeAnchor a) {
        BlockState st = level.getBlockState(a.pos());
        double y = a.pos().getY() + TIE_Y;
        if (st.getBlock() instanceof RopeFencePostBlock) {
            return new Vec3(a.pos().getX() + 0.5, y, a.pos().getZ() + 0.5);
        }
        if (st.getBlock() instanceof RopeFenceGateBlock) {
            double modelX = a.slot() == 0 ? RopeFenceGateBlock.LEFT_X : RopeFenceGateBlock.RIGHT_X;
            double[] off = rotate(modelX - 0.5, 0.0, st.getValue(RopeFenceGateBlock.FACING));
            return new Vec3(a.pos().getX() + 0.5 + off[0], y, a.pos().getZ() + 0.5 + off[1]);
        }
        return null;
    }

    /** Rotate a model-space XZ offset by the gate's facing (matches the blockstate y-rotation). */
    private static double[] rotate(double ox, double oz, Direction facing) {
        return switch (facing) {
            case EAST -> new double[] {-oz, ox};
            case SOUTH -> new double[] {-ox, -oz};
            case WEST -> new double[] {oz, -ox};
            default -> new double[] {ox, oz}; // NORTH (and the non-horizontal fallback)
        };
    }
}
