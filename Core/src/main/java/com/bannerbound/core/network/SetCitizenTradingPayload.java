package com.bannerbound.core.network;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.BannerboundCore;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** C→S: flip a stocker's "Trading" opt-in from the Job tab — whether this stocker may be adopted
 *  as a walking trade courier. The server re-checks management permission + the stocker job. */
@ApiStatus.Internal
public record SetCitizenTradingPayload(int entityId, boolean enabled) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<SetCitizenTradingPayload> TYPE =
        new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(
            BannerboundCore.MODID, "set_citizen_trading"));

    public static final StreamCodec<ByteBuf, SetCitizenTradingPayload> STREAM_CODEC = StreamCodec.of(
        (buf, p) -> {
            ByteBufCodecs.VAR_INT.encode(buf, p.entityId());
            ByteBufCodecs.BOOL.encode(buf, p.enabled());
        },
        buf -> new SetCitizenTradingPayload(
            ByteBufCodecs.VAR_INT.decode(buf),
            ByteBufCodecs.BOOL.decode(buf))
    );

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() { return TYPE; }
}
