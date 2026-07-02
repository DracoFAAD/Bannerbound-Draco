package com.bannerbound.antiquity.client;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.antiquity.BannerboundAntiquity;
import com.bannerbound.antiquity.combat.BluntStun;

import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderGuiEvent;

/**
 * Client driver for the blunt-weapon crit DAZE: while the local player is mid-stun, blur their vision
 * in and out for the 1s stagger. Reads the synced {@link BannerboundAntiquity#STUN_UNTIL} deadline and
 * drives the shared {@link PoisonPostProcessor} blur pass. Run at the GUI stage (after the world +
 * shaders, before the HUD) so it's Iris-safe and the HUD stays crisp — same as the poison vision.
 */
@OnlyIn(Dist.CLIENT)
@ApiStatus.Internal
@EventBusSubscriber(modid = BannerboundAntiquity.MODID, value = Dist.CLIENT)
public final class StunClientEffects {
    private StunClientEffects() {}

    @SubscribeEvent
    static void onRenderGuiPre(RenderGuiEvent.Pre event) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) {
            return;
        }
        long until = mc.player.getData(BannerboundAntiquity.STUN_UNTIL.get());
        if (until <= 0L) {
            return;
        }
        // Smooth game time (with the partial tick) so the fade doesn't step at 20 Hz.
        float now = mc.level.getGameTime() + event.getPartialTick().getGameTimeDeltaPartialTick(false);
        float remaining = until - now;
        if (remaining <= 0.0F || remaining > BluntStun.STUN_TICKS) {
            return;
        }
        // Fade IN over the first ~0.2s, then OUT over the rest of the 1s — a quick daze that lifts.
        float elapsed = BluntStun.STUN_TICKS - remaining;             // 0 → 20 ticks
        float p = elapsed / BluntStun.STUN_TICKS;                     // 0 → 1
        float envelope = p < 0.2F ? p / 0.2F : 1.0F - (p - 0.2F) / 0.8F;
        PoisonPostProcessor.renderStun(envelope, now);
    }
}
