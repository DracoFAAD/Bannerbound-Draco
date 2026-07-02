package com.bannerbound.core.network;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.BannerboundCore;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Client → server: a Chiefdom non-chief toggles a suggestion on {@code policyId}. The server
 * adds/removes the suggester from the policy's suggestion set (no chat — the marker is the
 * feedback) and re-broadcasts so the chief sees the face badge on that policy row.
 */
@ApiStatus.Internal
public record SuggestPolicyPayload(String policyId) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<SuggestPolicyPayload> TYPE =
        new CustomPacketPayload.Type<>(
            ResourceLocation.fromNamespaceAndPath(BannerboundCore.MODID, "suggest_policy"));

    public static final StreamCodec<ByteBuf, SuggestPolicyPayload> STREAM_CODEC = StreamCodec.of(
        (buf, p) -> ByteBufCodecs.STRING_UTF8.encode(buf, p.policyId()),
        buf -> new SuggestPolicyPayload(ByteBufCodecs.STRING_UTF8.decode(buf))
    );

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
