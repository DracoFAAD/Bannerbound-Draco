package com.bannerbound.core.celestial;

/**
 * The colored "typed" stars of the faith sky (FAITH_PLAN.md Part 3). Domain mapping
 * (GOLD→HARVEST etc.) arrives with astrology — here they are only a color identity so
 * the sky can ship before any faith mechanics exist.
 */
public enum StarType {
    GOLD(0xFFDF8E),
    RED(0xFF4B3D),
    TWIN(0xE6E6FF),
    BLUE(0x8FC4FF),
    AMBER(0xFF9E4A),
    /** JOURNEY signature: isolated stars far from any cluster. */
    LONE_PALE(0xE3EDEF),
    /** SEA signature: generated near the celestial poles → always low over the horizon. */
    SEA_GREEN(0x63E6BE);

    public final int rgb;

    StarType(int rgb) {
        this.rgb = rgb;
    }
}
