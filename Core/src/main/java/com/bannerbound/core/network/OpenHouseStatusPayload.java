package com.bannerbound.core.network;

import java.util.List;
import java.util.UUID;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.BannerboundCore;

import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.ComponentSerialization;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * Server → client: open the House status panel. Carries a snapshot of the home's status
 * (resolved to a translated string), bed count, current appeal score + beauty tier (name + signed
 * tier index for colouring), the enclosed interior volume (the crowdedness / space-per-bed source),
 * and each resident's styled display name component.
 *
 * <p>The Component-typed fields piggy-back on {@link ComponentSerialization#STREAM_CODEC} for
 * settlement-tint preservation, same trick as {@link OpenCitizenScreenPayload}.
 */
@ApiStatus.Internal
public record OpenHouseStatusPayload(
    UUID homeId,
    Component statusText,
    int statusOrdinal,
    int bedCount,
    int residentCount,
    double appealScore,
    Component beautyText,
    int beautyTier,
    int interiorVolume,
    int homeHappiness,
    List<DemandView> demands,
    List<Component> residentNames,
    List<UUID> residentIds
) implements CustomPacketPayload {
    /** One active home demand for the panel checklist: its tag suffix (→ lang label) + met state. */
    public record DemandView(String suffix, boolean met) {}

    public static final CustomPacketPayload.Type<OpenHouseStatusPayload> TYPE =
        new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(BannerboundCore.MODID, "open_house_status"));

    public static final StreamCodec<RegistryFriendlyByteBuf, OpenHouseStatusPayload> STREAM_CODEC = StreamCodec.of(
        (buf, p) -> {
            buf.writeUUID(p.homeId());
            ComponentSerialization.STREAM_CODEC.encode(buf, p.statusText());
            ByteBufCodecs.VAR_INT.encode(buf, p.statusOrdinal());
            ByteBufCodecs.VAR_INT.encode(buf, p.bedCount());
            ByteBufCodecs.VAR_INT.encode(buf, p.residentCount());
            buf.writeDouble(p.appealScore());
            ComponentSerialization.STREAM_CODEC.encode(buf, p.beautyText());
            buf.writeInt(p.beautyTier());
            ByteBufCodecs.VAR_INT.encode(buf, p.interiorVolume());
            ByteBufCodecs.VAR_INT.encode(buf, p.homeHappiness());
            ByteBufCodecs.VAR_INT.encode(buf, p.demands().size());
            for (DemandView d : p.demands()) {
                ByteBufCodecs.STRING_UTF8.encode(buf, d.suffix());
                buf.writeBoolean(d.met());
            }
            ByteBufCodecs.VAR_INT.encode(buf, p.residentNames().size());
            for (Component c : p.residentNames()) {
                ComponentSerialization.STREAM_CODEC.encode(buf, c);
            }
            ByteBufCodecs.VAR_INT.encode(buf, p.residentIds().size());
            for (UUID id : p.residentIds()) {
                buf.writeUUID(id);
            }
        },
        buf -> {
            UUID homeId = buf.readUUID();
            Component statusText = ComponentSerialization.STREAM_CODEC.decode(buf);
            int statusOrdinal = ByteBufCodecs.VAR_INT.decode(buf);
            int beds = ByteBufCodecs.VAR_INT.decode(buf);
            int residents = ByteBufCodecs.VAR_INT.decode(buf);
            double score = buf.readDouble();
            Component beautyText = ComponentSerialization.STREAM_CODEC.decode(buf);
            int beautyTier = buf.readInt();
            int interiorVolume = ByteBufCodecs.VAR_INT.decode(buf);
            int homeHappiness = ByteBufCodecs.VAR_INT.decode(buf);
            int dn = ByteBufCodecs.VAR_INT.decode(buf);
            List<DemandView> demands = new java.util.ArrayList<>(dn);
            for (int i = 0; i < dn; i++) {
                demands.add(new DemandView(ByteBufCodecs.STRING_UTF8.decode(buf), buf.readBoolean()));
            }
            int n = ByteBufCodecs.VAR_INT.decode(buf);
            List<Component> names = new java.util.ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                names.add(ComponentSerialization.STREAM_CODEC.decode(buf));
            }
            int idn = ByteBufCodecs.VAR_INT.decode(buf);
            List<UUID> ids = new java.util.ArrayList<>(idn);
            for (int i = 0; i < idn; i++) {
                ids.add(buf.readUUID());
            }
            return new OpenHouseStatusPayload(homeId, statusText, statusOrdinal, beds, residents,
                score, beautyText, beautyTier, interiorVolume, homeHappiness, demands, names, ids);
        }
    );

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
