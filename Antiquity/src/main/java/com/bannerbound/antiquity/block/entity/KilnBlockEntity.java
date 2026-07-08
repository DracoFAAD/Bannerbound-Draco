package com.bannerbound.antiquity.block.entity;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.antiquity.BannerboundAntiquity;
import com.bannerbound.antiquity.block.KilnBlock;
import com.bannerbound.antiquity.recipe.KilnRecipe;
import com.bannerbound.antiquity.recipe.KilnRecipeManager;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.util.RandomSource;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Block entity for the 2x2x2 Kiln multiblock; lives on the controller cell (PART == 0), whose
 * BlockPos is the min corner -- world coordinates in this class add +1.0 to reach the footprint
 * center. Like the Bloomery it tracks a held item, a burn timer (MAX_LIT_TICKS = 30s) and firing
 * progress, but it has no door: it fires whenever lit (flint and steel / fire sticks) with a
 * valid KilnRecipe ingredient inside, and the burn is kept alive by stoking with coal/charcoal --
 * stoke() only refreshes an already-lit fire, it never starts one. All timers run server-side;
 * setChanged() broadcasts block updates so litTicks, the slide-in animation (SLIDE_TICKS) and
 * smeltingActive mirror to the client for rendering and particles. Firing time is linear in stack
 * count (64 items = 64x one item); at completion each item independently rolls recipe.chance(),
 * and a batch where every roll misses is consumed with no output. If the fire dies mid-firing,
 * progress drains back gradually instead of resetting. Research gating (CraftGating) is applied
 * before progress accrues, not at completion, so an unresearched output just idles the kiln and
 * never consumes the ingredient. reconcileLitState keeps the LIT blockstate (mouth light
 * emission) on all eight cells in step with the burn timer.
 */
@ApiStatus.Internal
public class KilnBlockEntity extends BlockEntity {
    public static final int SLIDE_TICKS = 6;
    public static final int MAX_LIT_TICKS = 600;

    private ItemStack heldItem = ItemStack.EMPTY;
    private int insertAnimTicks = 0;
    private int litTicks = 0;
    private int smeltProgress = 0;
    private boolean smeltingActive = false;

    public KilnBlockEntity(BlockPos pos, BlockState state) {
        super(BannerboundAntiquity.KILN_BE.get(), pos, state);
    }

    public ItemStack getHeldItem() {
        return heldItem;
    }

    public int getInsertAnimTicks() {
        return insertAnimTicks;
    }

    public void insert(ItemStack stack) {
        this.heldItem = stack;
        this.insertAnimTicks = SLIDE_TICKS;
        this.smeltProgress = 0;
        this.smeltingActive = false;
        if (level != null) {
            level.playSound(null, getBlockPos(), SoundEvents.GRAVEL_PLACE, SoundSource.BLOCKS, 0.7F, 1.3F);
        }
        if (level instanceof ServerLevel server) {
            Direction facing = getBlockState().getValue(KilnBlock.FACING);
            server.sendParticles(ParticleTypes.SMOKE,
                getBlockPos().getX() + 1.0 + facing.getStepX() * 0.4,
                getBlockPos().getY() + 0.4,
                getBlockPos().getZ() + 1.0 + facing.getStepZ() * 0.4,
                5, 0.15, 0.1, 0.15, 0.01);
        }
        setChanged();
    }

    public ItemStack extract() {
        ItemStack out = heldItem;
        this.heldItem = ItemStack.EMPTY;
        this.insertAnimTicks = 0;
        this.smeltProgress = 0;
        this.smeltingActive = false;
        if (level != null && !out.isEmpty()) {
            level.playSound(null, getBlockPos(), SoundEvents.ITEM_PICKUP, SoundSource.BLOCKS, 0.6F, 1.1F);
        }
        setChanged();
        return out;
    }

    public boolean isLit() {
        return litTicks > 0;
    }

    public int getLitTicks() {
        return litTicks;
    }

    public int getSmeltProgress() {
        return smeltProgress;
    }

    public boolean isSmelting() {
        return smeltingActive;
    }

    public void ignite() {
        boolean wasLit = litTicks > 0;
        litTicks = MAX_LIT_TICKS;
        if (level != null) {
            level.playSound(null, getBlockPos(), SoundEvents.FLINTANDSTEEL_USE, SoundSource.BLOCKS, 1.0F, 1.0F);
            if (!wasLit) {
                level.playSound(null, getBlockPos(), SoundEvents.FIRECHARGE_USE, SoundSource.BLOCKS, 0.6F, 1.2F);
            }
        }
        spawnIgniteBurst();
        setChanged();
    }

    public boolean stoke() {
        if (litTicks <= 0) {
            return false;
        }
        litTicks = MAX_LIT_TICKS;
        spawnIgniteBurst();
        if (level != null) {
            level.playSound(null, getBlockPos(), SoundEvents.FIRECHARGE_USE, SoundSource.BLOCKS, 0.8F, 1.4F);
            level.playSound(null, getBlockPos(), SoundEvents.FURNACE_FIRE_CRACKLE, SoundSource.BLOCKS, 1.2F, 0.9F);
        }
        setChanged();
        return true;
    }

    private void spawnIgniteBurst() {
        if (level instanceof ServerLevel server) {
            server.sendParticles(ParticleTypes.FLAME,
                getBlockPos().getX() + 1.0, getBlockPos().getY() + 0.5, getBlockPos().getZ() + 1.0,
                22, 0.4, 0.35, 0.4, 0.02);
        }
    }

    public static void tick(Level level, BlockPos pos, BlockState state, KilnBlockEntity be) {
        if (be.insertAnimTicks > 0) {
            be.insertAnimTicks--;
        }

        if (be.litTicks > 0) {
            be.litTicks--;
            if (level.isClientSide) {
                be.spawnFireParticles(level, pos);
            } else if (be.litTicks == 0) {
                level.playSound(null, pos, SoundEvents.FIRE_EXTINGUISH, SoundSource.BLOCKS, 1.0F, 1.0F);
                be.setChanged();
            } else if (be.litTicks % 80 == 0) {
                level.playSound(null, pos, SoundEvents.FURNACE_FIRE_CRACKLE, SoundSource.BLOCKS, 0.7F, 1.0F);
            }
        }

        if (level.isClientSide) {
            if (be.smeltingActive) {
                be.spawnMouthSmoke(level, pos, state);
            }
        } else {
            be.tickSmelting(level);
            be.reconcileLitState(level);
        }
    }

    private void reconcileLitState(Level level) {
        boolean shouldBeLit = litTicks > 0;
        BlockState st = getBlockState();
        if (st.getBlock() instanceof KilnBlock && st.getValue(KilnBlock.LIT) != shouldBeLit) {
            BlockPos base = getBlockPos();
            for (int dx = 0; dx < 2; dx++) {
                for (int dy = 0; dy < 2; dy++) {
                    for (int dz = 0; dz < 2; dz++) {
                        BlockPos cell = base.offset(dx, dy, dz);
                        BlockState cs = level.getBlockState(cell);
                        if (cs.getBlock() instanceof KilnBlock && cs.getValue(KilnBlock.LIT) != shouldBeLit) {
                            level.setBlock(cell, cs.setValue(KilnBlock.LIT, shouldBeLit), Block.UPDATE_ALL);
                        }
                    }
                }
            }
        }
    }

    private void tickSmelting(Level level) {
        KilnRecipe recipe = heldItem.isEmpty() ? null : KilnRecipeManager.find(heldItem);
        // Gate BEFORE progress, not at completion, so an unresearched output never consumes the ingredient.
        if (recipe != null && !com.bannerbound.core.api.research.CraftGating.canProduceAt(
                level, getBlockPos(), recipe.result().getItem())) {
            recipe = null;
        }
        boolean active = litTicks > 0 && recipe != null;
        if (active != smeltingActive) {
            smeltingActive = active;
            setChanged();
        }
        if (active) {
            smeltProgress++;
            if (smeltProgress >= totalTicks(recipe, heldItem.getCount())) {
                completeSmelt(level, recipe);
            } else if (smeltProgress % 20 == 0) {
                setChanged();
            }
        } else if (litTicks <= 0 && smeltProgress > 0) {
            smeltProgress--;
            if (smeltProgress == 0 || smeltProgress % 20 == 0) {
                setChanged();
            }
        }
    }

    private static int totalTicks(KilnRecipe recipe, int count) {
        return recipe.ticks() * Math.max(1, count);
    }

    private void completeSmelt(Level level, KilnRecipe recipe) {
        int produced = 0;
        for (int i = 0; i < heldItem.getCount(); i++) {
            if (level.random.nextFloat() < recipe.chance()) {
                produced += recipe.result().getCount();
            }
        }
        if (produced <= 0) {
            heldItem = ItemStack.EMPTY;
        } else {
            ItemStack output = recipe.result().copy();
            output.setCount(Math.min(produced, output.getMaxStackSize()));
            heldItem = output;
            insertAnimTicks = SLIDE_TICKS;
        }
        smeltProgress = 0;
        smeltingActive = false;
        level.playSound(null, getBlockPos(), BannerboundAntiquity.SMELTING_DONE_SOUND.get(),
            SoundSource.BLOCKS, 1.0F, 1.0F);
        if (level instanceof ServerLevel server) {
            Direction facing = getBlockState().getValue(KilnBlock.FACING);
            server.sendParticles(ParticleTypes.LARGE_SMOKE,
                getBlockPos().getX() + 1.0, getBlockPos().getY() + 1.7, getBlockPos().getZ() + 1.0,
                10, 0.25, 0.15, 0.25, 0.02);
            server.sendParticles(ParticleTypes.FLAME,
                getBlockPos().getX() + 1.0 + facing.getStepX() * 0.5,
                getBlockPos().getY() + 0.35,
                getBlockPos().getZ() + 1.0 + facing.getStepZ() * 0.5,
                8, 0.2, 0.15, 0.2, 0.03);
        }
        setChanged();
    }

    private void spawnFireParticles(Level level, BlockPos pos) {
        float intensity = litTicks / (float) MAX_LIT_TICKS;
        RandomSource rand = level.random;
        Direction facing = getBlockState().getValue(KilnBlock.FACING);
        double mouthX = pos.getX() + 1.0 + facing.getStepX() * 0.55;
        double mouthZ = pos.getZ() + 1.0 + facing.getStepZ() * 0.55;
        if (rand.nextFloat() < intensity) {
            level.addParticle(ParticleTypes.SMALL_FLAME,
                mouthX + (rand.nextDouble() - 0.5) * 0.4,
                pos.getY() + 0.2 + rand.nextDouble() * 0.2,
                mouthZ + (rand.nextDouble() - 0.5) * 0.4,
                0.0, 0.0, 0.0);
        }
        if (rand.nextFloat() < intensity * 0.3F) {
            level.addParticle(ParticleTypes.LAVA, mouthX, pos.getY() + 0.3, mouthZ, 0.0, 0.0, 0.0);
        }
        if (rand.nextFloat() < intensity) {
            level.addParticle(ParticleTypes.SMOKE,
                pos.getX() + 1.0 + (rand.nextDouble() - 0.5) * 0.3,
                pos.getY() + 1.8,
                pos.getZ() + 1.0 + (rand.nextDouble() - 0.5) * 0.3,
                0.0, 0.04, 0.0);
        }
    }

    private void spawnMouthSmoke(Level level, BlockPos pos, BlockState state) {
        Direction facing = state.getValue(KilnBlock.FACING);
        RandomSource rand = level.random;
        if (rand.nextInt(2) == 0) {
            level.addParticle(ParticleTypes.LARGE_SMOKE,
                pos.getX() + 1.0 + (rand.nextDouble() - 0.5) * 0.4,
                pos.getY() + 1.85,
                pos.getZ() + 1.0 + (rand.nextDouble() - 0.5) * 0.4,
                0.0, 0.03, 0.0);
        }
        if (rand.nextInt(8) == 0) {
            double mouthX = pos.getX() + 1.0 + facing.getStepX() * 0.6;
            double mouthZ = pos.getZ() + 1.0 + facing.getStepZ() * 0.6;
            level.addParticle(ParticleTypes.LAVA, mouthX, pos.getY() + 0.3, mouthZ,
                facing.getStepX() * 0.05, 0.05, facing.getStepZ() * 0.05);
        }
    }

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
        tag.putInt("InsertAnimTicks", insertAnimTicks);
        tag.putInt("LitTicks", litTicks);
        tag.putInt("SmeltProgress", smeltProgress);
        tag.putBoolean("SmeltingActive", smeltingActive);
        if (!heldItem.isEmpty()) {
            tag.put("HeldItem", heldItem.save(provider));
        }
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider provider) {
        super.loadAdditional(tag, provider);
        insertAnimTicks = tag.getInt("InsertAnimTicks");
        litTicks = tag.getInt("LitTicks");
        smeltProgress = tag.getInt("SmeltProgress");
        smeltingActive = tag.getBoolean("SmeltingActive");
        heldItem = tag.contains("HeldItem")
            ? ItemStack.parse(provider, tag.getCompound("HeldItem")).orElse(ItemStack.EMPTY)
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
