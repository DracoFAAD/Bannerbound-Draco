package com.bannerbound.core.network;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.BannerboundCore;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Client → server: this Council member's Agree ({@code true}) / Disagree ({@code false}) vote on
 * the settlement's pending palette change. Twin of {@link CastPolicyVotePayload}.
 */
@ApiStatus.Internal
public record CastPaletteVotePayload(boolean agree) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<CastPaletteVotePayload> TYPE =
        new CustomPacketPayload.Type<>(
            ResourceLocation.fromNamespaceAndPath(BannerboundCore.MODID, "cast_palette_vote"));

    public static final StreamCodec<ByteBuf, CastPaletteVotePayload> STREAM_CODEC = StreamCodec.of(
        (buf, p) -> buf.writeBoolean(p.agree()),
        buf -> new CastPaletteVotePayload(buf.readBoolean())
    );

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
