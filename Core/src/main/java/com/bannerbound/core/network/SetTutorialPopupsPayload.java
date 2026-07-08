package com.bannerbound.core.network;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.BannerboundCore;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Client -> server: set the per-player "pop-up tutorials" Chronicle preference. When off,
 *  fired popups downgrade to Chronicle toasts server-side (the content still unlocks). */
@ApiStatus.Internal
public record SetTutorialPopupsPayload(boolean enabled) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<SetTutorialPopupsPayload> TYPE =
        new CustomPacketPayload.Type<>(
            ResourceLocation.fromNamespaceAndPath(BannerboundCore.MODID, "set_tutorial_popups"));

    public static final StreamCodec<ByteBuf, SetTutorialPopupsPayload> STREAM_CODEC = StreamCodec.composite(
        ByteBufCodecs.BOOL,
        SetTutorialPopupsPayload::enabled,
        SetTutorialPopupsPayload::new
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
