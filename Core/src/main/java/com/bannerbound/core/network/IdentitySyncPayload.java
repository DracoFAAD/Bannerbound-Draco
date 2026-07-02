package com.bannerbound.core.network;

import java.util.List;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.BannerboundCore;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * S→C: the server-wide settlement IDENTITY color table — for each settlement, the founding
 * {@code SettlementColor} ordinal (unique per server, so it doubles as the settlement key in
 * every payload that already carries a colorIndex) and its banner-derived identity colors as
 * an ORDERED 0xRRGGBB list, most-present dye first. The list is as long as the banner is
 * colorful (1..n) — a one-color banner syncs one color, a five-color banner five.
 *
 * <p>Every client renderer that shows "a settlement's color" resolves through
 * {@code ClientIdentityState} instead of mapping the ordinal back to the founding rgb — the
 * banner IS the color. Sent with claim syncs (login + every claims broadcast) and
 * re-broadcast on banner-design saves.
 */
@ApiStatus.Internal
public record IdentitySyncPayload(List<Integer> colorOrdinals, List<List<Integer>> rgbLists)
        implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<IdentitySyncPayload> TYPE =
        new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(
            BannerboundCore.MODID, "identity_sync"));

    public static final StreamCodec<ByteBuf, IdentitySyncPayload> STREAM_CODEC =
        StreamCodec.composite(
            ByteBufCodecs.VAR_INT.apply(ByteBufCodecs.list()), IdentitySyncPayload::colorOrdinals,
            ByteBufCodecs.VAR_INT.apply(ByteBufCodecs.list()).apply(ByteBufCodecs.list()),
            IdentitySyncPayload::rgbLists,
            IdentitySyncPayload::new);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() { return TYPE; }
}
