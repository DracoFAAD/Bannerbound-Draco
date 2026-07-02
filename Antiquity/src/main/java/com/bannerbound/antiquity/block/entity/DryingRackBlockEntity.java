package com.bannerbound.antiquity.block.entity;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.antiquity.BannerboundAntiquity;
import com.bannerbound.antiquity.recipe.DryingRackRecipe;
import com.bannerbound.antiquity.recipe.DryingRackRecipeManager;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.particles.ParticleTypes;
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
 * Block entity for a Drying Rack — a line with up to {@value #SLOTS} hanging spots. Right-click with
 * a dryable item to hang it on the first free spot; right-click empty-handed to take the last hung
 * item back (the finished result if it's dry, the original if it isn't). Each spot dries
 * independently per its {@link DryingRackRecipe} (data-driven). The renderer cross-fades each spot
 * from its input to its result as it dries.
 *
 * <p>A "double" rack is purely cosmetic (two adjacent single racks rendered as one, chest-style):
 * each block keeps its own four spots and its own block entity.
 */
@ApiStatus.Internal
public class DryingRackBlockEntity extends BlockEntity {
    public static final int SLOTS = 4;

    /** The item hung on each spot (the original input — kept so an early pull returns it). */
    private final ItemStack[] inputs = new ItemStack[SLOTS];
    /** Drying countdown per spot (ticks remaining); 0 once dry. */
    private final int[] dryTicks = new int[SLOTS];
    /** Total drying time per spot (for the render progress fraction). */
    private final int[] dryTotal = new int[SLOTS];

    public DryingRackBlockEntity(BlockPos pos, BlockState state) {
        super(BannerboundAntiquity.DRYING_RACK_BE.get(), pos, state);
        java.util.Arrays.fill(inputs, ItemStack.EMPTY);
    }

    // ─── Queries (renderer + interaction) ──────────────────────────────────────────────────────

    public boolean isEmpty() {
        for (ItemStack s : inputs) if (!s.isEmpty()) return false;
        return true;
    }

    public boolean isFull() {
        return firstFreeSlot() < 0;
    }

    /** The raw item hung on a spot (empty if the spot is free), for the renderer's "before" decal. */
    public ItemStack input(int slot) {
        return inputs[slot];
    }

    /** The dried result for a spot (the recipe output), or empty if the spot is free / has no recipe. */
    public ItemStack result(int slot) {
        if (inputs[slot].isEmpty()) return ItemStack.EMPTY;
        DryingRackRecipe recipe = DryingRackRecipeManager.find(inputs[slot].getItem());
        return recipe == null ? ItemStack.EMPTY : recipe.result();
    }

    /** Drying progress 0..1 for a spot (0 = just hung, 1 = dry) — drives the render cross-fade. */
    public float progress(int slot) {
        if (inputs[slot].isEmpty()) return 0.0F;
        if (dryTotal[slot] <= 0) return 1.0F;
        return 1.0F - dryTicks[slot] / (float) dryTotal[slot];
    }

    public boolean isDry(int slot) {
        return !inputs[slot].isEmpty() && dryTicks[slot] <= 0;
    }

    private int firstFreeSlot() {
        for (int i = 0; i < SLOTS; i++) if (inputs[i].isEmpty()) return i;
        return -1;
    }

    private int lastUsedSlot() {
        for (int i = SLOTS - 1; i >= 0; i--) if (!inputs[i].isEmpty()) return i;
        return -1;
    }

    // ─── Interaction ─────────────────────────────────────────────────────────────────────────────

    /** True if {@code stack} can be hung here (has a drying recipe and there's a free spot). */
    public boolean canAccept(ItemStack stack) {
        return !stack.isEmpty() && !isFull() && DryingRackRecipeManager.find(stack.getItem()) != null;
    }

    /** Hang one of {@code stack} on the first free spot. Returns false if it can't be dried / no room. */
    public boolean hang(ItemStack stack) {
        DryingRackRecipe recipe = DryingRackRecipeManager.find(stack.getItem());
        if (recipe == null) return false;
        int slot = firstFreeSlot();
        if (slot < 0) return false;
        inputs[slot] = stack.copyWithCount(1);
        dryTotal[slot] = Math.max(1, recipe.ticks());
        dryTicks[slot] = dryTotal[slot];
        setChanged();
        return true;
    }

    /** Take the most-recently-hung spot's contents — the dried result if it's finished, else the
     *  original item back. Returns empty if the rack is bare. */
    public ItemStack takeLast() {
        int slot = lastUsedSlot();
        if (slot < 0) return ItemStack.EMPTY;
        return takeSlot(slot);
    }

    /** Take a SPECIFIC spot's contents (the dried result if finished, else the input back) — the
     *  NPC rack-tending path, which collects the exact slot it found dry rather than the last hung. */
    public ItemStack takeSlot(int slot) {
        if (slot < 0 || slot >= SLOTS || inputs[slot].isEmpty()) return ItemStack.EMPTY;
        ItemStack out = isDry(slot) ? result(slot).copy() : inputs[slot].copy();
        inputs[slot] = ItemStack.EMPTY;
        dryTicks[slot] = 0;
        dryTotal[slot] = 0;
        setChanged();
        return out;
    }

    /** The first spot that finished drying and whose recipe passes {@code filter}, or {@code -1} —
     *  lets each NPC line collect only its own category (food vs craft). */
    public int firstDrySlot(java.util.function.Predicate<DryingRackRecipe> filter) {
        for (int i = 0; i < SLOTS; i++) {
            if (!isDry(i)) continue;
            DryingRackRecipe recipe = DryingRackRecipeManager.find(inputs[i].getItem());
            if (recipe != null && filter.test(recipe)) return i;
        }
        return -1;
    }

    /** Everything currently hung (results if dry, inputs otherwise) — for block-break drops. */
    public java.util.List<ItemStack> dropContents() {
        java.util.List<ItemStack> out = new java.util.ArrayList<>();
        for (int i = 0; i < SLOTS; i++) {
            if (inputs[i].isEmpty()) continue;
            out.add(isDry(i) ? result(i).copy() : inputs[i].copy());
        }
        return out;
    }

    // ─── Ticking (drying timers + ambient particles) ───────────────────────────────────────────────

    public static void tick(Level level, BlockPos pos, BlockState state, DryingRackBlockEntity be) {
        boolean anyDrying = false;
        boolean finishedNow = false;
        for (int i = 0; i < SLOTS; i++) {
            if (be.inputs[i].isEmpty() || be.dryTicks[i] <= 0) continue;
            anyDrying = true;
            // Both sides count down so the client fade stays smooth without per-tick sync; the server
            // is authoritative for the dry transition (and re-syncs when a spot finishes).
            be.dryTicks[i]--;
            if (be.dryTicks[i] <= 0 && !level.isClientSide) finishedNow = true;
        }
        if (level.isClientSide) {
            if (anyDrying && level.random.nextInt(6) == 0) {
                level.addParticle(ParticleTypes.SMOKE,
                    pos.getX() + 0.25 + level.random.nextDouble() * 0.5,
                    pos.getY() + 0.85 + level.random.nextDouble() * 0.35,
                    pos.getZ() + 0.4 + level.random.nextDouble() * 0.2,
                    0.0, 0.012, 0.0);
            }
        } else if (finishedNow) {
            be.setChanged();
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
        // Always write all four spots (an empty spot = an empty compound) so the update tag is NEVER
        // empty — the client skips empty block-entity tags, which would otherwise leave the last item
        // rendered after it's pulled off (the cleared rack would send {} and never update).
        net.minecraft.nbt.ListTag list = new net.minecraft.nbt.ListTag();
        for (int i = 0; i < SLOTS; i++) {
            CompoundTag slot = new CompoundTag();
            if (!inputs[i].isEmpty()) {
                slot.put("Item", inputs[i].save(provider));
                slot.putInt("T", dryTicks[i]);
                slot.putInt("Total", dryTotal[i]);
            }
            list.add(slot);
        }
        tag.put("Slots", list);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider provider) {
        super.loadAdditional(tag, provider);
        net.minecraft.nbt.ListTag list = tag.getList("Slots", net.minecraft.nbt.Tag.TAG_COMPOUND);
        for (int i = 0; i < SLOTS; i++) {
            CompoundTag slot = i < list.size() ? list.getCompound(i) : new CompoundTag();
            if (slot.contains("Item")) {
                inputs[i] = ItemStack.parse(provider, slot.getCompound("Item")).orElse(ItemStack.EMPTY);
                dryTicks[i] = slot.getInt("T");
                dryTotal[i] = slot.getInt("Total");
            } else {
                inputs[i] = ItemStack.EMPTY;
                dryTicks[i] = 0;
                dryTotal[i] = 0;
            }
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
