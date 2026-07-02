package com.bannerbound.antiquity.item;

import com.bannerbound.antiquity.BannerboundAntiquity;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.Tier;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.level.block.Block;

/**
 * Custom tool tiers for Antiquity.
 *
 * <p><b>Bone</b> sits <i>before</i> wood in the progression: lower durability than wood (48 vs 59)
 * but a touch faster to mine with (2.5 vs wood's 2.0). It mines the stone family for drops but
 * <b>no ores at all</b> (even coal) — the {@link #INCORRECT_FOR_BONE_TOOL} tag denies everything a
 * wooden tool can't mine PLUS every ore. Attack damage is left to each tool's own modifiers
 * (tier bonus 0), so the per-item numbers read exactly like the user-specified values.
 */
public final class ModTiers {
    private ModTiers() {}

    /** Blocks the bone tools can't get drops from: {@code #minecraft:incorrect_for_wooden_tool}
     *  ∪ {@code #c:ores} (see the datapack tag of the same name). */
    public static final TagKey<Block> INCORRECT_FOR_BONE_TOOL = TagKey.create(Registries.BLOCK,
        ResourceLocation.fromNamespaceAndPath(BannerboundAntiquity.MODID, "incorrect_for_bone_tool"));

    public static final Tier BONE = new Tier() {
        @Override public int getUses() { return 48; }            // < wood (59), > flint knife (26)
        @Override public float getSpeed() { return 2.5F; }       // slightly above wood (2.0)
        @Override public float getAttackDamageBonus() { return 0.0F; } // per-tool modifiers carry combat
        @Override public TagKey<Block> getIncorrectBlocksForDrops() { return INCORRECT_FOR_BONE_TOOL; }
        @Override public int getEnchantmentValue() { return 5; }
        @Override public Ingredient getRepairIngredient() { return Ingredient.of(Items.BONE); }
    };
}
