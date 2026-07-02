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

/** Twin of {@link ResearchTreeSyncPayload} for the Culture tree. The {@link ResearchDefinition}
 *  record itself is reused — the two trees only differ in which node IDs they contain. */
@ApiStatus.Internal
public record CultureTreeSyncPayload(List<ResearchDefinition> definitions) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<CultureTreeSyncPayload> TYPE =
        new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(
            BannerboundCore.MODID, "culture_tree_sync"));

    public static final StreamCodec<ByteBuf, CultureTreeSyncPayload> STREAM_CODEC =
        ResearchDefinition.STREAM_CODEC.apply(ByteBufCodecs.list())
            .map(CultureTreeSyncPayload::new, CultureTreeSyncPayload::definitions);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() { return TYPE; }
}
