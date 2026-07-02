package com.bannerbound.core.network;

import java.util.List;

import org.jetbrains.annotations.ApiStatus;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;

/**
 * One line in a barter offer or storage panel: a registry item id, a count, and the server-computed
 * abstract worth of a single unit ({@link com.bannerbound.core.barbarian.ItemValue}). The value is
 * carried on S→C payloads so the client can score offers without a server round-trip (food values are
 * datapack/server-only, so the client can't recompute them); on C→S payloads it's unused (server
 * recomputes authoritatively) and sent as 0.
 */
@ApiStatus.Internal
public record BarterEntry(String itemId, int count, int unitValue) {
    public static final StreamCodec<ByteBuf, BarterEntry> STREAM_CODEC = StreamCodec.of(
        (buf, e) -> {
            ByteBufCodecs.STRING_UTF8.encode(buf, e.itemId);
            ByteBufCodecs.VAR_INT.encode(buf, e.count);
            ByteBufCodecs.VAR_INT.encode(buf, e.unitValue);
        },
        buf -> new BarterEntry(
            ByteBufCodecs.STRING_UTF8.decode(buf),
            ByteBufCodecs.VAR_INT.decode(buf),
            ByteBufCodecs.VAR_INT.decode(buf)));

    public static final StreamCodec<ByteBuf, List<BarterEntry>> LIST =
        STREAM_CODEC.apply(ByteBufCodecs.list());

    public int totalValue() {
        return unitValue * Math.max(0, count);
    }
}
