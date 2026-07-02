package com.bannerbound.core.network;

import org.jetbrains.annotations.ApiStatus;

import java.util.List;

import com.bannerbound.core.BannerboundCore;
import com.bannerbound.core.api.research.OreDisguise;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

@ApiStatus.Internal
public record OreDisguisesSyncPayload(List<OreDisguise> disguises) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<OreDisguisesSyncPayload> TYPE =
        new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(BannerboundCore.MODID, "ore_disguises_sync"));

    public static final StreamCodec<ByteBuf, OreDisguisesSyncPayload> STREAM_CODEC =
        OreDisguise.STREAM_CODEC.apply(ByteBufCodecs.list())
            .map(OreDisguisesSyncPayload::new, OreDisguisesSyncPayload::disguises);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
