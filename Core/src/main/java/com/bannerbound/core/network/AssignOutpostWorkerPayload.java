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
 * Client → server. The Outpost Banner screen's assign/unassign action: bind the outpost's deposit
 * marker to the chosen miner ({@code citizenUuid}), or clear it ({@code ""} = unassign, which
 * removes the marker entirely — an outpost with nobody appointed mines nothing). The server
 * validates membership/claim/job, mutates the marker, and replies with a fresh
 * {@link OpenOutpostScreenPayload} so the screen live-updates.
 */
@ApiStatus.Internal
public record AssignOutpostWorkerPayload(BlockPos bannerPos, String citizenUuid)
        implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<AssignOutpostWorkerPayload> TYPE =
        new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(
            BannerboundCore.MODID, "assign_outpost_worker"));

    public static final StreamCodec<ByteBuf, AssignOutpostWorkerPayload> STREAM_CODEC = StreamCodec.composite(
        BlockPos.STREAM_CODEC, AssignOutpostWorkerPayload::bannerPos,
        ByteBufCodecs.STRING_UTF8, AssignOutpostWorkerPayload::citizenUuid,
        AssignOutpostWorkerPayload::new);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
