package com.bannerbound.antiquity.block.entity;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.antiquity.BannerboundAntiquity;
import com.bannerbound.antiquity.block.ClayTankBlock;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Block entity for the Clay Tank (lives on the controller cell — the bottom of the pillar,
 * {@code PART == 0}). Stores a simple liquid: a bucket count plus one {@link LiquidType} shared by
 * the whole pillar (not a Forge fluid — the curing liquid is no registered fluid and never flows).
 * Each tank piece holds 8 buckets, so capacity = {@code 8 × pillar height}. Water is added/removed by
 * the bucket; a quicklime converts the held water into the hide-curing liquid; each hide cured
 * draws one bucket of curing liquid. State mirrors to the client for the fill renderer.
 */
@ApiStatus.Internal
public class ClayTankBlockEntity extends BlockEntity {
    /** Buckets one tank piece holds. Capacity scales with pillar height. */
    public static final int BUCKETS_PER_PIECE = 8;

    /** What a tank is holding. EMPTY shows no fill; WATER and CURING render with their tint. */
    public enum LiquidType {
        EMPTY(0x00000000),
        WATER(0xFF3F76E4),
        CURING(0xFFE9E2C4);

        private final int color;

        LiquidType(int color) {
            this.color = color;
        }

        /** Packed ARGB tint for the fill surface. */
        public int color() {
            return color;
        }
    }

    private int buckets = 0;
    private LiquidType liquid = LiquidType.EMPTY;

    public ClayTankBlockEntity(BlockPos pos, BlockState state) {
        super(BannerboundAntiquity.CLAY_TANK_BE.get(), pos, state);
    }

    public int getBuckets() {
        return buckets;
    }

    public LiquidType getLiquid() {
        return liquid;
    }

    /** Pillar height (this controller + connected tank blocks above it), min 1. */
    public int pillarHeight() {
        if (level == null) return 1;
        int h = 1;
        BlockPos.MutableBlockPos p = getBlockPos().mutable().move(0, 1, 0);
        while (level.getBlockState(p).getBlock() instanceof ClayTankBlock && h < ClayTankBlock.MAX_PIECES) {
            h++;
            p.move(0, 1, 0);
        }
        return h;
    }

    public int maxBuckets() {
        return BUCKETS_PER_PIECE * pillarHeight();
    }

    /** Total fill as a fraction of the whole pillar height (0..1) — drives the render surface Y. */
    public float fillFraction() {
        int max = maxBuckets();
        return max <= 0 ? 0f : (float) buckets / max;
    }

    // ─── Interactions (called from ClayTankBlock) ────────────────────────────────────────────────

    /** Add one bucket of water. Fails if full or already holding curing liquid. */
    public boolean addWater() {
        if (liquid == LiquidType.CURING) return false;
        if (buckets >= maxBuckets()) return false;
        liquid = LiquidType.WATER;
        buckets++;
        playSplash(1.0F);
        setChanged();
        return true;
    }

    /** Remove one bucket of water (only WATER is bucket-recoverable). */
    public boolean removeWater() {
        if (liquid != LiquidType.WATER || buckets <= 0) return false;
        buckets--;
        if (buckets == 0) liquid = LiquidType.EMPTY;
        playSplash(1.2F);
        setChanged();
        return true;
    }

    /** Convert the pillar's held water into curing liquid (a quicklime charge). */
    public boolean convertToCuring() {
        if (liquid != LiquidType.WATER || buckets <= 0) return false;
        liquid = LiquidType.CURING;
        if (level != null) {
            level.playSound(null, getBlockPos(), SoundEvents.BREWING_STAND_BREW, SoundSource.BLOCKS, 0.8F, 1.1F);
        }
        setChanged();
        return true;
    }

    /** True when at least one bucket of curing liquid is available. */
    public boolean hasCuring() {
        return liquid == LiquidType.CURING && buckets > 0;
    }

    /** Fill the whole tank to full water (the NPC tanner pours a fetched bucket in). Held curing
     *  liquid is left alone — water is only poured into an empty (or already-watered) tank. */
    public void fillWater() {
        if (liquid == LiquidType.CURING) return;
        liquid = LiquidType.WATER;
        buckets = maxBuckets();
        playSplash(1.0F);
        setChanged();
    }

    /** Charge the whole tank to full curing liquid (the NPC tanner: fills water + adds quicklime). */
    public void fillCuring() {
        liquid = LiquidType.CURING;
        buckets = maxBuckets();
        if (level != null) {
            level.playSound(null, getBlockPos(), SoundEvents.BREWING_STAND_BREW, SoundSource.BLOCKS, 0.8F, 1.0F);
        }
        setChanged();
    }

    /** Clamp the held liquid to a new capacity (a piece of the pillar was destroyed). */
    public void clampBuckets(int max) {
        if (buckets > Math.max(0, max)) {
            buckets = Math.max(0, max);
            if (buckets == 0) liquid = LiquidType.EMPTY;
            setChanged();
        }
    }

    /** Consume one bucket of curing liquid (one hide). */
    public boolean drawCuring() {
        if (!hasCuring()) return false;
        buckets--;
        if (buckets == 0) liquid = LiquidType.EMPTY;
        playSplash(0.9F);
        setChanged();
        return true;
    }

    private void playSplash(float pitch) {
        if (level != null) {
            level.playSound(null, getBlockPos(), SoundEvents.BUCKET_EMPTY, SoundSource.BLOCKS, 0.6F, pitch);
        }
    }

    // ─── NBT + client sync ───────────────────────────────────────────────────────────────────────

    @Override
    public void setChanged() {
        super.setChanged();
        if (level != null && !level.isClientSide) {
            level.sendBlockUpdated(getBlockPos(), getBlockState(), getBlockState(), Block.UPDATE_CLIENTS);
        }
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider provider) {
        super.saveAdditional(tag, provider);
        tag.putInt("Buckets", buckets);
        tag.putString("Liquid", liquid.name());
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider provider) {
        super.loadAdditional(tag, provider);
        buckets = tag.getInt("Buckets");
        try {
            liquid = LiquidType.valueOf(tag.getString("Liquid"));
        } catch (IllegalArgumentException e) {
            liquid = LiquidType.EMPTY;
        }
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
