package com.bannerbound.core.api.walls;

import java.util.ArrayList;
import java.util.List;

import org.jetbrains.annotations.Nullable;

import net.minecraft.core.HolderGetter;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * One authored wall piece — a SEGMENT (straight run tile), CORNER (square turn tile) or GATE
 * (segment-sized piece containing a pathable opening). Immutable once built.
 *
 * <p>Authoring convention (see WALLS_PLAN.md §A): designs face <b>outward = north</b>. Local
 * axes: {@code l} runs along the wall (+X / east), {@code d} runs inward (+Z / south, depth),
 * {@code h} is up. The layout engine rotates placed pieces per border edge; block states are
 * rotated with {@link BlockState#rotate} so directional blocks (stairs, gates) stay correct.
 *
 * <p>Voxels store palette indices ({@code 0} = air, {@code n} = {@code palette.get(n-1)}), so a
 * design's NBT is tiny and the palette is the single place block states live. Sizes are
 * validated to the plan's powers-of-two rule: length ∈ {2,4,8,16} (corners: footprint is
 * length×length and {@code depth == length}), depth ≤ {@link #MAX_DEPTH}, height ≤
 * {@link #MAX_HEIGHT}.
 */
public final class WallDesign {

    public enum Kind { SEGMENT, CORNER, GATE }

    public static final int MAX_DEPTH = 4;
    public static final int MAX_HEIGHT = 16;
    private static final int[] ALLOWED_LENGTHS = {2, 4, 8, 16};

    private final String id;
    private final String name;
    private final Kind kind;
    private final int length;
    private final int depth;
    private final int height;
    private final List<BlockState> palette;
    private final byte[] voxels;
    private final BlockState foundation;

    public WallDesign(String id, String name, Kind kind, int length, int depth, int height,
                      List<BlockState> palette, byte[] voxels, BlockState foundation) {
        // Corners are NOT run-tiled (one per vertex), so any 1..16 footprint works — the run
        // clipper + truncation absorb odd remainders. Segments/gates stay powers of two.
        if (kind == Kind.CORNER) {
            if (length < 1 || length > 16) {
                throw new IllegalArgumentException("Corner footprint must be 1-16, got " + length);
            }
            if (depth != length) {
                throw new IllegalArgumentException("Corner designs must be square (depth == length)");
            }
        } else if (!isAllowedLength(length)) {
            throw new IllegalArgumentException("Wall design length must be 2/4/8/16, got " + length);
        }
        if (depth < 1 || (kind != Kind.CORNER && depth > MAX_DEPTH)) {
            throw new IllegalArgumentException("Wall design depth out of range: " + depth);
        }
        if (height < 1 || height > MAX_HEIGHT) {
            throw new IllegalArgumentException("Wall design height out of range: " + height);
        }
        if (voxels.length != length * depth * height) {
            throw new IllegalArgumentException("Voxel array size mismatch");
        }
        if (palette.size() > 127) {
            throw new IllegalArgumentException("Wall design palette too large (max 127 states)");
        }
        this.id = id;
        this.name = name;
        this.kind = kind;
        this.length = length;
        this.depth = depth;
        this.height = height;
        this.palette = List.copyOf(palette);
        this.voxels = voxels;
        this.foundation = foundation;
    }

    public static boolean isAllowedLength(int length) {
        for (int allowed : ALLOWED_LENGTHS) {
            if (length == allowed) return true;
        }
        return false;
    }

    public String id() { return id; }
    public String name() { return name; }
    public Kind kind() { return kind; }
    public int length() { return length; }
    public int depth() { return depth; }
    public int height() { return height; }
    public BlockState foundation() { return foundation; }

    private int index(int l, int d, int h) {
        return (h * depth + d) * length + l;
    }

    /** Block state at local design coords, or {@code null} for air. */
    @Nullable
    public BlockState stateAt(int l, int d, int h) {
        byte v = voxels[index(l, d, h)];
        return v == 0 ? null : palette.get((v & 0xFF) - 1);
    }

    /** Immutable palette view — index {@code n} backs voxel value {@code n + 1}. */
    public List<BlockState> palette() {
        return palette;
    }

    /** Defensive copy of the packed voxel indices (0 = air, n = palette n-1). */
    public byte[] voxelsCopy() {
        return voxels.clone();
    }

    /** Count of non-air voxels (one design instance's block cost, before foundation fill). */
    public int blockCount() {
        int n = 0;
        for (byte v : voxels) {
            if (v != 0) n++;
        }
        return n;
    }

    // ─── NBT ────────────────────────────────────────────────────────────────────────────────

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putString("Id", id);
        tag.putString("Name", name);
        tag.putInt("Kind", kind.ordinal());
        tag.putInt("Length", length);
        tag.putInt("Depth", depth);
        tag.putInt("Height", height);
        ListTag paletteTag = new ListTag();
        for (BlockState state : palette) {
            paletteTag.add(NbtUtils.writeBlockState(state));
        }
        tag.put("Palette", paletteTag);
        tag.putByteArray("Voxels", voxels.clone());
        tag.put("Foundation", NbtUtils.writeBlockState(foundation));
        return tag;
    }

    public static WallDesign load(CompoundTag tag, HolderGetter<Block> blocks) {
        List<BlockState> palette = new ArrayList<>();
        ListTag paletteTag = tag.getList("Palette", Tag.TAG_COMPOUND);
        for (int i = 0; i < paletteTag.size(); i++) {
            palette.add(NbtUtils.readBlockState(blocks, paletteTag.getCompound(i)));
        }
        return new WallDesign(
            tag.getString("Id"),
            tag.getString("Name"),
            Kind.values()[tag.getInt("Kind")],
            tag.getInt("Length"),
            tag.getInt("Depth"),
            tag.getInt("Height"),
            palette,
            tag.getByteArray("Voxels").clone(),
            NbtUtils.readBlockState(blocks, tag.getCompound("Foundation")));
    }

    // ─── Builder (code-built defaults + future editor saves) ────────────────────────────────

    public static Builder builder(String id, String name, Kind kind, int length, int depth, int height) {
        return new Builder(id, name, kind, length, depth, height);
    }

    public static final class Builder {
        private final String id;
        private final String name;
        private final Kind kind;
        private final int length;
        private final int depth;
        private final int height;
        private final List<BlockState> palette = new ArrayList<>();
        private final byte[] voxels;
        private BlockState foundation;

        private Builder(String id, String name, Kind kind, int length, int depth, int height) {
            this.id = id;
            this.name = name;
            this.kind = kind;
            this.length = length;
            this.depth = depth;
            this.height = height;
            this.voxels = new byte[length * depth * height];
        }

        public Builder set(int l, int d, int h, BlockState state) {
            int paletteIndex = palette.indexOf(state);
            if (paletteIndex < 0) {
                palette.add(state);
                paletteIndex = palette.size() - 1;
            }
            voxels[(h * depth + d) * length + l] = (byte) (paletteIndex + 1);
            return this;
        }

        /** Fill the full length×depth slab at layer {@code h}. */
        public Builder fillLayer(int h, BlockState state) {
            for (int l = 0; l < length; l++) {
                for (int d = 0; d < depth; d++) {
                    set(l, d, h, state);
                }
            }
            return this;
        }

        public Builder foundation(BlockState state) {
            this.foundation = state;
            return this;
        }

        public WallDesign build() {
            if (foundation == null) {
                throw new IllegalStateException("Wall design needs a foundation block");
            }
            return new WallDesign(id, name, kind, length, depth, height, palette, voxels, foundation);
        }
    }
}
