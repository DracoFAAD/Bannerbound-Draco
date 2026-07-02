package com.bannerbound.core.api.quality;

import com.mojang.serialization.Codec;

import io.netty.buffer.ByteBuf;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.util.StringRepresentable;

/**
 * Craftsmanship quality of a tool/weapon, the canonical cross-cutting quality ladder for the whole
 * Bannerbound suite (lives in Core so any expansion can read/write it without depending on the
 * expansion that produced the item — e.g. a future Medieval guild upgrading an Antiquity-made bow).
 *
 * <p>{@link #CRUDE}..{@link #FINE} are reachable by player hand-craft (minigames) — {@link #fromScore(int)}
 * caps at FINE. {@link #MASTERWORK} and above are reserved for later systems (veteran Crafter NPCs,
 * Medieval guild Perfect/Legendary upgrades) and are never produced by a hand-craft roll.
 *
 * <p>Quality scales item stats by {@link #statMultiplier()} (durability and effectiveness share the
 * same factor, per the design): Crude −25%, Standard baseline, Fine slightly better. See
 * {@code FLETCHING_PLAN.md} Part 4.
 */
public enum QualityTier implements StringRepresentable {
    CRUDE("crude", 0.75F, ChatFormatting.RED, 0xFFFF5555),
    STANDARD("standard", 1.00F, ChatFormatting.GRAY, 0xFFAAAAAA),
    FINE("fine", 1.10F, ChatFormatting.GREEN, 0xFF55FF55),
    MASTERWORK("masterwork", 1.20F, ChatFormatting.AQUA, 0xFF55FFFF),
    PERFECT("perfect", 1.35F, ChatFormatting.LIGHT_PURPLE, 0xFFFF55FF),
    LEGENDARY("legendary", 1.50F, ChatFormatting.GOLD, 0xFFFFAA00);

    /** Codec for persistence (the data component) — stored by serialized name. */
    public static final Codec<QualityTier> CODEC = StringRepresentable.fromEnum(QualityTier::values);
    /** Stream codec for network sync (ordinal-based; compact and stable for an append-only enum). */
    public static final StreamCodec<ByteBuf, QualityTier> STREAM_CODEC =
        ByteBufCodecs.VAR_INT.map(i -> values()[i], QualityTier::ordinal);

    private final String name;
    private final float statMultiplier;
    private final ChatFormatting format;
    private final int color;

    QualityTier(String name, float statMultiplier, ChatFormatting format, int color) {
        this.name = name;
        this.statMultiplier = statMultiplier;
        this.format = format;
        this.color = color;
    }

    @Override
    public String getSerializedName() {
        return name;
    }

    /** Multiplier applied to durability and effectiveness (mining/attack/yield). */
    public float statMultiplier() {
        return statMultiplier;
    }

    /** ChatFormatting for the tier word in tooltips / item names. */
    public ChatFormatting format() {
        return format;
    }

    /** Packed ARGB color for HUD rendering (matches {@link #format()}). */
    public int color() {
        return color;
    }

    /** Localized tier name, e.g. "Fine" — {@code bannerbound.quality.<name>}. */
    public Component displayName() {
        return Component.translatable("bannerbound.quality." + name).withStyle(format);
    }

    /**
     * Maps an aggregate craft score (0–100) to a hand-craft-reachable tier. Caps at {@link #FINE}:
     * with 3 stretches only all-green (100) or two-green-one-good (~87) reach it. MASTERWORK and
     * above are never rolled here — reserved for veteran crafters / guilds later.
     */
    public static QualityTier fromScore(int score) {
        if (score < 25) return CRUDE;
        if (score < 85) return STANDARD;
        return FINE;
    }

    /**
     * The quality carried by {@code stack}, or {@link #STANDARD} when it has none — so stat hooks
     * can treat un-componented (e.g. creative-spawned) items as baseline.
     */
    public static QualityTier of(net.minecraft.world.item.ItemStack stack) {
        QualityTier tier = stack.get(com.bannerbound.core.BannerboundCore.TOOL_QUALITY.get());
        return tier == null ? STANDARD : tier;
    }

    /**
     * Work-tick scaling for a tool of {@code tool}'s quality: a better tool finishes a work cycle in
     * FEWER ticks (a crude one in more), AMPLIFIED so the difference is felt — a +10% (Fine) stat tier
     * works ~17% faster, a Crude tool ~33% slower. Floored at 1 tick. Used by the gathering work goals
     * (miner / digger / forester / farmer) so a fine pickaxe digs faster in an NPC's hands the way it
     * does in a player's. An un-componented (creative/standard) tool reads as ×1.0 — no change.
     */
    public static int scaleWorkTicks(net.minecraft.world.item.ItemStack tool, int baseTicks) {
        float m = of(tool).statMultiplier();
        float speed = m >= 1.0F ? 1.0F + (m - 1.0F) * 2.0F : m;
        return Math.max(1, Math.round(baseTicks / speed));
    }
}
