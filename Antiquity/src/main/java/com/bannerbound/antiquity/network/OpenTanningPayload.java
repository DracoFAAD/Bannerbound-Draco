package com.bannerbound.antiquity.network;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.antiquity.BannerboundAntiquity;

import io.netty.buffer.ByteBuf;
import net.minecraft.core.BlockPos;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/** Server to client: open the tanning-rack scrape minigame. The minigame is non-skill (no accuracy
 *  scoring); {@code swipes} is just how many knife swipes the scraping takes. */
@ApiStatus.Internal
public record OpenTanningPayload(BlockPos pos, int swipes) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<OpenTanningPayload> TYPE =
        new CustomPacketPayload.Type<>(
            ResourceLocation.fromNamespaceAndPath(BannerboundAntiquity.MODID, "open_tanning"));

    public static final StreamCodec<ByteBuf, OpenTanningPayload> STREAM_CODEC =
        StreamCodec.composite(
            BlockPos.STREAM_CODEC, OpenTanningPayload::pos,
            ByteBufCodecs.VAR_INT, OpenTanningPayload::swipes,
            OpenTanningPayload::new
        );

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
