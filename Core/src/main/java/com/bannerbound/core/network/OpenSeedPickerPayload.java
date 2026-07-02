package com.bannerbound.core.network;

import org.jetbrains.annotations.ApiStatus;

import java.util.List;
import java.util.UUID;

import com.bannerbound.core.BannerboundCore;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Server → client. Tells a specific player to open the seed-picker UI for a particular farmer
 * selection (identified by its {@code rodId}). The candidate list is computed server-side and
 * sent inline so the client doesn't have to know about modded seeds or future expansions.
 */
@ApiStatus.Internal
public record OpenSeedPickerPayload(UUID rodId, List<String> candidateSeeds, List<String> bonusSeeds)
        implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<OpenSeedPickerPayload> TYPE =
        new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(BannerboundCore.MODID, "open_seed_picker"));

    public static final StreamCodec<RegistryFriendlyByteBuf, OpenSeedPickerPayload> STREAM_CODEC = StreamCodec.of(
        (buf, p) -> {
            buf.writeLong(p.rodId().getMostSignificantBits());
            buf.writeLong(p.rodId().getLeastSignificantBits());
            buf.writeVarInt(p.candidateSeeds().size());
            for (String id : p.candidateSeeds()) ByteBufCodecs.STRING_UTF8.encode((ByteBuf) buf, id);
            buf.writeVarInt(p.bonusSeeds().size());
            for (String id : p.bonusSeeds()) ByteBufCodecs.STRING_UTF8.encode((ByteBuf) buf, id);
        },
        buf -> {
            long hi = buf.readLong();
            long lo = buf.readLong();
            int n = buf.readVarInt();
            List<String> list = new java.util.ArrayList<>(n);
            for (int i = 0; i < n; i++) list.add(ByteBufCodecs.STRING_UTF8.decode((ByteBuf) buf));
            int bn = buf.readVarInt();
            List<String> bonus = new java.util.ArrayList<>(bn);
            for (int i = 0; i < bn; i++) bonus.add(ByteBufCodecs.STRING_UTF8.decode((ByteBuf) buf));
            return new OpenSeedPickerPayload(new UUID(hi, lo), list, bonus);
        }
    );

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
