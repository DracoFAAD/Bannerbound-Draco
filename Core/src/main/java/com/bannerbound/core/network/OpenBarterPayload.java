package com.bannerbound.core.network;

import java.util.List;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.BannerboundCore;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;

/**
 * S→C: opens the barbarian barter screen. Carries the camp's identity + stance, the camp's opening
 * offer ({@code youGive} = what they ask from you, {@code youGet} = what they'd hand over), and the two
 * pools the player can draw from to counter-offer ({@code yourStorage}, {@code theirGoods}). Every line
 * includes its unit value so the client can score offers locally (see {@link BarterEntry}).
 *
 * <p>Acceptance the client mirrors: {@code value(youGive) >= floor(value(youGet) * marginPercent/100) +
 * tributeFloor}. The server re-validates on submit.
 */
@ApiStatus.Internal
public record OpenBarterPayload(
    int messengerEntityId,
    String campName,
    int campColor,
    String typeName,
    String greetingKey,
    int relState,          // CampRelationState ordinal
    boolean isDemand,      // true = tribute (youGet empty, refusing is hostile); false = open trade
    boolean canDefer,      // grace allowed ("we'll get it for you") — false for hostile camps
    int tributeFloor,      // value the camp expects on top of any trade balance (0 for pure trades)
    int marginPercent,     // how much give-value the camp wants per unit of get-value (relationship)
    List<BarterEntry> youGive,
    List<BarterEntry> youGet,
    List<BarterEntry> yourStorage,
    List<BarterEntry> theirGoods
) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<OpenBarterPayload> TYPE =
        new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(
            BannerboundCore.MODID, "open_barter"));

    public static final StreamCodec<ByteBuf, OpenBarterPayload> STREAM_CODEC = StreamCodec.of(
        (buf, p) -> {
            ByteBufCodecs.VAR_INT.encode(buf, p.messengerEntityId);
            ByteBufCodecs.STRING_UTF8.encode(buf, p.campName);
            ByteBufCodecs.VAR_INT.encode(buf, p.campColor);
            ByteBufCodecs.STRING_UTF8.encode(buf, p.typeName);
            ByteBufCodecs.STRING_UTF8.encode(buf, p.greetingKey);
            ByteBufCodecs.VAR_INT.encode(buf, p.relState);
            buf.writeBoolean(p.isDemand);
            buf.writeBoolean(p.canDefer);
            ByteBufCodecs.VAR_INT.encode(buf, p.tributeFloor);
            ByteBufCodecs.VAR_INT.encode(buf, p.marginPercent);
            BarterEntry.LIST.encode(buf, p.youGive);
            BarterEntry.LIST.encode(buf, p.youGet);
            BarterEntry.LIST.encode(buf, p.yourStorage);
            BarterEntry.LIST.encode(buf, p.theirGoods);
        },
        buf -> new OpenBarterPayload(
            ByteBufCodecs.VAR_INT.decode(buf),
            ByteBufCodecs.STRING_UTF8.decode(buf),
            ByteBufCodecs.VAR_INT.decode(buf),
            ByteBufCodecs.STRING_UTF8.decode(buf),
            ByteBufCodecs.STRING_UTF8.decode(buf),
            ByteBufCodecs.VAR_INT.decode(buf),
            buf.readBoolean(),
            buf.readBoolean(),
            ByteBufCodecs.VAR_INT.decode(buf),
            ByteBufCodecs.VAR_INT.decode(buf),
            BarterEntry.LIST.decode(buf),
            BarterEntry.LIST.decode(buf),
            BarterEntry.LIST.decode(buf),
            BarterEntry.LIST.decode(buf)));

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
