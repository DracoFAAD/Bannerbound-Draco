package com.bannerbound.core.api.walls;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.jetbrains.annotations.Nullable;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import it.unimi.dsi.fastutil.longs.LongOpenHashSet;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.state.BlockState;

/**
 * A settlement's applied wall blueprint — the frozen output of {@link WallLayoutEngine}.
 * Pieces reference designs by id only (block states live in the designs), so the plan's NBT is
 * compact and survives design-library edits (an edited design re-applies via the adapt flow,
 * never silently mutates a frozen plan... the resolver passed to {@link #buildBlueprint} decides
 * which design snapshot the plan expands against).
 *
 * <p>{@code obsolete} carries demolition targets from a superseded plan (adapt flow, Phase 4) —
 * positions whose blocks must be removed and refunded. Empty until adapt ships.
 */
public final class WallPlan {

    private final List<WallPiece> pieces;
    /** Demolition set from the previously-applied plan ({@code BlockPos.asLong()} packed). */
    private final LongSet obsolete;

    public WallPlan(List<WallPiece> pieces) {
        this(pieces, new LongOpenHashSet());
    }

    public WallPlan(List<WallPiece> pieces, LongSet obsolete) {
        this.pieces = pieces;
        this.obsolete = obsolete;
    }

    public List<WallPiece> pieces() { return pieces; }
    public LongSet obsolete() { return obsolete; }

    /**
     * Expands every piece into the pos → expected-state map the task board, ghost renderer and
     * completeness scan all read. Emission order is corners → gates → segments so overlapping
     * cells resolve by the plan's precedence rule (first write wins via putIfAbsent).
     */
    public Long2ObjectMap<BlockState> buildBlueprint(Function<String, WallDesign> designs) {
        Long2ObjectMap<BlockState> blueprint = new Long2ObjectOpenHashMap<>();
        forEachInPrecedenceOrder(designs, (piece, design) ->
            piece.forEachBlock(design, (pos, state, foundation) ->
                blueprint.putIfAbsent(pos.asLong(), state)));
        return blueprint;
    }

    /** Total items needed to build the full blueprint, grouped by item (foundation included). */
    public Map<Item, Integer> requiredItems(Function<String, WallDesign> designs) {
        Long2ObjectMap<BlockState> blueprint = buildBlueprint(designs);
        Map<Item, Integer> required = new LinkedHashMap<>();
        for (BlockState state : blueprint.values()) {
            Item item = state.getBlock().asItem();
            required.merge(item, 1, Integer::sum);
        }
        return required;
    }

    @FunctionalInterface
    public interface PieceVisitor {
        void visit(WallPiece piece, WallDesign design);
    }

    /** Visits pieces corner-first (the blueprint precedence order), skipping unresolvable ids. */
    public void forEachInPrecedenceOrder(Function<String, WallDesign> designs, PieceVisitor visitor) {
        visitKind(WallDesign.Kind.CORNER, designs, visitor);
        visitKind(WallDesign.Kind.GATE, designs, visitor);
        visitKind(WallDesign.Kind.SEGMENT, designs, visitor);
    }

    private void visitKind(WallDesign.Kind kind, Function<String, WallDesign> designs, PieceVisitor visitor) {
        for (WallPiece piece : pieces) {
            if (piece.kind() != kind) continue;
            WallDesign design = designs.apply(piece.designId());
            if (design != null) {
                visitor.visit(piece, design);
            }
        }
    }

    // ─── NBT ────────────────────────────────────────────────────────────────────────────────

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        ListTag pieceList = new ListTag();
        for (WallPiece piece : pieces) {
            pieceList.add(piece.save());
        }
        tag.put("Pieces", pieceList);
        tag.putLongArray("Obsolete", obsolete.toLongArray());
        return tag;
    }

    public static WallPlan load(CompoundTag tag) {
        List<WallPiece> pieces = new ArrayList<>();
        ListTag pieceList = tag.getList("Pieces", Tag.TAG_COMPOUND);
        for (int i = 0; i < pieceList.size(); i++) {
            pieces.add(WallPiece.load(pieceList.getCompound(i)));
        }
        LongSet obsolete = new LongOpenHashSet(tag.getLongArray("Obsolete"));
        return new WallPlan(pieces, obsolete);
    }

    @Nullable
    public static WallPlan loadNullable(@Nullable CompoundTag tag) {
        return tag == null ? null : load(tag);
    }
}
