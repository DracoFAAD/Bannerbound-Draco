package com.bannerbound.core.api.walls;

import java.util.Map;

import org.jetbrains.annotations.Nullable;

import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The hardcoded default design set (WALLS_PLAN.md Phase 1): a plain cobblestone wall, a taller
 * corner post, and a fence-gate opening. Exists so the layout engine, ghost rendering, builders
 * and the whole construct flow are playtestable long before the Wall Designer screen (Phase 5)
 * ships. Ids use the {@code bannerbound:default_*} namespace so library designs (player-authored,
 * UUID ids) can never collide.
 */
public final class DefaultWallDesigns {

    public static final String WALL_ID = "bannerbound:default_wall";
    public static final String CORNER_ID = "bannerbound:default_corner";
    public static final String GATE_ID = "bannerbound:default_gate";

    private static final WallDesign WALL = buildWall();
    private static final WallDesign CORNER = buildCorner();
    private static final WallDesign GATE = buildGate();
    private static final Map<String, WallDesign> BY_ID = Map.of(
        WALL_ID, WALL, CORNER_ID, CORNER, GATE_ID, GATE);

    private DefaultWallDesigns() {
    }

    public static WallDesignSet set() {
        return new WallDesignSet(WALL, CORNER, GATE);
    }

    @Nullable
    public static WallDesign byId(String id) {
        return BY_ID.get(id);
    }

    /** 2×1×3 — two cobble courses topped with a cobblestone wall (silhouette reads as a wall). */
    private static WallDesign buildWall() {
        BlockState cobble = Blocks.COBBLESTONE.defaultBlockState();
        return WallDesign.builder(WALL_ID, "Default Wall", WallDesign.Kind.SEGMENT, 2, 1, 3)
            .fillLayer(0, cobble)
            .fillLayer(1, cobble)
            .fillLayer(2, Blocks.COBBLESTONE_WALL.defaultBlockState())
            .foundation(cobble)
            .build();
    }

    /** 2×2×4 solid cobble post — one course taller than the wall so corners read at a glance. */
    private static WallDesign buildCorner() {
        BlockState cobble = Blocks.COBBLESTONE.defaultBlockState();
        WallDesign.Builder b = WallDesign.builder(
            CORNER_ID, "Default Corner", WallDesign.Kind.CORNER, 2, 2, 4);
        for (int h = 0; h < 4; h++) {
            b.fillLayer(h, cobble);
        }
        return b.foundation(cobble).build();
    }

    /**
     * 2×1×3 — fence gates at ground level (outward = north, so FACING=NORTH spans the wall
     * line and the {@code minecraft:fence_gates} tag makes citizens route through), headroom
     * above, cobble lintel on top.
     */
    private static WallDesign buildGate() {
        BlockState gate = Blocks.OAK_FENCE_GATE.defaultBlockState()
            .setValue(FenceGateBlock.FACING, Direction.NORTH);
        return WallDesign.builder(GATE_ID, "Default Gate", WallDesign.Kind.GATE, 2, 1, 3)
            .fillLayer(0, gate)
            .fillLayer(2, Blocks.COBBLESTONE.defaultBlockState())
            .foundation(Blocks.COBBLESTONE.defaultBlockState())
            .build();
    }

    /** The active trio the layout engine works from. Phase 5 swaps in library designs here. */
    public record WallDesignSet(WallDesign wall, WallDesign corner, WallDesign gate) {

        @Nullable
        public WallDesign byId(String id) {
            if (wall.id().equals(id)) return wall;
            if (corner.id().equals(id)) return corner;
            if (gate.id().equals(id)) return gate;
            return DefaultWallDesigns.byId(id);
        }
    }
}
