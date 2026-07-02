package com.bannerbound.core.network;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.BannerboundCore;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * S→C signal: close any Bannerbound settlement-related screen the player has open. Sent to
 * every former member after a disband so a player who was sitting in the town hall / expand-
 * territory / citizen / research / culture screen doesn't end up staring at stale data for a
 * settlement that no longer exists.
 *
 * <p>No fields — the trigger is the whole payload; the client checks the current
 * {@code Minecraft.screen} type and dismisses if it matches any of the known settlement
 * screens. Cheap: one packet per former member, no per-screen specialisation.
 */
@ApiStatus.Internal
public record CloseSettlementScreensPayload() implements CustomPacketPayload {
    public static final CloseSettlementScreensPayload INSTANCE = new CloseSettlementScreensPayload();

    public static final CustomPacketPayload.Type<CloseSettlementScreensPayload> TYPE =
        new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(
            BannerboundCore.MODID, "close_settlement_screens"));

    public static final StreamCodec<ByteBuf, CloseSettlementScreensPayload> STREAM_CODEC =
        StreamCodec.unit(INSTANCE);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() { return TYPE; }
}
