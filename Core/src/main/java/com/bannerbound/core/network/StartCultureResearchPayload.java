package com.bannerbound.core.network;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.BannerboundCore;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Twin of {@link StartResearchPayload} for the Culture tree (Step 8). Distinct payload
 *  rather than a shared one with a tree-type byte so the server-side handler routing stays
 *  trivially type-safe (no enum dispatch). */
@ApiStatus.Internal
public record StartCultureResearchPayload(String researchId) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<StartCultureResearchPayload> TYPE =
        new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(
            BannerboundCore.MODID, "start_culture_research"));

    public static final StreamCodec<ByteBuf, StartCultureResearchPayload> STREAM_CODEC =
        ByteBufCodecs.STRING_UTF8.map(StartCultureResearchPayload::new, StartCultureResearchPayload::researchId);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() { return TYPE; }
}
