package com.bannerbound.core.network;

import java.util.List;

import com.bannerbound.core.BannerboundCore;
import com.bannerbound.core.api.research.ResearchDefinition;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * S→C on login + datapack reload: the full faith-tree definitions (third tree —
 * FAITH_PLAN Part 2.5). Mirror of {@link CultureTreeSyncPayload}.
 */
public record FaithTreeSyncPayload(List<ResearchDefinition> definitions) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<FaithTreeSyncPayload> TYPE =
        new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(BannerboundCore.MODID, "faith_tree_sync"));

    public static final StreamCodec<ByteBuf, FaithTreeSyncPayload> STREAM_CODEC = StreamCodec.composite(
        ResearchDefinition.STREAM_CODEC.apply(ByteBufCodecs.list()), FaithTreeSyncPayload::definitions,
        FaithTreeSyncPayload::new);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
