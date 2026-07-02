package com.bannerbound.core.social;

import net.minecraft.nbt.CompoundTag;

/**
 * One citizen's view of one other citizen. The record holds the raw score plus two reserved
 * overflow counters ({@code loverProgress}, {@code bestFriendProgress}) that v2 will use for
 * the mutex-overflow bars without forcing an NBT migration. {@code isFamily} flips the entry
 * into the permanent parent ↔ child bond, which short-circuits the score path entirely.
 * <p>
 * Records are immutable; mutation goes through {@link #withScoreDelta(int, long)} which clamps
 * the new score into {@code [-Relationships.MIN, Relationships.MAX]} and refreshes the
 * interact tick. Family relationships absorb deltas without changing — every conversation
 * outcome with a family member is a no-op on the score.
 */
public record Relationship(int score, int loverProgress, int bestFriendProgress,
                           long lastInteractTick, boolean isFamily) {
    public static final Relationship STRANGERS = new Relationship(0, 0, 0, 0L, false);
    /** Canonical family record: locked at the maximum score with the family flag set. The
     *  birth code overwrites both sides of the pair with this exact value. */
    public static final Relationship FAMILY = new Relationship(
        Relationships.MAX, 0, 0, 0L, true);

    public Relationship withScoreDelta(int delta, long now) {
        // Family ties are inert — neither score nor lastInteractTick moves on a family pair.
        // Without this guard, the conversation resolve path would silently downgrade FAMILY
        // by sliding the score below MAX (it'd still render as FAMILY because tier() honours
        // the flag, but the score field would drift, hurting the invariant).
        if (isFamily) return this;
        int next = Math.max(Relationships.MIN, Math.min(Relationships.MAX, score + delta));
        return new Relationship(next, loverProgress, bestFriendProgress, now, false);
    }

    /** Resolves the named tier. Family beats every score-based tier; otherwise score decides. */
    public RelationshipTier tier() {
        if (isFamily) return RelationshipTier.FAMILY;
        return RelationshipTier.of(score);
    }

    /** Writes the record to NBT. Empty defaults are omitted to keep saves compact. */
    public CompoundTag save() {
        CompoundTag t = new CompoundTag();
        if (score != 0)              t.putInt("S", score);
        if (loverProgress != 0)      t.putInt("L", loverProgress);
        if (bestFriendProgress != 0) t.putInt("B", bestFriendProgress);
        if (lastInteractTick != 0L)  t.putLong("T", lastInteractTick);
        if (isFamily)                t.putBoolean("F", true);
        return t;
    }

    /** Tolerant load — missing keys default to 0 / false. Old saves predating the family flag
     *  load with {@code isFamily = false} which matches the previous record's behaviour. */
    public static Relationship load(CompoundTag t) {
        return new Relationship(
            t.contains("S") ? t.getInt("S")  : 0,
            t.contains("L") ? t.getInt("L")  : 0,
            t.contains("B") ? t.getInt("B")  : 0,
            t.contains("T") ? t.getLong("T") : 0L,
            t.contains("F") && t.getBoolean("F"));
    }
}
