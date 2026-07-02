package com.bannerbound.antiquity.block.entity;

import com.bannerbound.antiquity.BannerboundAntiquity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Holds the Bellows Block's "Push" animation timer. A jump sets {@link #animTicks} to {@link
 * #PUSH_TICKS}; the renderer plays {@link com.bannerbound.antiquity.client.BellowsAnimations#PUSH}
 * over that window, and it ticks back to 0.
 */
public class BellowsBlockEntity extends BlockEntity {
    /** One push lasts the animation's 1-second length (20 ticks). */
    public static final int PUSH_TICKS = 20;

    private int animTicks = 0;

    public BellowsBlockEntity(BlockPos pos, BlockState state) {
        super(BannerboundAntiquity.BELLOWS_BLOCK_BE.get(), pos, state);
    }

    public int animTicks() {
        return animTicks;
    }

    /** Start (or restart) the push animation — called when a player lands on the block. */
    public void triggerPush() {
        animTicks = PUSH_TICKS;
        if (level != null && !level.isClientSide) {
            level.sendBlockUpdated(getBlockPos(), getBlockState(), getBlockState(), Block.UPDATE_CLIENTS);
        }
        setChanged();
    }

    public static void tick(Level level, BlockPos pos, BlockState state, BellowsBlockEntity be) {
        if (be.animTicks > 0) {
            be.animTicks--;
        }
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider provider) {
        super.saveAdditional(tag, provider);
        tag.putInt("AnimTicks", animTicks);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider provider) {
        super.loadAdditional(tag, provider);
        animTicks = tag.getInt("AnimTicks");
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
