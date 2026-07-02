package com.bannerbound.antiquity.client.ponder.scenes;

import com.bannerbound.antiquity.BannerboundAntiquity;
import com.bannerbound.antiquity.block.ClayTankBlock;
import com.bannerbound.antiquity.block.TanningRackBlock;
import com.bannerbound.antiquity.block.entity.ClayTankBlockEntity;
import com.bannerbound.antiquity.block.entity.TanningRackBlockEntity;

import net.createmod.catnip.math.Pointing;
import net.createmod.ponder.api.scene.SceneBuilder;
import net.createmod.ponder.api.scene.SceneBuildingUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * Ponder storyboards for the Tannery workflow — a 2×2 Tanning Rack beside a stacked Clay Tank.
 * Scenes registered under {@code bannerboundantiquity:tannery}.
 */
@OnlyIn(Dist.CLIENT)
public final class TanneryScenes {
    /** Rack master (PART 0) — width runs +EAST when facing NORTH, height runs up. */
    private static final int RX = 2, RY = PonderUtil.CY, RZ = 2;
    /** Clay-tank controller (PART 0) just to the rack's west. */
    private static final int TX = 0, TY = PonderUtil.CY, TZ = 2;
    private static final Direction FACING = Direction.NORTH;

    private TanneryScenes() {}

    public static void construction(SceneBuilder scene, SceneBuildingUtil util) {
        scene.title("tannery_construction", "Setting up the Tannery");
        PonderUtil.basePlate(scene, util, 0.8f);

        // Rack.
        scene.overlay().showControls(util.vector().centerOf(util.grid().at(RX, RY, RZ)), Pointing.DOWN, 50)
            .rightClick().withItem(PonderUtil.stack("bannerboundantiquity:tanning_rack"));
        scene.idle(5);
        placeRack(scene, util);
        scene.idle(10);
        scene.overlay().showText(80)
            .text("Place a Tanning Rack — a 2×2 frame that holds one hide at a time.")
            .pointAt(util.vector().topOf(util.grid().at(RX, RY + 1, RZ)))
            .attachKeyFrame()
            .placeNearTarget();
        scene.idle(80);

        // Clay tank, stacked two high.
        scene.overlay().showControls(util.vector().centerOf(util.grid().at(TX, TY, TZ)), Pointing.DOWN, 50)
            .rightClick().withItem(PonderUtil.stack("bannerboundantiquity:clay_tank", 2));
        scene.idle(5);
        placeTank(scene, util, 2);
        scene.idle(10);
        scene.overlay().showText(80)
            .text("Beside it, stack a Clay Tank. Each piece adds eight buckets of capacity.")
            .pointAt(util.vector().topOf(util.grid().at(TX, TY + 1, TZ)))
            .attachKeyFrame()
            .placeNearTarget();
        scene.idle(80);
    }

    public static void operation(SceneBuilder scene, SceneBuildingUtil util) {
        scene.title("tannery_operation", "Raw Hide to Leather");
        PonderUtil.basePlate(scene, util, 0.75f);

        BlockPos rack = util.grid().at(RX, RY, RZ);
        placeRack(scene, util);
        placeTank(scene, util, 2);
        scene.idle(10);

        // 1. Lay a raw hide.
        scene.overlay().showControls(util.vector().centerOf(rack), Pointing.RIGHT, 40)
            .rightClick().withItem(PonderUtil.stack("bannerboundantiquity:cow_hide"));
        scene.idle(5);
        modifyRack(scene, util, tag -> {
            tag.putString("Phase", "RAW");
            tag.put("Held", PonderUtil.itemNbt("bannerboundantiquity:cow_hide", 1));
        });
        scene.idle(10);
        scene.overlay().showText(70)
            .text("Drape a raw hide over the rack.")
            .pointAt(util.vector().centerOf(rack))
            .attachKeyFrame()
            .placeNearTarget();
        scene.idle(70);

        // 2. Scrape it (knife minigame).
        scene.overlay().showControls(util.vector().centerOf(rack), Pointing.RIGHT, 40)
            .rightClick().withItem(PonderUtil.stack("bannerboundantiquity:flint_knife"));
        scene.idle(5);
        modifyRack(scene, util, tag -> {
            tag.putString("Phase", "EMPTY");
            tag.remove("Held");
        });
        scene.idle(10);
        scene.overlay().showText(80)
            .text("Right-click with a cutting tool and scrape it clean — six swipes yields Scraped Hide.")
            .pointAt(util.vector().centerOf(rack))
            .attachKeyFrame()
            .placeNearTarget();
        scene.idle(80);

        // 3. Fill the tank with water, then add quicklime to make curing liquid.
        scene.overlay().showControls(util.vector().topOf(util.grid().at(TX, TY + 1, TZ)), Pointing.DOWN, 40)
            .rightClick().withItem(PonderUtil.stack("minecraft:water_bucket"));
        scene.idle(5);
        modifyTank(scene, util, tag -> { tag.putString("Liquid", "WATER"); tag.putInt("Buckets", 8); });
        scene.idle(10);
        scene.overlay().showText(70)
            .text("Fill the Clay Tank with water...")
            .pointAt(util.vector().topOf(util.grid().at(TX, TY + 1, TZ)))
            .attachKeyFrame()
            .placeNearTarget();
        scene.idle(60);

        scene.overlay().showControls(util.vector().topOf(util.grid().at(TX, TY + 1, TZ)), Pointing.DOWN, 40)
            .rightClick().withItem(PonderUtil.stack("bannerboundantiquity:quicklime"));
        scene.idle(5);
        modifyTank(scene, util, tag -> tag.putString("Liquid", "CURING"));
        scene.idle(10);
        scene.overlay().showText(70)
            .text("...then add Quicklime to turn it into curing liquid.")
            .pointAt(util.vector().topOf(util.grid().at(TX, TY + 1, TZ)))
            .attachKeyFrame()
            .placeNearTarget();
        scene.idle(70);

        // 4. Cure the scraped hide at the tank.
        scene.overlay().showControls(util.vector().centerOf(util.grid().at(TX, TY + 1, TZ)), Pointing.DOWN, 40)
            .rightClick().withItem(PonderUtil.stack("bannerboundantiquity:scraped_hide"));
        scene.idle(5);
        modifyTank(scene, util, tag -> tag.putInt("Buckets", 7));
        scene.idle(10);
        scene.overlay().showText(70)
            .text("Dip the Scraped Hide to draw a bucket of curing — it becomes a Cured Hide.")
            .pointAt(util.vector().centerOf(util.grid().at(TX, TY + 1, TZ)))
            .attachKeyFrame()
            .placeNearTarget();
        scene.idle(70);

        // 5. Lay the cured hide back on the rack to dry, then collect leather.
        scene.overlay().showControls(util.vector().centerOf(rack), Pointing.RIGHT, 40)
            .rightClick().withItem(PonderUtil.stack("bannerboundantiquity:cured_hide"));
        scene.idle(5);
        modifyRack(scene, util, tag -> {
            tag.putString("Phase", "DRYING");
            tag.putInt("DryTicks", TanningRackBlockEntity.DRY_TICKS);
        });
        scene.idle(10);
        scene.overlay().showText(70)
            .text("Hang the Cured Hide back on the rack to dry.")
            .pointAt(util.vector().centerOf(rack))
            .attachKeyFrame()
            .placeNearTarget();
        scene.idle(70);

        modifyRack(scene, util, tag -> { tag.putString("Phase", "DRY"); tag.putInt("DryTicks", 0); });
        scene.idle(10);
        scene.overlay().showText(80)
            .text("Once dry, right-click empty-handed to collect finished Leather.")
            .pointAt(util.vector().centerOf(rack))
            .attachKeyFrame()
            .placeNearTarget();
        scene.idle(80);
    }

    // ─── Helpers ────────────────────────────────────────────────────────────────────────────────

    /** Places the four rack cells; PART 0 (master, BE-bearing) at (RX,RY,RZ). */
    private static void placeRack(SceneBuilder scene, SceneBuildingUtil util) {
        // PART: bit0 = width (+EAST), bit1 = height (+up).
        int[][] cells = {{0, 0, 0}, {1, 0, 1}, {0, 1, 2}, {1, 1, 3}}; // {dxEast, dyUp, part}
        for (int[] c : cells) {
            BlockState s = BannerboundAntiquity.TANNING_RACK.get().defaultBlockState()
                .setValue(TanningRackBlock.PART, c[2])
                .setValue(BlockStateProperties.HORIZONTAL_FACING, FACING);
            scene.world().setBlock(util.grid().at(RX + c[0], RY + c[1], RZ), s, false);
        }
    }

    /** Places a Clay Tank pillar {@code pieces} high; PART 0 (controller) at the base. */
    private static void placeTank(SceneBuilder scene, SceneBuildingUtil util, int pieces) {
        for (int p = 0; p < pieces; p++) {
            BlockState s = BannerboundAntiquity.CLAY_TANK.get().defaultBlockState()
                .setValue(ClayTankBlock.PART, p);
            scene.world().setBlock(util.grid().at(TX, TY + p, TZ), s, false);
        }
    }

    private static void modifyRack(SceneBuilder scene, SceneBuildingUtil util,
                                   java.util.function.Consumer<net.minecraft.nbt.CompoundTag> m) {
        scene.world().modifyBlockEntityNBT(util.select().position(RX, RY, RZ), TanningRackBlockEntity.class, m);
    }

    private static void modifyTank(SceneBuilder scene, SceneBuildingUtil util,
                                   java.util.function.Consumer<net.minecraft.nbt.CompoundTag> m) {
        scene.world().modifyBlockEntityNBT(util.select().position(TX, TY, TZ), ClayTankBlockEntity.class, m);
    }
}
