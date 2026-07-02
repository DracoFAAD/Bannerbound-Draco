package com.bannerbound.core.barbarian;

import java.util.Set;

/**
 * The resolved combat/behaviour capability of a camp, folded from every loadout entry whose gating
 * research is in the camp's known-tech set (see {@link BarbarianLoadoutLoader#resolve}). Capped at
 * the known set: a camp can never wield a weapon whose research the world hasn't (almost) reached.
 *
 * @param weaponKey   abstract weapon id (e.g. "fists", "spear", "bow") — for display/debug.
 * @param weaponItem  the concrete item id held in the main hand (e.g. "bannerboundantiquity:stone_spear"),
 *                    or "" for fists. Resolved to an {@code Item} at spawn (camp NPCs have no settlement).
 * @param meleeWeaponItem close-combat fallback for ranged users (a bowman switches to this up close);
 *                    "" means none / use {@link #weaponItem}.
 * @param weaponTier  ordering rank; the highest among known entries wins.
 * @param damage      melee (and thrown) half-hearts per hit — drives {@code CitizenCombatGoal}/ranged.
 * @param attackSpeed melee attacks per second.
 * @param ranged      whether members fire {@link #projectile} at range (thrown spears / arrows).
 * @param projectile  projectile entity id fired when {@link #ranged} (e.g. "bannerboundantiquity:spear").
 * @param behaviors   union of behaviour tags across known entries (e.g. "skirmisher", "brute").
 * @param squadWeight largest single-entry squad weight contribution (used by raid sizing).
 */
public record BarbarianCapability(String weaponKey, String weaponItem, String meleeWeaponItem,
                                  int weaponTier, double damage, double attackSpeed, boolean ranged,
                                  String projectile, Set<String> behaviors, int squadWeight) {

    /** A camp that knows nothing yet: bare fists. */
    public static final BarbarianCapability FISTS =
        new BarbarianCapability("fists", "", "", 0, 1.0, 1.0, false, "", Set.of(), 1);

    public boolean has(String behavior) {
        return behaviors.contains(behavior);
    }

    /** Ranged users that should hold their distance + kite (vs brutes that charge and melee). */
    public boolean kites() {
        return ranged && behaviors.contains("skirmisher");
    }
}
