package com.bannerbound.core.network;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.BannerboundCore;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** C→S: release a citizen's job pin — return them to settlement labor auto-distribution ("Auto"). */
@ApiStatus.Internal
public record SetJobAutoPayload(int entityId) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<SetJobAutoPayload> TYPE =
        new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(
            BannerboundCore.MODID, "set_job_auto"));

    public static final StreamCodec<ByteBuf, SetJobAutoPayload> STREAM_CODEC = StreamCodec.of(
        (buf, p) -> ByteBufCodecs.VAR_INT.encode(buf, p.entityId()),
        buf -> new SetJobAutoPayload(ByteBufCodecs.VAR_INT.decode(buf))
    );

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() { return TYPE; }
}
