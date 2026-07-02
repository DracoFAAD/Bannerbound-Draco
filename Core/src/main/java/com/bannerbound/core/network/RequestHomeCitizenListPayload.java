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
 * Client → server: open the home-resident picker for the home with id {@code homeId}. The server
 * responds with {@link HomeCitizenListPayload}, listing every citizen in the player's settlement
 * bucketed into (current residents · homeless · other-home residents) so the picker can render the
 * three sections in priority order.
 */
@ApiStatus.Internal
public record RequestHomeCitizenListPayload(UUID homeId) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<RequestHomeCitizenListPayload> TYPE =
        new CustomPacketPayload.Type<>(
            ResourceLocation.fromNamespaceAndPath(BannerboundCore.MODID, "request_home_citizen_list"));

    public static final StreamCodec<ByteBuf, RequestHomeCitizenListPayload> STREAM_CODEC =
        StreamCodec.composite(
            UUIDUtil.STREAM_CODEC, RequestHomeCitizenListPayload::homeId,
            RequestHomeCitizenListPayload::new
        );

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
