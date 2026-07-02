package com.bannerbound.core.network;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.BannerboundCore;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** C→S: toggle whether a forester citizen also stores the canopy's saplings / apples / sticks
 *  ({@code keep == true}) or keeps only logs ({@code keep == false}). Set from the Job tab. */
@ApiStatus.Internal
public record SetForesterKeepExtrasPayload(int entityId, boolean keep) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<SetForesterKeepExtrasPayload> TYPE =
        new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(
            BannerboundCore.MODID, "set_forester_keep_extras"));

    public static final StreamCodec<ByteBuf, SetForesterKeepExtrasPayload> STREAM_CODEC = StreamCodec.of(
        (buf, p) -> {
            ByteBufCodecs.VAR_INT.encode(buf, p.entityId());
            ByteBufCodecs.BOOL.encode(buf, p.keep());
        },
        buf -> new SetForesterKeepExtrasPayload(
            ByteBufCodecs.VAR_INT.decode(buf),
            ByteBufCodecs.BOOL.decode(buf))
    );

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() { return TYPE; }
}
