package com.bannerbound.core.network;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.BannerboundCore;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Client -> server: set the "auto-pin tutorials" Chronicle preference. */
@ApiStatus.Internal
public record SetAutoPinTutorialPayload(boolean enabled) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<SetAutoPinTutorialPayload> TYPE =
        new CustomPacketPayload.Type<>(
            ResourceLocation.fromNamespaceAndPath(BannerboundCore.MODID, "set_auto_pin_tutorial"));

    public static final StreamCodec<ByteBuf, SetAutoPinTutorialPayload> STREAM_CODEC = StreamCodec.composite(
        ByteBufCodecs.BOOL,
        SetAutoPinTutorialPayload::enabled,
        SetAutoPinTutorialPayload::new
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
