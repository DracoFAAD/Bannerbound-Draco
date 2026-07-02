package com.bannerbound.antiquity.network;

import java.util.List;

import com.bannerbound.antiquity.BannerboundAntiquity;
import com.bannerbound.antiquity.recipe.ArrowPart;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Server→client sync of the datapack-loaded {@link ArrowPart} registry, so clients can render modular
 * arrows (icon + projectile) and show their tooltips even for modpack-added parts the client jar
 * doesn't bundle. Sent on player join / {@code /reload} (see {@code AntiquityEvents}); the handler
 * (registered in {@link AntiquityNetwork}) replaces {@code ArrowPartRegistry}.
 */
public record ArrowPartsSyncPayload(List<ArrowPart> parts) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<ArrowPartsSyncPayload> TYPE =
        new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(
            BannerboundAntiquity.MODID, "arrow_parts_sync"));

    public static final StreamCodec<RegistryFriendlyByteBuf, ArrowPartsSyncPayload> STREAM_CODEC =
        ArrowPart.STREAM_CODEC.apply(ByteBufCodecs.list())
            .map(ArrowPartsSyncPayload::new, ArrowPartsSyncPayload::parts);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
