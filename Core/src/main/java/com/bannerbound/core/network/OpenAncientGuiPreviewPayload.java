package com.bannerbound.core.network;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.BannerboundCore;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Server→client nudge to open {@link com.bannerbound.core.client.AncientWorldBoxScreen}, the
 * preview-only WorldBox-style Ancient-era Town Hall skin. Sent by {@code /bannerbound gui ancient}
 * so the look can be evaluated without touching the live Town Hall flow.
 */
@ApiStatus.Internal
public record OpenAncientGuiPreviewPayload() implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<OpenAncientGuiPreviewPayload> TYPE =
        new CustomPacketPayload.Type<>(
            ResourceLocation.fromNamespaceAndPath(BannerboundCore.MODID, "open_ancient_gui_preview"));

    public static final StreamCodec<ByteBuf, OpenAncientGuiPreviewPayload> STREAM_CODEC =
        StreamCodec.unit(new OpenAncientGuiPreviewPayload());

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
