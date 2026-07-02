package com.bannerbound.core.network;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.BannerboundCore;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** C→S: while a citizen screen is open, the client requests fresh compliance + resentment
 *  data once a second (every 20 ticks) so the screen reflects live values instead of the
 *  frozen open-time snapshot. The server replies with {@link CitizenLiveStatePayload}. */
@ApiStatus.Internal
public record RequestCitizenLiveStatePayload(int entityId) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<RequestCitizenLiveStatePayload> TYPE =
        new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(
            BannerboundCore.MODID, "request_citizen_live"));

    public static final StreamCodec<ByteBuf, RequestCitizenLiveStatePayload> STREAM_CODEC =
        ByteBufCodecs.VAR_INT.map(RequestCitizenLiveStatePayload::new,
            RequestCitizenLiveStatePayload::entityId);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() { return TYPE; }
}
