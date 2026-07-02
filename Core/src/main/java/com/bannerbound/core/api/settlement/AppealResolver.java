package com.bannerbound.core.api.settlement;

import java.util.List;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.api.settlement.data.BlockAppealLoader;
import com.bannerbound.core.api.settlement.data.CultureStyleLoader;
import com.bannerbound.core.api.settlement.data.PaletteLoader;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;

/**
 * Stateless appeal math. Resolves a block's effective appeal for a settlement (base value plus
 * the settlement's culture-style overrides) and turns per-block-type counts into a chunk score
 * contribution.
 */
@ApiStatus.Internal
public final class AppealResolver {
    private AppealResolver() {
    }

    /**
     * True for a block position that should be SKIPPED when tallying appeal because it's the
     * non-anchor half of a multi-block object — counting it would double the appeal of a single
     * object. Covers:
     * <ul>
     *   <li>the UPPER half of any {@code DOUBLE_BLOCK_HALF} block — tall grass, large fern, the
     *       two-tall flowers (rose bush, peony, lilac, sunflower, pitcher plant), and doors;</li>
     *   <li>the FOOT of a {@code BED_PART} bed (beds split side-by-side, not stacked).</li>
     * </ul>
     * The anchor — LOWER for double blocks, HEAD for beds — is the half that counts; resolve it with
     * {@link #appealAnchor}. (HEAD matches how the resident-cap bed count is tallied.)
     */
    public static boolean isAppealDuplicateHalf(BlockState state) {
        if (state.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)
                && state.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.UPPER) {
            return true;
        }
        return state.hasProperty(BlockStateProperties.BED_PART)
            && state.getValue(BlockStateProperties.BED_PART) == BedPart.FOOT;
    }

    /**
     * The anchor position of a multi-block object, given the non-anchor half's {@code state} and
     * {@code pos}: the LOWER cell directly below an upper double-block half, or the HEAD cell of a
     * bed (toward the bed's facing from the foot). Returns {@code pos} unchanged for any block that
     * isn't a {@link #isAppealDuplicateHalf duplicate half}. Used to redirect the appeal-debug
     * overlay so querying the top of a tall plant / the foot of a bed reports the value at the
     * counted half instead of 0.
     */
    public static BlockPos appealAnchor(BlockState state, BlockPos pos) {
        if (state.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF)
                && state.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.UPPER) {
            return pos.below();
        }
        if (state.hasProperty(BlockStateProperties.BED_PART)
                && state.getValue(BlockStateProperties.BED_PART) == BedPart.FOOT) {
            // BedBlock.FACING points foot → head, so the head is the foot's neighbour that way.
            return pos.relative(state.getValue(BlockStateProperties.HORIZONTAL_FACING));
        }
        return pos;
    }

    /**
     * Effective appeal of {@code block} for a settlement whose culture styles are {@code styleIds}.
     * Starts from the base appeal; each style that lists the block <b>overrides</b> the value
     * outright (it does not add). With several styles the last one to list the block wins. A
     * {@code null} or empty style list yields the base appeal. Result clamped to {@code [-1, 1]}.
     */
    public static float appealOf(Block block, List<String> styleIds) {
        return appealOf(block, styleIds, null);
    }

    /**
     * Effective appeal of {@code block} for a settlement with culture styles {@code styleIds} and
     * active palettes {@code paletteIds}. Resolution order: base appeal → each style that lists the
     * block <b>overrides</b> it (last wins) → each active palette that lists the block <b>adds</b>
     * its bonus on top. The final value is clamped to {@code [-1, 1]}. A {@code null}/empty palette
     * list reduces to the style-only {@link #appealOf(Block, List)} behaviour, so every caller that
     * doesn't supply palettes is unaffected.
     */
    public static float appealOf(Block block, List<String> styleIds, List<String> paletteIds) {
        float v = BlockAppealLoader.base(block);
        if (styleIds != null) {
            for (String id : styleIds) {
                CultureStyle style = CultureStyleLoader.get(id);
                if (style != null && style.hasOverride(block)) {
                    v = style.override(block);
                }
            }
        }
        if (paletteIds != null) {
            for (String id : paletteIds) {
                Palette palette = PaletteLoader.get(id);
                if (palette != null && palette.has(block)) {
                    v += palette.bonus(block);
                }
            }
        }
        return Math.max(-1f, Math.min(1f, v));
    }

    /**
     * Total appeal contributed by {@code count} blocks of one type, applying the 10%
     * diminishing-returns rule: the Nth block is worth {@code appeal · 0.9^(N-1)}. Summed over
     * all N this is the finite geometric series
     * {@code appeal · (1 - 0.9^count) / (1 - 0.9) = appeal · (1 - 0.9^count) · 10}.
     *
     * <p>Because only the count matters (not placement order), the chunk scan needs nothing more
     * than a per-block-type tally — and "the queue clamps up when a block is destroyed" falls out
     * for free, since a recount of {@code count-1} blocks yields exactly the clamped total.
     */
    public static double typeContribution(float appeal, int count) {
        if (count <= 0 || appeal == 0f) return 0.0;
        return appeal * (1.0 - Math.pow(0.9, count)) * 10.0;
    }
}
