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
 * Server → client. Opens the {@code PenKeepScreen} ("keep how many adults?") when the player
 * plain-right-clicks an existing pen with the Foreman's Rod. Carries the pen's marker pos (round-tripped
 * back so the server edits the right pen) plus what the screen needs to display: the contained animal id,
 * the current mature count, the pen's capacity, the kill counter, and the current adult keep threshold
 * (0 = auto/full capacity).
 */
@ApiStatus.Internal
public record OpenPenKeepPayload(BlockPos penPos, String animalId, int mature, int capacity, int kills, int keep)
        implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<OpenPenKeepPayload> TYPE =
        new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(BannerboundCore.MODID, "open_pen_keep"));

    public static final StreamCodec<ByteBuf, OpenPenKeepPayload> STREAM_CODEC = StreamCodec.composite(
        BlockPos.STREAM_CODEC, OpenPenKeepPayload::penPos,
        ByteBufCodecs.STRING_UTF8, OpenPenKeepPayload::animalId,
        ByteBufCodecs.VAR_INT, OpenPenKeepPayload::mature,
        ByteBufCodecs.VAR_INT, OpenPenKeepPayload::capacity,
        ByteBufCodecs.VAR_INT, OpenPenKeepPayload::kills,
        ByteBufCodecs.VAR_INT, OpenPenKeepPayload::keep,
        OpenPenKeepPayload::new);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
