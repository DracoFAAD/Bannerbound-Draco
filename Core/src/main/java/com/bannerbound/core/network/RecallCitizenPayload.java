package com.bannerbound.core.network;

import org.jetbrains.annotations.ApiStatus;

import java.util.UUID;

import com.bannerbound.core.BannerboundCore;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Client → server: teleport the named citizen to their settlement's town hall. Server-side check
 * ensures the requester is a member of the citizen's settlement so a stranger can't yank someone
 * else's worker.
 */
@ApiStatus.Internal
public record RecallCitizenPayload(UUID citizenId) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<RecallCitizenPayload> TYPE =
        new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(BannerboundCore.MODID, "recall_citizen"));

    public static final StreamCodec<ByteBuf, RecallCitizenPayload> STREAM_CODEC = StreamCodec.of(
        (buf, p) -> {
            buf.writeLong(p.citizenId().getMostSignificantBits());
            buf.writeLong(p.citizenId().getLeastSignificantBits());
        },
        buf -> new RecallCitizenPayload(new UUID(buf.readLong(), buf.readLong()))
    );

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
