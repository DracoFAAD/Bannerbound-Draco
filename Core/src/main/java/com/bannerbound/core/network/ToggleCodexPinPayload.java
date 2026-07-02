package com.bannerbound.core.network;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.BannerboundCore;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Client -> server: pin/unpin a Chronicle entry on the side journal. */
@ApiStatus.Internal
public record ToggleCodexPinPayload(String entryId) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<ToggleCodexPinPayload> TYPE =
        new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(BannerboundCore.MODID, "toggle_codex_pin"));

    public static final StreamCodec<ByteBuf, ToggleCodexPinPayload> STREAM_CODEC = StreamCodec.composite(
        ByteBufCodecs.STRING_UTF8,
        ToggleCodexPinPayload::entryId,
        ToggleCodexPinPayload::new
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
