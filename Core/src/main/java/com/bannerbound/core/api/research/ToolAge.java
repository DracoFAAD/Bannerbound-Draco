package com.bannerbound.core.api.research;

import java.util.Map;
import java.util.OptionalInt;

import net.minecraft.network.chat.Component;
import net.minecraft.world.item.Item;

/**
 * A parsed tool-age entry. Represents one tier of tools (stone, iron, …) that a settlement can
 * advance to via research. {@link #order} is the lever for "higher tier wins" — settlements only
 * upgrade their current age when a new research grants one with a higher order.
 * <p>
 * {@link #tools} is open-ended: keys are role strings (e.g. "axe", "shovel", "hoe", "sword") that
 * workers look up to find the item they're equipped with. Modders can introduce new roles by
 * adding tool ages that include them; no schema change needed.
 *
 * @param id                 datapack id of this age (filename minus .json)
 * @param displayName        what to show in tooltips (lang-key resolved or literal fallback)
 * @param order              monotonic tier index; higher = more advanced
 * @param chopTicks          overrides {@code ForesterWorkGoal}'s default chop wind-up; empty = use the
 *                           goal's default (30 ticks for bare-handed)
 * @param mineTicks          overrides {@code DiggerWorkGoal}'s default mine wind-up; empty = use
 *                           the goal's default (80 ticks for bare-handed). JSON key: {@code mine_speed}
 *                           (semantically "ticks per block" — lower is faster).
 * @param harvestTicks       overrides {@code FarmerWorkGoal}'s default till/plant/harvest wind-up;
 *                           empty = use the goal's default (70 ticks for bare-handed). JSON key:
 *                           {@code harvest_speed}.
 * @param weaponDamage       half-hearts per swing when a citizen wields the age's {@code sword} in
 *                           {@code CitizenCombatGoal}. JSON key: {@code weapon_damage}. Default 4.0
 *                           (wood-sword baseline) when omitted.
 * @param weaponAttackSpeed  attacks per second for the age's sword. JSON key:
 *                           {@code weapon_attack_speed}. Combat cooldown = {@code 20 / value}
 *                           ticks per swing; e.g. {@code 1.6} → ~12 ticks. Default 1.6.
 * @param tools              role → item map. {@link Item#getDefaultInstance()} on lookup if needed.
 */
public record ToolAge(String id, Component displayName, int order, OptionalInt chopTicks,
                      OptionalInt mineTicks, OptionalInt harvestTicks,
                      double weaponDamage, double weaponAttackSpeed,
                      Map<String, Item> tools) {
}
