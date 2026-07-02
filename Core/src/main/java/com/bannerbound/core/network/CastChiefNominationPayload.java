package com.bannerbound.core.network;

import java.util.UUID;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.BannerboundCore;

import io.netty.buffer.ByteBuf;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Client → server: this player nominates {@code candidate} for the active chief election.
 * Replaces any previous nomination from the same voter (you can change your mind until the
 * round resolves). Routed to {@code SettlementManager.handleChiefNomination} via
 * {@code ServerPayloadHandler.handleCastChiefNomination}.
 *
 * <p>The candidate must be a current member of the voter's settlement; the server rejects
 * non-members with a red toast, so a tampered packet can't install an outsider.
 */
@ApiStatus.Internal
public record CastChiefNominationPayload(UUID candidate) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<CastChiefNominationPayload> TYPE =
        new CustomPacketPayload.Type<>(
            ResourceLocation.fromNamespaceAndPath(BannerboundCore.MODID, "cast_chief_nomination"));

    public static final StreamCodec<ByteBuf, CastChiefNominationPayload> STREAM_CODEC = StreamCodec.of(
        (buf, p) -> UUIDUtil.STREAM_CODEC.encode(buf, p.candidate()),
        buf -> new CastChiefNominationPayload(UUIDUtil.STREAM_CODEC.decode(buf))
    );

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
