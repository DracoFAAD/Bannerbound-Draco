package com.bannerbound.core.network;

import com.bannerbound.core.BannerboundCore;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * C→S: forget a god (PantheonScreen). Governance-gated like creation; the constellation
 * fades and its stars return to the faith's claimable pool. No devotion refund.
 */
public record ForgetConstellationPayload(String constellationId) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<ForgetConstellationPayload> TYPE =
        new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(BannerboundCore.MODID, "forget_constellation"));

    public static final StreamCodec<ByteBuf, ForgetConstellationPayload> STREAM_CODEC = StreamCodec.of(
        (buf, p) -> ByteBufCodecs.stringUtf8(40).encode(buf, p.constellationId()),
        buf -> new ForgetConstellationPayload(ByteBufCodecs.stringUtf8(40).decode(buf))
    );

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
