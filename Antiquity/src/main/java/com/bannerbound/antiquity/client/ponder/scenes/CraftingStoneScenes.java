package com.bannerbound.antiquity.client.ponder.scenes;

import com.bannerbound.antiquity.BannerboundAntiquity;
import com.bannerbound.antiquity.block.entity.CraftingStoneBlockEntity;

import net.createmod.catnip.math.Pointing;
import net.createmod.ponder.api.scene.SceneBuilder;
import net.createmod.ponder.api.scene.SceneBuildingUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.ListTag;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * Ponder storyboards for the Crafting Stone — the off-station knapping bench where flint and stone
 * become the first tools. Scenes registered under {@code bannerboundantiquity:crafting_stone}.
 */
@OnlyIn(Dist.CLIENT)
public final class CraftingStoneScenes {
    private static final int X = PonderUtil.CX, Y = PonderUtil.CY, Z = PonderUtil.CZ;
    private static final Direction FACING = Direction.NORTH;

    private CraftingStoneScenes() {}

    public static void construction(SceneBuilder scene, SceneBuildingUtil util) {
        scene.title("crafting_stone_construction", "Carving a Crafting Stone");
        PonderUtil.basePlate(scene, util, 1.0f);

        BlockPos pos = util.grid().at(X, Y, Z);
        scene.world().setBlock(pos, Blocks.STONE.defaultBlockState(), false);
        scene.idle(8);
        scene.overlay().showControls(util.vector().topOf(pos), Pointing.DOWN, 50)
            .rightClick().withItem(PonderUtil.stack("bannerboundantiquity:flint_knife"));
        scene.idle(8);
        scene.world().setBlock(pos, BannerboundAntiquity.CRAFTING_STONE.get().defaultBlockState()
            .setValue(BlockStateProperties.HORIZONTAL_FACING, FACING), true);
        scene.overlay().showText(80)
            .text("Right-click a Stone block with a Flint Knife to carve a Crafting Stone.")
            .pointAt(util.vector().topOf(pos))
            .attachKeyFrame()
            .placeNearTarget();
        scene.idle(80);
    }

    public static void operation(SceneBuilder scene, SceneBuildingUtil util) {
        scene.title("crafting_stone_operation", "Knapping Tools");
        PonderUtil.basePlate(scene, util, 1.0f);

        BlockPos pos = util.grid().at(X, Y, Z);
        scene.world().setBlock(pos, BannerboundAntiquity.CRAFTING_STONE.get().defaultBlockState()
            .setValue(BlockStateProperties.HORIZONTAL_FACING, FACING), false);
        scene.idle(10);

        // Build the pile.
        scene.overlay().showControls(util.vector().topOf(pos), Pointing.DOWN, 50)
            .rightClick().withItem(PonderUtil.stack("minecraft:flint", 2));
        scene.idle(5);
        modify(scene, util, tag -> {
            ListTag contents = new ListTag();
            contents.add(PonderUtil.itemNbt("minecraft:flint", 2));
            contents.add(PonderUtil.itemNbt("minecraft:stick", 1));
            tag.put("Contents", contents);
            tag.putInt("InsertAnimTicks", CraftingStoneBlockEntity.SLIDE_TICKS);
        });
        scene.idle(10);
        scene.overlay().showText(80)
            .text("Right-click materials onto the stone, up to nine. A spinning ghost shows what they'll become.")
            .pointAt(util.vector().topOf(pos))
            .attachKeyFrame()
            .placeNearTarget();
        scene.idle(80);

        // Craft.
        scene.overlay().showControls(util.vector().centerOf(pos), Pointing.DOWN, 50).rightClick();
        scene.idle(5);
        modify(scene, util, tag -> tag.put("Contents", new ListTag()));
        scene.idle(10);
        scene.overlay().showText(90)
            .text("Sneak + right-click to knap them together — the result pops up off the stone.")
            .pointAt(util.vector().topOf(pos))
            .attachKeyFrame()
            .placeNearTarget();
        scene.idle(90);
    }

    private static void modify(SceneBuilder scene, SceneBuildingUtil util,
                               java.util.function.Consumer<net.minecraft.nbt.CompoundTag> m) {
        scene.world().modifyBlockEntityNBT(util.select().position(X, Y, Z), CraftingStoneBlockEntity.class, m);
    }
}
