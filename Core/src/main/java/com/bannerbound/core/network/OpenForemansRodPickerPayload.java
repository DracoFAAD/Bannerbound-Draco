package com.bannerbound.core.network;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.BannerboundCore;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Server → client. Trigger to open the {@code ForemansRodPickerScreen}. Carries no payload
 * fields today — the picker list is hard-coded client-side (only "Digger" + Clear in v1).
 * When more workstation types come online, this can grow into carrying the allowlist.
 */
@ApiStatus.Internal
public record OpenForemansRodPickerPayload() implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<OpenForemansRodPickerPayload> TYPE =
        new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(BannerboundCore.MODID, "open_foremans_rod_picker"));

    public static final StreamCodec<ByteBuf, OpenForemansRodPickerPayload> STREAM_CODEC =
        StreamCodec.unit(new OpenForemansRodPickerPayload());

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
