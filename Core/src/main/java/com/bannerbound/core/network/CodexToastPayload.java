package com.bannerbound.core.network;

import com.bannerbound.core.BannerboundCore;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Server -> client: show a queued "new Chronicle entry" HUD toast. */
public record CodexToastPayload(int count, String title, String icon) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<CodexToastPayload> TYPE =
        new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(BannerboundCore.MODID, "codex_toast"));

    public static final StreamCodec<ByteBuf, CodexToastPayload> STREAM_CODEC = StreamCodec.of(
        (buf, p) -> {
            ByteBufCodecs.VAR_INT.encode(buf, p.count());
            ByteBufCodecs.STRING_UTF8.encode(buf, p.title());
            ByteBufCodecs.STRING_UTF8.encode(buf, p.icon());
        },
        buf -> new CodexToastPayload(
            ByteBufCodecs.VAR_INT.decode(buf),
            ByteBufCodecs.STRING_UTF8.decode(buf),
            ByteBufCodecs.STRING_UTF8.decode(buf)
        )
    );

    @Override
    public Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
