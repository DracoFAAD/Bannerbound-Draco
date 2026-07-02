package com.bannerbound.antiquity.block.entity;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.antiquity.BannerboundAntiquity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Block entity for the Mortar and Pestle. Holds the bowl's liquid and the (possibly batched)
 * ingredient being ground. Grinding is the press-and-grind minigame ({@code MortarGrind}); this
 * entity just stores the loaded ingredients and plays a short "Mix" flourish (driven by
 * {@link #mixAnimTicks}) when a grind finishes, so players nearby see the pestle strike. Liquid,
 * ingredient and the flourish timer are mirrored to the client; the live in-session pestle motion
 * is driven client-side by {@code MortarGrindState}.
 */
@ApiStatus.Internal
public class MortarAndPestleBlockEntity extends BlockEntity {
    /** Ticks the completion flourish runs — matches the 1-second "Mix" animation. */
    public static final int MIX_CYCLE_TICKS = 20;
    /** Most ingredients the bowl holds at once (item-output recipes grind a whole batch in one go). */
    public static final int MAX_BATCH = 16;

    private String liquidId = "";
    private ItemStack ingredient = ItemStack.EMPTY;
    /** Ticks left in the completion flourish; 0 means idle. */
    private int mixAnimTicks = 0;

    public MortarAndPestleBlockEntity(BlockPos pos, BlockState state) {
        super(BannerboundAntiquity.MORTAR_AND_PESTLE_BE.get(), pos, state);
    }

    // ─── State ─────────────────────────────────────────────────────────────────────────────────

    public String getLiquidId() {
        return liquidId;
    }

    public boolean hasLiquid() {
        return !liquidId.isEmpty();
    }

    /** Sets the liquid (use {@code ""} to empty) and re-syncs. */
    public void setLiquid(String id) {
        this.liquidId = id == null ? "" : id;
        setChanged();
    }

    public ItemStack getIngredient() {
        return ingredient;
    }

    /** Sets the (possibly batched) ingredient and re-syncs. */
    public void setIngredient(ItemStack stack) {
        this.ingredient = stack == null ? ItemStack.EMPTY : stack;
        setChanged();
    }

    /** True while the completion flourish is playing — used to drive the renderer for nearby players. */
    public boolean isMixing() {
        return mixAnimTicks > 0;
    }

    /** Ticks left in the completion flourish (0..MIX_CYCLE_TICKS). */
    public int getMixAnimTicks() {
        return mixAnimTicks;
    }

    /** Plays one "Mix" flourish — a single pestle strike visible to everyone nearby. Called by the
     *  server when a grind session completes (the live in-session motion is driven client-side). */
    public void playFlourish() {
        mixAnimTicks = MIX_CYCLE_TICKS;
        setChanged();
    }

    /** Ticker — runs on both sides; counts down the completion flourish. */
    public static void tick(Level level, BlockPos pos, BlockState state, MortarAndPestleBlockEntity be) {
        if (be.mixAnimTicks > 0) {
            be.mixAnimTicks--;
        }
    }

    @Override
    public void setChanged() {
        super.setChanged();
        if (level != null && !level.isClientSide) {
            level.sendBlockUpdated(getBlockPos(), getBlockState(), getBlockState(), Block.UPDATE_CLIENTS);
        }
    }

    // ─── NBT + client sync ─────────────────────────────────────────────────────────────────────

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider provider) {
        super.saveAdditional(tag, provider);
        tag.putString("Liquid", liquidId);
        tag.putInt("MixAnimTicks", mixAnimTicks);
        if (!ingredient.isEmpty()) {
            tag.put("Ingredient", ingredient.save(provider));
        }
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider provider) {
        super.loadAdditional(tag, provider);
        liquidId = tag.getString("Liquid");
        mixAnimTicks = tag.getInt("MixAnimTicks");
        ingredient = tag.contains("Ingredient")
            ? ItemStack.parse(provider, tag.getCompound("Ingredient")).orElse(ItemStack.EMPTY)
            : ItemStack.EMPTY;
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider provider) {
        CompoundTag tag = new CompoundTag();
        saveAdditional(tag, provider);
        return tag;
    }

    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }
}
