package com.bannerbound.core.social;

import java.util.UUID;

import javax.annotation.Nullable;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtUtils;

/**
 * One active per-citizen thought. Mutable in name only — {@link #expireGameTime} is set on
 * creation/refresh and never changes afterwards; the "tick" is simply comparing the world's
 * current game time against this stored absolute. That keeps the tick loop trivial (no
 * decrement per thought per tick) and makes save/load idempotent — a thought saved at
 * gameTime=1000 and loaded at gameTime=2000 still expires at the same absolute tick.
 *
 * <p>Two flavours:
 * <ul>
 *   <li><b>Solo</b> ({@code otherUuid == null}): one instance per kind per citizen. Adding
 *       refreshes (replaces) the existing entry.</li>
 *   <li><b>Per-partner</b> ({@code otherUuid != null}): one instance per {@code (kind, partner)}
 *       pair. Two simultaneous "Argument with X" / "Argument with Y" entries are fine.</li>
 * </ul>
 *
 * <p>{@code totalDurationTicks} is preserved so the client can render a "time remaining /
 * total" progress bar without separate bookkeeping. For infinite thoughts both
 * {@code expireGameTime} and {@code totalDurationTicks} are {@code -1}.
 */
public record Thought(
    ThoughtType kind,
    int modifier,
    long expireGameTime,
    int totalDurationTicks,
    /** Absolute game-time the thought was created/refreshed. Used by escalating kinds to
     *  compute how long the grievance has festered (see {@link #effectiveModifier(long)}). */
    long startGameTime,
    @Nullable UUID otherUuid,
    /** Snapshot of the partner's bare-string name at thought-creation time. Set when the
     *  partner may no longer be lookup-able by UUID — death thoughts capture the dead citizen's
     *  name here so the label still reads cleanly after the entity is gone from the world.
     *  Null for thoughts whose partner is expected to remain resolvable (conversation outcomes,
     *  child-born) — those still go through the UUID-resolution path at screen-build time. */
    @Nullable String savedPartnerName
) {
    /** Sentinel matching {@link ThoughtKind#INFINITE_DURATION} for {@code expireGameTime}. */
    public static final long INFINITE_EXPIRY = -1L;

    /** True iff {@code now} is past this thought's expiry. Infinite thoughts never expire. */
    public boolean isExpired(long now) {
        return expireGameTime != INFINITE_EXPIRY && now >= expireGameTime;
    }

    /** Ticks remaining until expiry, or {@code -1} for infinite. Used by the client UI bar. */
    public long remainingTicks(long now) {
        if (expireGameTime == INFINITE_EXPIRY) return -1L;
        return Math.max(0L, expireGameTime - now);
    }

    /** This thought's CURRENT happiness contribution at game-time {@code now}. For escalating
     *  kinds it deepens from {@link #modifier} toward the kind's floor as the grievance ages;
     *  for every other kind it's just {@link #modifier}. Both happiness aggregation and the
     *  Thoughts screen read through here so the displayed number matches the felt one. */
    public int effectiveModifier(long now) {
        if (!kind.escalates()) return modifier;
        long age = Math.max(0L, now - startGameTime);
        return kind.modifierAt(age);
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putString("KID", kind.id().toString());
        tag.putInt("M", modifier);
        tag.putLong("E", expireGameTime);
        tag.putInt("D", totalDurationTicks);
        tag.putLong("S", startGameTime);
        if (otherUuid != null) {
            tag.put("O", NbtUtils.createUUID(otherUuid));
        }
        if (savedPartnerName != null) {
            tag.putString("N", savedPartnerName);
        }
        return tag;
    }

    /** Tolerant load. Returns {@code null} for entries whose kind no longer resolves (unknown id, or a
     *  removed legacy ordinal) so the caller can silently drop them instead of crashing the citizen.
     *  Reads the new id key ({@code "KID"}); falls back to the legacy ordinal key ({@code "K"}) so
     *  pre-registry saves still load. */
    @Nullable
    public static Thought load(CompoundTag tag) {
        ThoughtType kind = tag.contains("KID") ? ThoughtTypes.byId(tag.getString("KID")) : null;
        if (kind == null && tag.contains("K")) {
            kind = ThoughtKind.fromOrdinal(tag.getInt("K"));
        }
        if (kind == null) return null;
        int modifier = tag.contains("M") ? tag.getInt("M") : kind.modifier();
        long expire = tag.contains("E") ? tag.getLong("E") : INFINITE_EXPIRY;
        int total = tag.contains("D") ? tag.getInt("D") : ThoughtKind.INFINITE_DURATION;
        // Pre-escalation saves have no "S"; treat them as freshly created (0) so an old
        // homeless citizen restarts the ramp rather than snapping to the floor on load.
        long start = tag.contains("S") ? tag.getLong("S") : 0L;
        UUID other = tag.contains("O") ? NbtUtils.loadUUID(tag.get("O")) : null;
        String savedName = tag.contains("N") ? tag.getString("N") : null;
        return new Thought(kind, modifier, expire, total, start, other, savedName);
    }
}
