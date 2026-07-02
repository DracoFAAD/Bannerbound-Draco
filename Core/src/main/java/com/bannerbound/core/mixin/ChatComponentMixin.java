package com.bannerbound.core.mixin;

import org.jetbrains.annotations.ApiStatus;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

import com.bannerbound.core.client.ChatLineAlpha;
import com.bannerbound.core.client.ProximityChatSink;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;

import net.minecraft.client.GuiMessage;
import net.minecraft.client.GuiMessageTag;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MessageSignature;
import net.minecraft.util.FormattedCharSequence;

/**
 * Per-message text transparency for proximity chat. A proximity line is pushed via
 * {@link #bannerbound$addProximityMessage} carrying an alpha (0–1) audibility factor; the alpha is
 * stamped onto the {@link GuiMessage}, copied onto every wrapped {@link GuiMessage.Line}, and finally
 * multiplied into the text colour the moment that line is drawn. Distant chatter therefore fades out
 * "as if you can't quite hear them," while every ordinary message (alpha left at its 1.0 default)
 * renders exactly as vanilla.
 *
 * <p>Storing the factor on the persistent {@link GuiMessage} (not only the transient line) makes it
 * survive a chat rescale, which rebuilds the lines from their messages.
 */
@Mixin(ChatComponent.class)
@ApiStatus.Internal
public class ChatComponentMixin implements ProximityChatSink {

    /** Alpha to stamp on the next message added; reset to 1.0 (opaque) after each proximity add. */
    @Unique
    private float bannerbound$nextAlpha = 1.0f;

    @Override
    public void bannerbound$addProximityMessage(Component message, float alpha) {
        this.bannerbound$nextAlpha = alpha;
        ((ChatComponent) (Object) this).addMessage(message);
        this.bannerbound$nextAlpha = 1.0f;
    }

    /** Stamp the pending alpha onto the GuiMessage as it is constructed. */
    @WrapOperation(
        method = "addMessage(Lnet/minecraft/network/chat/Component;Lnet/minecraft/network/chat/MessageSignature;Lnet/minecraft/client/GuiMessageTag;)V",
        at = @At(value = "NEW", target = "net/minecraft/client/GuiMessage"))
    private GuiMessage bannerbound$stampMessage(int addedTime, Component content, MessageSignature signature,
                                                GuiMessageTag tag, Operation<GuiMessage> original) {
        GuiMessage msg = original.call(addedTime, content, signature, tag);
        ((ChatLineAlpha) (Object) msg).bannerbound$setChatAlpha(this.bannerbound$nextAlpha);
        return msg;
    }

    /** Copy the message's alpha onto each wrapped display line (also runs on rescale). */
    @WrapOperation(
        method = "addMessageToDisplayQueue(Lnet/minecraft/client/GuiMessage;)V",
        at = @At(value = "NEW", target = "net/minecraft/client/GuiMessage$Line"))
    private GuiMessage.Line bannerbound$stampLine(int addedTime, FormattedCharSequence content, GuiMessageTag tag,
                                                  boolean endOfEntry, Operation<GuiMessage.Line> original,
                                                  @Local(argsOnly = true) GuiMessage message) {
        GuiMessage.Line line = original.call(addedTime, content, tag, endOfEntry);
        ((ChatLineAlpha) (Object) line).bannerbound$setChatAlpha(
            ((ChatLineAlpha) (Object) message).bannerbound$getChatAlpha());
        return line;
    }

    /** Fade the text colour of the line being drawn by its stored audibility alpha. */
    @ModifyArg(
        method = "render(Lnet/minecraft/client/gui/GuiGraphics;IIIZ)V",
        at = @At(value = "INVOKE",
            target = "Lnet/minecraft/client/gui/GuiGraphics;drawString(Lnet/minecraft/client/gui/Font;Lnet/minecraft/util/FormattedCharSequence;III)I"),
        index = 4)
    private int bannerbound$fadeChat(int color, @Local GuiMessage.Line line) {
        float a = ((ChatLineAlpha) (Object) line).bannerbound$getChatAlpha();
        if (a <= 0f || a >= 1f) {
            return color; // 0 == unset (opaque); >=1 == full clarity
        }
        int srcAlpha = (color >>> 24) & 0xFF;
        int newAlpha = Math.max(1, (int) (srcAlpha * a));
        return (color & 0x00FFFFFF) | (newAlpha << 24);
    }
}
