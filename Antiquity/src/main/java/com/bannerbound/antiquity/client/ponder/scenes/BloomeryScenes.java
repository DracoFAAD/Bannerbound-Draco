package com.bannerbound.antiquity.client.ponder.scenes;

import com.bannerbound.antiquity.BannerboundAntiquity;
import com.bannerbound.antiquity.block.BloomeryBlock;
import com.bannerbound.antiquity.block.entity.BloomeryBlockEntity;

import net.createmod.catnip.math.Pointing;
import net.createmod.ponder.api.scene.SceneBuilder;
import net.createmod.ponder.api.scene.SceneBuildingUtil;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * Ponder storyboards for the Bloomery — two scenes registered under the
 * {@code bannerboundantiquity:bloomery} tag.
 * <p>
 * Both scenes assume their structure NBTs centre the build on the plate at {@code (2, 1, _)} —
 * x=2, z=2 with the lower segment at y=1 and the upper at y=2. If your saved structure put the
 * bloomery elsewhere, adjust the four {@code GRID_*} constants below.
 */
@OnlyIn(Dist.CLIENT)
public final class BloomeryScenes {
    /** Centre-X of the plate where the build sits. */
    private static final int GRID_X = 2;
    /** Y of the lower segment (the BE-bearing one). */
    private static final int GRID_LOWER_Y = 1;
    /** Y of the upper segment. */
    private static final int GRID_UPPER_Y = 2;
    /** Centre-Z of the plate. */
    private static final int GRID_Z = 2;

    /** Direction the Bloomery faces in the Construction scene — was SOUTH, rotated 90° right
     *  (clockwise from above) to WEST so the door faces the camera. */
    private static final Direction CONSTRUCTION_FACING = Direction.WEST;
    /** Direction the Bloomery faces in the Operation scene — 180° flip from the structure's
     *  saved orientation so the door faces the camera instead of away. */
    private static final Direction OPERATION_FACING = Direction.NORTH;

    private BloomeryScenes() {}

    // ─── Scene 1: Construction ─────────────────────────────────────────────────────────────────

    /** Two mud bricks + right-click a coal block on the lower one → Bloomery formed. */
    public static void construction(SceneBuilder scene, SceneBuildingUtil util) {
        scene.title("bloomery_construction", "Building the Bloomery");
        scene.configureBasePlate(0, 0, 5);
        scene.scaleSceneView(0.95f);
        scene.showBasePlate();
        scene.idle(5);

        BlockPos lower = util.grid().at(GRID_X, GRID_LOWER_Y, GRID_Z);
        BlockPos upper = util.grid().at(GRID_X, GRID_UPPER_Y, GRID_Z);

        // Reveal the bottom mud brick.
        scene.world().showSection(util.select().position(GRID_X, GRID_LOWER_Y, GRID_Z), Direction.DOWN);
        scene.idle(10);
        scene.overlay().showText(70)
            .text("Stack two Mud Bricks where you want a Bloomery.")
            .pointAt(util.vector().topOf(lower))
            .attachKeyFrame()
            .placeNearTarget();
        scene.idle(60);

        // Reveal the second mud brick on top.
        scene.world().showSection(util.select().position(GRID_X, GRID_UPPER_Y, GRID_Z), Direction.DOWN);
        scene.idle(20);

        // Show a phantom right-click with a coal block on the lower brick.
        scene.overlay().showControls(util.vector().centerOf(lower), Pointing.DOWN, 60)
            .rightClick()
            .withItem(new ItemStack(Items.COAL_BLOCK));
        scene.idle(10);
        scene.overlay().showText(80)
            .text("Right-click the bottom brick with a Block of Coal to form the Bloomery.")
            .pointAt(util.vector().centerOf(lower))
            .attachKeyFrame()
            .placeNearTarget();
        scene.idle(90);

        // Transform the two bricks into a formed Bloomery (lower + upper segments).
        scene.world().setBlock(lower,
            BannerboundAntiquity.BLOOMERY.get().defaultBlockState()
                .setValue(BloomeryBlock.HALF, DoubleBlockHalf.LOWER)
                .setValue(BloomeryBlock.FACING, CONSTRUCTION_FACING), false);
        scene.world().setBlock(upper,
            BannerboundAntiquity.BLOOMERY.get().defaultBlockState()
                .setValue(BloomeryBlock.HALF, DoubleBlockHalf.UPPER)
                .setValue(BloomeryBlock.FACING, CONSTRUCTION_FACING), false);
        scene.idle(20);

        scene.overlay().showText(80)
            .text("Two mud bricks, one block of coal — and a Bloomery is born.")
            .pointAt(util.vector().topOf(upper))
            .placeNearTarget();
        scene.idle(70);
    }

    // ─── Scene 2: Operation ────────────────────────────────────────────────────────────────────

    /** Everything that happens at the formed Bloomery: open, insert, ignite, bellow, harvest. */
    public static void operation(SceneBuilder scene, SceneBuildingUtil util) {
        scene.title("bloomery_operation", "Using the Bloomery");
        scene.configureBasePlate(0, 0, 5);
        scene.scaleSceneView(0.85f);
        scene.showBasePlate();
        scene.idle(5);

        BlockPos lower = util.grid().at(GRID_X, GRID_LOWER_Y, GRID_Z);
        BlockPos upper = util.grid().at(GRID_X, GRID_UPPER_Y, GRID_Z);

        // Override the structure's saved facing so the door points at the camera. Done before
        // showSection so the reveal animates the already-rotated block — no visible re-rotate pop.
        scene.world().modifyBlock(lower, s -> s.setValue(BloomeryBlock.FACING, OPERATION_FACING), false);
        scene.world().modifyBlock(upper, s -> s.setValue(BloomeryBlock.FACING, OPERATION_FACING), false);

        // Reveal the whole 1×1×2 build.
        scene.world().showSection(
            util.select().fromTo(GRID_X, GRID_LOWER_Y, GRID_Z, GRID_X, GRID_UPPER_Y, GRID_Z),
            Direction.DOWN);
        scene.idle(15);

        scene.overlay().showText(80)
            .text("The Bloomery serves as the Antiquity-era forge.")
            .pointAt(util.vector().topOf(upper))
            .attachKeyFrame()
            .placeNearTarget();
        scene.idle(70);

        // ── Open the door ─────────────────────────────────────────────────────────────────
        scene.overlay().showControls(util.vector().centerOf(lower), Pointing.DOWN, 50).rightClick();
        scene.idle(5);
        modifyBloomery(scene, util, GRID_X, GRID_LOWER_Y, GRID_Z, tag -> {
            tag.putBoolean("Open", true);
            tag.putInt("AnimTicks", BloomeryBlockEntity.ANIM_TICKS);
        });
        scene.idle(15);
        scene.overlay().showText(70)
            .text("Shift + right-click to swing the door open.")
            .pointAt(util.vector().centerOf(lower))
            .placeNearTarget();
        scene.idle(70);

        // ── Insert raw iron ───────────────────────────────────────────────────────────────
        scene.overlay().showControls(util.vector().centerOf(lower), Pointing.DOWN, 50)
            .rightClick()
            .withItem(new ItemStack(Items.RAW_IRON));
        scene.idle(5);
        modifyBloomery(scene, util, GRID_X, GRID_LOWER_Y, GRID_Z, tag -> {
            tag.put("HeldItem", itemNbt("minecraft:raw_iron", 4));
            tag.putInt("InsertAnimTicks", BloomeryBlockEntity.SLIDE_TICKS);
        });
        scene.idle(10);
        scene.overlay().showText(70)
            .text("Right-click with ingredients to put inside.")
            .pointAt(util.vector().centerOf(lower))
            .attachKeyFrame()
            .placeNearTarget();
        scene.idle(70);

        // ── Close the door ────────────────────────────────────────────────────────────────
        modifyBloomery(scene, util, GRID_X, GRID_LOWER_Y, GRID_Z, tag -> {
            tag.putBoolean("Open", false);
            tag.putInt("AnimTicks", BloomeryBlockEntity.ANIM_TICKS);
        });
        scene.idle(15);

        // ── Ignite ────────────────────────────────────────────────────────────────────────
        scene.overlay().showControls(util.vector().centerOf(lower), Pointing.DOWN, 50)
            .rightClick()
            .withItem(new ItemStack(BannerboundAntiquity.FIRE_STICKS.get()));
        scene.idle(5);
        modifyBloomery(scene, util, GRID_X, GRID_LOWER_Y, GRID_Z, tag -> {
            tag.putInt("LitTicks", BloomeryBlockEntity.MAX_LIT_TICKS);
            tag.putBoolean("SmeltingActive", true);
        });
        scene.idle(15);
        scene.overlay().showText(80)
            .text("Ignite it. The flames will die down after a while.")
            .pointAt(util.vector().topOf(upper))
            .attachKeyFrame()
            .placeNearTarget();
        scene.idle(120);

        // ── Bellows stoke ─────────────────────────────────────────────────────────────────
        scene.overlay().showControls(util.vector().topOf(upper), Pointing.DOWN, 70)
            .rightClick()
            .withItem(new ItemStack(BannerboundAntiquity.BELLOWS_BLOCK_ITEM.get()));
        scene.idle(10);
        scene.overlay().showText(80)
            .text("Place a Bellows beside the Bloomery and jump on it to stoke the fire.")
            .pointAt(util.vector().topOf(upper))
            .attachKeyFrame()
            .placeNearTarget();
        scene.idle(120);

        // ── Smelt finishes — door pops open, raw iron has become ingots ───────────────────
        modifyBloomery(scene, util, GRID_X, GRID_LOWER_Y, GRID_Z, tag -> {
            tag.put("HeldItem", itemNbt("minecraft:iron_ingot", 4));
            tag.putInt("InsertAnimTicks", BloomeryBlockEntity.SLIDE_TICKS);
            tag.putInt("LitTicks", 0);
            tag.putInt("SmeltProgress", 0);
            tag.putBoolean("SmeltingActive", false);
            tag.putBoolean("Open", true);
            tag.putInt("AnimTicks", BloomeryBlockEntity.ANIM_TICKS);
        });
        scene.idle(15);
        scene.overlay().showText(90)
            .text("When the smelting is done, open the door and right-click empty-handed to take your output.")
            .pointAt(util.vector().centerOf(lower))
            .attachKeyFrame()
            .placeNearTarget();
        scene.idle(90);
    }

    // ─── Helpers ───────────────────────────────────────────────────────────────────────────────

    /** Rewrites the bloomery BE's NBT at the given grid cell — the BE reloads + the renderer
     *  picks up the new {@code Open}, {@code LitTicks}, {@code HeldItem}, … on the next tick. */
    private static void modifyBloomery(SceneBuilder scene, SceneBuildingUtil util,
                                        int x, int y, int z,
                                        java.util.function.Consumer<CompoundTag> mutator) {
        scene.world().modifyBlockEntityNBT(
            util.select().position(x, y, z),
            BloomeryBlockEntity.class,
            mutator);
    }

    /** Minimal 1.21.1 ItemStack NBT — id + count, components defaults to empty. */
    private static CompoundTag itemNbt(String id, int count) {
        CompoundTag t = new CompoundTag();
        t.putString("id", id);
        t.putInt("count", count);
        return t;
    }
}
