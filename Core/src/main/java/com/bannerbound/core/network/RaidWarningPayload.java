package com.bannerbound.core.network;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.BannerboundCore;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * S→C: a settlement-wide RAID alert broadcast to every member, driving a red top-of-screen banner
 * (see {@code RaidWarningHudLayer}) — the raid analogue of the food-starving banner. {@code active} is
 * true while a raid is in progress against the player's settlement, false when it ends.
 */
@ApiStatus.Internal
public record RaidWarningPayload(boolean active) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<RaidWarningPayload> TYPE =
        new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(
            BannerboundCore.MODID, "raid_warning"));

    public static final StreamCodec<ByteBuf, RaidWarningPayload> STREAM_CODEC =
        StreamCodec.of(
            (buf, p) -> ByteBufCodecs.BOOL.encode(buf, p.active()),
            buf -> new RaidWarningPayload(ByteBufCodecs.BOOL.decode(buf))
        );

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
