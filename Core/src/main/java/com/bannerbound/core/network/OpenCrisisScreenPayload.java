package com.bannerbound.core.network;

import java.util.List;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.BannerboundCore;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Server -> client full crisis decision/outcome screen payload. */
@ApiStatus.Internal
public record OpenCrisisScreenPayload(
    String settlementName,
    String crisisId,
    String category,
    String title,
    String headline,
    String body,
    String prompt,
    String background,
    List<OpenCrisisScreenPayload.ArtLayer> backgroundLayers,
    boolean awaitingChoice,
    boolean canChoose,
    boolean canAdvise,
    boolean councilVote,
    int onlineMembers,
    int requiredVotes,
    String playerChoiceId,
    String chosenChoiceId,
    List<Choice> choices,
    boolean forceOpen
) implements CustomPacketPayload {
    public record ArtLayer(String texture, float parallax, float driftX, float driftY,
                           float scale, float opacity, int revealDelayMs,
                           int revealDurationMs) {
        public static final StreamCodec<ByteBuf, ArtLayer> STREAM_CODEC = StreamCodec.of(
            (buf, l) -> {
                ByteBufCodecs.STRING_UTF8.encode(buf, l.texture());
                buf.writeFloat(l.parallax());
                buf.writeFloat(l.driftX());
                buf.writeFloat(l.driftY());
                buf.writeFloat(l.scale());
                buf.writeFloat(l.opacity());
                ByteBufCodecs.VAR_INT.encode(buf, l.revealDelayMs());
                ByteBufCodecs.VAR_INT.encode(buf, l.revealDurationMs());
            },
            buf -> new ArtLayer(
                ByteBufCodecs.STRING_UTF8.decode(buf),
                buf.readFloat(),
                buf.readFloat(),
                buf.readFloat(),
                buf.readFloat(),
                buf.readFloat(),
                ByteBufCodecs.VAR_INT.decode(buf),
                ByteBufCodecs.VAR_INT.decode(buf)
            )
        );
    }

    public record Choice(String id, String label, String description, String outcome,
                         boolean viable, String warning, int votes) {
        public static final StreamCodec<ByteBuf, Choice> STREAM_CODEC = StreamCodec.of(
            (buf, c) -> {
                ByteBufCodecs.STRING_UTF8.encode(buf, c.id());
                ByteBufCodecs.STRING_UTF8.encode(buf, c.label());
                ByteBufCodecs.STRING_UTF8.encode(buf, c.description());
                ByteBufCodecs.STRING_UTF8.encode(buf, c.outcome());
                buf.writeBoolean(c.viable());
                ByteBufCodecs.STRING_UTF8.encode(buf, c.warning());
                ByteBufCodecs.VAR_INT.encode(buf, c.votes());
            },
            buf -> new Choice(
                ByteBufCodecs.STRING_UTF8.decode(buf),
                ByteBufCodecs.STRING_UTF8.decode(buf),
                ByteBufCodecs.STRING_UTF8.decode(buf),
                ByteBufCodecs.STRING_UTF8.decode(buf),
                buf.readBoolean(),
                ByteBufCodecs.STRING_UTF8.decode(buf),
                ByteBufCodecs.VAR_INT.decode(buf)
            )
        );
    }

    public static final CustomPacketPayload.Type<OpenCrisisScreenPayload> TYPE =
        new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(BannerboundCore.MODID, "open_crisis_screen"));

    public static final StreamCodec<ByteBuf, OpenCrisisScreenPayload> STREAM_CODEC = StreamCodec.of(
        (buf, p) -> {
            ByteBufCodecs.STRING_UTF8.encode(buf, p.settlementName());
            ByteBufCodecs.STRING_UTF8.encode(buf, p.crisisId());
            ByteBufCodecs.STRING_UTF8.encode(buf, p.category());
            ByteBufCodecs.STRING_UTF8.encode(buf, p.title());
            ByteBufCodecs.STRING_UTF8.encode(buf, p.headline());
            ByteBufCodecs.STRING_UTF8.encode(buf, p.body());
            ByteBufCodecs.STRING_UTF8.encode(buf, p.prompt());
            ByteBufCodecs.STRING_UTF8.encode(buf, p.background());
            ArtLayer.STREAM_CODEC.apply(ByteBufCodecs.list()).encode(buf, p.backgroundLayers());
            buf.writeBoolean(p.awaitingChoice());
            buf.writeBoolean(p.canChoose());
            buf.writeBoolean(p.canAdvise());
            buf.writeBoolean(p.councilVote());
            ByteBufCodecs.VAR_INT.encode(buf, p.onlineMembers());
            ByteBufCodecs.VAR_INT.encode(buf, p.requiredVotes());
            ByteBufCodecs.STRING_UTF8.encode(buf, p.playerChoiceId());
            ByteBufCodecs.STRING_UTF8.encode(buf, p.chosenChoiceId());
            Choice.STREAM_CODEC.apply(ByteBufCodecs.list()).encode(buf, p.choices());
            buf.writeBoolean(p.forceOpen());
        },
        buf -> new OpenCrisisScreenPayload(
            ByteBufCodecs.STRING_UTF8.decode(buf),
            ByteBufCodecs.STRING_UTF8.decode(buf),
            ByteBufCodecs.STRING_UTF8.decode(buf),
            ByteBufCodecs.STRING_UTF8.decode(buf),
            ByteBufCodecs.STRING_UTF8.decode(buf),
            ByteBufCodecs.STRING_UTF8.decode(buf),
            ByteBufCodecs.STRING_UTF8.decode(buf),
            ByteBufCodecs.STRING_UTF8.decode(buf),
            ArtLayer.STREAM_CODEC.apply(ByteBufCodecs.list()).decode(buf),
            buf.readBoolean(),
            buf.readBoolean(),
            buf.readBoolean(),
            buf.readBoolean(),
            ByteBufCodecs.VAR_INT.decode(buf),
            ByteBufCodecs.VAR_INT.decode(buf),
            ByteBufCodecs.STRING_UTF8.decode(buf),
            ByteBufCodecs.STRING_UTF8.decode(buf),
            Choice.STREAM_CODEC.apply(ByteBufCodecs.list()).decode(buf),
            buf.readBoolean()
        )
    );

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
