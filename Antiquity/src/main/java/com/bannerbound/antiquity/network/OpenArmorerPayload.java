package com.bannerbound.antiquity.network;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.antiquity.BannerboundAntiquity;

import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Server → client: open the Armorer's Workbench design screen for the receiving player. Carries the
 * station block so a later crafting step can validate against it server-side. Mirrors the other
 * {@code Open*Payload}s; the real client handler touches client-only classes, so it's dist-guarded in
 * {@link AntiquityNetwork} (no-op on the server, which still registers TYPE + CODEC to encode it).
 */
@ApiStatus.Internal
public record OpenArmorerPayload(BlockPos pos) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<OpenArmorerPayload> TYPE =
        new CustomPacketPayload.Type<>(
            ResourceLocation.fromNamespaceAndPath(BannerboundAntiquity.MODID, "open_armorer"));

    public static final StreamCodec<io.netty.buffer.ByteBuf, OpenArmorerPayload> STREAM_CODEC =
        BlockPos.STREAM_CODEC.map(OpenArmorerPayload::new, OpenArmorerPayload::pos);

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
