package com.bannerbound.antiquity.item;

import java.util.ArrayList;
import java.util.List;

import com.mojang.serialization.Codec;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import io.netty.buffer.ByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.world.item.ItemStack;

/**
 * What a crucible holds, stored as a {@code DataComponentType} on the crucible item (and mirrored on
 * the placed {@link com.bannerbound.antiquity.block.entity.CrucibleBlockEntity}). Two phases:
 *
 * <ul>
 *   <li><b>Charge</b> — a list of raw, smeltable items dropped in while the crucible sits on the ground
 *       (raw ore, metal heads, ingots…). Visible inside the block; kept when broken and re-inserted.</li>
 *   <li><b>Molten</b> — once heated in a bloomery the charge melts to {@code mb} of {@code metalId};
 *       the charge clears and {@code molten} flips true. Only a molten crucible can pour. Pulled out
 *       before it melts, the charge is still there (no loss).</li>
 * </ul>
 *
 * @param charge    raw items waiting to be melted (empty once molten)
 * @param molten    {@code true} while liquid (pourable)
 * @param metalId   resolved metal/alloy id once molten (e.g. {@code "bronze"}); {@code ""} pre-melt
 * @param mb        molten millibuckets once molten; 0 pre-melt
 * @param tintColor resolved packed 0xRRGGBB colour for the molten layer
 */
public record CrucibleContents(List<ItemStack> charge, boolean molten, String metalId, int mb, int tintColor) {

    public static final CrucibleContents EMPTY =
        new CrucibleContents(List.of(), false, "", 0, 0xFFFFFF);

    public static final Codec<CrucibleContents> CODEC = RecordCodecBuilder.create(instance -> instance.group(
        ItemStack.CODEC.listOf().optionalFieldOf("charge", List.of()).forGetter(CrucibleContents::charge),
        Codec.BOOL.optionalFieldOf("molten", false).forGetter(CrucibleContents::molten),
        Codec.STRING.optionalFieldOf("metal_id", "").forGetter(CrucibleContents::metalId),
        Codec.INT.optionalFieldOf("mb", 0).forGetter(CrucibleContents::mb),
        Codec.INT.optionalFieldOf("tint_color", 0xFFFFFF).forGetter(CrucibleContents::tintColor)
    ).apply(instance, CrucibleContents::new));

    public static final StreamCodec<RegistryFriendlyByteBuf, CrucibleContents> STREAM_CODEC =
        StreamCodec.composite(
            ItemStack.OPTIONAL_STREAM_CODEC.apply(ByteBufCodecs.list()), CrucibleContents::charge,
            ByteBufCodecs.BOOL, CrucibleContents::molten,
            ByteBufCodecs.STRING_UTF8, CrucibleContents::metalId,
            ByteBufCodecs.VAR_INT, CrucibleContents::mb,
            ByteBufCodecs.INT, CrucibleContents::tintColor,
            CrucibleContents::new);

    /** A pre-melt crucible carrying these raw items. */
    public static CrucibleContents ofCharge(List<ItemStack> items) {
        return new CrucibleContents(List.copyOf(items), false, "", 0, 0xFFFFFF);
    }

    /** The molten result of a melt: liquid metal, charge cleared. */
    public static CrucibleContents molten(String metalId, int mb, int tintColor) {
        return new CrucibleContents(List.of(), true, metalId, mb, tintColor);
    }

    public boolean isEmpty() {
        return charge.isEmpty() && mb <= 0;
    }

    public boolean hasCharge() {
        return !charge.isEmpty();
    }

    /** Molten millibuckets (0 until melted). */
    public int totalMb() {
        return molten ? mb : 0;
    }

    public String dominantMetal() {
        return metalId;
    }

    /** This crucible with {@code stack} added to its (pre-melt) charge. */
    public CrucibleContents withAdded(ItemStack stack) {
        List<ItemStack> next = new ArrayList<>(charge);
        next.add(stack.copyWithCount(1));
        return new CrucibleContents(next, false, "", 0, tintColor);
    }

    /** The last charged item (for popping it back out while still solid), or EMPTY. */
    public ItemStack lastItem() {
        return charge.isEmpty() ? ItemStack.EMPTY : charge.get(charge.size() - 1).copy();
    }

    /** This (pre-melt) charge minus its last item. */
    public CrucibleContents withoutLast() {
        if (charge.isEmpty()) return this;
        List<ItemStack> next = new ArrayList<>(charge);
        next.remove(next.size() - 1);
        return new CrucibleContents(next, false, "", 0, tintColor);
    }

    /** Same molten charge minus {@code drainMb}; empties when drained dry. */
    public CrucibleContents drain(int drainMb) {
        if (!molten || drainMb <= 0) return this;
        int left = Math.max(0, mb - drainMb);
        if (left <= 0) return EMPTY;
        return new CrucibleContents(List.of(), true, metalId, left, tintColor);
    }
}
