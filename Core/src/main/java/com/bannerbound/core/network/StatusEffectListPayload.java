package com.bannerbound.core.network;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.BannerboundCore;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Server → client snapshot of every active {@link com.bannerbound.core.api.settlement.StatusEffect}
 * on the player's settlement. Replaces the client mirror wholesale — sent on add and on remove
 * (not every tick). Between syncs the client decrements remaining ticks locally so the progress
 * bars animate smoothly without flooding the network.
 */
@ApiStatus.Internal
public record StatusEffectListPayload(List<Entry> effects) implements CustomPacketPayload {

    public record Entry(
        UUID instanceId,
        String translationKey,
        List<String> args,
        int iconOrdinal,
        double iconValue,
        int totalDurationTicks,
        int remainingTicks
    ) {}

    public static final CustomPacketPayload.Type<StatusEffectListPayload> TYPE =
        new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(BannerboundCore.MODID, "status_effect_list"));

    public static final StreamCodec<ByteBuf, StatusEffectListPayload> STREAM_CODEC = StreamCodec.of(
        (buf, p) -> {
            ByteBufCodecs.VAR_INT.encode(buf, p.effects().size());
            for (Entry e : p.effects()) {
                ByteBufCodecs.STRING_UTF8.encode(buf, e.instanceId().toString());
                ByteBufCodecs.STRING_UTF8.encode(buf, e.translationKey());
                ByteBufCodecs.VAR_INT.encode(buf, e.args().size());
                for (String a : e.args()) {
                    ByteBufCodecs.STRING_UTF8.encode(buf, a);
                }
                ByteBufCodecs.VAR_INT.encode(buf, e.iconOrdinal());
                ByteBufCodecs.DOUBLE.encode(buf, e.iconValue());
                ByteBufCodecs.VAR_INT.encode(buf, e.totalDurationTicks());
                ByteBufCodecs.VAR_INT.encode(buf, e.remainingTicks());
            }
        },
        buf -> {
            int count = ByteBufCodecs.VAR_INT.decode(buf);
            List<Entry> entries = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                UUID id = UUID.fromString(ByteBufCodecs.STRING_UTF8.decode(buf));
                String key = ByteBufCodecs.STRING_UTF8.decode(buf);
                int argCount = ByteBufCodecs.VAR_INT.decode(buf);
                List<String> args = new ArrayList<>(argCount);
                for (int j = 0; j < argCount; j++) {
                    args.add(ByteBufCodecs.STRING_UTF8.decode(buf));
                }
                int icon = ByteBufCodecs.VAR_INT.decode(buf);
                double value = ByteBufCodecs.DOUBLE.decode(buf);
                int total = ByteBufCodecs.VAR_INT.decode(buf);
                int remaining = ByteBufCodecs.VAR_INT.decode(buf);
                entries.add(new Entry(id, key, args, icon, value, total, remaining));
            }
            return new StatusEffectListPayload(entries);
        }
    );

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
