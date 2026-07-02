package com.bannerbound.antiquity.client.ponder.scenes;

import com.bannerbound.antiquity.BannerboundAntiquity;

import net.createmod.catnip.math.Pointing;
import net.createmod.ponder.api.scene.SceneBuilder;
import net.createmod.ponder.api.scene.SceneBuildingUtil;
import net.minecraft.core.BlockPos;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * Ponder storyboards for the Crucible — a portable single block that gathers a raw-metal charge and
 * is melted inside a Bloomery, then poured into moulds. Scenes under {@code bannerboundantiquity:crucible}.
 * <p>
 * The crucible's charge lives in a codec-encoded {@code Contents} tag, so these scenes teach the
 * interactions through control prompts rather than faking the molten render via raw NBT.
 */
@OnlyIn(Dist.CLIENT)
public final class CrucibleScenes {
    private static final int X = PonderUtil.CX, Y = PonderUtil.CY, Z = PonderUtil.CZ;

    private CrucibleScenes() {}

    public static void construction(SceneBuilder scene, SceneBuildingUtil util) {
        scene.title("crucible_construction", "The Crucible");
        PonderUtil.basePlate(scene, util, 1.0f);

        BlockPos pos = util.grid().at(X, Y, Z);
        scene.overlay().showControls(util.vector().topOf(pos), Pointing.DOWN, 50)
            .rightClick().withItem(PonderUtil.stack("bannerboundantiquity:crucible"));
        scene.idle(5);
        scene.world().setBlock(pos, BannerboundAntiquity.CRUCIBLE_BLOCK.get().defaultBlockState(), true);
        scene.idle(10);
        scene.overlay().showText(80)
            .text("The Crucible is shaped from clay and fired in a Kiln, then set down anywhere.")
            .pointAt(util.vector().topOf(pos))
            .attachKeyFrame()
            .placeNearTarget();
        scene.idle(80);
        scene.overlay().showText(80)
            .text("It is the vessel that carries molten metal — empty, for now.")
            .pointAt(util.vector().centerOf(pos))
            .placeNearTarget();
        scene.idle(70);
    }

    public static void operation(SceneBuilder scene, SceneBuildingUtil util) {
        scene.title("crucible_operation", "Charging & Casting");
        PonderUtil.basePlate(scene, util, 1.0f);

        BlockPos pos = util.grid().at(X, Y, Z);
        scene.world().setBlock(pos, BannerboundAntiquity.CRUCIBLE_BLOCK.get().defaultBlockState(), false);
        scene.idle(10);

        // Load raw ore.
        scene.overlay().showControls(util.vector().topOf(pos), Pointing.DOWN, 60)
            .rightClick().withItem(PonderUtil.stack("minecraft:raw_copper", 4));
        scene.idle(10);
        scene.overlay().showText(80)
            .text("Right-click with smeltable raw metal to drop pieces in — up to eight.")
            .pointAt(util.vector().topOf(pos))
            .attachKeyFrame()
            .placeNearTarget();
        scene.idle(80);

        scene.overlay().showText(80)
            .text("Empty-handed right-click pops the last piece back out.")
            .pointAt(util.vector().centerOf(pos))
            .placeNearTarget();
        scene.idle(70);

        // Melt it in the bloomery.
        scene.overlay().showText(90)
            .text("Place the charged Crucible inside a lit Bloomery to melt it into liquid metal.")
            .pointAt(util.vector().topOf(pos))
            .attachKeyFrame()
            .placeNearTarget();
        scene.idle(90);
        scene.overlay().showText(90)
            .text("Carry the molten Crucible to a Stone Anvil and pour it into a mould to cast.")
            .pointAt(util.vector().centerOf(pos))
            .placeNearTarget();
        scene.idle(80);
    }
}
