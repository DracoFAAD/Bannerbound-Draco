package com.bannerbound.core.chat;

import org.jetbrains.annotations.ApiStatus;

import net.minecraft.world.level.GameRules;

/**
 * Custom game rules owned by the mod.
 *
 * <p>{@link #GLOBAL_CHAT} — when {@code true}, the mod's proximity-chat handling
 * ({@link com.bannerbound.core.event.ChatEvents}) is disabled and chat / private messages behave
 * exactly like vanilla (server-wide, full clarity). When {@code false} (the default) chat and
 * {@code /msg|/tell|/w} are range-limited and fade with distance — see {@link ProximityChat}.
 *
 * <p>{@code GameRules.register} and {@code BooleanValue.create} are exposed publicly by NeoForge's
 * bundled access transformer, so we can register a vanilla-style boolean rule directly. Must run
 * before any level loads, so {@link #register()} is called from the mod constructor.
 */
@ApiStatus.Internal
public final class BannerboundGameRules {
    private BannerboundGameRules() {
    }

    /** {@code /gamerule globalChat <true|false>} — default false (proximity chat on). */
    public static GameRules.Key<GameRules.BooleanValue> GLOBAL_CHAT;

    /** {@code /gamerule celestialSpeed <multiplier>} — orbital/seasonal time multiplier for
     *  the faith sky (testing). 1 = designed speed, 0 = frozen heavens, big = time-lapse.
     *  Synced to clients through {@link com.bannerbound.core.api.faith.SkyStateSync}. */
    public static GameRules.Key<GameRules.IntegerValue> CELESTIAL_SPEED;

    /** {@code /gamerule meteorAmount <perMinute>} — ambient meteor rate, ~meteors per minute
     *  (testing). Default 2 = designed rate, 0 = none, big = meteor storm. Synced like
     *  celestialSpeed. */
    public static GameRules.Key<GameRules.IntegerValue> METEOR_AMOUNT;

    /** {@code /gamerule allowOfflineWar <true|false>} — default false. When false, declaring
     *  war requires an online target member, and the warning countdown pauses if they log out. */
    public static GameRules.Key<GameRules.BooleanValue> ALLOW_OFFLINE_WAR;

    /** {@code /gamerule useCustomLanguage <true|false>} — default false. When true, known item
     *  titles and controlled job/entity labels use generated language. Citizen names always do. */
    public static GameRules.Key<GameRules.BooleanValue> USE_CUSTOM_LANGUAGE;

    /**
     * {@code /gamerule forceMaxAge <era>} — hard cap on how far any settlement (and the world age)
     * may progress. The value is a Bannerbound {@link com.bannerbound.core.api.settlement.Era} name
     * ({@code ancient}, {@code classical}, …, {@code future}) plus the alias {@code none} (= the
     * last era = no cap), tab-completed as friendly presets rather than a raw ordinal — see
     * {@link ForceMaxAgeGameRule} / {@link com.bannerbound.core.command.EraGameRuleArgument}.
     * Default = the last era (FUTURE) = no cap, derived from {@code Era.values().length - 1} so
     * inserting an era never silently caps the game.
     *
     * <p>While capped, research that would advance the age past the cap is blocked: the
     * {@code bannerbound.advance_age:<era>} feature is a no-op, and capped nodes can't be started
     * or queued. Enforcement lives in
     * {@link com.bannerbound.core.api.research.ResearchManager#forceMaxAge} /
     * {@link com.bannerbound.core.api.research.ResearchManager#isEraCapped}. The cap is
     * forward-only — lowering it does not roll back settlements already past it, it just freezes
     * further progress. The friendly era-name setter is {@code /bannerbound force_max_age <era>}. */
    public static GameRules.Key<ForceMaxAgeGameRule> FORCE_MAX_AGE;

    /** Registers the mod's game rules. Call once during mod construction. */
    public static void register() {
        if (GLOBAL_CHAT != null) {
            return; // idempotent
        }
        GLOBAL_CHAT = GameRules.register(
            "globalChat",
            GameRules.Category.CHAT,
            GameRules.BooleanValue.create(false));
        CELESTIAL_SPEED = GameRules.register(
            "celestialSpeed",
            GameRules.Category.MISC,
            GameRules.IntegerValue.create(1, (server, value) ->
                com.bannerbound.core.api.faith.SkyStateSync.broadcast(server)));
        METEOR_AMOUNT = GameRules.register(
            "meteorAmount",
            GameRules.Category.MISC,
            GameRules.IntegerValue.create(2, (server, value) ->
                com.bannerbound.core.api.faith.SkyStateSync.broadcast(server)));
        ALLOW_OFFLINE_WAR = GameRules.register(
            "allowOfflineWar",
            GameRules.Category.PLAYER,
            GameRules.BooleanValue.create(false));
        USE_CUSTOM_LANGUAGE = GameRules.register(
            "useCustomLanguage",
            GameRules.Category.MISC,
            GameRules.BooleanValue.create(false, (server, value) ->
                com.bannerbound.core.language.CustomLanguageSync.onRuleChanged(server)));
        FORCE_MAX_AGE = GameRules.register(
            "forceMaxAge",
            GameRules.Category.MISC,
            // Default = last era = no cap. Derived dynamically so inserting an era (e.g. Classical
            // between Ancient and Medieval) never silently caps progression. No change callback:
            // research re-resolves the cap on demand via ResearchManager.forceMaxAge.
            ForceMaxAgeGameRule.type(lastEra(), (server, value) -> {}));
    }

    /** The final {@link com.bannerbound.core.api.settlement.Era} — the "no cap" sentinel. */
    private static com.bannerbound.core.api.settlement.Era lastEra() {
        com.bannerbound.core.api.settlement.Era[] vals =
            com.bannerbound.core.api.settlement.Era.values();
        return vals[vals.length - 1];
    }
}
