package com.bannerbound.antiquity.client.ponder.scenes;

import com.bannerbound.antiquity.BannerboundAntiquity;
import com.bannerbound.antiquity.block.MasonsBenchBlock;
import com.bannerbound.antiquity.block.entity.MasonsBenchBlockEntity;

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
 * Ponder storyboards for the Mason's Bench — the stone-working twin of the Carpenter's Table.
 * Scenes registered under {@code bannerboundantiquity:masonry}.
 */
@OnlyIn(Dist.CLIENT)
public final class MasonryScenes {
    private static final int X = PonderUtil.CX, Y = PonderUtil.CY, Z = PonderUtil.CZ;
    private static final Direction FACING = Direction.NORTH;

    private MasonryScenes() {}

    public static void construction(SceneBuilder scene, SceneBuildingUtil util) {
        scene.title("masons_bench_construction", "The Mason's Bench");
        PonderUtil.basePlate(scene, util, 0.95f);

        BlockPos main = util.grid().at(X, Y, Z);
        scene.overlay().showControls(util.vector().topOf(main), Pointing.DOWN, 50)
            .rightClick().withItem(PonderUtil.stack("bannerboundantiquity:masons_bench"));
        scene.idle(5);
        placeBench(scene, util);
        scene.idle(10);
        scene.overlay().showText(80)
            .text("The Mason's Bench is two blocks long — place one half and the other appears.")
            .pointAt(util.vector().topOf(main))
            .attachKeyFrame()
            .placeNearTarget();
        scene.idle(80);
    }

    public static void operation(SceneBuilder scene, SceneBuildingUtil util) {
        scene.title("masons_bench_operation", "Batch Stone-working");
        PonderUtil.basePlate(scene, util, 0.9f);

        BlockPos main = util.grid().at(X, Y, Z);
        placeBench(scene, util);
        scene.idle(10);

        // Load stone into the pile.
        scene.overlay().showControls(util.vector().topOf(main), Pointing.DOWN, 50)
            .rightClick().withItem(PonderUtil.stack("minecraft:cobblestone", 8));
        scene.idle(5);
        modify(scene, util, tag -> {
            ListTag stones = new ListTag();
            for (int i = 0; i < 4; i++) stones.add(PonderUtil.itemNbt("minecraft:cobblestone", 1));
            tag.put("Stones", stones);
            tag.putInt("InsertAnimTicks", MasonsBenchBlockEntity.SLIDE_TICKS);
        });
        scene.idle(10);
        scene.overlay().showText(80)
            .text("Stack base stone on the bench to set your budget — sneak adds a whole stack.")
            .pointAt(util.vector().topOf(main))
            .attachKeyFrame()
            .placeNearTarget();
        scene.idle(80);

        // Pick an output.
        scene.overlay().showText(80)
            .text("The floating ghost shows slabs, stairs, walls and bricks — browse and click to queue.")
            .pointAt(util.vector().topOf(main).add(0, 0.8, 0))
            .attachKeyFrame()
            .placeNearTarget();
        scene.idle(80);

        // Chisel it.
        scene.overlay().showControls(util.vector().topOf(main), Pointing.DOWN, 50)
            .rightClick().withItem(PonderUtil.stack("bannerboundantiquity:stone_chisel"));
        scene.idle(5);
        modify(scene, util, tag -> tag.put("Stones", new ListTag()));
        scene.idle(10);
        scene.overlay().showText(90)
            .text("Right-click with a Stone Chisel and play the strike minigame to dress the whole batch.")
            .pointAt(util.vector().topOf(main))
            .attachKeyFrame()
            .placeNearTarget();
        scene.idle(90);
    }

    private static void placeBench(SceneBuilder scene, SceneBuildingUtil util) {
        BlockState mainState = BannerboundAntiquity.MASONS_BENCH.get().defaultBlockState()
            .setValue(MasonsBenchBlock.MAIN, true)
            .setValue(BlockStateProperties.HORIZONTAL_FACING, FACING);
        BlockState secondary = BannerboundAntiquity.MASONS_BENCH.get().defaultBlockState()
            .setValue(MasonsBenchBlock.MAIN, false)
            .setValue(BlockStateProperties.HORIZONTAL_FACING, FACING);
        scene.world().setBlock(util.grid().at(X, Y, Z), mainState, false);
        scene.world().setBlock(util.grid().at(X, Y, Z - 1), secondary, false); // FACING NORTH -> -Z
    }

    private static void modify(SceneBuilder scene, SceneBuildingUtil util,
                               java.util.function.Consumer<net.minecraft.nbt.CompoundTag> m) {
        scene.world().modifyBlockEntityNBT(util.select().position(X, Y, Z), MasonsBenchBlockEntity.class, m);
    }
}
