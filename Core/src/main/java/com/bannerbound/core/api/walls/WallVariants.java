package com.bannerbound.core.api.walls;

/**
 * Auto-derived design VARIANTS (playtest 2026-06-12: "a player could replace a wall segment
 * with a step wall segment so they could create stairs there"). A variant is generated FROM
 * the base design — same footprint, same palette, no extra authoring — and is addressed by
 * an id suffix the variant-aware resolver ({@code WallService.resolver}) understands:
 *
 * <ul>
 *   <li>{@code <baseId>#steps}   — tops descend stepwise along +length (stair silhouette)</li>
 *   <li>{@code <baseId>#steps_r} — the mirror, descending the other way</li>
 * </ul>
 *
 * Same-footprint is what keeps run tiling intact: swapping a piece's designId never changes
 * the layout geometry, only the blocks it expands to. Deterministic: client and server both
 * derive the identical variant from the synced base design.
 */
public final class WallVariants {

    public static final String STEPS = "#steps";
    public static final String STEPS_R = "#steps_r";

    /** Cycle order used by the refine view's Variant button. */
    public static final int ORDINAL_BASE = 0;
    public static final int ORDINAL_STEPS = 1;
    public static final int ORDINAL_STEPS_R = 2;

    private WallVariants() {
    }

    /** The base design id with any variant suffix stripped. */
    public static String baseId(String designId) {
        if (designId.endsWith(STEPS_R)) return designId.substring(0, designId.length() - STEPS_R.length());
        if (designId.endsWith(STEPS)) return designId.substring(0, designId.length() - STEPS.length());
        return designId;
    }

    public static int ordinalOf(String designId) {
        if (designId.endsWith(STEPS_R)) return ORDINAL_STEPS_R;
        if (designId.endsWith(STEPS)) return ORDINAL_STEPS;
        return ORDINAL_BASE;
    }

    public static String idFor(String baseId, int ordinal) {
        return switch (ordinal) {
            case ORDINAL_STEPS -> baseId + STEPS;
            case ORDINAL_STEPS_R -> baseId + STEPS_R;
            default -> baseId;
        };
    }

    public static String label(int ordinal) {
        return switch (ordinal) {
            case ORDINAL_STEPS -> "Steps ▲";
            case ORDINAL_STEPS_R -> "Steps ▼";
            default -> "Default";
        };
    }

    /**
     * Derives the steps variant: column {@code l}'s kept height ramps linearly from one
     * course up to the full design height across the length ({@code reversed} ramps the
     * other way). Cells above the ramp are dropped; everything kept is the base design's
     * own blocks, so materials and details carry over.
     */
    public static WallDesign stepped(WallDesign base, boolean reversed) {
        int length = base.length();
        int depth = base.depth();
        int height = base.height();
        WallDesign.Builder b = WallDesign.builder(
            base.id() + (reversed ? STEPS_R : STEPS),
            base.name() + " (steps)", base.kind(), length, depth, height);
        for (int l = 0; l < length; l++) {
            int rank = reversed ? length - l : l + 1; // 1..length
            int target = Math.max(1, (int) Math.round((double) height * rank / length));
            for (int d = 0; d < depth; d++) {
                for (int h = 0; h < height && h < target; h++) {
                    net.minecraft.world.level.block.state.BlockState state = base.stateAt(l, d, h);
                    if (state != null) {
                        b.set(l, d, h, state);
                    }
                }
            }
        }
        return b.foundation(base.foundation()).build();
    }

    /** Resolves a possibly-variant id against a base resolver — null base stays null. */
    @org.jetbrains.annotations.Nullable
    public static WallDesign resolve(String designId,
                                     java.util.function.Function<String, WallDesign> baseResolver) {
        int ordinal = ordinalOf(designId);
        WallDesign base = baseResolver.apply(baseId(designId));
        if (base == null || ordinal == ORDINAL_BASE) return base;
        return stepped(base, ordinal == ORDINAL_STEPS_R);
    }
}
