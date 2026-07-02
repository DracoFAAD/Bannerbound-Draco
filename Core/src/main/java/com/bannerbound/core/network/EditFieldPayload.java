package com.bannerbound.core.network;

import org.jetbrains.annotations.ApiStatus;

import java.util.UUID;

import com.bannerbound.core.BannerboundCore;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Client → server. The player confirmed edits to the farmer selection {@code rodId} from the
 * field-edit screen: a new crop ({@code seedItemId}) and the assigned worker ({@code assignedCitizen},
 * the zero UUID = open to all farmers). The server validates ownership + the crop + that the worker is
 * a farmer in the settlement before applying.
 */
@ApiStatus.Internal
public record EditFieldPayload(UUID rodId, String seedItemId, UUID assignedCitizen) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<EditFieldPayload> TYPE =
        new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(BannerboundCore.MODID, "edit_field"));

    public static final StreamCodec<ByteBuf, EditFieldPayload> STREAM_CODEC = StreamCodec.of(
        (buf, p) -> {
            buf.writeLong(p.rodId().getMostSignificantBits());
            buf.writeLong(p.rodId().getLeastSignificantBits());
            ByteBufCodecs.STRING_UTF8.encode(buf, p.seedItemId());
            buf.writeLong(p.assignedCitizen().getMostSignificantBits());
            buf.writeLong(p.assignedCitizen().getLeastSignificantBits());
        },
        buf -> {
            UUID rodId = new UUID(buf.readLong(), buf.readLong());
            String seedItemId = ByteBufCodecs.STRING_UTF8.decode(buf);
            UUID assignedCitizen = new UUID(buf.readLong(), buf.readLong());
            return new EditFieldPayload(rodId, seedItemId, assignedCitizen);
        }
    );

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
