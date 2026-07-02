package com.bannerbound.antiquity.network;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.antiquity.BannerboundAntiquity;

import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Server → client: open the fletching stretch minigame for the receiving player, carrying the matched
 * recipe's stretch parameters. The server holds the authoritative session (player + station pos +
 * recipe); the client plays the minigame and replies with {@link FletchingActionPayload}.
 *
 * @param pos          the station block (echoed back in the action payload to match the session)
 * @param stretches    number of hold-and-release reps
 * @param baseZonePct  green-zone width (bar fraction) on the first stretch
 * @param zoneDecay    per-stretch green-zone shrink factor
 * @param minZonePct   floor for the green-zone width
 * @param yellowPadPct width of the amber "good" band on each side of the green zone
 */
@ApiStatus.Internal
public record OpenFletchingPayload(BlockPos pos, int stretches, float baseZonePct, float zoneDecay,
                                   float minZonePct, float yellowPadPct) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<OpenFletchingPayload> TYPE =
        new CustomPacketPayload.Type<>(
            ResourceLocation.fromNamespaceAndPath(BannerboundAntiquity.MODID, "open_fletching"));

    public static final StreamCodec<ByteBuf, OpenFletchingPayload> STREAM_CODEC =
        StreamCodec.composite(
            BlockPos.STREAM_CODEC, OpenFletchingPayload::pos,
            ByteBufCodecs.VAR_INT, OpenFletchingPayload::stretches,
            ByteBufCodecs.FLOAT, OpenFletchingPayload::baseZonePct,
            ByteBufCodecs.FLOAT, OpenFletchingPayload::zoneDecay,
            ByteBufCodecs.FLOAT, OpenFletchingPayload::minZonePct,
            ByteBufCodecs.FLOAT, OpenFletchingPayload::yellowPadPct,
            OpenFletchingPayload::new
        );

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
