package com.bannerbound.core.api.settlement;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;

/**
 * Eight banner-aligned colors a player can pick when founding a settlement. Order matters
 * (saved as an ordinal in {@link Settlement#save()}) — append new colors at the end if you
 * extend the palette.
 */
public enum SettlementColor {
    WHITE(ChatFormatting.WHITE, 0xFFFFFF, "bannerbound.color.white"),
    RED(ChatFormatting.RED, 0xFF5555, "bannerbound.color.red"),
    GOLD(ChatFormatting.GOLD, 0xFFAA00, "bannerbound.color.gold"),
    YELLOW(ChatFormatting.YELLOW, 0xFFFF55, "bannerbound.color.yellow"),
    GREEN(ChatFormatting.GREEN, 0x55FF55, "bannerbound.color.green"),
    AQUA(ChatFormatting.AQUA, 0x55FFFF, "bannerbound.color.aqua"),
    BLUE(ChatFormatting.BLUE, 0x5555FF, "bannerbound.color.blue"),
    LIGHT_PURPLE(ChatFormatting.LIGHT_PURPLE, 0xFF55FF, "bannerbound.color.light_purple");

    private static final SettlementColor[] VALUES = values();

    private final ChatFormatting formatting;
    private final int rgb;
    private final String translationKey;

    SettlementColor(ChatFormatting formatting, int rgb, String translationKey) {
        this.formatting = formatting;
        this.rgb = rgb;
        this.translationKey = translationKey;
    }

    public ChatFormatting formatting() {
        return formatting;
    }

    public int rgb() {
        return rgb;
    }

    public Component displayName() {
        return Component.translatable(translationKey);
    }

    public static SettlementColor byIndex(int index) {
        if (index < 0 || index >= VALUES.length) {
            return WHITE;
        }
        return VALUES[index];
    }

    public static int count() {
        return VALUES.length;
    }
}
