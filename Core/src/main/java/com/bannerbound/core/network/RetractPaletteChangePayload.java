package com.bannerbound.core.network;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.BannerboundCore;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * C→S: cancel the settlement's pending palette change + its confirm votes. Twin of
 * {@link RetractPolicyChangePayload}. No fields — clears whatever change is currently pending.
 */
@ApiStatus.Internal
public record RetractPaletteChangePayload() implements CustomPacketPayload {
    public static final RetractPaletteChangePayload INSTANCE = new RetractPaletteChangePayload();

    public static final CustomPacketPayload.Type<RetractPaletteChangePayload> TYPE =
        new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(
            BannerboundCore.MODID, "retract_palette_change"));

    public static final StreamCodec<ByteBuf, RetractPaletteChangePayload> STREAM_CODEC =
        StreamCodec.unit(INSTANCE);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() { return TYPE; }
}
