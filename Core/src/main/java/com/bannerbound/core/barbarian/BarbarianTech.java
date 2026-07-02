package com.bannerbound.core.barbarian;

import java.util.HashSet;
import java.util.Set;

import com.bannerbound.core.api.research.ResearchDefinition;
import com.bannerbound.core.api.research.ToolAge;
import com.bannerbound.core.api.research.data.ResearchTreeLoader;
import com.bannerbound.core.api.research.data.ToolAgeLoader;
import com.bannerbound.core.api.settlement.Era;
import com.bannerbound.core.api.settlement.Settlement;
import com.bannerbound.core.api.settlement.SettlementData;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

/**
 * Derives barbarian tech purely from world progress: camps are always exactly one research behind
 * the most advanced settlement.
 *
 * <p>"Most advanced" is the LIVE settlement furthest along right now (highest era, then most
 * completed research) â€” NOT the monotonic global "ever researched by anyone" set, which retains
 * research from disbanded settlements, {@code /bannerbound unlock} prereq-walks, and undone
 * {@code unresearch} commands. Barbarians know everything that settlement currently has EXCEPT its
 * single most-recent completion.
 *
 * <p>The global completion-ORDER log ({@link SettlementData#getGlobalResearchOrder()}) is used only
 * to identify which of the lead settlement's completions came last (so we drop the right one). When
 * the lead settlement completes archery, archery becomes its newest entry and the prior one drops
 * into the camp known-set â€” camps then start using primitive bows. No per-camp tech state, no
 * simulation; a pure function evaluated when a camp is observed or a raid is built.
 */
public final class BarbarianTech {
    private BarbarianTech() {
    }

    /** The live settlement furthest along (era first, then completed-research count), or null. */
    public static Settlement mostAdvanced(SettlementData data) {
        Settlement lead = null;
        for (Settlement s : data.all()) {
            if (lead == null) {
                lead = s;
                continue;
            }
            int byEra = s.age().ordinal() - lead.age().ordinal();
            if (byEra > 0 || (byEra == 0
                    && s.completedResearches().size() > lead.completedResearches().size())) {
                lead = s;
            }
        }
        return lead;
    }

    /** The single research the camps are "behind" on â€” the lead settlement's most-recent completion
     *  (by world completion order), or null if undeterminable. */
    public static String frontier(SettlementData data) {
        Settlement lead = mostAdvanced(data);
        if (lead == null) return null;
        return frontierOf(lead, data);
    }

    private static String frontierOf(Settlement lead, SettlementData data) {
        Set<String> completed = lead.completedResearches();
        String frontier = null;
        for (String id : data.getGlobalResearchOrder()) {
            if (completed.contains(id)) frontier = id; // last match wins â†’ most-recent completion
        }
        return frontier;
    }

    /** What barbarians know: the most advanced settlement's CURRENT completed research, minus its
     *  single most-recent. Empty until some settlement has completed more than one research. */
    public static Set<String> campKnownTech(SettlementData data) {
        Settlement lead = mostAdvanced(data);
        if (lead == null) return Set.of();
        Set<String> completed = lead.completedResearches();
        if (completed.size() <= 1) return Set.of(); // need >1 to be a research "behind"
        Set<String> known = new HashSet<>(completed);
        String frontier = frontierOf(lead, data);
        if (frontier != null) known.remove(frontier); // drop the lead's newest; one behind
        return known;
    }

    /** Highest {@code min_age} across the known set â€” the era a camp visually/equipment-wise sits in. */
    public static Era techEra(Set<String> knownTech) {
        Era era = Era.ANCIENT;
        for (String id : knownTech) {
            ResearchDefinition def = ResearchTreeLoader.get(id);
            if (def != null && def.minAge().ordinal() > era.ordinal()) {
                era = def.minAge();
            }
        }
        return era;
    }

    private static final String SET_TOOL_AGE = "bannerbound.set_tool_age:";
    /** Thrown-spear projectile entity id (the SpearProjectile renders the held spear). */
    private static final String SPEAR_PROJECTILE = "bannerboundantiquity:spear";

    /** The camp's tool age â€” the highest {@code set_tool_age} among its known research (already one
     *  behind the frontier via {@link #campKnownTech}), defaulting to the base "bone" age. So when the
     *  lead settlement reaches knapping (stone), the camp sits at the prior age (wood/woodworking). */
    public static ToolAge campToolAge(Set<String> known) {
        ToolAge best = ToolAgeLoader.get("bone"); // base age â€” bone tools predate any research
        int bestOrder = best == null ? Integer.MIN_VALUE : best.order();
        for (String id : known) {
            ResearchDefinition def = ResearchTreeLoader.get(id);
            if (def == null) continue;
            for (String feat : def.unlocksFeatures()) {
                if (!feat.startsWith(SET_TOOL_AGE)) continue;
                ToolAge age = ToolAgeLoader.get(feat.substring(SET_TOOL_AGE.length()).trim());
                if (age != null && age.order() > bestOrder) {
                    best = age;
                    bestOrder = age.order();
                }
            }
        }
        return best;
    }

    /**
     * Builds the camp's capability from its TOOL AGE (the research tree already orders boneâ†’woodâ†’
     * stoneâ†’iron via {@code set_tool_age}) plus a thin ranged override: if a known research grants a
     * ranged loadout (archery â†’ bow), the camp wields that with the tool-age weapon as its melee
     * fallback; otherwise it uses the tool-age weapon directly (spears are throwable, swords melee-only).
     */
    public static BarbarianCapability capability(Set<String> known) {
        ToolAge age = campToolAge(known);
        Item meleeItem = age == null ? Items.AIR
            : age.tools().getOrDefault("spear", age.tools().getOrDefault("sword", Items.AIR));
        String meleeId = meleeItem == Items.AIR ? "" : BuiltInRegistries.ITEM.getKey(meleeItem).toString();
        int tier = age == null ? 0 : age.order();
        double dmg = age == null ? 2.0 : age.weaponDamage();
        double atk = age == null ? 1.2 : age.weaponAttackSpeed();

        // Ranged override: a bow if the camp knows archery (gated in data on the archery research).
        BarbarianLoadoutLoader.Entry bow = BarbarianLoadoutLoader.rangedOverride(known);
        if (bow != null) {
            Set<String> behaviors = bow.behavior().isEmpty() ? Set.of() : Set.of(bow.behavior());
            return new BarbarianCapability(bow.weapon(), bow.weaponItem(), meleeId, tier + 1,
                bow.damage(), bow.attackSpeed(), true, bow.projectile(), behaviors, bow.squadWeight());
        }
        boolean isSpear = age != null && age.tools().containsKey("spear"); // spears are thrown; swords aren't
        return new BarbarianCapability(age == null ? "fists" : age.id() + "_weapon", meleeId, "", tier,
            dmg, atk, isSpear, isSpear ? SPEAR_PROJECTILE : "", Set.of("brute"), 1);
    }

    /** Convenience: the resolved capability for the current world frontier. */
    public static BarbarianCapability currentCapability(SettlementData data) {
        return capability(campKnownTech(data));
    }

    /** Combat weapon roles in the tool-age tools map, in display preference order. */
    private static final String[] COMBAT_ROLES = {"spear", "sword", "club"};

    /**
     * A PER-MEMBER capability so a camp isn't a uniform wall of one weapon (the player saw "all spears").
     * Picks a random combat role present in the tool age â€” spear (thrown), sword or club (melee) â€” using
     * {@code rng} (seed it per member for a stable-but-varied roster). If the camp knows archery, ~60% of
     * members become bow-kiters (mixed archer/melee squads) with the tool-age weapon as melee fallback.
     */
    public static BarbarianCapability memberCapability(Set<String> known, net.minecraft.util.RandomSource rng) {
        ToolAge age = campToolAge(known);
        int tier = age == null ? 0 : age.order();
        double dmg = age == null ? 2.0 : age.weaponDamage();
        double atk = age == null ? 1.2 : age.weaponAttackSpeed();
        Item meleeFallback = age == null ? Items.AIR
            : age.tools().getOrDefault("sword", age.tools().getOrDefault("spear", Items.AIR));
        String meleeFallbackId = meleeFallback == Items.AIR ? ""
            : BuiltInRegistries.ITEM.getKey(meleeFallback).toString();

        BarbarianLoadoutLoader.Entry bow = BarbarianLoadoutLoader.rangedOverride(known);
        if (bow != null && rng.nextFloat() < 0.6f) {
            Set<String> behaviors = bow.behavior().isEmpty() ? Set.of() : Set.of(bow.behavior());
            return new BarbarianCapability(bow.weapon(), bow.weaponItem(), meleeFallbackId, tier + 1,
                bow.damage(), bow.attackSpeed(), true, bow.projectile(), behaviors, bow.squadWeight());
        }

        java.util.List<String> roles = new java.util.ArrayList<>();
        if (age != null) {
            for (String role : COMBAT_ROLES) {
                if (age.tools().containsKey(role)) roles.add(role);
            }
        }
        if (roles.isEmpty()) {
            return new BarbarianCapability("fists", "", "", tier, dmg, atk, false, "", Set.of("brute"), 1);
        }
        String role = roles.get(rng.nextInt(roles.size()));
        Item weapon = age.tools().get(role);
        String weaponId = weapon == null || weapon == Items.AIR ? ""
            : BuiltInRegistries.ITEM.getKey(weapon).toString();
        boolean thrown = role.equals("spear"); // only spears are hurled; swords/clubs melee-only
        return new BarbarianCapability(age.id() + "_" + role, weaponId, "", tier, dmg, atk,
            thrown, thrown ? SPEAR_PROJECTILE : "", Set.of("brute"), 1);
    }
}
