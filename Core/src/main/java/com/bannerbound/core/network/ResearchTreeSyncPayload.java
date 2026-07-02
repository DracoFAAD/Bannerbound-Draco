package com.bannerbound.core.network;

import org.jetbrains.annotations.ApiStatus;

import java.util.List;

import com.bannerbound.core.BannerboundCore;
import com.bannerbound.core.api.research.ResearchDefinition;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

@ApiStatus.Internal
public record ResearchTreeSyncPayload(List<ResearchDefinition> definitions) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<ResearchTreeSyncPayload> TYPE =
        new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(BannerboundCore.MODID, "research_tree_sync"));

    public static final StreamCodec<ByteBuf, ResearchTreeSyncPayload> STREAM_CODEC =
        ResearchDefinition.STREAM_CODEC.apply(ByteBufCodecs.list())
            .map(ResearchTreeSyncPayload::new, ResearchTreeSyncPayload::definitions);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
