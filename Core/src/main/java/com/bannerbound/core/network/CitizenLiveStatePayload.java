package com.bannerbound.core.network;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.BannerboundCore;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** S→C: small response carrying just the fields that change between
 *  {@link OpenCitizenScreenPayload} opens — currently compliance + the viewer's resentment.
 *  Health / stamina / happiness live in synced entity data and are already real-time via
 *  the existing client-side entity lookup. */
@ApiStatus.Internal
public record CitizenLiveStatePayload(
    int entityId,
    int compliance,
    int viewerResentment
) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<CitizenLiveStatePayload> TYPE =
        new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(
            BannerboundCore.MODID, "citizen_live_state"));

    public static final StreamCodec<ByteBuf, CitizenLiveStatePayload> STREAM_CODEC = StreamCodec.of(
        (buf, p) -> {
            ByteBufCodecs.VAR_INT.encode(buf, p.entityId());
            ByteBufCodecs.VAR_INT.encode(buf, p.compliance());
            ByteBufCodecs.VAR_INT.encode(buf, p.viewerResentment());
        },
        buf -> new CitizenLiveStatePayload(
            ByteBufCodecs.VAR_INT.decode(buf),
            ByteBufCodecs.VAR_INT.decode(buf),
            ByteBufCodecs.VAR_INT.decode(buf))
    );

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() { return TYPE; }
}
