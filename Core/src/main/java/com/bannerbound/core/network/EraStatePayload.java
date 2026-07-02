package com.bannerbound.core.network;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.BannerboundCore;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

@ApiStatus.Internal
public record EraStatePayload(int playerEra, int worldEra, int worldYear) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<EraStatePayload> TYPE =
        new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(BannerboundCore.MODID, "era_state"));

    // VAR_INT for worldYear handles the BC range fine (Antiquity is around -100000, well within
    // the int range). Not unsigned — varint encodes signed ints with zig-zag in some libs but
    // Mojang's ByteBufCodecs.VAR_INT is plain VarInt; negative values just consume 5 bytes.
    public static final StreamCodec<ByteBuf, EraStatePayload> STREAM_CODEC =
        StreamCodec.composite(
            ByteBufCodecs.VAR_INT, EraStatePayload::playerEra,
            ByteBufCodecs.VAR_INT, EraStatePayload::worldEra,
            ByteBufCodecs.VAR_INT, EraStatePayload::worldYear,
            EraStatePayload::new
        );

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
