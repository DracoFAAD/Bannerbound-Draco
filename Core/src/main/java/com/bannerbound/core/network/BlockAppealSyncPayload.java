package com.bannerbound.core.network;

import java.util.List;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.BannerboundCore;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Server → client: the <i>resolved</i> block appeal for the receiving player's settlement —
 * base appeal already combined with the settlement's culture-style overrides. Two parallel
 * lists (block id, appeal). Re-sent whenever the styles change (founding, datapack reload) so
 * the in-game appeal tooltip and the beauty-debug overlay stay accurate.
 */
@ApiStatus.Internal
public record BlockAppealSyncPayload(List<String> blockIds, List<Float> appeals)
        implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<BlockAppealSyncPayload> TYPE =
        new CustomPacketPayload.Type<>(
            ResourceLocation.fromNamespaceAndPath(BannerboundCore.MODID, "block_appeal_sync"));

    public static final StreamCodec<ByteBuf, BlockAppealSyncPayload> STREAM_CODEC = StreamCodec.composite(
        ByteBufCodecs.STRING_UTF8.apply(ByteBufCodecs.list()), BlockAppealSyncPayload::blockIds,
        ByteBufCodecs.FLOAT.apply(ByteBufCodecs.list()), BlockAppealSyncPayload::appeals,
        BlockAppealSyncPayload::new
    );

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
