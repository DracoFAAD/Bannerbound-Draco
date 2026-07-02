package com.bannerbound.antiquity.network;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.antiquity.BannerboundAntiquity;

import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Server to client: open the pottery wheel minigame for the selected slab recipe. The wheel is a
 *  non-skill minigame (no accuracy scoring); {@code spins} is just how many turns the shaping takes. */
@ApiStatus.Internal
public record OpenPotteryPayload(BlockPos pos, int spins)
        implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<OpenPotteryPayload> TYPE =
        new CustomPacketPayload.Type<>(
            ResourceLocation.fromNamespaceAndPath(BannerboundAntiquity.MODID, "open_pottery"));

    public static final StreamCodec<ByteBuf, OpenPotteryPayload> STREAM_CODEC =
        StreamCodec.composite(
            BlockPos.STREAM_CODEC, OpenPotteryPayload::pos,
            ByteBufCodecs.VAR_INT, OpenPotteryPayload::spins,
            OpenPotteryPayload::new
        );

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
