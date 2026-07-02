package com.bannerbound.core.network;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.BannerboundCore;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * C→S: an individual member is leaving their settlement (the Town Hall "Leave Settlement" button).
 * Server runs {@code SettlementManager.tryLeave}, which removes the player, drops their research
 * access, and collapses the settlement if they were the last member.
 *
 * <p>No fields — the actor is the {@code IPayloadContext}'s player. The server refuses a <b>seated
 * Chief</b> (CHIEFDOM): a chief must Step Down first (and serve the minimum term), mirroring the UI
 * where a chief sees the Step Down button in this slot instead of Leave.
 */
@ApiStatus.Internal
public record LeaveSettlementPayload() implements CustomPacketPayload {
    public static final LeaveSettlementPayload INSTANCE = new LeaveSettlementPayload();

    public static final CustomPacketPayload.Type<LeaveSettlementPayload> TYPE =
        new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(
            BannerboundCore.MODID, "leave_settlement"));

    public static final StreamCodec<ByteBuf, LeaveSettlementPayload> STREAM_CODEC =
        StreamCodec.unit(INSTANCE);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() { return TYPE; }
}
