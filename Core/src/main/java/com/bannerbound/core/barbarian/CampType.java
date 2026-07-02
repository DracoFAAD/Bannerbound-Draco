package com.bannerbound.core.barbarian;

import net.minecraft.ChatFormatting;
import net.minecraft.world.item.DyeColor;

/**
 * The four barbarian-camp flavours, resolved once from the camp-center biome at seed time (see
 * the Antiquity biome→type table) and then stored on the {@link BarbarianCamp} record.
 *
 * <p>Note the name clash: "Barbarians" is both the umbrella system and the temperate type. In code
 * the temperate always-hostile type is {@link #MARAUDER} to disambiguate; its display string is
 * still "Barbarians" (lang key {@code bannerbound.barbarian.type.marauder}).
 */
public enum CampType {
    /** Hot/desert. Neutral by default; scouts come to TRADE, lack of trade sours relations. */
    NOMAD(CampRelationState.NEUTRAL, true),
    /** Jungle/swamp. Starts HOSTILE; demands antidotes, poisons, food; persuadable back to neutral. */
    TRIBE(CampRelationState.HOSTILE, true),
    /** Cold. Demands meat & livestock; much stronger to defeat. */
    RAIDER(CampRelationState.NEUTRAL, true),
    /** Temperate plains/forest. The umbrella's namesake type: ALWAYS hostile, never persuadable. */
    MARAUDER(CampRelationState.HOSTILE, false);

    private final CampRelationState defaultRelation;
    private final boolean persuadableCeilingIsNeutral;

    CampType(CampRelationState defaultRelation, boolean persuadable) {
        this.defaultRelation = defaultRelation;
        this.persuadableCeilingIsNeutral = persuadable;
    }

    /** Relationship a freshly-discovered camp of this type starts at toward a settlement. */
    public CampRelationState defaultRelation() {
        return defaultRelation;
    }

    /** MARAUDER can never be talked up out of HOSTILE (accepting demands only buys raid-cooldown). */
    public boolean isAlwaysHostile() {
        return this == MARAUDER;
    }

    /** Whether accepting demands/trades can lift this type back toward NEUTRAL (false for MARAUDER). */
    public boolean canBePersuaded() {
        return persuadableCeilingIsNeutral && this != MARAUDER;
    }

    /** Lang key for the player-facing name. MARAUDER displays as "Barbarians". */
    public String displayKey() {
        return "bannerbound.barbarian.type." + name().toLowerCase(java.util.Locale.ROOT);
    }

    /** Player-facing translated name. */
    public net.minecraft.network.chat.Component displayName() {
        return net.minecraft.network.chat.Component.translatable(displayKey());
    }

    /** Plain-English name for server-built strings (journal titles render literally). */
    public String englishName() {
        return switch (this) {
            case NOMAD -> "Nomads";
            case TRIBE -> "Tribe";
            case RAIDER -> "Raiders";
            case MARAUDER -> "Barbarians";
        };
    }

    /** Banner colour for the camp's central standard (the raze target). */
    public DyeColor bannerDye() {
        return switch (this) {
            case NOMAD -> DyeColor.YELLOW;
            case TRIBE -> DyeColor.GREEN;
            case RAIDER -> DyeColor.LIGHT_BLUE;
            case MARAUDER -> DyeColor.RED;
        };
    }

    /** The best relationship this type can ever be talked up to: MARAUDER stays HOSTILE (demands only
     *  buy raid-cooldown), TRIBE can reach NEUTRAL, NOMAD/RAIDER can become FRIENDLY through trade. */
    public CampRelationState relationCeiling() {
        return switch (this) {
            case MARAUDER -> CampRelationState.HOSTILE;
            case TRIBE -> CampRelationState.NEUTRAL;
            case NOMAD, RAIDER -> CampRelationState.FRIENDLY;
        };
    }

    /** Name-tag tint for this type's members. */
    public ChatFormatting nameColor() {
        return switch (this) {
            case NOMAD -> ChatFormatting.YELLOW;
            case TRIBE -> ChatFormatting.GREEN;
            case RAIDER -> ChatFormatting.AQUA;
            case MARAUDER -> ChatFormatting.RED;
        };
    }

    public static CampType fromName(String name) {
        if (name == null) return null;
        for (CampType t : values()) {
            if (t.name().equalsIgnoreCase(name)) return t;
        }
        return null;
    }
}
