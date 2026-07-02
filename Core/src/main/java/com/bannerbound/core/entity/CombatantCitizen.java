package com.bannerbound.core.entity;

import net.minecraft.world.item.Item;

/**
 * A settlement-less {@link CitizenEntity} fighter whose combat stats come from an explicit loadout
 * rather than a settlement's tool age — barbarians and city-state mercenaries. {@link CitizenCombatGoal}
 * reads these instead of the settlement lookup, so both fight with their assigned weapon/damage.
 */
public interface CombatantCitizen {
    /** Melee/thrown damage in half-hearts ({@code <= 0} = fall back to bare hands). */
    double combatDamage();

    /** Attacks per second ({@code <= 0} = default). */
    double combatAttackSpeed();

    /** The weapon to hold/swap to in melee (may be null / AIR). */
    Item meleeItem();

    /** A kiting bowman that only melees when cornered. */
    boolean prefersRanged();

    /** Per-member chase-speed variance multiplier. */
    double combatSpeed();
}
