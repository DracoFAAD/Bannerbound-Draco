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
 * Block entity for the Kiln multiblock (lives on the controller cell, {@code PART == 0}). Like the
 * Bloomery, it tracks an item held inside, a burn timer, and firing progress — but it has no door:
 * it fires whenever it's lit with a valid ingredient inside. Lit with flint &amp; steel / fire
 * sticks; the burn is kept alive by feeding charcoal (in place of the bloomery's bellows). Burn +
 * firing timers run server-side; the burn timer and slide animation mirror to the client.
 */
@ApiStatus.Internal
public class KilnBlockEntity extends BlockEntity {
    /** Ticks the inserted item's slide-in animation runs. */
    public static final int SLIDE_TICKS = 6;
    /** Burn duration once ignited or stoked — 30 seconds. */
    public static final int MAX_LIT_TICKS = 600;

    private ItemStack heldItem = ItemStack.EMPTY;
    private int insertAnimTicks = 0;
    private int litTicks = 0;
    private int smeltProgress = 0;
    private boolean smeltingActive = false;

    public KilnBlockEntity(BlockPos pos, BlockState state) {
        super(BannerboundAntiquity.KILN_BE.get(), pos, state);
    }

    // ─── Held item ─────────────────────────────────────────────────────────────────────────────

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

    // ─── Fire ──────────────────────────────────────────────────────────────────────────────────

    public boolean isLit() {
        return litTicks > 0;
    }

    public int getLitTicks() {
        return litTicks;
    }

    /** Lights the kiln (fire sticks / flint &amp; steel). Plays the ignite sound if it was unlit. */
    public void ignite() {
        boolean wasLit = litTicks > 0;
        litTicks = MAX_LIT_TICKS;
        if (level != null) {
            level.playSound(null, getBlockPos(), SoundEvents.FLINTANDSTEEL_USE, SoundSource.BLOCKS, 1.0F, 1.0F);
            if (!wasLit) {
                // A soft whoosh as the fire catches.
                level.playSound(null, getBlockPos(), SoundEvents.FIRECHARGE_USE, SoundSource.BLOCKS, 0.6F, 1.2F);
            }
        }
        spawnIgniteBurst();
        setChanged();
    }

    /** Stokes an already-lit kiln with coal or charcoal, resetting the burn timer. No effect when unlit. */
    public boolean stoke() {
        if (litTicks <= 0) {
            return false; // fuel only feeds an existing fire — light it first
        }
        litTicks = MAX_LIT_TICKS;
        spawnIgniteBurst();
        if (level != null) {
            // A clear "fwoomp" of the fire catching the fresh fuel, plus the crackle.
            level.playSound(null, getBlockPos(), SoundEvents.FIRECHARGE_USE, SoundSource.BLOCKS, 0.8F, 1.4F);
            level.playSound(null, getBlockPos(), SoundEvents.FURNACE_FIRE_CRACKLE, SoundSource.BLOCKS, 1.2F, 0.9F);
        }
        setChanged();
        return true;
    }

    /** A splash of flame particles when the kiln is ignited or stoked. */
    private void spawnIgniteBurst() {
        if (level instanceof ServerLevel server) {
            server.sendParticles(ParticleTypes.FLAME,
                getBlockPos().getX() + 1.0, getBlockPos().getY() + 0.5, getBlockPos().getZ() + 1.0,
                22, 0.4, 0.35, 0.4, 0.02);
        }
    }

    // ─── Ticking ───────────────────────────────────────────────────────────────────────────────

    /** Ticker — runs on both sides; drives the slide animation, burn timer and firing. */
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

    /** Keeps the LIT blockstate (and thus the mouth's light emission) in step with the burn timer. */
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

    /** Server-side firing: progress accrues while the kiln is lit with a valid ingredient inside. */
    private void tickSmelting(Level level) {
        KilnRecipe recipe = heldItem.isEmpty() ? null : KilnRecipeManager.find(heldItem);
        // Won't fire toward an output the owning civ hasn't researched yet. Gating here (rather
        // than at completion) means the ingredient is never consumed — the kiln just sits idle.
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
            // Fire's out — progress slowly drains away.
            smeltProgress--;
            if (smeltProgress == 0 || smeltProgress % 20 == 0) {
                setChanged();
            }
        }
    }

    /** Total firing time for a stack — linear: every item costs a full base time, so a bigger
     *  batch takes proportionally longer (64 logs = 64× one log). */
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
            heldItem = ItemStack.EMPTY; // every roll missed — the batch yielded nothing
        } else {
            ItemStack output = recipe.result().copy();
            output.setCount(Math.min(produced, output.getMaxStackSize()));
            heldItem = output;
            insertAnimTicks = SLIDE_TICKS; // the result settles in with a little slide
        }
        smeltProgress = 0;
        smeltingActive = false;
        level.playSound(null, getBlockPos(), BannerboundAntiquity.SMELTING_DONE_SOUND.get(),
            SoundSource.BLOCKS, 1.0F, 1.0F);
        // A puff of smoke from the apex + a lick of flame at the mouth signals the firing is done.
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

    /** Flame + embers at the mouth and smoke rising from the dome top; thins as the fire dies. */
    private void spawnFireParticles(Level level, BlockPos pos) {
        float intensity = litTicks / (float) MAX_LIT_TICKS;
        RandomSource rand = level.random;
        Direction facing = getBlockState().getValue(KilnBlock.FACING);
        double mouthX = pos.getX() + 1.0 + facing.getStepX() * 0.55;
        double mouthZ = pos.getZ() + 1.0 + facing.getStepZ() * 0.55;
        // Flames licking out of the mouth (front-bottom, toward the kiln's facing).
        if (rand.nextFloat() < intensity) {
            level.addParticle(ParticleTypes.SMALL_FLAME,
                mouthX + (rand.nextDouble() - 0.5) * 0.4,
                pos.getY() + 0.2 + rand.nextDouble() * 0.2,
                mouthZ + (rand.nextDouble() - 0.5) * 0.4,
                0.0, 0.0, 0.0);
        }
        // The odd ember drifting up from the mouth.
        if (rand.nextFloat() < intensity * 0.3F) {
            level.addParticle(ParticleTypes.LAVA, mouthX, pos.getY() + 0.3, mouthZ, 0.0, 0.0, 0.0);
        }
        // Smoke from the apex of the dome (centered over the 2×2 footprint).
        if (rand.nextFloat() < intensity) {
            level.addParticle(ParticleTypes.SMOKE,
                pos.getX() + 1.0 + (rand.nextDouble() - 0.5) * 0.3,
                pos.getY() + 1.8,
                pos.getZ() + 1.0 + (rand.nextDouble() - 0.5) * 0.3,
                0.0, 0.04, 0.0);
        }
    }

    /** The "it's working" tell: thick smoke from the apex + the odd spark spat from the mouth. */
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

    // ─── NBT + client sync ─────────────────────────────────────────────────────────────────────

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
