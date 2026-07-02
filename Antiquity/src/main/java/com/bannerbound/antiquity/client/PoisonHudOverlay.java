package com.bannerbound.antiquity.client;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.antiquity.BannerboundAntiquity;
import com.bannerbound.antiquity.Config;
import com.bannerbound.antiquity.poison.PoisonState;
import com.bannerbound.antiquity.poison.PoisonType;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.util.Mth;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * The HUD-stage poison overlay. The wolfsbane "world goes cold and closes in" look (desaturate / blur
 * / vignette) is now a real framebuffer shader ({@link PoisonPostProcessor}); all that remains here is
 * the warm antidote relief-flash, which is a simple full-screen fade drawn over the (crisp) HUD.
 */
@OnlyIn(Dist.CLIENT)
@ApiStatus.Internal
public final class PoisonHudOverlay {
    private PoisonHudOverlay() {}

    public static void render(GuiGraphics g, DeltaTracker delta) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null || mc.options.hideGui) {
            return;
        }
        float time = (float) mc.level.getGameTime() + delta.getGameTimeDeltaPartialTick(false);
        float flash = PoisonClientEffects.healFlash(time);
        if (flash > 0.0F) {
            int a = (int) (flash * 105);
            g.fill(0, 0, g.guiWidth(), g.guiHeight(), (a << 24) | 0xFFE85C); // warm yellow relief
        }
        renderCurareEyelids(mc, g, time);
    }

    /** Curare: heavy eyelids drooping (and fluttering as you fight it) during the stun, then closing to
     *  full black while unconscious. Local player only; phase from the synced curare deadlines. */
    private static void renderCurareEyelids(Minecraft mc, GuiGraphics g, float time) {
        PoisonState s = mc.player.getData(BannerboundAntiquity.POISON_STATE.get());
        if (s.type() != PoisonType.CURARE) {
            return;
        }
        long now = mc.level.getGameTime();
        long faintAt = mc.player.getData(BannerboundAntiquity.POISON_CURARE_FAINT_AT.get());
        long wakeAt = mc.player.getData(BannerboundAntiquity.POISON_CURARE_WAKE_AT.get());
        if (faintAt <= 0L) {
            return;
        }
        float cover;
        if (now < faintAt) {
            int stunTicks = Math.max(1, Config.POISON_CURARE_STUN_TICKS.get());
            float stunFrac = Mth.clamp((now - (faintAt - stunTicks)) / (float) stunTicks, 0.0F, 1.0F);
            cover = Math.min(0.92F, 0.20F + 0.65F * stunFrac + 0.05F * Mth.sin(time * 0.5F) * stunFrac);
        } else if (now < wakeAt) {
            cover = 1.0F; // passed out — eyes shut, full black
        } else {
            return;
        }
        int lid = (int) (cover * g.guiHeight() * 0.5F);
        if (lid > 0) {
            g.fill(0, 0, g.guiWidth(), lid, 0xFF000000);
            g.fill(0, g.guiHeight() - lid, g.guiWidth(), g.guiHeight(), 0xFF000000);
        }
    }
}
