package com.bannerbound.core.mixin;

import org.jetbrains.annotations.ApiStatus;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;

import com.bannerbound.core.client.ChatLineAlpha;

import net.minecraft.client.GuiMessage;

/**
 * Carries a proximity-chat text alpha on the persistent {@link GuiMessage} (the entry kept in
 * {@code allMessages}). Storing it here — not just on the transient display {@code Line} — means
 * the alpha survives a chat rescale, which rebuilds every {@code Line} from its {@code GuiMessage}.
 *
 * <p>Default {@code 0} == "unset → opaque"; see {@link ChatLineAlpha}.
 */
@Mixin(GuiMessage.class)
@ApiStatus.Internal
public class GuiMessageMixin implements ChatLineAlpha {
    @Unique
    private float bannerbound$chatAlpha;

    @Override
    public float bannerbound$getChatAlpha() {
        return this.bannerbound$chatAlpha;
    }

    @Override
    public void bannerbound$setChatAlpha(float alpha) {
        this.bannerbound$chatAlpha = alpha;
    }
}
