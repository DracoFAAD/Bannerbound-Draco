package com.bannerbound.antiquity.network;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.antiquity.BannerboundAntiquity;

import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Client to server: end of the tanning-rack scrape minigame. */
@ApiStatus.Internal
public record TanningActionPayload(BlockPos pos, int action) implements CustomPacketPayload {
    public static final int COMPLETE = 0;
    public static final int CANCEL = 1;

    public static final CustomPacketPayload.Type<TanningActionPayload> TYPE =
        new CustomPacketPayload.Type<>(
            ResourceLocation.fromNamespaceAndPath(BannerboundAntiquity.MODID, "tanning_action"));

    public static final StreamCodec<ByteBuf, TanningActionPayload> STREAM_CODEC =
        StreamCodec.composite(
            BlockPos.STREAM_CODEC, TanningActionPayload::pos,
            ByteBufCodecs.VAR_INT, TanningActionPayload::action,
            TanningActionPayload::new
        );

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
