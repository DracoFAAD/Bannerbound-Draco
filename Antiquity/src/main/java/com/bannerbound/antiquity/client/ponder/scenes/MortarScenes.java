package com.bannerbound.antiquity.client.ponder.scenes;

import com.bannerbound.antiquity.BannerboundAntiquity;
import com.bannerbound.antiquity.block.entity.MortarAndPestleBlockEntity;

import net.createmod.catnip.math.Pointing;
import net.createmod.ponder.api.scene.SceneBuilder;
import net.createmod.ponder.api.scene.SceneBuildingUtil;
import net.minecraft.core.BlockPos;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * Ponder storyboards for the Mortar &amp; Pestle — a single block that grinds ingredients into dyes,
 * inks and pastes. Scenes registered under {@code bannerboundantiquity:mortar_and_pestle}.
 */
@OnlyIn(Dist.CLIENT)
public final class MortarScenes {
    private static final int X = PonderUtil.CX, Y = PonderUtil.CY, Z = PonderUtil.CZ;

    private MortarScenes() {}

    public static void construction(SceneBuilder scene, SceneBuildingUtil util) {
        scene.title("mortar_construction", "The Mortar & Pestle");
        PonderUtil.basePlate(scene, util, 1.0f);

        BlockPos pos = util.grid().at(X, Y, Z);
        scene.overlay().showControls(util.vector().topOf(pos), Pointing.DOWN, 50)
            .rightClick().withItem(PonderUtil.stack("bannerboundantiquity:mortar_and_pestle"));
        scene.idle(5);
        scene.world().setBlock(pos, BannerboundAntiquity.MORTAR_AND_PESTLE.get().defaultBlockState(), true);
        scene.idle(10);
        scene.overlay().showText(80)
            .text("Set down a Mortar & Pestle — the simplest way to grind raw goods into something useful.")
            .pointAt(util.vector().topOf(pos))
            .attachKeyFrame()
            .placeNearTarget();
        scene.idle(80);
    }

    public static void operation(SceneBuilder scene, SceneBuildingUtil util) {
        scene.title("mortar_operation", "Grinding");
        PonderUtil.basePlate(scene, util, 1.0f);

        BlockPos pos = util.grid().at(X, Y, Z);
        scene.world().setBlock(pos, BannerboundAntiquity.MORTAR_AND_PESTLE.get().defaultBlockState(), false);
        scene.idle(10);

        // Add water.
        scene.overlay().showControls(util.vector().topOf(pos), Pointing.DOWN, 40)
            .rightClick().withItem(PonderUtil.stack("minecraft:potion"));
        scene.idle(5);
        modify(scene, util, tag -> tag.putString("Liquid", "water"));
        scene.idle(10);
        scene.overlay().showText(70)
            .text("Some recipes start with water — right-click with a Water Bottle to pour it in.")
            .pointAt(util.vector().topOf(pos))
            .attachKeyFrame()
            .placeNearTarget();
        scene.idle(70);

        // Add ingredient.
        scene.overlay().showControls(util.vector().topOf(pos), Pointing.DOWN, 40)
            .rightClick().withItem(PonderUtil.stack("minecraft:poppy"));
        scene.idle(5);
        modify(scene, util, tag -> tag.put("Ingredient", PonderUtil.itemNbt("minecraft:poppy", 1)));
        scene.idle(10);
        scene.overlay().showText(80)
            .text("Right-click the ingredient in. Dyes and inks take a single flower; pastes and powders grind a whole stack at once.")
            .pointAt(util.vector().topOf(pos))
            .attachKeyFrame()
            .placeNearTarget();
        scene.idle(80);

        // Grind it (press-and-grind minigame). Gated by Herbalism; finishes the batch in four crushes.
        scene.overlay().showControls(util.vector().centerOf(pos), Pointing.DOWN, 40).rightClick();
        scene.idle(5);
        modify(scene, util, tag -> {
            tag.putInt("MixAnimTicks", MortarAndPestleBlockEntity.MIX_CYCLE_TICKS);
            tag.remove("Ingredient");
            tag.putString("Liquid", "red");
        });
        scene.idle(10);
        scene.overlay().showText(100)
            .text("Right-click empty-handed to work the pestle — press down, then grind in circles. Four crushes finish the batch (you'll need Herbalism researched first).")
            .pointAt(util.vector().centerOf(pos))
            .attachKeyFrame()
            .placeNearTarget();
        scene.idle(100);

        // Use the finished dye on an item.
        scene.overlay().showControls(util.vector().topOf(pos), Pointing.DOWN, 40)
            .rightClick().withItem(PonderUtil.stack("minecraft:leather_chestplate"));
        scene.idle(5);
        modify(scene, util, tag -> tag.putString("Liquid", ""));
        scene.idle(10);
        scene.overlay().showText(90)
            .text("The bowl now holds red dye — right-click a dyeable item to colour up to eight at once. Pastes and powders pop straight out instead.")
            .pointAt(util.vector().topOf(pos))
            .attachKeyFrame()
            .placeNearTarget();
        scene.idle(90);
    }

    private static void modify(SceneBuilder scene, SceneBuildingUtil util,
                               java.util.function.Consumer<net.minecraft.nbt.CompoundTag> m) {
        scene.world().modifyBlockEntityNBT(util.select().position(X, Y, Z), MortarAndPestleBlockEntity.class, m);
    }
}
