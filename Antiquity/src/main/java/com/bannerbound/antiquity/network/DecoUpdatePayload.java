package com.bannerbound.antiquity.network;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.antiquity.BannerboundAntiquity;
import com.bannerbound.antiquity.deco.FaceDecoEntry;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Server → client: one face's decoration changed (apply or clear). Sent to players tracking the
 *  chunk. A cleared face is carried as an entry whose {@code deco.isEmpty()}. */
@ApiStatus.Internal
public record DecoUpdatePayload(FaceDecoEntry entry) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<DecoUpdatePayload> TYPE =
        new CustomPacketPayload.Type<>(
            ResourceLocation.fromNamespaceAndPath(BannerboundAntiquity.MODID, "deco_update"));

    public static final StreamCodec<ByteBuf, DecoUpdatePayload> STREAM_CODEC =
        StreamCodec.composite(
            FaceDecoEntry.STREAM_CODEC, DecoUpdatePayload::entry,
            DecoUpdatePayload::new);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
