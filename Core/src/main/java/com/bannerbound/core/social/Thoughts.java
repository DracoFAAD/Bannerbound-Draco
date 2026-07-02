package com.bannerbound.core.social;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;

import javax.annotation.Nullable;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.util.RandomSource;

/**
 * One citizen's active list of {@link Thought}s. Source of truth for the citizen's happiness
 * modifier: aggregate happiness = {@link #BASE_HAPPINESS} + sum of every thought's
 * {@link Thought#modifier()}, clamped to {@code [0, 100]}.
 *
 * <p>Mutation API (only three operations):
 * <ul>
 *   <li>{@link #add(ThoughtKind, UUID, long, RandomSource)} — add or refresh.</li>
 *   <li>{@link #remove(ThoughtKind, UUID)} — drop a specific entry (used to clear UNEMPLOYED
 *       when a workstation is assigned, etc.).</li>
 *   <li>{@link #tick(long)} — remove expired entries; returns true iff anything changed so the
 *       caller can decide whether to recompute & resync happiness.</li>
 * </ul>
 *
 * <p>NBT layout (list of compound tags under the {@code "Thoughts"} key on {@link
 * com.bannerbound.core.entity.CitizenEntity}): see {@link Thought#save()}.
 */
public final class Thoughts {
    /** Neutral baseline. A citizen with no active thoughts sits at exactly 60/100. */
    public static final int BASE_HAPPINESS = 60;
    public static final int MIN_HAPPINESS = 0;
    public static final int MAX_HAPPINESS = 100;

    private final List<Thought> entries = new ArrayList<>();

    /** True iff there are no active thoughts — used to skip writing an empty list tag. */
    public boolean isEmpty() { return entries.isEmpty(); }

    /** Unmodifiable snapshot view. The screen payload builds its transport rows from this. */
    public List<Thought> entries() { return Collections.unmodifiableList(entries); }

    /** Satisfaction (0–100) with one happiness pillar at game-time {@code now}: the neutral
     *  {@link #BASE_HAPPINESS} baseline plus every active thought in that category, clamped. */
    public int categorySatisfaction(HappinessCategory cat, long now) {
        int sum = BASE_HAPPINESS;
        for (Thought t : entries) {
            if (t.kind() != null && t.kind().category() == cat) sum += t.effectiveModifier(now);
        }
        if (sum < MIN_HAPPINESS) return MIN_HAPPINESS;
        if (sum > MAX_HAPPINESS) return MAX_HAPPINESS;
        return sum;
    }

    /** Overall happiness (0–100) = the AVERAGE of the four pillar satisfactions, so each pillar
     *  (Food/Culture/Comfort/Society) is an equal 25-point slice. Reads each thought's
     *  {@link Thought#effectiveModifier(long)} so escalating grievances deepen as they age. */
    public int aggregateHappiness(long now) {
        HappinessCategory[] cats = HappinessCategory.values();
        int total = 0;
        for (HappinessCategory c : cats) total += categorySatisfaction(c, now);
        return Math.round(total / (float) cats.length);
    }

    /**
     * Adds a thought of {@code kind}, or refreshes the existing entry if one is already present
     * for this {@code (kind, partner)} pair. {@code partner} is {@code null} for solo thoughts.
     * Returns the resulting {@link Thought} (whether new or refreshed) for the caller's benefit
     * (e.g. to mark the settlement dirty only when something actually changed).
     */
    public Thought add(ThoughtType kind, @Nullable UUID partner, long now, RandomSource rng) {
        return add(kind, partner, null, now, rng);
    }

    /** Overload that snapshots the partner's bare-string name into the thought — used by
     *  death thoughts so the label still reads correctly after the partner entity is gone.
     *  See {@link Thought#savedPartnerName()} for the rationale. */
    public Thought add(ThoughtType kind, @Nullable UUID partner, @Nullable String savedName,
                       long now, RandomSource rng) {
        int duration = kind.rollDurationTicks(rng);
        long expire = duration == ThoughtKind.INFINITE_DURATION
            ? Thought.INFINITE_EXPIRY
            : now + duration;
        Thought next = new Thought(kind, kind.modifier(), expire, duration, now, partner, savedName);
        // Refresh path: replace any existing entry for this (kind, partner) pair.
        for (int i = 0; i < entries.size(); i++) {
            Thought t = entries.get(i);
            if (sameKind(t.kind(), kind) && sameOther(t.otherUuid(), partner)) {
                entries.set(i, next);
                return next;
            }
        }
        entries.add(next);
        return next;
    }

    /** Adds/refreshes a thought with an EXPLICIT instance modifier instead of the kind's default —
     *  for thoughts whose magnitude varies per occurrence (e.g. ENJOYED_MEAL scaled by food tier ×
     *  variety). Only valid for non-escalating kinds (the custom modifier is what aggregation and the
     *  screen read via {@link Thought#effectiveModifier(long)}). */
    public Thought addWithModifier(ThoughtType kind, @Nullable UUID partner, int modifier,
                                   long now, RandomSource rng) {
        int duration = kind.rollDurationTicks(rng);
        long expire = duration == ThoughtKind.INFINITE_DURATION ? Thought.INFINITE_EXPIRY : now + duration;
        Thought next = new Thought(kind, modifier, expire, duration, now, partner, null);
        for (int i = 0; i < entries.size(); i++) {
            Thought t = entries.get(i);
            if (sameKind(t.kind(), kind) && sameOther(t.otherUuid(), partner)) {
                entries.set(i, next);
                return next;
            }
        }
        entries.add(next);
        return next;
    }

    /** Removes the entry for {@code (kind, partner)} if present. Returns true iff anything
     *  changed — caller decides whether that warrants a happiness resync. */
    public boolean remove(ThoughtType kind, @Nullable UUID partner) {
        for (int i = 0; i < entries.size(); i++) {
            Thought t = entries.get(i);
            if (sameKind(t.kind(), kind) && sameOther(t.otherUuid(), partner)) {
                entries.remove(i);
                return true;
            }
        }
        return false;
    }

    /** True iff a thought of this kind/partner is currently active. Cheap — used by aiStep to
     *  decide whether the UNEMPLOYED thought needs to be added (avoids re-adding every tick). */
    public boolean has(ThoughtType kind, @Nullable UUID partner) {
        for (Thought t : entries) {
            if (sameKind(t.kind(), kind) && sameOther(t.otherUuid(), partner)) return true;
        }
        return false;
    }

    /** Type identity — by reference (built-ins/registered types are singletons) or, defensively, by id. */
    private static boolean sameKind(ThoughtType a, ThoughtType b) {
        return a == b || (a != null && b != null && a.id().equals(b.id()));
    }

    /** Drops every expired entry (skipping infinite ones). Returns true iff anything changed. */
    public boolean tick(long now) {
        boolean changed = false;
        Iterator<Thought> it = entries.iterator();
        while (it.hasNext()) {
            if (it.next().isExpired(now)) {
                it.remove();
                changed = true;
            }
        }
        return changed;
    }

    public ListTag save() {
        ListTag list = new ListTag();
        for (Thought t : entries) list.add(t.save());
        return list;
    }

    /** Tolerant load. Entries with an unknown kind ordinal are silently dropped. */
    public void load(ListTag list) {
        entries.clear();
        if (list == null) return;
        for (Tag t : list) {
            if (!(t instanceof CompoundTag ct)) continue;
            Thought th = Thought.load(ct);
            if (th != null) entries.add(th);
        }
    }

    private static boolean sameOther(@Nullable UUID a, @Nullable UUID b) {
        if (a == null && b == null) return true;
        if (a == null || b == null) return false;
        return a.equals(b);
    }
}
