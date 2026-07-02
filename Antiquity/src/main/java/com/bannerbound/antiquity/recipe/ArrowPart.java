package com.bannerbound.antiquity.recipe;

import org.jetbrains.annotations.ApiStatus;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;

/**
 * One datapack-defined modular-arrow part — a tip, shaft, or back. Loaded from
 * {@code data/<namespace>/arrow_parts/*.json} by {@link ArrowPartManager} and synced to clients for
 * rendering, so a modpack adds an arrow material (e.g. an iron tip) with a single JSON + its two
 * textures and gets crafting, stats, the NPC fletcher's part choice, the in-flight projectile, and
 * the layered inventory icon — no code or model files.
 *
 * <pre>
 * { "slot": "tip",                                  // tip | shaft | back
 *   "material": "bronze",                            // the id stored on the arrow's component
 *   "ingredient": "bannerboundantiquity:bronze_arrow_head",
 *   "damage": 1.45,                                  // tip: base damage factor (default 1.0)
 *   "weight": 3,                                     // tip/shaft: trajectory + small dmg (default 0)
 *   "accuracy": 1.0,                                 // back: bow-inaccuracy multiplier (default 1.0)
 *   "priority": 30,                                  // NPC "best part first" ordering (default 0)
 *   "item_texture": "bannerboundantiquity:item/arrows/bronze_arrow_tip",
 *   "projectile_texture": "bannerboundantiquity:textures/projectiles/bronze_arrow_tip.png" }
 * </pre>
 *
 * @param slot       which of the three layers this part fills ("tip" / "shaft" / "back")
 * @param material   the id written to the arrow's {@code ARROW_TIP/SHAFT/BACK} component
 * @param ingredient the crafting item consumed at the fletching station for this part
 * @param damage     tip-only base damage factor (1.0 = flint baseline)
 * @param weight     density points (0 = light flint/wood) — heavier = more damage + faster drop
 * @param accuracy   back-only multiplier on the bow's inaccuracy (lower = tighter)
 * @param priority   higher = an NPC fletcher prefers this part when several are stocked
 * @param itemTexture       atlas sprite for the inventory-icon layer (under {@code textures/item/…})
 * @param projectileTexture full texture path for the in-flight layer ({@code textures/…/x.png})
 */
@ApiStatus.Internal
public record ArrowPart(String slot, String material, Item ingredient,
                        double damage, int weight, double accuracy, int priority,
                        ResourceLocation itemTexture, ResourceLocation projectileTexture) {

    public static final String SLOT_TIP = "tip";
    public static final String SLOT_SHAFT = "shaft";
    public static final String SLOT_BACK = "back";

    public static final Codec<ArrowPart> CODEC = RecordCodecBuilder.create(i -> i.group(
        Codec.STRING.fieldOf("slot").forGetter(ArrowPart::slot),
        Codec.STRING.fieldOf("material").forGetter(ArrowPart::material),
        BuiltInRegistries.ITEM.byNameCodec().fieldOf("ingredient").forGetter(ArrowPart::ingredient),
        Codec.DOUBLE.optionalFieldOf("damage", 1.0).forGetter(ArrowPart::damage),
        Codec.INT.optionalFieldOf("weight", 0).forGetter(ArrowPart::weight),
        Codec.DOUBLE.optionalFieldOf("accuracy", 1.0).forGetter(ArrowPart::accuracy),
        Codec.INT.optionalFieldOf("priority", 0).forGetter(ArrowPart::priority),
        ResourceLocation.CODEC.fieldOf("item_texture").forGetter(ArrowPart::itemTexture),
        ResourceLocation.CODEC.fieldOf("projectile_texture").forGetter(ArrowPart::projectileTexture)
    ).apply(i, ArrowPart::new));

    /** Network codec for the server→client registry sync ({@link ArrowPartsSyncPayload}). */
    public static final StreamCodec<RegistryFriendlyByteBuf, ArrowPart> STREAM_CODEC = StreamCodec.of(
        (buf, p) -> {
            ByteBufCodecs.STRING_UTF8.encode(buf, p.slot);
            ByteBufCodecs.STRING_UTF8.encode(buf, p.material);
            ByteBufCodecs.STRING_UTF8.encode(buf, BuiltInRegistries.ITEM.getKey(p.ingredient).toString());
            buf.writeDouble(p.damage);
            buf.writeVarInt(p.weight);
            buf.writeDouble(p.accuracy);
            buf.writeVarInt(p.priority);
            ResourceLocation.STREAM_CODEC.encode(buf, p.itemTexture);
            ResourceLocation.STREAM_CODEC.encode(buf, p.projectileTexture);
        },
        buf -> new ArrowPart(
            ByteBufCodecs.STRING_UTF8.decode(buf),
            ByteBufCodecs.STRING_UTF8.decode(buf),
            BuiltInRegistries.ITEM.get(ResourceLocation.parse(ByteBufCodecs.STRING_UTF8.decode(buf))),
            buf.readDouble(),
            buf.readVarInt(),
            buf.readDouble(),
            buf.readVarInt(),
            ResourceLocation.STREAM_CODEC.decode(buf),
            ResourceLocation.STREAM_CODEC.decode(buf)));
}
