package com.bannerbound.core.network;

import org.jetbrains.annotations.ApiStatus;

import java.util.UUID;

import com.bannerbound.core.BannerboundCore;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Client → server. Player has chosen a seed for the farmer selection identified by {@code rodId}.
 * Empty {@code seedItemId} means "skip" — the selection stays awaiting-seed and the prompt will
 * be re-queued on the next applicable trigger.
 */
@ApiStatus.Internal
public record PickSeedPayload(UUID rodId, String seedItemId) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<PickSeedPayload> TYPE =
        new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(BannerboundCore.MODID, "pick_seed"));

    public static final StreamCodec<ByteBuf, PickSeedPayload> STREAM_CODEC = StreamCodec.of(
        (buf, p) -> {
            buf.writeLong(p.rodId().getMostSignificantBits());
            buf.writeLong(p.rodId().getLeastSignificantBits());
            ByteBufCodecs.STRING_UTF8.encode(buf, p.seedItemId());
        },
        buf -> {
            long hi = buf.readLong();
            long lo = buf.readLong();
            String id = ByteBufCodecs.STRING_UTF8.decode(buf);
            return new PickSeedPayload(new UUID(hi, lo), id);
        }
    );

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
