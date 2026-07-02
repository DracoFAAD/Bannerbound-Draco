package com.bannerbound.antiquity.client;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.antiquity.BannerboundAntiquity;
import com.bannerbound.antiquity.item.Intoxication;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderGuiEvent;

/**
 * Client driver for grog drunkenness (GROG_PLAN.md Phase 3.5), at the GUI stage (after the world +
 * shaders, before the HUD) so it's Iris-safe and the HUD stays crisp:
 * <ul>
 *   <li>the swimming drunk shader, eased off the synced intoxication level;</li>
 *   <li>a fade-to-black while you're {@link BannerboundAntiquity#PASS_OUT_UNTIL black-out} cold;</li>
 *   <li>a pounding, throbbing vignette for the morning-after {@link BannerboundAntiquity#HANGOVER_UNTIL
 *       hangover}.</li>
 * </ul>
 */
@OnlyIn(Dist.CLIENT)
@ApiStatus.Internal
@EventBusSubscriber(modid = BannerboundAntiquity.MODID, value = Dist.CLIENT)
public final class DrunkClientEffects {
    /** Level at which the drunk visuals peak — L7, right before the L8 black-out, so 5→6→7 ramp up hard. */
    private static final float FULL_LEVEL = 7.0F;
    /** Smoothed visual intensity 0→1, eased toward the level each frame so it fades in/out cleanly. */
    private static float smooth = 0.0F;

    private DrunkClientEffects() {}

    @SubscribeEvent
    static void onRenderGuiPre(RenderGuiEvent.Pre event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            return;
        }
        float now = mc.level.getGameTime() + event.getPartialTick().getGameTimeDeltaPartialTick(false);

        // Hangover: pounding vignette, fading over the 30s (a touch stronger near the start).
        long hangoverUntil = mc.player.getData(BannerboundAntiquity.HANGOVER_UNTIL.get());
        if (hangoverUntil > now) {
            float frac = Math.min(1.0F, (hangoverUntil - now) / (float) Intoxication.HANGOVER_TICKS);
            PoisonPostProcessor.renderHangover(0.55F + 0.45F * frac, now);
        }

        // Vomited on: green goo splattered across the screen, fading over the 10s (sober or not).
        long gooUntil = mc.player.getData(BannerboundAntiquity.VOMIT_OVERLAY_UNTIL.get());
        if (gooUntil > now) {
            // Seed off the deadline: constant for this splat, different for the next → unique blob layout.
            float seed = (gooUntil % 9973L) * 0.0137F;
            PoisonPostProcessor.renderGoo((gooUntil - now) / (float) Intoxication.VOMIT_OVERLAY_TICKS, seed, now);
        }

        // Drunk swim, eased so a sip fades it in and sobering fades it out.
        int level = mc.player.getData(BannerboundAntiquity.INTOXICATION_LEVEL.get());
        // Jumble a growing fraction of glyphs once drunk (text goes weird) — set for the font mixin.
        DrunkText.setChance(level < 4 ? 0.0F : Math.min(0.55F, 0.11F * (level - 3)));
        float target = Math.min(1.0F, level / FULL_LEVEL);
        smooth += (target - smooth) * 0.06F;
        if (smooth > 0.002F) {
            PoisonPostProcessor.renderDrunk(smooth, now);
        } else {
            smooth = 0.0F;
        }

        // Black-out: heavy eyelids droop shut (curare-style), slowly — not a snap to black. Two black
        // bars close from top + bottom to the centre; fully shut while out cold, then open as you come to.
        long passOutUntil = mc.player.getData(BannerboundAntiquity.PASS_OUT_UNTIL.get());
        if (passOutUntil > now) {
            float remaining = passOutUntil - now;
            float elapsed = Intoxication.PASS_OUT_TICKS - remaining;
            float closeIn = Math.min(1.0F, elapsed / 35.0F);    // ~1.7s for the eyes to droop shut
            float openOut = Math.min(1.0F, remaining / 28.0F);  // ~1.4s for them to crack back open
            float cover = Math.max(0.0F, Math.min(closeIn, openOut));
            // Heavy-lidded flutter as you fight it (only while not yet fully shut).
            cover = Math.min(1.0F, cover + 0.05F * (float) Math.sin(now * 0.5F) * (1.0F - cover));
            int w = mc.getWindow().getGuiScaledWidth();
            int h = mc.getWindow().getGuiScaledHeight();
            int lid = (int) (cover * h * 0.5F);
            if (lid > 0) {
                GuiGraphics g = event.getGuiGraphics();
                g.fill(0, 0, w, lid, 0xFF000000);
                g.fill(0, h - lid, w, h, 0xFF000000);
            }
        }
    }
}
