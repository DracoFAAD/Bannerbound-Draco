package com.bannerbound.core.network;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.BannerboundCore;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * C→S: cancel the settlement's pending policy change + its confirm votes. Sent by the original
 * proposer (or, in a Chiefdom, the chief) clicking Cancel on the pending slot. No fields — the
 * server clears whatever change is currently pending.
 */
@ApiStatus.Internal
public record RetractPolicyChangePayload() implements CustomPacketPayload {
    public static final RetractPolicyChangePayload INSTANCE = new RetractPolicyChangePayload();

    public static final CustomPacketPayload.Type<RetractPolicyChangePayload> TYPE =
        new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(
            BannerboundCore.MODID, "retract_policy_change"));

    public static final StreamCodec<ByteBuf, RetractPolicyChangePayload> STREAM_CODEC =
        StreamCodec.unit(INSTANCE);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() { return TYPE; }
}
