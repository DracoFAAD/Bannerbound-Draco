package com.bannerbound.core.network;

import org.jetbrains.annotations.ApiStatus;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

/**
 * One row in the Relationships tab of the per-citizen screen. The {@code name} is a full
 * {@link Component} (carries the gender-icon glyph + settlement tint the same way the citizen's
 * own display name does), {@code score} is the raw {@code -100..100} relationship score, and
 * {@code isFamily} flips the row into the permanent parent ↔ child bond — the screen renders
 * Family rows without the red/green score bar (Family doesn't have a movable score).
 * <p>
 * Sent inside {@link OpenCitizenScreenPayload}; never persisted.
 */
@ApiStatus.Internal
public record RelationshipEntry(Component name, int score, boolean isFamily) {
    public static final StreamCodec<RegistryFriendlyByteBuf, RelationshipEntry> STREAM_CODEC = StreamCodec.of(
        (buf, e) -> {
            ComponentSerialization.STREAM_CODEC.encode(buf, e.name());
            ByteBufCodecs.VAR_INT.encode(buf, e.score());
            ByteBufCodecs.BOOL.encode(buf, e.isFamily());
        },
        buf -> new RelationshipEntry(
            ComponentSerialization.STREAM_CODEC.decode(buf),
            ByteBufCodecs.VAR_INT.decode(buf),
            ByteBufCodecs.BOOL.decode(buf)
        )
    );
}
