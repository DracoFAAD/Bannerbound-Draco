package com.bannerbound.core.api.settlement;

import net.minecraft.network.chat.Component;

/**
 * Bannerbound tech-tier enum. Ordinals are persisted (in {@link Settlement#save()} as
 * {@code Age} and in {@link SettlementData}'s {@code WorldAge}), so existing entries must
 * keep their position — append new ones at the end. The one exception so far: {@code CLASSICAL}
 * was inserted between {@code ANCIENT} and {@code MEDIEVAL} (so {@code min_age} ordering stays
 * monotonic for the Ancient&rarr;Classical&rarr;Medieval progression), which shifted every
 * later era's ordinal up by one. Worlds saved before that insert will read one era too low —
 * acceptable during development; re-create test worlds. Lang keys follow the pattern
 * {@code bannerbound.era.<lowercase_name>} (so {@code ANCIENT} &rarr; {@code bannerbound.era.ancient}).
 */
public enum Era {
    ANCIENT,
    CLASSICAL,
    MEDIEVAL,
    RENAISSANCE,
    INDUSTRIAL,
    DIESEL,
    ATOMIC,
    MODERN,
    FUTURE;

    public Component displayName() {
        return Component.translatable("bannerbound.era." + name().toLowerCase());
    }

    public String key() {
        return name().toLowerCase();
    }

    /**
     * Per-era <i>immigration floor</i>: the population number that immigration tops a settlement
     * up to. Immigration fires whenever {@code population() < immigrationFloor()} — so a
     * settlement whose citizens all died drops back below the floor and resumes immigration
     * automatically. There is no hard lifetime cap; this prevents soft-locks where every original
     * immigrant dies and the player can never rebuild.
     *
     * <p>Ancient is locked at 7 (the original spec). Later eras scale up — placeholder values
     * for now, tuned when each era ships. Adjust here; every gate (immigration, consumption,
     * population-max floor) reads the per-era number through {@link Settlement#immigrationFloor()}.
     */
    public int immigrationFloor() {
        return switch (this) {
            case ANCIENT     -> 7;
            case CLASSICAL   -> 10;  // TODO: tune when Classical ships
            case MEDIEVAL    -> 14;  // TODO: tune when Medieval ships
            case RENAISSANCE -> 23;  // TODO: tune when Renaissance ships
            case INDUSTRIAL  -> 34;  // TODO: tune when Industrial ships
            case DIESEL      -> 57;  // TODO: tune when Diesel ships
            case ATOMIC      -> 80;  // TODO: tune when Atomic ships
            case MODERN      -> 100;  // TODO: tune when Modern ships
            case FUTURE      -> 150;  // TODO: tune when Future ships
        };
    }

    /**
     * Number of active policy slots a settlement of this era has. Scales +1 per era:
     * Ancient = 1, Classical = 2, Medieval = 3, ... A settlement that advances an era
     * gains a slot; slots never shrink, so an active policy is never forcibly evicted.
     */
    public int activePolicySlots() {
        return ordinal() + 1;
    }

    /**
     * Number of active palette slots a settlement of this era has. Same per-era curve as
     * {@link #activePolicySlots()} (Antiquity = 1, Medieval = 2, ...) but tracked separately so
     * the two can diverge later without entangling them.
     * (Ancient = 1, Classical = 2, ...)
     */
    public int activePaletteSlots() {
        return ordinal() + 1;
    }

    /**
     * Number of registration documents a settlement of this era may issue over its lifetime —
     * a Registration Tablet in the Ancient era, a Registration Paper from Medieval on. Same
     * +1-per-era curve as {@link #activePolicySlots()} (Ancient = 1, Classical = 2, ...), so each age
     * advance grants exactly one more document on top of those already issued.
     */
    public int registrationDocumentSlots() {
        return ordinal() + 1;
    }

    /** Returns the era following this one, or {@code this} if already at the last tier. */
    public Era next() {
        Era[] vals = values();
        int idx = ordinal();
        return idx + 1 < vals.length ? vals[idx + 1] : this;
    }

    public static Era fromOrdinalOrDefault(int ord) {
        Era[] vals = values();
        if (ord < 0 || ord >= vals.length) {
            return ANCIENT;
        }
        return vals[ord];
    }

    public static Era fromName(String name) {
        if (name == null) {
            return null;
        }
        for (Era e : values()) {
            if (e.name().equalsIgnoreCase(name)) {
                return e;
            }
        }
        return null;
    }
}
