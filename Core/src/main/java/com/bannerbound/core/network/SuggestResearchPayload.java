package com.bannerbound.core.network;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.BannerboundCore;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Client → server: a non-Chief member of a {@link com.bannerbound.core.api.settlement.Settlement.Government#CHIEFDOM}
 * settlement clicked a research node. Instead of starting/queueing the research, the client
 * routes the click through this payload; the server broadcasts a chat suggestion to the
 * seated Chief ("Brom suggested getting Sturdy Tools in the Scientific Research.") so the
 * Chief can decide whether to act on it.
 *
 * <p>{@code treeType} = 0 means the Scientific tree; 1 means the Culture tree (added in
 * Step 8 — the framework reserves the slot so the suggestion message can name the right tree
 * without a Step-8-vs-Step-7 schema bump).
 */
@ApiStatus.Internal
public record SuggestResearchPayload(String researchId, int treeType) implements CustomPacketPayload {
    public static final int TREE_SCIENCE = 0;
    public static final int TREE_CULTURE = 1;

    public static final CustomPacketPayload.Type<SuggestResearchPayload> TYPE =
        new CustomPacketPayload.Type<>(
            ResourceLocation.fromNamespaceAndPath(BannerboundCore.MODID, "suggest_research"));

    public static final StreamCodec<ByteBuf, SuggestResearchPayload> STREAM_CODEC = StreamCodec.of(
        (buf, p) -> {
            ByteBufCodecs.STRING_UTF8.encode(buf, p.researchId());
            ByteBufCodecs.VAR_INT.encode(buf, p.treeType());
        },
        buf -> new SuggestResearchPayload(
            ByteBufCodecs.STRING_UTF8.decode(buf),
            ByteBufCodecs.VAR_INT.decode(buf))
    );

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
