package com.bannerbound.core.network;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.BannerboundCore;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * C→S: the seated Chief is stepping down. Server clears {@code chiefPlayerId} (which opens
 * the chief-election window again via {@code chiefdomElectionWindowOpen()}) and broadcasts
 * the news. The regent recompute fires automatically afterward — if the now-ex-chief stays
 * online they may end up as their own settlement's regent until a new chief is elected.
 *
 * <p>No fields — the actor is the {@code IPayloadContext}'s player; the server validates that
 * the caller actually IS the chief before honouring the click.
 */
@ApiStatus.Internal
public record QuitChiefPayload() implements CustomPacketPayload {
    public static final QuitChiefPayload INSTANCE = new QuitChiefPayload();

    public static final CustomPacketPayload.Type<QuitChiefPayload> TYPE =
        new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(
            BannerboundCore.MODID, "quit_chief"));

    public static final StreamCodec<ByteBuf, QuitChiefPayload> STREAM_CODEC =
        StreamCodec.unit(INSTANCE);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() { return TYPE; }
}
