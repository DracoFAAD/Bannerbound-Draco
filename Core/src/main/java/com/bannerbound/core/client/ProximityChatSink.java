package com.bannerbound.core.client;

import org.jetbrains.annotations.ApiStatus;

import net.minecraft.network.chat.Component;

/**
 * Duck-typing interface mixed onto {@code net.minecraft.client.gui.components.ChatComponent}.
 * Lets the proximity-chat payload handler push a chat line that renders at a given alpha
 * (audibility) without disturbing vanilla's message pipeline.
 */
@ApiStatus.Internal
public interface ProximityChatSink {
    /**
     * Adds {@code message} to the chat HUD, rendered at {@code alpha} (0–1) text transparency.
     * @param alpha audibility factor; values {@code >= 1} render fully opaque.
     */
    void bannerbound$addProximityMessage(Component message, float alpha);
}
