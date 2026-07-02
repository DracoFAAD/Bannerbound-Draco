package com.bannerbound.core.network;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.BannerboundCore;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * C→S: a member retracts their OWN suggestion. Mirrors {@link IgnoreSuggestionPayload}'s shape, but
 * the server removes ONLY the calling player's UUID from the suggestion's suggester set (a chief's
 * Ignore clears the whole set; a member's Retract pulls just themselves). After removal the server
 * re-broadcasts suggestion state so the chief's Suggestions tab updates live.
 *
 * @param kind {@code KIND_*} as in {@link IgnoreSuggestionPayload}
 * @param id   the suggested thing's id within its kind (citizen UUID string for exile, "" for tablet)
 */
@ApiStatus.Internal
public record RetractSuggestionPayload(int kind, String id) implements CustomPacketPayload {
    public static final int KIND_SCIENCE = 0;
    public static final int KIND_CULTURE = 1;
    public static final int KIND_POLICY = 2;
    public static final int KIND_PALETTE = 3;
    public static final int KIND_EXILE = 4;
    public static final int KIND_TABLET = 5;

    public static final CustomPacketPayload.Type<RetractSuggestionPayload> TYPE =
        new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(
            BannerboundCore.MODID, "retract_suggestion"));

    public static final StreamCodec<ByteBuf, RetractSuggestionPayload> STREAM_CODEC = StreamCodec.of(
        (buf, p) -> {
            ByteBufCodecs.VAR_INT.encode(buf, p.kind());
            ByteBufCodecs.STRING_UTF8.encode(buf, p.id());
        },
        buf -> new RetractSuggestionPayload(
            ByteBufCodecs.VAR_INT.decode(buf),
            ByteBufCodecs.STRING_UTF8.decode(buf))
    );

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() { return TYPE; }
}
