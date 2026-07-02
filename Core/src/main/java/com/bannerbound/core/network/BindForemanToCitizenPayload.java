package com.bannerbound.core.network;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.BannerboundCore;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** C→S: the Job tab's "Select work area" button. Points the player's Foreman's Rod at the digger
 *  unit and binds it to this citizen, so areas they mark with it are dug only by this digger. */
@ApiStatus.Internal
public record BindForemanToCitizenPayload(int entityId) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<BindForemanToCitizenPayload> TYPE =
        new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(
            BannerboundCore.MODID, "bind_foreman_to_citizen"));

    public static final StreamCodec<ByteBuf, BindForemanToCitizenPayload> STREAM_CODEC = StreamCodec.of(
        (buf, p) -> ByteBufCodecs.VAR_INT.encode(buf, p.entityId()),
        buf -> new BindForemanToCitizenPayload(ByteBufCodecs.VAR_INT.decode(buf))
    );

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() { return TYPE; }
}
