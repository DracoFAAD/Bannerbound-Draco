package com.bannerbound.core.network;

import java.util.List;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.BannerboundCore;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Server → client: the list of available culture styles, so the founding screen's style picker
 * can list them. Three parallel lists — style id, its display name, and its preview-image
 * ResourceLocation string.
 */
@ApiStatus.Internal
public record CultureStyleSyncPayload(List<String> ids, List<String> nameKeys, List<String> images)
        implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<CultureStyleSyncPayload> TYPE =
        new CustomPacketPayload.Type<>(
            ResourceLocation.fromNamespaceAndPath(BannerboundCore.MODID, "culture_style_sync"));

    public static final StreamCodec<ByteBuf, CultureStyleSyncPayload> STREAM_CODEC = StreamCodec.composite(
        ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list()), CultureStyleSyncPayload::ids,
        ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list()), CultureStyleSyncPayload::nameKeys,
        ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list()), CultureStyleSyncPayload::images,
        CultureStyleSyncPayload::new
    );

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
