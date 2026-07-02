package com.bannerbound.antiquity.network;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.antiquity.BannerboundAntiquity;

import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Client to server: completion/cancel for the active pottery minigame. */
@ApiStatus.Internal
public record PotteryActionPayload(BlockPos pos, int action)
        implements CustomPacketPayload {
    public static final int COMPLETE = 0;
    public static final int CANCEL = 1;

    public static final CustomPacketPayload.Type<PotteryActionPayload> TYPE =
        new CustomPacketPayload.Type<>(
            ResourceLocation.fromNamespaceAndPath(BannerboundAntiquity.MODID, "pottery_action"));

    public static final StreamCodec<ByteBuf, PotteryActionPayload> STREAM_CODEC =
        StreamCodec.composite(
            BlockPos.STREAM_CODEC, PotteryActionPayload::pos,
            ByteBufCodecs.VAR_INT, PotteryActionPayload::action,
            PotteryActionPayload::new
        );

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
