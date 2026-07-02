package com.bannerbound.core.network;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.BannerboundCore;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Server -> client lightweight crisis mirror for HUD and world markers. */
@ApiStatus.Internal
public record CrisisStatePayload(
    boolean active,
    String crisisId,
    String title,
    String headline,
    boolean awaitingChoice,
    boolean hasChoice,
    boolean resolved,
    boolean failed,
    String choiceId,
    long townHallPos
) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<CrisisStatePayload> TYPE =
        new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(BannerboundCore.MODID, "crisis_state"));

    public static final StreamCodec<ByteBuf, CrisisStatePayload> STREAM_CODEC = StreamCodec.of(
        (buf, p) -> {
            buf.writeBoolean(p.active());
            ByteBufCodecs.STRING_UTF8.encode(buf, p.crisisId());
            ByteBufCodecs.STRING_UTF8.encode(buf, p.title());
            ByteBufCodecs.STRING_UTF8.encode(buf, p.headline());
            buf.writeBoolean(p.awaitingChoice());
            buf.writeBoolean(p.hasChoice());
            buf.writeBoolean(p.resolved());
            buf.writeBoolean(p.failed());
            ByteBufCodecs.STRING_UTF8.encode(buf, p.choiceId());
            buf.writeLong(p.townHallPos());
        },
        buf -> new CrisisStatePayload(
            buf.readBoolean(),
            ByteBufCodecs.STRING_UTF8.decode(buf),
            ByteBufCodecs.STRING_UTF8.decode(buf),
            ByteBufCodecs.STRING_UTF8.decode(buf),
            buf.readBoolean(),
            buf.readBoolean(),
            buf.readBoolean(),
            buf.readBoolean(),
            ByteBufCodecs.STRING_UTF8.decode(buf),
            buf.readLong()
        )
    );

    public static CrisisStatePayload empty() {
        return new CrisisStatePayload(false, "", "", "", false, false, false, false, "", 0L);
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
