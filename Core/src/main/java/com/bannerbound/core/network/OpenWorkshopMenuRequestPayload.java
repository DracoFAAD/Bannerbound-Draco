package com.bannerbound.core.network;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.BannerboundCore;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Client → server: open the workshop menu by id (the Job tab's "Open" button beside a crafter's
 * bound workshop — no rod needed). The server resolves + re-validates and replies with the usual
 * {@code OpenWorkshopMenuPayload}.
 */
@ApiStatus.Internal
public record OpenWorkshopMenuRequestPayload(String workshopId) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<OpenWorkshopMenuRequestPayload> TYPE =
        new CustomPacketPayload.Type<>(
            ResourceLocation.fromNamespaceAndPath(BannerboundCore.MODID, "open_workshop_menu_request"));

    public static final StreamCodec<ByteBuf, OpenWorkshopMenuRequestPayload> STREAM_CODEC =
        StreamCodec.composite(
            ByteBufCodecs.STRING_UTF8, OpenWorkshopMenuRequestPayload::workshopId,
            OpenWorkshopMenuRequestPayload::new
        );

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
