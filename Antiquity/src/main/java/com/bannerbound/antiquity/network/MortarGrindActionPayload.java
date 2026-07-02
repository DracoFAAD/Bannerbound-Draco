package com.bannerbound.antiquity.network;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.antiquity.BannerboundAntiquity;

import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Client to server: completion/cancel for the active mortar press-and-grind minigame. */
@ApiStatus.Internal
public record MortarGrindActionPayload(BlockPos pos, int action)
        implements CustomPacketPayload {
    public static final int COMPLETE = 0;
    public static final int CANCEL = 1;

    public static final CustomPacketPayload.Type<MortarGrindActionPayload> TYPE =
        new CustomPacketPayload.Type<>(
            ResourceLocation.fromNamespaceAndPath(BannerboundAntiquity.MODID, "mortar_grind_action"));

    public static final StreamCodec<ByteBuf, MortarGrindActionPayload> STREAM_CODEC =
        StreamCodec.composite(
            BlockPos.STREAM_CODEC, MortarGrindActionPayload::pos,
            ByteBufCodecs.VAR_INT, MortarGrindActionPayload::action,
            MortarGrindActionPayload::new
        );

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
