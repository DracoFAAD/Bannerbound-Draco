package com.bannerbound.antiquity.client.ponder.scenes;

import com.bannerbound.antiquity.BannerboundAntiquity;
import com.bannerbound.antiquity.block.entity.FletchingStationBlockEntity;

import net.createmod.catnip.math.Pointing;
import net.createmod.ponder.api.scene.SceneBuilder;
import net.createmod.ponder.api.scene.SceneBuildingUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.ListTag;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * Ponder storyboards for the Fletching Station — refines arrows through a quality-rolled stretch
 * minigame. Scenes registered under {@code bannerboundantiquity:fletching_station}.
 */
@OnlyIn(Dist.CLIENT)
public final class FletchingScenes {
    private static final int X = PonderUtil.CX, Y = PonderUtil.CY, Z = PonderUtil.CZ;
    private static final Direction FACING = Direction.NORTH;

    private FletchingScenes() {}

    public static void construction(SceneBuilder scene, SceneBuildingUtil util) {
        scene.title("fletching_construction", "The Fletching Station");
        PonderUtil.basePlate(scene, util, 1.0f);

        BlockPos pos = util.grid().at(X, Y, Z);
        scene.overlay().showControls(util.vector().topOf(pos), Pointing.DOWN, 50)
            .rightClick().withItem(PonderUtil.stack("bannerboundantiquity:fletching_station"));
        scene.idle(5);
        scene.world().setBlock(pos, BannerboundAntiquity.FLETCHING_STATION.get().defaultBlockState()
            .setValue(BlockStateProperties.HORIZONTAL_FACING, FACING), true);
        scene.idle(10);
        scene.overlay().showText(80)
            .text("Place a Fletching Station — where rough arrows are bound true.")
            .pointAt(util.vector().topOf(pos))
            .attachKeyFrame()
            .placeNearTarget();
        scene.idle(80);
    }

    public static void operation(SceneBuilder scene, SceneBuildingUtil util) {
        scene.title("fletching_operation", "Fletching Arrows");
        PonderUtil.basePlate(scene, util, 1.0f);

        BlockPos pos = util.grid().at(X, Y, Z);
        scene.world().setBlock(pos, BannerboundAntiquity.FLETCHING_STATION.get().defaultBlockState()
            .setValue(BlockStateProperties.HORIZONTAL_FACING, FACING), false);
        scene.idle(10);

        // Build the pile.
        scene.overlay().showControls(util.vector().topOf(pos), Pointing.DOWN, 50)
            .rightClick().withItem(PonderUtil.stack("minecraft:flint", 1));
        scene.idle(5);
        modify(scene, util, tag -> {
            ListTag contents = new ListTag();
            contents.add(PonderUtil.itemNbt("minecraft:flint", 1));
            contents.add(PonderUtil.itemNbt("minecraft:stick", 1));
            contents.add(PonderUtil.itemNbt("minecraft:feather", 1));
            tag.put("Contents", contents);
            tag.putInt("InsertAnimTicks", FletchingStationBlockEntity.SLIDE_TICKS);
        });
        scene.idle(10);
        scene.overlay().showText(80)
            .text("Right-click the parts onto the station — flint, stick and feather make an arrow.")
            .pointAt(util.vector().topOf(pos))
            .attachKeyFrame()
            .placeNearTarget();
        scene.idle(80);

        // Start the minigame — pile is consumed, an in-progress sprite appears.
        scene.overlay().showControls(util.vector().centerOf(pos), Pointing.DOWN, 50).rightClick();
        scene.idle(5);
        modify(scene, util, tag -> {
            tag.put("Contents", new ListTag());
            tag.put("InProgress", PonderUtil.itemNbt("minecraft:arrow", 1));
        });
        scene.idle(10);
        scene.overlay().showText(90)
            .text("Sneak + right-click to begin the stretch minigame — play it well for a higher-quality arrow.")
            .pointAt(util.vector().topOf(pos))
            .attachKeyFrame()
            .placeNearTarget();
        scene.idle(90);

        modify(scene, util, tag -> tag.remove("InProgress"));
        scene.idle(10);
        scene.overlay().showText(70)
            .text("When the minigame ends, the finished arrows are handed to you.")
            .pointAt(util.vector().centerOf(pos))
            .placeNearTarget();
        scene.idle(70);
    }

    private static void modify(SceneBuilder scene, SceneBuildingUtil util,
                               java.util.function.Consumer<net.minecraft.nbt.CompoundTag> m) {
        scene.world().modifyBlockEntityNBT(util.select().position(X, Y, Z), FletchingStationBlockEntity.class, m);
    }
}
