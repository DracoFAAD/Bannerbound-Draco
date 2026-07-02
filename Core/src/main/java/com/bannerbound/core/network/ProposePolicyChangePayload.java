package com.bannerbound.core.network;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.BannerboundCore;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Client → server: propose a policy change — drop {@code addPolicyId} into slot
 * {@code slotIndex} and/or remove {@code removePolicyId}. Empty strings mean "no add" /
 * "no remove" respectively.
 *
 * <p>In a Council this opens a confirm vote (all online members must vote, &gt;50% Agree
 * enacts). For a Chiefdom chief it enacts immediately. A Chiefdom non-chief should send
 * {@link SuggestPolicyPayload} instead — the server rejects a propose from a non-chief.
 */
@ApiStatus.Internal
public record ProposePolicyChangePayload(int slotIndex, String addPolicyId, String removePolicyId)
        implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<ProposePolicyChangePayload> TYPE =
        new CustomPacketPayload.Type<>(
            ResourceLocation.fromNamespaceAndPath(BannerboundCore.MODID, "propose_policy_change"));

    public static final StreamCodec<ByteBuf, ProposePolicyChangePayload> STREAM_CODEC = StreamCodec.of(
        (buf, p) -> {
            ByteBufCodecs.VAR_INT.encode(buf, p.slotIndex());
            ByteBufCodecs.STRING_UTF8.encode(buf, p.addPolicyId() == null ? "" : p.addPolicyId());
            ByteBufCodecs.STRING_UTF8.encode(buf, p.removePolicyId() == null ? "" : p.removePolicyId());
        },
        buf -> new ProposePolicyChangePayload(
            ByteBufCodecs.VAR_INT.decode(buf),
            ByteBufCodecs.STRING_UTF8.decode(buf),
            ByteBufCodecs.STRING_UTF8.decode(buf))
    );

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
