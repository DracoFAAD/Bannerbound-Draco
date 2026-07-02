package com.bannerbound.core.network;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.BannerboundCore;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** C→S: set a forester citizen's preferred log species (block registry id) from the Job tab. */
@ApiStatus.Internal
public record SetCitizenPreferredLogPayload(int entityId, String logId) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<SetCitizenPreferredLogPayload> TYPE =
        new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(
            BannerboundCore.MODID, "set_citizen_preferred_log"));

    public static final StreamCodec<ByteBuf, SetCitizenPreferredLogPayload> STREAM_CODEC = StreamCodec.of(
        (buf, p) -> {
            ByteBufCodecs.VAR_INT.encode(buf, p.entityId());
            ByteBufCodecs.STRING_UTF8.encode(buf, p.logId());
        },
        buf -> new SetCitizenPreferredLogPayload(
            ByteBufCodecs.VAR_INT.decode(buf),
            ByteBufCodecs.STRING_UTF8.decode(buf))
    );

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() { return TYPE; }
}
