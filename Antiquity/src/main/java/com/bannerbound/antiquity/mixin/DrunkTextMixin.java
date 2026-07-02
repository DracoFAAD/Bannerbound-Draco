package com.bannerbound.antiquity.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

import com.bannerbound.antiquity.client.DrunkText;

import net.minecraft.network.chat.Style;

/**
 * Drunk text jumbling (GROG_PLAN.md Phase 3.5): in the font's per-glyph sink ({@code
 * Font$StringRenderOutput.accept(int, Style, int)} — the single funnel every drawn glyph passes
 * through), randomly flip a glyph's style to obfuscated when the local player is drunk, so all text
 * jumbles. Sober → no-op. {@code require = 0} so a mapping shift just disables the effect instead of
 * crashing.
 */
@Mixin(targets = "net.minecraft.client.gui.Font$StringRenderOutput")
public class DrunkTextMixin {
    @ModifyVariable(method = "accept", at = @At("HEAD"), argsOnly = true, require = 0)
    private Style bannerbound$jumble(Style style) {
        if (style != null && !style.isObfuscated() && DrunkText.jumble()) {
            return style.withObfuscated(true);
        }
        return style;
    }
}
