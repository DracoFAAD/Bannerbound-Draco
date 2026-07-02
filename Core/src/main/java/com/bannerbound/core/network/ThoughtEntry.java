package com.bannerbound.core.network;

import org.jetbrains.annotations.ApiStatus;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

/**
 * One row in the Thoughts tab. Built server-side from a per-citizen {@code Thought}: the
 * {@code label} component already has any social-partner name substituted in, the
 * {@code modifier} is the signed happiness delta.
 *
 * <p><b>Live time-left.</b> {@code expireGameTime} is the absolute world tick at which the
 * thought will expire (or {@code -1} for infinite thoughts). The client subtracts it from
 * {@code level.getGameTime()} on every render frame to compute the live remaining ticks — so
 * the time bar shrinks in real time while the screen is open without re-fetching the entity.
 * {@code totalDurationTicks} is the original full duration the thought was rolled with, used
 * as the bar's denominator. Both fields are {@code -1} for infinite thoughts (no bar drawn).
 *
 * <p>Sent inside {@link OpenCitizenScreenPayload}; never persisted.
 */
@ApiStatus.Internal
public record ThoughtEntry(Component label, int modifier, long expireGameTime, int totalDurationTicks,
                           int category) {
    public static final StreamCodec<RegistryFriendlyByteBuf, ThoughtEntry> STREAM_CODEC = StreamCodec.of(
        (buf, e) -> {
            ComponentSerialization.STREAM_CODEC.encode(buf, e.label());
            ByteBufCodecs.VAR_INT.encode(buf, e.modifier());
            ByteBufCodecs.VAR_LONG.encode(buf, e.expireGameTime());
            ByteBufCodecs.VAR_INT.encode(buf, e.totalDurationTicks());
            ByteBufCodecs.VAR_INT.encode(buf, e.category());
        },
        buf -> new ThoughtEntry(
            ComponentSerialization.STREAM_CODEC.decode(buf),
            ByteBufCodecs.VAR_INT.decode(buf),
            ByteBufCodecs.VAR_LONG.decode(buf),
            ByteBufCodecs.VAR_INT.decode(buf),
            ByteBufCodecs.VAR_INT.decode(buf)
        )
    );

    /** The happiness pillar this thought feeds, decoded from the synced ordinal. */
    public com.bannerbound.core.social.HappinessCategory categoryEnum() {
        com.bannerbound.core.social.HappinessCategory[] v = com.bannerbound.core.social.HappinessCategory.values();
        return v[category >= 0 && category < v.length ? category : com.bannerbound.core.social.HappinessCategory.SOCIETY.ordinal()];
    }
}
