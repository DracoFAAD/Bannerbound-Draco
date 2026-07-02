package com.bannerbound.antiquity.client;

import com.bannerbound.antiquity.BannerboundAntiquity;

import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RenderHandEvent;
import net.neoforged.neoforge.client.event.RenderLivingEvent;

/**
 * Publishes the entity currently being rendered into {@link HeldItemRenderContext} so held-item model
 * wrappers (the spear raise-flip) can key on the real entity instead of a global. Without this, the
 * local player raising a spear flips every spear on screen.
 *
 * <ul>
 *   <li>{@link RenderLivingEvent} brackets every living entity's third-person render — other players
 *       and citizens. Pre sets the entity, Post clears it.</li>
 *   <li>{@link RenderHandEvent} brackets the local player's first-person hand. (First person already
 *       looked correct, but we set the context anyway so the wrapper has a consistent source and the
 *       held-spear render is never keyed on a stale entity left over from a living render.)</li>
 * </ul>
 *
 * Game (NeoForge) bus, client only — the bus is auto-detected from the game-bus event types below.
 */
@EventBusSubscriber(modid = BannerboundAntiquity.MODID, value = Dist.CLIENT)
public final class SpearRenderEvents {
    private SpearRenderEvents() {
    }

    @SubscribeEvent
    public static void onRenderLivingPre(RenderLivingEvent.Pre<?, ?> event) {
        HeldItemRenderContext.set(event.getEntity());
    }

    @SubscribeEvent
    public static void onRenderLivingPost(RenderLivingEvent.Post<?, ?> event) {
        HeldItemRenderContext.clear();
    }

    @SubscribeEvent
    public static void onRenderHandPre(RenderHandEvent event) {
        // First-person: the held item belongs to the local player.
        HeldItemRenderContext.set(Minecraft.getInstance().player);
    }
}
