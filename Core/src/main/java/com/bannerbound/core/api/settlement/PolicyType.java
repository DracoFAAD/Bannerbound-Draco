package com.bannerbound.core.api.settlement;

import java.util.Locale;

import org.jetbrains.annotations.Nullable;

/**
 * The category a policy belongs to. Every policy is tagged with exactly one {@code PolicyType},
 * and a policy can only be adopted into a slot of its matching type (see
 * {@link Settlement#policyTypeSlots()}). Government-exclusive "signature" policies are the lone
 * exception: they occupy a government's single signature slot rather than a typed slot (their
 * type is still used for the display icon/colour).
 *
 * <p>Slot count is driven by {@link Settlement#governmentBaseLayout(Settlement.Government)} plus
 * research grants — era never enters the calculation. {@link #DIPLOMATIC} and {@link #FAITH}
 * slots only ever come from research/system unlocks (no government grants them at base), so they
 * never surface as dead empty slots before those systems exist. See {@code POLICY_PLAN.md}.
 */
public enum PolicyType {
    ECONOMIC(0xFFE0A040),
    CULTURAL(0xFFD060C0),
    SCIENTIFIC(0xFF40A0E0),
    MILITARISTIC(0xFFE05050),
    DIPLOMATIC(0xFF50C080),
    FAITH(0xFFE0D060);

    /** Accent colour (ARGB) used to tint the type's slots + glyph in the policies UI. */
    private final int color;

    PolicyType(int color) {
        this.color = color;
    }

    public int color() {
        return color;
    }

    /** Lang key for the type's display name, e.g. {@code bannerbound.policy.type.economic}. */
    public String langKey() {
        return "bannerbound.policy.type." + name().toLowerCase(Locale.ROOT);
    }

    /** Parses a stored type name (case-insensitive), or null if it doesn't match — used when
     *  decoding {@code unlocks.policy_slot} entries and the slot-type sync list. */
    @Nullable
    public static PolicyType byName(String raw) {
        if (raw == null) return null;
        try {
            return valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }
}
