package com.bannerbound.antiquity.network;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.antiquity.BannerboundAntiquity;

import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Client → server: a mason's-bench interaction.
 * <ul>
 *   <li>{@link #COMPLETE} / {@link #CANCEL} — end of the chisel-strike minigame (session-based;
 *       {@code index} unused).</li>
 *   <li>{@link #REMOVE_QUEUE} — right-clicked the in-world queue item at {@code index}; the server
 *       removes that queued output.</li>
 * </ul>
 * (Add-to-queue and browse-cycle reuse the shared ghost-preview path — {@code GhostActionPayload}.)
 *
 * @param pos    the bench
 * @param action one of {@link #COMPLETE} / {@link #CANCEL} / {@link #REMOVE_QUEUE}
 * @param index  the queue slot for {@link #REMOVE_QUEUE} (else ignored)
 */
@ApiStatus.Internal
public record MasonryActionPayload(BlockPos pos, int action, int index) implements CustomPacketPayload {
    public static final int COMPLETE = 0;
    public static final int CANCEL = 1;
    public static final int REMOVE_QUEUE = 2;

    public MasonryActionPayload(BlockPos pos, int action) {
        this(pos, action, -1);
    }

    public static final CustomPacketPayload.Type<MasonryActionPayload> TYPE =
        new CustomPacketPayload.Type<>(
            ResourceLocation.fromNamespaceAndPath(BannerboundAntiquity.MODID, "masonry_action"));

    public static final StreamCodec<ByteBuf, MasonryActionPayload> STREAM_CODEC =
        StreamCodec.composite(
            BlockPos.STREAM_CODEC, MasonryActionPayload::pos,
            ByteBufCodecs.VAR_INT, MasonryActionPayload::action,
            ByteBufCodecs.VAR_INT, MasonryActionPayload::index,
            MasonryActionPayload::new
        );

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
