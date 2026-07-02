package com.bannerbound.core.network;

import com.bannerbound.core.BannerboundCore;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * S→C on login, on /bannerbound sky reroll, and on celestialSpeed gamerule change: the
 * world's sky seed + the current celestial time multiplier. The client generates the
 * entire faith sky ({@link com.bannerbound.core.celestial.SkyField}) from the seed —
 * star positions and planet orbits are never themselves synced.
 */
public record SkySeedPayload(long skySeed, int celestialSpeed, int meteorAmount, int[] monthDays) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<SkySeedPayload> TYPE =
        new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(BannerboundCore.MODID, "sky_seed"));

    public static final StreamCodec<ByteBuf, SkySeedPayload> STREAM_CODEC = StreamCodec.of(
        (buf, p) -> {
            buf.writeLong(p.skySeed());
            buf.writeInt(p.celestialSpeed());
            buf.writeInt(p.meteorAmount());
            for (int i = 0; i < 12; i++) {
                buf.writeByte(i < p.monthDays().length ? p.monthDays()[i] : 7);
            }
        },
        buf -> {
            long seed = buf.readLong();
            int speed = buf.readInt();
            int meteors = buf.readInt();
            int[] months = new int[12];
            for (int i = 0; i < 12; i++) {
                months[i] = buf.readByte();
            }
            return new SkySeedPayload(seed, speed, meteors, months);
        }
    );

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
