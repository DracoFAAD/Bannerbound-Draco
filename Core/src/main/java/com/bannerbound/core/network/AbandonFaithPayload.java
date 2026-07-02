package com.bannerbound.core.network;

import com.bannerbound.core.BannerboundCore;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * C→S: the player clicked Abandon Faith (FaithInfoScreen). Chief/owner resolve instantly;
 * COUNCIL members each click to add a yes-vote (see FaithManager.handleAbandonFaith).
 */
public record AbandonFaithPayload() implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<AbandonFaithPayload> TYPE =
        new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(BannerboundCore.MODID, "abandon_faith"));

    public static final StreamCodec<ByteBuf, AbandonFaithPayload> STREAM_CODEC =
        StreamCodec.of((buf, p) -> { }, buf -> new AbandonFaithPayload());

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
