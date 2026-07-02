package com.bannerbound.core.network;

import java.util.List;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.BannerboundCore;

import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Server → client. Opens the {@code PenAnimalPickerScreen} for a pen the player just marked with the
 * Foreman's Rod. Carries the pen's marker position (round-tripped back so the server commits the right
 * pen) and the list of animal entity ids the player may choose (basics always; horse only on a horse chunk).
 */
@ApiStatus.Internal
public record OpenPenAnimalPickerPayload(BlockPos penPos, List<String> animalIds) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<OpenPenAnimalPickerPayload> TYPE =
        new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(BannerboundCore.MODID, "open_pen_animal_picker"));

    public static final StreamCodec<ByteBuf, OpenPenAnimalPickerPayload> STREAM_CODEC = StreamCodec.composite(
        BlockPos.STREAM_CODEC, OpenPenAnimalPickerPayload::penPos,
        ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list()), OpenPenAnimalPickerPayload::animalIds,
        OpenPenAnimalPickerPayload::new);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
