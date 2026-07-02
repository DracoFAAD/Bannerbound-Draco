package com.bannerbound.core.network;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.BannerboundCore;

import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Client → server. The player's chosen animal for the pen they just marked (from {@code PenAnimalPickerScreen}).
 * Carries the pen marker pos (so the server commits the right pen) and the chosen animal entity id; the
 * server re-validates the pen + that the animal is allowed before registering the selection.
 */
@ApiStatus.Internal
public record PickPenAnimalPayload(BlockPos penPos, String animalId) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<PickPenAnimalPayload> TYPE =
        new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(BannerboundCore.MODID, "pick_pen_animal"));

    public static final StreamCodec<ByteBuf, PickPenAnimalPayload> STREAM_CODEC = StreamCodec.composite(
        BlockPos.STREAM_CODEC, PickPenAnimalPayload::penPos,
        ByteBufCodecs.STRING_UTF8, PickPenAnimalPayload::animalId,
        PickPenAnimalPayload::new);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
