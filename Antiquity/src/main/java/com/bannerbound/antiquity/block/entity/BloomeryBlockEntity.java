package com.bannerbound.antiquity.block.entity;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.antiquity.BannerboundAntiquity;
import com.bannerbound.antiquity.block.BloomeryBlock;
import com.bannerbound.antiquity.recipe.BloomeryRecipe;
import com.bannerbound.antiquity.recipe.BloomeryRecipeManager;

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
import com.bannerbound.antiquity.workshop.MetalworkingItems;

/**
 * Block entity for the Bloomery multiblock (lives on the lower segment). Tracks the door's
 * open/closed state, an item held inside, a burn timer (litTicks), smelting progress, and a
 * fire-driven temperature with a decaying bellows boost. Burn + smelt logic run server-side; door
 * and item-slide animations plus the burn timer mirror to the client, and setChanged always
 * re-syncs (sendBlockUpdated) so the looked-at temperature readout stays live.
 *
 * Temperature is fire-driven and independent of the contents: it climbs toward baseCeiling +
 * bellowsBoost only while lit AND the door is shut (an open door bleeds heat to 0 regardless), and
 * decays when the fire is out; bellowsBoost bleeds off every tick. pumpBellows (a jump on the
 * Bellows Block, or the held bellows) and refuel both reset the burn timer but feed a LIT fire
 * only -> air on a dead fire does nothing, you must re-light first (flint and steel / fire
 * sticks). While hot, server temperature sync is throttled to every 5th tick to keep the
 * looked-at readout live without per-tick packet spam.
 *
 * Smelting accrues only while the temperature sits in the active recipe's band with the door shut;
 * off-band merely stalls or slows and the batch is never consumed, and when the fire dies progress
 * drains slowly. A crucible inside is special-cased (band derived from the metal being melted; its
 * charge resolves to one molten metal, e.g. copper + tin -> bronze; pull it out before it melts and
 * the raw items are still inside, no loss). Research gating is applied in tickSmelting (not at
 * completion) so an unresearched output leaves the ore unconsumed and the bloomery simply idle.
 * completeSmelt rolls recipe.chance() per item (all-miss yields nothing); totalTicks gives a bulk
 * discount of half a base time per extra item. Constants: ANIM_TICKS 10 = 0.5s door anim,
 * SLIDE_TICKS 6 item slide-in, MAX_LIT_TICKS 600 = 30s burn, CRUCIBLE_MELT_TICKS 160.
 */
@ApiStatus.Internal
public class BloomeryBlockEntity extends BlockEntity {
    public static final int ANIM_TICKS = 10;
    public static final int SLIDE_TICKS = 6;
    public static final int MAX_LIT_TICKS = 600;
    public static final int CRUCIBLE_MELT_TICKS = 160;

    private boolean open = false;
    private int animTicks = 0;
    private ItemStack heldItem = ItemStack.EMPTY;
    private int insertAnimTicks = 0;
    private int litTicks = 0;
    private float smeltProgress = 0;
    private boolean smeltingActive = false;
    private float temperatureC = 0;
    private float bellowsBoost = 0;

    public BloomeryBlockEntity(BlockPos pos, BlockState state) {
        super(BannerboundAntiquity.BLOOMERY_BE.get(), pos, state);
    }

    public boolean isOpen() {
        return open;
    }

    public int getAnimTicks() {
        return animTicks;
    }

    public void toggle() {
        if (animTicks > 0) {
            return;
        }
        this.open = !this.open;
        this.animTicks = ANIM_TICKS;
        if (level != null) {
            level.playSound(null, getBlockPos(),
                open ? BannerboundAntiquity.BLOOMERY_OPEN_SOUND.get() : BannerboundAntiquity.BLOOMERY_CLOSE_SOUND.get(),
                SoundSource.BLOCKS, 1.0F, 1.0F);
        }
        setChanged();
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
        setChanged();
    }

    public ItemStack extract() {
        ItemStack out = heldItem;
        this.heldItem = ItemStack.EMPTY;
        this.insertAnimTicks = 0;
        this.smeltProgress = 0;
        this.smeltingActive = false;
        setChanged();
        return out;
    }

    public boolean isLit() {
        return litTicks > 0;
    }

    public int getLitTicks() {
        return litTicks;
    }

    public void ignite() {
        boolean wasLit = litTicks > 0;
        litTicks = MAX_LIT_TICKS;
        if (!wasLit && level != null) {
            level.playSound(null, getBlockPos(), SoundEvents.FLINTANDSTEEL_USE, SoundSource.BLOCKS, 1.0F, 1.0F);
        }
        spawnIgniteBurst();
        setChanged();
    }

    public void refuel() {
        if (litTicks > 0) {
            litTicks = MAX_LIT_TICKS;
            pumpBellows();
            spawnIgniteBurst();
            setChanged();
        }
    }

    public void pumpBellows() {
        if (litTicks <= 0) return;
        litTicks = MAX_LIT_TICKS;
        bellowsBoost = Math.min(BloomeryHeat.bellowsMax(), bellowsBoost + BloomeryHeat.bellowsPerPump());
        spawnIgniteBurst();
        setChanged();
    }

    public float temperatureC() {
        return temperatureC;
    }

    public float getSmeltProgress() {
        return smeltProgress;
    }

    public boolean isSmelting() {
        return smeltingActive;
    }

    private void spawnIgniteBurst() {
        if (level instanceof ServerLevel server) {
            server.sendParticles(ParticleTypes.FLAME,
                getBlockPos().getX() + 0.5, getBlockPos().getY() + 0.5, getBlockPos().getZ() + 0.5,
                18, 0.25, 0.35, 0.25, 0.02);
        }
    }

    public static void tick(Level level, BlockPos pos, BlockState state, BloomeryBlockEntity be) {
        if (be.animTicks > 0) {
            be.animTicks--;
        }
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

        if (!level.isClientSide) {
            float target = (be.litTicks > 0 && !be.open) ? BloomeryHeat.baseCeiling() + be.bellowsBoost : 0f;
            if (be.bellowsBoost > 0f) {
                be.bellowsBoost = Math.max(0f, be.bellowsBoost - BloomeryHeat.bellowsDecay());
            }
            float k = target > be.temperatureC ? BloomeryHeat.climb() : BloomeryHeat.fall();
            be.temperatureC += (target - be.temperatureC) * k;
            if (be.temperatureC < 0.5f) be.temperatureC = 0f;
            if ((be.litTicks > 0 || be.temperatureC > 0.5f) && level.getGameTime() % 5 == 0) {
                be.setChanged();
            }
        }

        if (level.isClientSide) {
            if (be.smeltingActive) {
                be.spawnDoorSmoke(level, pos, state);
            }
        } else {
            be.tickSmelting(level);
        }
    }

    private void tickSmelting(Level level) {
        if (heldItem.is(BannerboundAntiquity.CRUCIBLE.get())) {
            tickCrucibleMelt(level);
            return;
        }
        BloomeryRecipe recipe = heldItem.isEmpty() ? null : BloomeryRecipeManager.find(heldItem);
        if (recipe != null && !com.bannerbound.core.api.research.CraftGating.canProduceAt(
                level, getBlockPos(), recipe.result().getItem())) {
            recipe = null;
        }
        BloomeryHeat.Band band = recipe == null ? BloomeryHeat.Band.NONE
            : BloomeryHeat.classify(litTicks > 0, true, temperatureC, recipe.bandLow(), recipe.bandHigh());
        float rate = BloomeryHeat.rate(band);
        boolean active = recipe != null && !open && rate > 0f;
        if (active != smeltingActive) {
            smeltingActive = active;
            setChanged();
        }
        if (active) {
            smeltProgress += rate;
            if (smeltProgress >= totalTicks(recipe, heldItem.getCount())) {
                completeSmelt(level, recipe);
            } else if ((int) smeltProgress % 20 == 0) {
                setChanged();
            }
        } else if (litTicks <= 0 && smeltProgress > 0) {
            smeltProgress = Math.max(0, smeltProgress - 1);
            if ((int) smeltProgress % 20 == 0) {
                setChanged();
            }
        }
    }

    private void tickCrucibleMelt(Level level) {
        com.bannerbound.antiquity.item.CrucibleContents contents =
            heldItem.get(BannerboundAntiquity.CRUCIBLE_CONTENTS.get());
        com.bannerbound.antiquity.workshop.MetalworkingItems.MeltValue resolved =
            contents != null && contents.hasCharge() && !contents.molten()
                ? com.bannerbound.antiquity.workshop.MetalworkingItems.resolveCharge(contents.charge())
                : null;
        boolean canMelt = resolved != null;
        int[] band = canMelt
            ? BloomeryHeat.meltBand(resolved.metalId())
            : new int[] { 0, 0 };
        BloomeryHeat.Band verdict = canMelt
            ? BloomeryHeat.classify(litTicks > 0, true, temperatureC, band[0], band[1])
            : BloomeryHeat.Band.NONE;
        float rate = BloomeryHeat.rate(verdict);
        boolean active = canMelt && !open && rate > 0f;
        if (active != smeltingActive) {
            smeltingActive = active;
            setChanged();
        }
        if (active) {
            smeltProgress += rate;
            if (smeltProgress >= CRUCIBLE_MELT_TICKS) {
                int color = com.bannerbound.antiquity.workshop.MetalworkingItems.colorOf(resolved.metalId());
                heldItem.set(BannerboundAntiquity.CRUCIBLE_CONTENTS.get(),
                    com.bannerbound.antiquity.item.CrucibleContents.molten(
                        resolved.metalId(), resolved.mb(), color));
                smeltProgress = 0;
                smeltingActive = false;
                level.playSound(null, getBlockPos(), BannerboundAntiquity.SMELTING_DONE_SOUND.get(),
                    SoundSource.BLOCKS, 0.9F, 1.3F);
                setChanged();
            } else if ((int) smeltProgress % 20 == 0) {
                setChanged();
            }
        } else if (litTicks <= 0 && smeltProgress > 0) {
            smeltProgress = Math.max(0, smeltProgress - 1);
        }
    }

    private static int totalTicks(BloomeryRecipe recipe, int count) {
        return Math.round(recipe.ticks() * (1.0F + (count - 1) * 0.5F));
    }

    private void completeSmelt(Level level, BloomeryRecipe recipe) {
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
        setChanged();
    }

    private void spawnFireParticles(Level level, BlockPos pos) {
        float intensity = litTicks / (float) MAX_LIT_TICKS;
        float hotMax = BloomeryHeat.baseCeiling() + BloomeryHeat.bellowsMax();
        float heat = hotMax > 1f ? Math.min(1f, temperatureC / hotMax) : 0f;
        RandomSource rand = level.random;

        if (rand.nextFloat() < intensity) {
            level.addParticle(heat > 0.45f ? ParticleTypes.FLAME : ParticleTypes.SMALL_FLAME,
                pos.getX() + 0.3 + rand.nextDouble() * 0.4,
                pos.getY() + 0.35,
                pos.getZ() + 0.3 + rand.nextDouble() * 0.4,
                0.0, 0.01 + heat * 0.02, 0.0);
        }
        if (rand.nextFloat() < heat * 0.6f) {
            level.addParticle(ParticleTypes.LAVA,
                pos.getX() + 0.35 + rand.nextDouble() * 0.3, pos.getY() + 0.45,
                pos.getZ() + 0.35 + rand.nextDouble() * 0.3, 0.0, 0.0, 0.0);
        }
        if (rand.nextFloat() < 0.3f + intensity * 0.4f) {
            level.addParticle(heat > 0.55f ? ParticleTypes.LARGE_SMOKE : ParticleTypes.SMOKE,
                pos.getX() + 0.5 + (rand.nextDouble() - 0.5) * 0.2, pos.getY() + 1.9,
                pos.getZ() + 0.5 + (rand.nextDouble() - 0.5) * 0.2, 0.0, 0.03 + heat * 0.04, 0.0);
        }
    }

    private void spawnDoorSmoke(Level level, BlockPos pos, BlockState state) {
        if (level.random.nextInt(4) != 0) {
            return;
        }
        Direction facing = state.getValue(BloomeryBlock.FACING);
        RandomSource rand = level.random;
        double x = pos.getX() + 0.5 + facing.getStepX() * 0.45 + (rand.nextDouble() - 0.5) * 0.3;
        double z = pos.getZ() + 0.5 + facing.getStepZ() * 0.45 + (rand.nextDouble() - 0.5) * 0.3;
        level.addParticle(ParticleTypes.SMOKE, x, pos.getY() + 0.5, z, 0.0, 0.015, 0.0);
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
        tag.putBoolean("Open", open);
        tag.putInt("AnimTicks", animTicks);
        tag.putInt("InsertAnimTicks", insertAnimTicks);
        tag.putInt("LitTicks", litTicks);
        tag.putFloat("SmeltProgress", smeltProgress);
        tag.putBoolean("SmeltingActive", smeltingActive);
        tag.putFloat("TemperatureC", temperatureC);
        tag.putFloat("BellowsBoost", bellowsBoost);
        if (!heldItem.isEmpty()) {
            tag.put("HeldItem", heldItem.save(provider));
        }
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider provider) {
        super.loadAdditional(tag, provider);
        open = tag.getBoolean("Open");
        animTicks = tag.getInt("AnimTicks");
        insertAnimTicks = tag.getInt("InsertAnimTicks");
        litTicks = tag.getInt("LitTicks");
        smeltProgress = tag.getFloat("SmeltProgress");
        smeltingActive = tag.getBoolean("SmeltingActive");
        temperatureC = tag.getFloat("TemperatureC");
        bellowsBoost = tag.getFloat("BellowsBoost");
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
