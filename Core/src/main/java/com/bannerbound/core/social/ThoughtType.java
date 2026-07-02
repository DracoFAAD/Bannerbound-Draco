package com.bannerbound.core.social;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.RandomSource;

/**
 * A kind of citizen thought (a happiness/mood modifier) — the extensible, registry-backed model that
 * replaces the old enum-only one. Core's built-in thoughts are the {@link ThoughtKind} enum (each
 * constant <em>is</em> a {@code ThoughtType}); any mod can define its own with {@link #builder} +
 * {@link ThoughtTypes#register}, then attach it to a citizen via
 * {@code citizen.getThoughts().add(type, partner, now, rng)} exactly like a built-in.
 *
 * <p>Persisted and looked up by {@link #id()} (a stable {@link ResourceLocation}), so registration
 * order is irrelevant and addon thoughts survive save/load. The network never serialises the type —
 * the server resolves {@link #labelKey()} to a Component before sending — so new types need no
 * protocol change.
 */
public interface ThoughtType {
    /** Stable id; the persistence + registry key. Core built-ins are {@code bannerbound:<name>}. */
    ResourceLocation id();

    /** Translation key for the label ({@code %s} = partner name for per-partner thoughts). */
    String labelKey();

    /** Base signed happiness delta while active. */
    int modifier();

    /** True if this thought never auto-expires (cleared explicitly when its condition resolves). */
    boolean isInfinite();

    /** True if it binds to a partner citizen — one entry per {@code (type, partner)} pair instead of
     *  one per type (conversation outcomes, per-child, per-death, …). */
    boolean isPerPartner();

    /** Which happiness pillar this thought contributes to. Defaults to {@link HappinessCategory#SOCIETY}
     *  so legacy/addon thoughts without an explicit category still aggregate somewhere sensible. */
    default HappinessCategory category() { return HappinessCategory.SOCIETY; }

    /** True if the hit deepens the longer the thought stays active. */
    boolean escalates();

    /** Current modifier for a thought of this type that has aged {@code ageTicks} (ramps toward the
     *  escalation floor if {@link #escalates()}, otherwise just {@link #modifier()}). */
    int modifierAt(long ageTicks);

    /** Rolls a fresh-thought duration in ticks, or {@link ThoughtKind#INFINITE_DURATION} if infinite. */
    int rollDurationTicks(RandomSource rng);

    /** Start a builder for an addon-defined thought. Call {@link ThoughtTypes#register} on the result. */
    static Builder builder(ResourceLocation id) {
        return new Builder(id);
    }

    /** Fluent builder producing an immutable {@code ThoughtType}. Mirrors the {@link ThoughtKind}
     *  constructor knobs: a label, a modifier, an optional finite duration, optional per-partner
     *  binding, and optional escalation. */
    final class Builder {
        private final ResourceLocation id;
        private String labelKey = "";
        private int modifier;
        private int minDuration = ThoughtKind.INFINITE_DURATION;
        private int maxDuration = ThoughtKind.INFINITE_DURATION;
        private boolean perPartner;
        private int escalationFloor;
        private boolean escalationSet;
        private int escalationRamp;
        private HappinessCategory category = HappinessCategory.SOCIETY;

        private Builder(ResourceLocation id) {
            this.id = id;
        }

        public Builder label(String key) { this.labelKey = key; return this; }
        public Builder modifier(int m) { this.modifier = m; return this; }
        /** The happiness pillar this thought feeds (Food/Culture/Comfort/Society). */
        public Builder category(HappinessCategory c) { this.category = c; return this; }

        /** Random duration in {@code [minTicks, maxTicks]}. Omit for a thought that persists until
         *  explicitly removed (e.g. an infinite "no home" grievance). */
        public Builder duration(int minTicks, int maxTicks) {
            this.minDuration = minTicks;
            this.maxDuration = maxTicks;
            return this;
        }

        public Builder perPartner() { this.perPartner = true; return this; }

        /** The hit deepens from {@link #modifier(int)} to {@code floor} over {@code rampTicks} of
         *  continuous activity (e.g. an ignored grievance festering). */
        public Builder escalating(int floor, int rampTicks) {
            this.escalationFloor = floor;
            this.escalationSet = true;
            this.escalationRamp = rampTicks;
            return this;
        }

        public ThoughtType build() {
            int floor = escalationSet ? escalationFloor : modifier;
            return new SimpleThoughtType(id, labelKey, modifier, minDuration, maxDuration,
                perPartner, floor, escalationRamp, category);
        }
    }

    /** Immutable {@link ThoughtType} backing the {@link Builder}; addon thoughts are instances of this. */
    record SimpleThoughtType(ResourceLocation id, String labelKey, int modifier, int minDurationTicks,
                             int maxDurationTicks, boolean isPerPartner, int escalationFloor,
                             int escalationRampTicks, HappinessCategory category) implements ThoughtType {
        @Override
        public boolean isInfinite() {
            return minDurationTicks == ThoughtKind.INFINITE_DURATION;
        }

        @Override
        public boolean escalates() {
            return escalationRampTicks > 0 && escalationFloor != modifier;
        }

        @Override
        public int modifierAt(long ageTicks) {
            if (!escalates() || ageTicks <= 0) return modifier;
            if (ageTicks >= escalationRampTicks) return escalationFloor;
            double t = ageTicks / (double) escalationRampTicks;
            return (int) Math.round(modifier + t * (escalationFloor - modifier));
        }

        @Override
        public int rollDurationTicks(RandomSource rng) {
            if (isInfinite()) return ThoughtKind.INFINITE_DURATION;
            if (maxDurationTicks <= minDurationTicks) return minDurationTicks;
            return minDurationTicks + rng.nextInt(maxDurationTicks - minDurationTicks + 1);
        }
    }
}
