package com.bannerbound.core.network;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.BannerboundCore;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Client → server: a Chiefdom non-chief toggles a suggestion on {@code paletteId}. Twin of
 * {@link SuggestPolicyPayload} — the server toggles the suggester and re-broadcasts so the chief
 * sees the face badge on that palette row.
 */
@ApiStatus.Internal
public record SuggestPalettePayload(String paletteId) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<SuggestPalettePayload> TYPE =
        new CustomPacketPayload.Type<>(
            ResourceLocation.fromNamespaceAndPath(BannerboundCore.MODID, "suggest_palette"));

    public static final StreamCodec<ByteBuf, SuggestPalettePayload> STREAM_CODEC = StreamCodec.of(
        (buf, p) -> ByteBufCodecs.STRING_UTF8.encode(buf, p.paletteId()),
        buf -> new SuggestPalettePayload(ByteBufCodecs.STRING_UTF8.decode(buf))
    );

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
