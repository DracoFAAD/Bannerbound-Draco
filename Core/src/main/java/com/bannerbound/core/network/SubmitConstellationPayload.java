package com.bannerbound.core.network;

import com.bannerbound.core.BannerboundCore;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * C→S: a finished Pantheon-mode draft — constellation name, deity name, and the star id
 * chain in draw order (FAITH_PLAN M2). The server is the final arbiter: governance gate,
 * star exclusivity, pantheon cap, devotion cost, domain computation all happen there.
 */
public record SubmitConstellationPayload(String name, String deityName, int[] starIds)
        implements CustomPacketPayload {
    public static final int MAX_NAME_LENGTH = 32;
    public static final int MAX_STARS = 12;

    public static final CustomPacketPayload.Type<SubmitConstellationPayload> TYPE =
        new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(BannerboundCore.MODID, "submit_constellation"));

    public static final StreamCodec<ByteBuf, SubmitConstellationPayload> STREAM_CODEC = StreamCodec.of(
        (buf, p) -> {
            ByteBufCodecs.stringUtf8(MAX_NAME_LENGTH).encode(buf, p.name());
            ByteBufCodecs.stringUtf8(MAX_NAME_LENGTH).encode(buf, p.deityName());
            int n = Math.min(p.starIds().length, MAX_STARS);
            ByteBufCodecs.VAR_INT.encode(buf, n);
            for (int i = 0; i < n; i++) {
                ByteBufCodecs.VAR_INT.encode(buf, p.starIds()[i]);
            }
        },
        buf -> {
            String name = ByteBufCodecs.stringUtf8(MAX_NAME_LENGTH).decode(buf);
            String deity = ByteBufCodecs.stringUtf8(MAX_NAME_LENGTH).decode(buf);
            int n = Math.min(ByteBufCodecs.VAR_INT.decode(buf), MAX_STARS);
            int[] ids = new int[Math.max(0, n)];
            for (int i = 0; i < ids.length; i++) {
                ids[i] = ByteBufCodecs.VAR_INT.decode(buf);
            }
            return new SubmitConstellationPayload(name, deity, ids);
        }
    );

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
