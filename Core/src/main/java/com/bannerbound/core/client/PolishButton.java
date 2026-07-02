package com.bannerbound.core.client;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.Config;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * A vanilla {@link Button} with the mod's polish animations: a soft brightness ease on hover
 * (instead of the sprite's instant swap) and a 1.5px press dip that pops back (~90ms) when
 * clicked. Both collapse to stock vanilla behaviour when {@link Config#UI_ANIMATIONS} is off,
 * so the config flag reverts every screen at once.
 *
 * <p>Drop-in replacement: {@code PolishButton.polished(...)} mirrors {@link Button#builder} (pos /
 * size / bounds / width / tooltip / createNarration), so swapping a call site is a one-word change.
 * (The factory can't be named {@code builder} — hiding the superclass static with an unrelated
 * return type doesn't compile.)
 */
@OnlyIn(Dist.CLIENT)
@ApiStatus.Internal
public class PolishButton extends Button {
    /** 0→1 hover ease — per-frame exponential approach toward the hover state. */
    private float hoverEase = 0f;
    /** When the button was last activated; drives the press dip-and-pop. 0 = idle. */
    private long pressedAtMs = 0L;
    /** Hover-wash tint (RGB part only; alpha comes from the ease). White by default; settlement
     *  screens pass their identity primary so even button hovers carry the banner color. */
    private int washRgb = 0xFFFFFF;

    protected PolishButton(int x, int y, int width, int height, Component message,
                           OnPress onPress, CreateNarration createNarration) {
        super(x, y, width, height, message, onPress, createNarration);
    }

    public static Builder polished(Component message, OnPress onPress) {
        return new Builder(message, onPress);
    }

    @Override
    public void onPress() {
        pressedAtMs = net.minecraft.Util.getMillis();
        super.onPress();
    }

    @Override
    protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        boolean animate = Config.UI_ANIMATIONS.get();
        // Hover only (not keyboard focus — focus lingers on the last-clicked button and would
        // leave a permanent wash).
        boolean hot = this.active && this.isHovered();
        if (animate) {
            hoverEase += ((hot ? 1f : 0f) - hoverEase) * 0.25f;
        } else {
            hoverEase = hot ? 1f : 0f;
        }
        // Press dip: 0→1→0 triangle over ~90ms, translating the whole button down ≤1.5px.
        float press = 0f;
        if (animate && pressedAtMs > 0L) {
            float t = (net.minecraft.Util.getMillis() - pressedAtMs) / 90f;
            if (t < 1f) press = 1f - Math.abs(t * 2f - 1f);
            else pressedAtMs = 0L;
        }
        int dipY = Math.round(press * 1.5f);
        boolean posed = dipY != 0;
        if (posed) {
            g.pose().pushPose();
            g.pose().translate(0, dipY, 0);
        }
        super.renderWidget(g, mouseX, mouseY, partialTick);
        // Hover wash: up to ~8% white over the sprite, eased — reads as a soft brighten. Drawn
        // only while easing/hot so idle buttons cost nothing. (Vanilla's own hover sprite still
        // applies underneath; this softens its on/off snap.)
        if (this.active && hoverEase > 0.02f) {
            // Identity-tinted washes get a touch more alpha than plain white needs to read.
            int alpha = (int) (hoverEase * (washRgb == 0xFFFFFF ? 0x16 : 0x2A));
            g.fill(getX() + 1, getY() + 1, getX() + getWidth() - 1, getY() + getHeight() - 1,
                (alpha << 24) | washRgb);
        }
        if (posed) {
            g.pose().popPose();
        }
    }

    /** Mirror of {@link Button.Builder} so call sites swap with a one-word change. */
    public static class Builder {
        private final Component message;
        private final OnPress onPress;
        private int x;
        private int y;
        private int width = 150;
        private int height = 20;
        private Tooltip tooltip;
        private CreateNarration createNarration = DEFAULT_NARRATION;
        private int washRgb = 0xFFFFFF;

        Builder(Component message, OnPress onPress) {
            this.message = message;
            this.onPress = onPress;
        }

        public Builder pos(int x, int y) {
            this.x = x;
            this.y = y;
            return this;
        }

        public Builder width(int width) {
            this.width = width;
            return this;
        }

        public Builder size(int width, int height) {
            this.width = width;
            this.height = height;
            return this;
        }

        public Builder bounds(int x, int y, int width, int height) {
            return this.pos(x, y).size(width, height);
        }

        public Builder tooltip(Tooltip tooltip) {
            this.tooltip = tooltip;
            return this;
        }

        public Builder createNarration(CreateNarration createNarration) {
            this.createNarration = createNarration;
            return this;
        }

        /** Tints the hover wash with a settlement identity color (ARGB or RGB; alpha ignored).
         *  Feed {@code primaryAccent()} from a PolishedScreen so hovers wear the banner. */
        public Builder accent(int accentColor) {
            this.washRgb = accentColor & 0x00FFFFFF;
            return this;
        }

        public PolishButton build() {
            PolishButton button = new PolishButton(x, y, width, height, message, onPress, createNarration);
            button.setTooltip(tooltip);
            button.washRgb = washRgb;
            return button;
        }
    }
}
