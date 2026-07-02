package com.bannerbound.antiquity.client.ponder.scenes;

import com.bannerbound.antiquity.BannerboundAntiquity;
import com.bannerbound.antiquity.block.entity.StoneAnvilBlockEntity;

import net.createmod.catnip.math.Pointing;
import net.createmod.ponder.api.scene.SceneBuilder;
import net.createmod.ponder.api.scene.SceneBuildingUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * Ponder storyboards for the Stone Anvil — cast molten metal in moulds and cold-hammer parts into
 * tools. Scenes registered under {@code bannerboundantiquity:stone_anvil}.
 */
@OnlyIn(Dist.CLIENT)
public final class StoneAnvilScenes {
    private static final int X = PonderUtil.CX, Y = PonderUtil.CY, Z = PonderUtil.CZ;
    private static final Direction FACING = Direction.NORTH;
    /** Copper-coloured molten tint (ARGB). */
    private static final int COPPER_TINT = 0xFFB87333;

    private StoneAnvilScenes() {}

    public static void construction(SceneBuilder scene, SceneBuildingUtil util) {
        scene.title("stone_anvil_construction", "Dressing a Stone Anvil");
        PonderUtil.basePlate(scene, util, 1.0f);

        BlockPos pos = util.grid().at(X, Y, Z);
        scene.world().setBlock(pos, Blocks.STONE.defaultBlockState(), false);
        scene.idle(8);
        scene.overlay().showControls(util.vector().topOf(pos), Pointing.DOWN, 50)
            .rightClick().withItem(PonderUtil.stack("bannerboundantiquity:stone_hammer"));
        scene.idle(8);
        scene.world().setBlock(pos, BannerboundAntiquity.STONE_ANVIL.get().defaultBlockState()
            .setValue(BlockStateProperties.HORIZONTAL_FACING, FACING), true);
        scene.overlay().showText(80)
            .text("Right-click a Stone block with a Hammer to dress it into a Stone Anvil.")
            .pointAt(util.vector().topOf(pos))
            .attachKeyFrame()
            .placeNearTarget();
        scene.idle(80);
    }

    public static void operation(SceneBuilder scene, SceneBuildingUtil util) {
        scene.title("stone_anvil_operation", "Casting & Forging");
        PonderUtil.basePlate(scene, util, 1.0f);

        BlockPos pos = util.grid().at(X, Y, Z);
        scene.world().setBlock(pos, BannerboundAntiquity.STONE_ANVIL.get().defaultBlockState()
            .setValue(BlockStateProperties.HORIZONTAL_FACING, FACING), false);
        scene.idle(10);

        // Place a mould.
        scene.overlay().showControls(util.vector().topOf(pos), Pointing.DOWN, 50)
            .rightClick().withItem(PonderUtil.stack("bannerboundantiquity:fired_clay_mold_ingot"));
        scene.idle(5);
        modify(scene, util, tag -> tag.putString("MoldShape", "ingot"));
        scene.idle(10);
        scene.overlay().showText(80)
            .text("Set a fired mould on the empty anvil to choose what you'll cast.")
            .pointAt(util.vector().topOf(pos))
            .attachKeyFrame()
            .placeNearTarget();
        scene.idle(80);

        // Pour molten metal from a crucible.
        scene.overlay().showControls(util.vector().topOf(pos), Pointing.DOWN, 60)
            .rightClick().withItem(PonderUtil.stack("bannerboundantiquity:crucible"));
        scene.idle(5);
        modify(scene, util, tag -> {
            tag.putInt("FillMb", 1000);
            tag.putString("MetalId", "copper");
            tag.putInt("TintColor", COPPER_TINT);
            tag.putBoolean("Molten", true);
            tag.putInt("CoolTicks", StoneAnvilBlockEntity.COOL_TICKS);
        });
        scene.idle(10);
        scene.overlay().showText(80)
            .text("Hold right-click with a molten Crucible to pour metal into the mould.")
            .pointAt(util.vector().topOf(pos))
            .attachKeyFrame()
            .placeNearTarget();
        scene.idle(80);

        // Let it cool, then extract.
        modify(scene, util, tag -> { tag.putBoolean("Molten", false); tag.putInt("CoolTicks", 0); });
        scene.idle(10);
        scene.overlay().showText(80)
            .text("Wait for it to cool and set, then right-click empty-handed to lift out the casting.")
            .pointAt(util.vector().topOf(pos))
            .attachKeyFrame()
            .placeNearTarget();
        scene.idle(80);
        modify(scene, util, tag -> { tag.putString("MoldShape", ""); tag.putInt("FillMb", 0); tag.putString("MetalId", ""); });
        scene.idle(10);

        // Cold-hammer path.
        scene.overlay().showText(90)
            .text("Cast parts can then be cold-hammered here: stack them on the anvil and strike with a Hammer to forge a tool.")
            .pointAt(util.vector().topOf(pos))
            .attachKeyFrame()
            .placeNearTarget();
        scene.idle(90);
    }

    private static void modify(SceneBuilder scene, SceneBuildingUtil util,
                               java.util.function.Consumer<net.minecraft.nbt.CompoundTag> m) {
        scene.world().modifyBlockEntityNBT(util.select().position(X, Y, Z), StoneAnvilBlockEntity.class, m);
    }
}
