package com.bannerbound.antiquity.network;

import java.util.List;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.antiquity.BannerboundAntiquity;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Server → client: open the knapping screen for the receiving player, carrying every loaded
 * {@link com.bannerbound.antiquity.recipe.KnappingShape} so the client is self-contained (no separate
 * shape sync). The client plays the shape grid + timing minigame and replies with
 * {@link KnappingActionPayload}; the server holds the authoritative session (consumes the rock,
 * rolls the quality, gives the head).
 *
 * @param shapes the knappable head silhouettes the player may shape this session
 */
@ApiStatus.Internal
public record OpenKnappingPayload(List<ShapeView> shapes) implements CustomPacketPayload {

    /** One knappable shape as seen by the client: which head, which cells stay stone, how many reps. */
    public record ShapeView(ResourceLocation head, int keepMask, int stretches) {
        public static final StreamCodec<ByteBuf, ShapeView> STREAM_CODEC = StreamCodec.composite(
            ResourceLocation.STREAM_CODEC, ShapeView::head,
            ByteBufCodecs.VAR_INT, ShapeView::keepMask,
            ByteBufCodecs.VAR_INT, ShapeView::stretches,
            ShapeView::new);
    }

    public static final CustomPacketPayload.Type<OpenKnappingPayload> TYPE =
        new CustomPacketPayload.Type<>(
            ResourceLocation.fromNamespaceAndPath(BannerboundAntiquity.MODID, "open_knapping"));

    public static final StreamCodec<ByteBuf, OpenKnappingPayload> STREAM_CODEC =
        StreamCodec.composite(
            ShapeView.STREAM_CODEC.apply(ByteBufCodecs.list()), OpenKnappingPayload::shapes,
            OpenKnappingPayload::new);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
