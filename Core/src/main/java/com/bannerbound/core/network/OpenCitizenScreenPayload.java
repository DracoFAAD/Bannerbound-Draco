package com.bannerbound.core.network;

import java.util.List;

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
 * Server → client: open the per-citizen detail screen when a player right-clicks one. Carries a
 * snapshot of the live state (name, current/max health, happiness, stamina) so the screen can
 * render before any live sync arrives, and the entityId so the Exile button can address the
 * same citizen on the server.
 * <p>
 * The name is a full {@link Component}, not a flattened string — citizen names carry a gender
 * icon (custom-font glyph) and a settlement-color tint, both of which a plain string would drop.
 */
@ApiStatus.Internal
public record OpenCitizenScreenPayload(
    int entityId,
    Component name,
    float currentHealth,
    float maxHealth,
    int happiness,
    int happinessMax,
    boolean canModify,
    int stamina,
    int staminaMax,
    List<RelationshipEntry> relationships,
    List<ThoughtEntry> thoughts,
    /** Step 9: 0..100, default 100. Displayed as a bar below stamina. */
    int compliance,
    /** Step 9: resentment this citizen holds toward the VIEWING player only — filtered
     *  server-side so the screen never reveals what a citizen thinks of other players. */
    int viewerResentment
) implements CustomPacketPayload {
    public static final CustomPacketPayload.Type<OpenCitizenScreenPayload> TYPE =
        new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(BannerboundCore.MODID, "open_citizen_screen"));

    // RegistryFriendlyByteBuf because ComponentSerialization.STREAM_CODEC needs registry access
    // to encode component contents (e.g. hover-event item stacks). The other fields' codecs work
    // over any ByteBuf, so they're unchanged.
    public static final StreamCodec<RegistryFriendlyByteBuf, OpenCitizenScreenPayload> STREAM_CODEC = StreamCodec.of(
        (buf, p) -> {
            ByteBufCodecs.VAR_INT.encode(buf, p.entityId());
            ComponentSerialization.STREAM_CODEC.encode(buf, p.name());
            ByteBufCodecs.FLOAT.encode(buf, p.currentHealth());
            ByteBufCodecs.FLOAT.encode(buf, p.maxHealth());
            ByteBufCodecs.VAR_INT.encode(buf, p.happiness());
            ByteBufCodecs.VAR_INT.encode(buf, p.happinessMax());
            ByteBufCodecs.BOOL.encode(buf, p.canModify());
            ByteBufCodecs.VAR_INT.encode(buf, p.stamina());
            ByteBufCodecs.VAR_INT.encode(buf, p.staminaMax());
            ByteBufCodecs.VAR_INT.encode(buf, p.relationships().size());
            for (RelationshipEntry e : p.relationships()) {
                RelationshipEntry.STREAM_CODEC.encode(buf, e);
            }
            ByteBufCodecs.VAR_INT.encode(buf, p.thoughts().size());
            for (ThoughtEntry t : p.thoughts()) {
                ThoughtEntry.STREAM_CODEC.encode(buf, t);
            }
            ByteBufCodecs.VAR_INT.encode(buf, p.compliance());
            ByteBufCodecs.VAR_INT.encode(buf, p.viewerResentment());
        },
        buf -> {
            int entityId = ByteBufCodecs.VAR_INT.decode(buf);
            Component name = ComponentSerialization.STREAM_CODEC.decode(buf);
            float currentHealth = ByteBufCodecs.FLOAT.decode(buf);
            float maxHealth = ByteBufCodecs.FLOAT.decode(buf);
            int happiness = ByteBufCodecs.VAR_INT.decode(buf);
            int happinessMax = ByteBufCodecs.VAR_INT.decode(buf);
            boolean canModify = ByteBufCodecs.BOOL.decode(buf);
            int stamina = ByteBufCodecs.VAR_INT.decode(buf);
            int staminaMax = ByteBufCodecs.VAR_INT.decode(buf);
            int relCount = ByteBufCodecs.VAR_INT.decode(buf);
            List<RelationshipEntry> rels = new java.util.ArrayList<>(relCount);
            for (int i = 0; i < relCount; i++) {
                rels.add(RelationshipEntry.STREAM_CODEC.decode(buf));
            }
            int thoughtCount = ByteBufCodecs.VAR_INT.decode(buf);
            List<ThoughtEntry> thoughts = new java.util.ArrayList<>(thoughtCount);
            for (int i = 0; i < thoughtCount; i++) {
                thoughts.add(ThoughtEntry.STREAM_CODEC.decode(buf));
            }
            int compliance = ByteBufCodecs.VAR_INT.decode(buf);
            int viewerResentment = ByteBufCodecs.VAR_INT.decode(buf);
            return new OpenCitizenScreenPayload(entityId, name, currentHealth, maxHealth,
                happiness, happinessMax, canModify, stamina, staminaMax, rels, thoughts,
                compliance, viewerResentment);
        }
    );

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }
}
