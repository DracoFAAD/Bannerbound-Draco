package com.bannerbound.core.social;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;

/**
 * One citizen's relationships with every other citizen they've interacted with. Stored as a
 * {@code Map<UUID, Relationship>} keyed by the other citizen's entityId. Tier thresholds for
 * the whole social system live as constants on this class — one file to edit if balance changes.
 * <p>
 * NBT layout (list of compound tags under the {@code "Relations"} key on {@link
 * com.bannerbound.core.entity.CitizenEntity}):
 * <pre>
 * [ { "Other": UUID, "S": int, "L": int, "B": int, "T": long }, ... ]
 * </pre>
 * Missing relationships read back as {@link Relationship#STRANGERS} ({@code score == 0}).
 */
public final class Relationships {
    public static final int MIN = -100;
    public static final int MAX = 100;
    public static final int ACQUAINTANCES    = 10;
    public static final int FRIENDS          = 25;
    public static final int CLOSE_FRIENDS    = 50;
    public static final int FRIENDS_FOR_LIFE = 80;

    private final Map<UUID, Relationship> byOther = new HashMap<>();

    /** Read-only fetch. Returns {@link Relationship#STRANGERS} for unknown UUIDs without storing
     *  anything — keeps the map small for citizens who only ever met a handful of others. */
    public Relationship get(UUID other) {
        Relationship r = byOther.get(other);
        return r != null ? r : Relationship.STRANGERS;
    }

    /** Clamps {@code score + delta} into [MIN, MAX], stamps {@code lastInteractTick = now},
     *  and stores the result. Returns the new record. Caller is responsible for {@code setDirty}
     *  on the owning settlement after this. */
    public Relationship applyDelta(UUID other, int delta, long now) {
        Relationship next = get(other).withScoreDelta(delta, now);
        byOther.put(other, next);
        return next;
    }

    /** Writes the canonical {@link Relationship#FAMILY} record for {@code other}, overwriting
     *  any previous score-based entry. Used at birth to install the permanent parent ↔ child
     *  bond; caller (see {@link SocialEvents#linkMutualFamily}) is responsible for setting the
     *  matching record on the other citizen and marking the settlement dirty. */
    public void linkFamily(UUID other) {
        byOther.put(other, Relationship.FAMILY);
    }

    /** Hard-overwrite the score for {@code other} to a specific value, regardless of what was
     *  there before (including a stale FAMILY entry). Used by the debug
     *  {@code /bannerbound set_relationship} command — normal gameplay should still go through
     *  {@link #applyDelta} so the family / lover guard rails apply. */
    public void setScore(UUID other, int score, long now) {
        int clamped = Math.max(MIN, Math.min(MAX, score));
        byOther.put(other, new Relationship(clamped, 0, 0, now, false));
    }

    /** Drops the entry for {@code dead} if present. Returns true iff the map changed —
     *  caller decides whether that change is worth marking the settlement dirty. */
    public boolean forget(UUID dead) {
        return byOther.remove(dead) != null;
    }

    /** True iff there are zero stored relationships — used to skip writing an empty list tag. */
    public boolean isEmpty() {
        return byOther.isEmpty();
    }

    /** Unmodifiable view of every stored {@code (otherId, Relationship)} pair. Useful for screens
     *  / tooltips that need to render every known relationship. The {@link Relationship#STRANGERS}
     *  default returned by {@link #get(UUID)} is intentionally NOT included — only citizens this
     *  one has actually met show up. */
    public Map<UUID, Relationship> entries() {
        return Collections.unmodifiableMap(byOther);
    }

    /** Resolves the tier with {@code other} — convenience around {@code get(other).tier()}. */
    public RelationshipTier tierWith(UUID other) {
        return get(other).tier();
    }

    /** Saves the whole map as a list of compound tags. Caller should skip the put if
     *  {@link #isEmpty()} to avoid writing an empty tag to fresh citizens. */
    public ListTag save() {
        ListTag list = new ListTag();
        for (Map.Entry<UUID, Relationship> e : byOther.entrySet()) {
            CompoundTag entry = e.getValue().save();
            entry.put("Other", NbtUtils.createUUID(e.getKey()));
            list.add(entry);
        }
        return list;
    }

    /** Tolerant load — entries missing {@code "Other"} are silently dropped so a malformed
     *  save doesn't crash the citizen on load. */
    public void load(ListTag list) {
        byOther.clear();
        if (list == null) return;
        for (Tag t : list) {
            if (!(t instanceof CompoundTag entry)) continue;
            if (!entry.contains("Other")) continue;
            UUID other = NbtUtils.loadUUID(entry.get("Other"));
            byOther.put(other, Relationship.load(entry));
        }
    }
}
