package com.bannerbound.core.api.vanilla;

import com.bannerbound.core.Config;

/**
 * The single source of truth for whether vanilla Minecraft's external content (hostile spawning,
 * vanilla portals, free chest/barrel access, vanilla structures &amp; loot) is left intact.
 *
 * <p>Core can't have its config TOML rewritten by another mod, so this layer lets an expansion
 * <i>force</i> the effective value: Bannerbound: Antiquity calls {@link #setOverride(boolean)
 * setOverride(false)} during its common setup, which strips vanilla content regardless of the
 * Core config. Standalone Core simply reads {@link Config#VANILLA_CONTENT} (default {@code true},
 * i.e. vanilla untouched).
 *
 * <p>All runtime gates ({@code HostileSpawnGate}, {@code VanillaPortalGate}, Antiquity's
 * {@code VanillaStorageGate}) call {@link #isEnabled()} and behave as vanilla when it returns
 * {@code true}. Static worldgen/loot changes are shipped as datapacks by the expansion instead,
 * since those can't be toggled at runtime.
 */
public final class VanillaContentState {
    /** {@code null} = no override; fall through to the Core config. Written once at setup,
     *  read from spawn/portal events that may fire off the main thread, so {@code volatile}. */
    private static volatile Boolean override = null;

    private VanillaContentState() {
    }

    /** Force the effective value (used by expansions like Antiquity during common setup). */
    public static void setOverride(boolean value) {
        override = value;
    }

    /** Drop any override and fall back to the Core config. */
    public static void clearOverride() {
        override = null;
    }

    /**
     * The effective flag: an active override wins, otherwise the Core config value.
     * Returns the documented default ({@code true}, vanilla untouched) if the config spec
     * hasn't loaded yet — all real gates run well after config load, so this only guards
     * unusually early reads.
     */
    public static boolean isEnabled() {
        Boolean o = override;
        if (o != null) return o;
        try {
            return Config.VANILLA_CONTENT.get();
        } catch (IllegalStateException specNotLoadedYet) {
            return true;
        }
    }
}
