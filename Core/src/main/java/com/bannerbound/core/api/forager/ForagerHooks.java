package com.bannerbound.core.api.forager;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Expansion hook for the forager's {@link ForageCategory#STICKS_FIBERS scavenging} yields —
 * the same shape as the player's cutting-tool harvest (Antiquity's {@code onCuttingHarvest}),
 * which Core can't replicate itself because the fiber item lives in the expansion.
 *
 * <p>Core's default yields sticks from leaves only (vanilla items). Antiquity replaces the
 * handler in its common setup to add plant fibers from grass, mirroring the player's knife
 * chances so a forager is exactly as productive as a player swinging a blade.
 */
public final class ForagerHooks {

    /** Extra drops when a forager scavenge-breaks {@code state} (additive to bare-hand loot). */
    @FunctionalInterface
    public interface ScavengeYield {
        List<ItemStack> yield(ServerLevel sl, BlockState state, RandomSource rng);
    }

    /** Core default: sticks from leaves at the player cutting-harvest rate, plus a forager-only
     *  trickle of wheat seeds from grass for the farmer chain. Fiber from grass is the expansion's
     *  to add (it owns the item); Antiquity replaces this whole handler with fiber + seeds. */
    private static ScavengeYield scavengeYield = (sl, state, rng) -> {
        if (state.is(net.minecraft.tags.BlockTags.LEAVES) && rng.nextFloat() < 0.40f) {
            List<ItemStack> out = new ArrayList<>(1);
            out.add(new ItemStack(Items.STICK, 1 + rng.nextInt(2)));
            return out;
        }
        if (isGrassy(state) && rng.nextFloat() < 0.25f) {
            List<ItemStack> out = new ArrayList<>(1);
            out.add(new ItemStack(Items.WHEAT_SEEDS, 1));
            return out;
        }
        return List.of();
    };

    private static boolean isGrassy(BlockState state) {
        return state.is(net.minecraft.world.level.block.Blocks.SHORT_GRASS)
            || state.is(net.minecraft.world.level.block.Blocks.TALL_GRASS)
            || state.is(net.minecraft.world.level.block.Blocks.FERN)
            || state.is(net.minecraft.world.level.block.Blocks.LARGE_FERN);
    }

    private ForagerHooks() {
    }

    /** Installs the expansion's yield handler (replaces the Core default — call once in setup). */
    public static void setScavengeYield(ScavengeYield handler) {
        if (handler != null) scavengeYield = handler;
    }

    public static List<ItemStack> scavenge(ServerLevel sl, BlockState state, RandomSource rng) {
        return scavengeYield.yield(sl, state, rng);
    }
}
