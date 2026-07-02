package com.bannerbound.core.client;

import org.jetbrains.annotations.ApiStatus;

/**
 * Duck-typing interface mixed onto {@code net.minecraft.client.GuiMessage} and
 * {@code GuiMessage.Line} to carry a per-message text alpha (audibility) for proximity chat.
 *
 * <p>Convention: {@code 0} means "unset → render fully opaque" (the default for every ordinary
 * message). A proximity message stamps a value in {@code (0,1]}; the chat render mixin multiplies
 * the line's text alpha by it.
 */
@ApiStatus.Internal
public interface ChatLineAlpha {
    float bannerbound$getChatAlpha();

    void bannerbound$setChatAlpha(float alpha);
}
