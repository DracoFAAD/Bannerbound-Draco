package com.bannerbound.core.api.settlement;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.jetbrains.annotations.Nullable;

import com.bannerbound.core.api.research.ResearchManager;

/**
 * Hard-coded catalogue of the settlement policies. Policies are NOT data-driven — each one's
 * effect is wired at a specific hook point in code (a goal's canUse, a yield multiplier, an
 * assignment gate, etc.) keyed off {@link Settlement#hasPolicy(String)}. This registry only
 * holds the metadata the UI needs (display keys, government restriction) and the unlock flag a
 * research node sets to make the policy available.
 *
 * <p>A policy becomes <i>available</i> to a settlement when:
 * <ul>
 *   <li>its {@link Policy#governmentType} is null (general) or matches the settlement's current
 *       government, AND</li>
 *   <li>a completed research (science OR culture) lists the policy's {@link Policy#unlockFlag}
 *       in its {@code unlocks.flags} — set in JSON via {@code "unlocks": {"policy": ["id"]}},
 *       which the loaders fold into the flag {@code "unlock.policy.<id>"}.</li>
 * </ul>
 *
 * <p>To add a policy: add an entry here + a lang triple (name/description/effect) + the effect
 * wiring at its hook point + a research node that unlocks it. See {@code docs/policies.md}.
 */
public final class PolicyRegistry {
    private PolicyRegistry() {}

    /**
     * Metadata for one policy.
     *
     * <p>{@code type} is the policy's category — it fits a typed slot of that type (see
     * {@link PolicyType}). {@code governmentType} is now the <b>signature exclusivity</b> marker,
     * NOT a general availability filter: when non-null the policy is exclusive to that government
     * and occupies the government's single signature slot; when null the policy is global and
     * fits any typed slot of its {@link #type}. (The few signature policies still carry a real
     * {@code type} for their display glyph.)
     */
    public record Policy(
        String id,
        @Nullable Settlement.Government governmentType,
        PolicyType type,
        String nameKey,
        String descriptionKey,
        String effectKey,
        String unlockFlag
    ) {}

    // Policy ids — referenced from effect hook points + JSON unlocks.policy arrays.
    public static final String NIGHTSHIFT = "nightshift";
    public static final String WORKLOAD_SHARE = "workload_share";
    public static final String OPINIONATED_CROWD = "opinionated_crowd";
    public static final String DOMESTICATION = "domestication";
    public static final String AGRICULTURAL_EFFORT = "agricultural_effort";
    public static final String ROADS = "roads";
    public static final String RALLYING_SPEECHES = "rallying_speeches";
    public static final String GLORY_TALES = "glory_tales";
    /** Quarryworkers have a small chance to find common raw ore in natural stone (MINER_PLAN.md
     *  phase 2 — the scarcity FLOOR for ore-poor starts; deliberately worse than trade/deposits).
     *  Effect hook: {@code ProspectingQuarry.tryBonus} called from {@code DiggerWorkGoal.mineBlock}. */
    public static final String PROSPECTING_QUARRY = "prospecting_quarry";
    /** The watch stands through the night: guards skip sleep and keep patrolling/manning posts, at
     *  the cost of the {@code NIGHT_WATCH_WEARY} happiness thought on every guard. Effect hooks:
     *  {@code SleepGoal.canUse} (sleep exemption), {@code WorkGoal.isAfternoonGathering} (guards
     *  skip the pre-bed social window), {@code PolicyEffects.syncPolicyThoughts} (the weary
     *  thought). See GUARD_PLAN.md. */
    public static final String NIGHT_WATCH = "night_watch";

    private static final Map<String, Policy> BY_ID = new LinkedHashMap<>();

    private static void register(String id, @Nullable Settlement.Government gov, PolicyType type) {
        BY_ID.put(id, new Policy(
            id, gov, type,
            "bannerbound.policy." + id + ".name",
            "bannerbound.policy." + id + ".description",
            "bannerbound.policy." + id + ".effect",
            "unlock.policy." + id));
    }

    static {
        // Signature policies — exclusive to one government, occupy its signature slot.
        register(WORKLOAD_SHARE, Settlement.Government.CHIEFDOM, PolicyType.ECONOMIC);
        register(OPINIONATED_CROWD, Settlement.Government.COUNCIL, PolicyType.CULTURAL);
        // Global policies — fit any typed slot of their type, under any government.
        register(NIGHTSHIFT, null, PolicyType.ECONOMIC);
        register(DOMESTICATION, null, PolicyType.CULTURAL);
        register(AGRICULTURAL_EFFORT, null, PolicyType.ECONOMIC);
        register(ROADS, null, PolicyType.CULTURAL);
        register(RALLYING_SPEECHES, null, PolicyType.MILITARISTIC);
        register(GLORY_TALES, null, PolicyType.CULTURAL);
        register(PROSPECTING_QUARRY, null, PolicyType.SCIENTIFIC);
        register(NIGHT_WATCH, null, PolicyType.MILITARISTIC);
    }

    /** A policy is "signature" (government-exclusive, occupies the signature slot) iff it carries
     *  a government restriction. Global policies have a null government. */
    public static boolean isSignature(String policyId) {
        Policy p = get(policyId);
        return p != null && p.governmentType() != null;
    }

    /** The single signature policy a government can run, or null for NONE / no signature policy. */
    @Nullable
    public static String signaturePolicyFor(Settlement.Government gov) {
        if (gov == null) return null;
        for (Policy p : BY_ID.values()) {
            if (p.governmentType() == gov) return p.id();
        }
        return null;
    }

    @Nullable
    public static Policy get(String id) {
        return id == null ? null : BY_ID.get(id);
    }

    public static List<Policy> all() {
        return new ArrayList<>(BY_ID.values());
    }

    @Nullable
    public static String exclusiveWith(String policyId) {
        if (RALLYING_SPEECHES.equals(policyId)) return GLORY_TALES;
        if (GLORY_TALES.equals(policyId)) return RALLYING_SPEECHES;
        return null;
    }

    /** Whether {@code policyId} exists AND its government restriction matches the settlement's
     *  current government (or is general). Does NOT check the unlock flag — see
     *  {@link #isAvailable}. */
    public static boolean matchesGovernment(Settlement settlement, String policyId) {
        Policy p = get(policyId);
        if (p == null || settlement == null) return false;
        return p.governmentType() == null || p.governmentType() == settlement.governmentType();
    }

    /** Whether {@code policyId} is currently available to the settlement: government matches
     *  AND a completed research unlocks it. */
    public static boolean isAvailable(Settlement settlement, String policyId) {
        Policy p = get(policyId);
        if (p == null || settlement == null) return false;
        if (!matchesGovernment(settlement, policyId)) return false;
        return ResearchManager.hasFlagEitherTree(settlement, p.unlockFlag());
    }

    /** Ordered list of policy ids currently available to the settlement (government match +
     *  research unlock). Drives the right-hand "Available policies" list in the town hall. */
    public static List<String> availableFor(Settlement settlement) {
        List<String> out = new ArrayList<>();
        if (settlement == null) return out;
        for (Policy p : BY_ID.values()) {
            if (isAvailable(settlement, p.id())) out.add(p.id());
        }
        return out;
    }
}
