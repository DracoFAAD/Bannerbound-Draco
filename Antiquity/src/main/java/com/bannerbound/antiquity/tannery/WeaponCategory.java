package com.bannerbound.antiquity.tannery;

import javax.annotation.Nullable;

import com.bannerbound.antiquity.AntiquityEvents;
import com.bannerbound.antiquity.BannerboundAntiquity;
import com.bannerbound.antiquity.item.SpearItem;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.tags.TagKey;
import net.minecraft.world.item.ArrowItem;
import net.minecraft.world.item.BowItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.SwordItem;

/**
 * The four hunting-weapon categories used for hide-quality grading (RDR2-style preferred weapon):
 * matching an animal's preferred category yields a GREAT hide; a different valid category yields
 * STANDARD; no valid weapon yields POOR. Detection is tag/instanceof based so the per-species
 * preference table ({@code hide_preferences/*.json}) stays the only thing a designer edits.
 */
public enum WeaponCategory {
    BLADE,
    SPEAR,
    ARROW,
    BLUNT;

    /** Blunt weapons (the bone club). */
    public static final TagKey<Item> BLUNT_WEAPONS = TagKey.create(Registries.ITEM,
        ResourceLocation.fromNamespaceAndPath(BannerboundAntiquity.MODID, "blunt_weapons"));
    /** Bows that fire huntable arrows (Core tag, shared with the hunter). */
    public static final TagKey<Item> HUNTER_BOWS = TagKey.create(Registries.ITEM,
        ResourceLocation.fromNamespaceAndPath("bannerbound", "hunter_bows"));

    /**
     * The category of a held weapon, or {@code null} when it isn't a recognized hunting weapon
     * (fists, a pickaxe, …). Arrow kills are usually resolved from the projectile by the caller; a
     * held bow still maps to ARROW here for completeness.
     */
    @Nullable
    public static WeaponCategory of(ItemStack weapon) {
        if (weapon == null || weapon.isEmpty()) return null;
        Item item = weapon.getItem();
        if (item instanceof SpearItem) return SPEAR;
        if (weapon.is(BLUNT_WEAPONS)) return BLUNT;
        if (weapon.is(HUNTER_BOWS) || item instanceof BowItem || item instanceof ArrowItem) return ARROW;
        if (weapon.is(AntiquityEvents.CUTTING_TOOLS) || item instanceof SwordItem) return BLADE;
        return null;
    }
}
