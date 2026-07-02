package com.bannerbound.core.network;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.BannerboundCore;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Client → server: this Council member's Agree ({@code true}) / Disagree ({@code false}) vote
 * on the settlement's pending policy change. The vote references whatever change is currently
 * pending — there's only ever one at a time. Resolves when every online member has voted.
 */
@ApiStatus.Internal
public record CastPolicyVotePayload(boolean agree) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<CastPolicyVotePayload> TYPE =
        new CustomPacketPayload.Type<>(
            ResourceLocation.fromNamespaceAndPath(BannerboundCore.MODID, "cast_policy_vote"));

    public static final StreamCodec<ByteBuf, CastPolicyVotePayload> STREAM_CODEC = StreamCodec.of(
        (buf, p) -> buf.writeBoolean(p.agree()),
        buf -> new CastPolicyVotePayload(buf.readBoolean())
    );

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
