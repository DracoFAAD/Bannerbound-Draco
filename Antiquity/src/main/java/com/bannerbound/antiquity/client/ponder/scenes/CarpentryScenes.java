package com.bannerbound.antiquity.client.ponder.scenes;

import com.bannerbound.antiquity.BannerboundAntiquity;
import com.bannerbound.antiquity.block.WoodworkingTableBlock;
import com.bannerbound.antiquity.block.entity.WoodworkingTableBlockEntity;

import net.createmod.catnip.math.Pointing;
import net.createmod.ponder.api.scene.SceneBuilder;
import net.createmod.ponder.api.scene.SceneBuildingUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.ListTag;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * Ponder storyboards for the Woodworking Table — a 2-block batch wood-working bench.
 * Scenes registered under {@code bannerboundantiquity:carpentry}.
 */
@OnlyIn(Dist.CLIENT)
public final class CarpentryScenes {
    private static final int X = PonderUtil.CX, Y = PonderUtil.CY, Z = PonderUtil.CZ;
    private static final Direction FACING = Direction.NORTH;

    private CarpentryScenes() {}

    public static void construction(SceneBuilder scene, SceneBuildingUtil util) {
        scene.title("woodworking_table_construction", "The Woodworking Table");
        PonderUtil.basePlate(scene, util, 0.95f);

        BlockPos main = util.grid().at(X, Y, Z);
        scene.overlay().showControls(util.vector().topOf(main), Pointing.DOWN, 50)
            .rightClick().withItem(PonderUtil.stack("bannerboundantiquity:woodworking_table"));
        scene.idle(5);
        placeTable(scene, util);
        scene.idle(10);
        scene.overlay().showText(80)
            .text("The Woodworking Table is two blocks long — place one and the second half follows.")
            .pointAt(util.vector().topOf(main))
            .attachKeyFrame()
            .placeNearTarget();
        scene.idle(80);
    }

    public static void operation(SceneBuilder scene, SceneBuildingUtil util) {
        scene.title("woodworking_table_operation", "Batch Wood-working");
        PonderUtil.basePlate(scene, util, 0.9f);

        BlockPos main = util.grid().at(X, Y, Z);
        placeTable(scene, util);
        scene.idle(10);

        // Load logs into the pile.
        scene.overlay().showControls(util.vector().topOf(main), Pointing.DOWN, 50)
            .rightClick().withItem(PonderUtil.stack("minecraft:oak_log", 8));
        scene.idle(5);
        modify(scene, util, tag -> {
            ListTag logs = new ListTag();
            for (int i = 0; i < 4; i++) logs.add(PonderUtil.itemNbt("minecraft:oak_log", 1));
            tag.put("Logs", logs);
            tag.putInt("InsertAnimTicks", WoodworkingTableBlockEntity.SLIDE_TICKS);
        });
        scene.idle(10);
        scene.overlay().showText(80)
            .text("Right-click logs onto the table to build a working budget — sneak adds a whole stack.")
            .pointAt(util.vector().topOf(main))
            .attachKeyFrame()
            .placeNearTarget();
        scene.idle(80);

        // Pick an output from the floating ghost.
        scene.overlay().showText(80)
            .text("A ghost output floats above; the arrows browse what your logs can become. Click to queue it.")
            .pointAt(util.vector().topOf(main).add(0, 0.8, 0))
            .attachKeyFrame()
            .placeNearTarget();
        scene.idle(80);

        // Saw it.
        scene.overlay().showControls(util.vector().topOf(main), Pointing.DOWN, 50)
            .rightClick().withItem(PonderUtil.stack("bannerboundantiquity:bone_saw"));
        scene.idle(5);
        modify(scene, util, tag -> tag.put("Logs", new ListTag()));
        scene.idle(10);
        scene.overlay().showText(90)
            .text("Right-click with a Bone Saw and play the sawing minigame to cut the whole batch at once.")
            .pointAt(util.vector().topOf(main))
            .attachKeyFrame()
            .placeNearTarget();
        scene.idle(90);
    }

    private static void placeTable(SceneBuilder scene, SceneBuildingUtil util) {
        BlockState mainState = BannerboundAntiquity.WOODWORKING_TABLE.get().defaultBlockState()
            .setValue(WoodworkingTableBlock.MAIN, true)
            .setValue(BlockStateProperties.HORIZONTAL_FACING, FACING);
        BlockState secondary = BannerboundAntiquity.WOODWORKING_TABLE.get().defaultBlockState()
            .setValue(WoodworkingTableBlock.MAIN, false)
            .setValue(BlockStateProperties.HORIZONTAL_FACING, FACING);
        scene.world().setBlock(util.grid().at(X, Y, Z), mainState, false);
        scene.world().setBlock(util.grid().at(X, Y, Z - 1), secondary, false); // FACING NORTH -> -Z
    }

    private static void modify(SceneBuilder scene, SceneBuildingUtil util,
                               java.util.function.Consumer<net.minecraft.nbt.CompoundTag> m) {
        scene.world().modifyBlockEntityNBT(util.select().position(X, Y, Z), WoodworkingTableBlockEntity.class, m);
    }
}
