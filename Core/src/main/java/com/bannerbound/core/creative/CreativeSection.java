package com.bannerbound.core.creative;

import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;

/**
 * Metadata for one labelled band ("section") inside a creative-mode tab — the equivalent of the
 * "Aeronautics / Offroad / Tracks…" dividers seen in Create Aeronautics. Pure data, safe to build on
 * either physical side (no client-only types).
 *
 * <p>The band itself is a GUI sprite (see {@link #sprite}) tinted by {@link #bannerTint} so a single
 * greyscale strip can serve every section; the label sits on a coloured chip ({@link #labelBackground})
 * with {@link #textColor} text. Register sections via {@link CreativeSections#forTab}.
 *
 * @param id              stable identifier (handy for logging / future per-section state)
 * @param title           the band label (translatable component recommended)
 * @param sprite          GUI sprite id, e.g. {@code bannerboundantiquity:sections/banner}
 *                        (file at {@code assets/<ns>/textures/gui/sprites/sections/banner.png})
 * @param bannerTint      ARGB multiplier applied to the whole strip (0xFFFFFFFF = untinted)
 * @param labelBackground ARGB fill drawn behind the label text
 * @param textColor       ARGB label text colour
 * @param animateOnHover  reserved: only tick the strip's animation while hovered (needs an animated
 *                        sprite; currently a no-op for static strips)
 */
public record CreativeSection(
        String id,
        Component title,
        ResourceLocation sprite,
        int bannerTint,
        int labelBackground,
        int textColor,
        boolean animateOnHover) {

    /** Convenience: opaque, untinted band with a translucent-dark label chip and light text. */
    public static CreativeSection of(String id, Component title, ResourceLocation sprite, int bannerTint) {
        return new CreativeSection(id, title, sprite, bannerTint, 0xBB000000, 0xFFFFFFFF, false);
    }
}
