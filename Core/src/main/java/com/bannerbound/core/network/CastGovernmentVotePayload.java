package com.bannerbound.core.network;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.BannerboundCore;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Client → server: this player casts (or overrides) their pick in the active Choose-Government
 * vote. The {@code governmentOrdinal} maps to {@link com.bannerbound.core.api.settlement.Settlement.Government}:
 * 1 = COUNCIL, 2 = CHIEFDOM. 0 (NONE) is rejected server-side — that would be voting for
 * anarchy, which is the absence of a government, not a valid option.
 *
 * <p>Server-side handling lives in {@code SettlementManager.handleGovernmentVote} via
 * {@code ServerPayloadHandler.handleCastGovernmentVote}.
 */
@ApiStatus.Internal
public record CastGovernmentVotePayload(int governmentOrdinal) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<CastGovernmentVotePayload> TYPE =
        new CustomPacketPayload.Type<>(
            ResourceLocation.fromNamespaceAndPath(BannerboundCore.MODID, "cast_government_vote"));

    public static final StreamCodec<ByteBuf, CastGovernmentVotePayload> STREAM_CODEC = StreamCodec.of(
        (buf, p) -> ByteBufCodecs.VAR_INT.encode(buf, p.governmentOrdinal()),
        buf -> new CastGovernmentVotePayload(ByteBufCodecs.VAR_INT.decode(buf))
    );

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
