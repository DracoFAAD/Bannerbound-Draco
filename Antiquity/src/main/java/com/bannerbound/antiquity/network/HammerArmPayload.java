package com.bannerbound.antiquity.network;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.antiquity.BannerboundAntiquity;

import io.netty.buffer.ByteBuf;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

import java.util.UUID;

/**
 * Server → tracking clients: a player started ({@code active=true}) or stopped a cold-hammer session.
 * Clients flag the player in {@code HammerArmState} so the third-person model raises the hammer arm.
 */
@ApiStatus.Internal
public record HammerArmPayload(UUID player, boolean active) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<HammerArmPayload> TYPE =
        new CustomPacketPayload.Type<>(
            ResourceLocation.fromNamespaceAndPath(BannerboundAntiquity.MODID, "hammer_arm"));

    public static final StreamCodec<ByteBuf, HammerArmPayload> STREAM_CODEC =
        StreamCodec.composite(
            UUIDUtil.STREAM_CODEC, HammerArmPayload::player,
            ByteBufCodecs.BOOL, HammerArmPayload::active,
            HammerArmPayload::new);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
