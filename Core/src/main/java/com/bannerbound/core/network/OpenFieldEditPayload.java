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
 * Server → client. Opens the field-edit UI for a farmer selection ({@code rodId}) when the player
 * shift-right-clicks the field with the Foreman's Rod. Carries everything the screen needs, computed
 * server-side: the candidate seeds, the field's current crop + assigned worker, and the roster of
 * this settlement's farmers (parallel {@code farmerIds}/{@code farmerNames}) to populate the worker
 * dropdown. {@link #currentWorker} is {@code BlockSelection.NO_CITIZEN} (zero UUID) when the field
 * is open to all farmers.
 */
@ApiStatus.Internal
public record OpenFieldEditPayload(UUID rodId, List<String> candidateSeeds, String currentSeed,
                                   List<UUID> farmerIds, List<String> farmerNames, UUID currentWorker,
                                   List<String> bonusSeeds)
        implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<OpenFieldEditPayload> TYPE =
        new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(BannerboundCore.MODID, "open_field_edit"));

    public static final StreamCodec<RegistryFriendlyByteBuf, OpenFieldEditPayload> STREAM_CODEC = StreamCodec.of(
        (buf, p) -> {
            buf.writeLong(p.rodId().getMostSignificantBits());
            buf.writeLong(p.rodId().getLeastSignificantBits());
            buf.writeVarInt(p.candidateSeeds().size());
            for (String id : p.candidateSeeds()) ByteBufCodecs.STRING_UTF8.encode((ByteBuf) buf, id);
            ByteBufCodecs.STRING_UTF8.encode((ByteBuf) buf, p.currentSeed());
            buf.writeVarInt(p.farmerIds().size());
            for (int i = 0; i < p.farmerIds().size(); i++) {
                buf.writeLong(p.farmerIds().get(i).getMostSignificantBits());
                buf.writeLong(p.farmerIds().get(i).getLeastSignificantBits());
                ByteBufCodecs.STRING_UTF8.encode((ByteBuf) buf, p.farmerNames().get(i));
            }
            buf.writeLong(p.currentWorker().getMostSignificantBits());
            buf.writeLong(p.currentWorker().getLeastSignificantBits());
            buf.writeVarInt(p.bonusSeeds().size());
            for (String id : p.bonusSeeds()) ByteBufCodecs.STRING_UTF8.encode((ByteBuf) buf, id);
        },
        buf -> {
            UUID rodId = new UUID(buf.readLong(), buf.readLong());
            int seedCount = buf.readVarInt();
            List<String> seeds = new java.util.ArrayList<>(seedCount);
            for (int i = 0; i < seedCount; i++) seeds.add(ByteBufCodecs.STRING_UTF8.decode((ByteBuf) buf));
            String currentSeed = ByteBufCodecs.STRING_UTF8.decode((ByteBuf) buf);
            int farmerCount = buf.readVarInt();
            List<UUID> ids = new java.util.ArrayList<>(farmerCount);
            List<String> names = new java.util.ArrayList<>(farmerCount);
            for (int i = 0; i < farmerCount; i++) {
                ids.add(new UUID(buf.readLong(), buf.readLong()));
                names.add(ByteBufCodecs.STRING_UTF8.decode((ByteBuf) buf));
            }
            UUID currentWorker = new UUID(buf.readLong(), buf.readLong());
            int bn = buf.readVarInt();
            List<String> bonus = new java.util.ArrayList<>(bn);
            for (int i = 0; i < bn; i++) bonus.add(ByteBufCodecs.STRING_UTF8.decode((ByteBuf) buf));
            return new OpenFieldEditPayload(rodId, seeds, currentSeed, ids, names, currentWorker, bonus);
        }
    );

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
