package com.bannerbound.antiquity.block;

import com.mojang.serialization.MapCodec;

import net.minecraft.world.level.block.CampfireBlock;

/**
 * A campfire that renders WITHOUT its flame (just the glowing logs) — the {@code stone_cooking_pot}
 * swaps a vanilla campfire for this while a pot sits on top, so the pot can rest down in the fire
 * without the flame poking up through it, then swaps it back when the pot is removed (see {@link
 * StoneCookingPotBlock}). A real block change always re-meshes, so this works under any renderer
 * (Sodium/Iris included) — unlike model-data tricks that race the chunk build.
 *
 * <p>Extends {@link CampfireBlock} so it stays {@code instanceof CampfireBlock} (the pot's heat checks
 * keep working) and is a FULL campfire — same light, shape, roasting and block entity (added to the
 * vanilla campfire {@code BlockEntityType} in {@code BannerboundAntiquity}); only its rendered flame is
 * gone (a flame-less log blockstate model). So nothing is lost vs a normal campfire except the flame.
 */
public class CookingFireBlock extends CampfireBlock {
    public static final MapCodec<CookingFireBlock> CODEC = simpleCodec(CookingFireBlock::new);

    public CookingFireBlock(Properties properties) {
        super(true, 1, properties);   // same spawnParticles/fire-damage as a vanilla campfire
    }

    @Override
    @SuppressWarnings("unchecked")
    public MapCodec<CampfireBlock> codec() {
        return (MapCodec<CampfireBlock>) (MapCodec<?>) CODEC;
    }
}
