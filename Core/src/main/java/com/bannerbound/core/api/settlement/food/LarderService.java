package com.bannerbound.core.api.settlement.food;

import java.util.ArrayList;
import java.util.List;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.api.research.ResearchManager;
import com.bannerbound.core.api.settlement.Settlement;
import com.bannerbound.core.api.settlement.data.FoodValueLoader;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import com.bannerbound.core.entity.DropOffContainers;
import net.minecraft.world.level.chunk.LevelChunk;
import net.neoforged.neoforge.capabilities.Capabilities;
import net.neoforged.neoforge.items.IItemHandler;
import net.neoforged.neoforge.items.IItemHandlerModifiable;
import net.neoforged.neoforge.items.wrapper.InvWrapper;

/**
 * Passive settlement larder (COOKING_PLAN.md). Valid food sitting in the settlement's claimed,
 * currently-loaded storage contributes a passive food/sec bonus: {@code storedFoodValue ×
 * Config.STORED_FOOD_RATE_PER_VALUE}. The food itself is <b>not consumed</b> by this scan — only the
 * spoilage system ({@link LarderHooks}) ever removes it, so "got enough cod for the day until it
 * spoils" just works without per-item management. Block-based food stores (cooking pots) count too.
 *
 * <p>Gated on Storage Logistics research: a settlement without it has no larder, so stored items stay
 * plain player food and contribute nothing. Called once/sec from {@code ImmigrationManager}.
 */
@ApiStatus.Internal
public final class LarderService {
    private LarderService() {}

    public static final String STORAGE_RESEARCH_FLAG = "bannerbound.unlock.stocker";

    /** Rescan claimed storage and set the settlement's stored food value + passive food/sec. */
    public static void refresh(ServerLevel level, Settlement s) {
        if (!ResearchManager.hasFlag(s, STORAGE_RESEARCH_FLAG)) {
            s.setStoredFoodValue(0.0);
            s.setStoredFoodPerSecond(0.0);
            return;
        }
        double valueTotal = 0.0;
        for (IItemHandler handler : storageHandlers(level, s)) {
            for (int slot = 0; slot < handler.getSlots(); slot++) {
                ItemStack raw = handler.getStackInSlot(slot);
                if (raw.isEmpty()) continue;
                // Cheap value lookup before any spoilage NBT work.
                double quick = Math.max(FoodValueLoader.effective(raw.getItem(), s),
                                        LarderHooks.extraValue(raw, level));
                if (quick <= 0.0) continue;
                ItemStack stack = processStorageStack(handler, slot, raw, level);
                if (stack.isEmpty() || !LarderHooks.counts(stack, level)) continue;
                double per = Math.max(FoodValueLoader.effective(stack.getItem(), s),
                                      LarderHooks.extraValue(stack, level));
                if (per <= 0.0) continue;
                // Per-stack value modifiers (e.g. bland food is worth half) ride on top of the base value.
                valueTotal += per * LarderHooks.valueMultiplier(stack, level) * stack.getCount();
            }
            // Re-merge stacks the per-slot stamping/spoiling fragmented (mainly spoiled_food).
            LarderHooks.normalize(handler, level);
        }
        // Block-based food stores (cooking pots) are part of the reserve too.
        for (LarderHooks.FoodStore store : LarderHooks.stores(level, s)) {
            valueTotal += Math.max(0.0, store.availableFoodValue());
        }
        s.setStoredFoodValue(valueTotal);
        s.setStoredFoodPerSecond(
            valueTotal * com.bannerbound.core.Config.STORED_FOOD_RATE_PER_VALUE.get());
    }

    private static ItemStack processStorageStack(IItemHandler handler, int slot, ItemStack stack, ServerLevel level) {
        boolean writable = handler instanceof IItemHandlerModifiable;
        ItemStack processed = LarderHooks.process(writable ? stack : stack.copy(), level);
        if (writable && processed != stack) {
            ((IItemHandlerModifiable) handler).setStackInSlot(slot, processed);
        }
        return processed;
    }

    /** Every container in the settlement's claimed, currently-loaded chunks, as item handlers. */
    private static List<IItemHandler> storageHandlers(ServerLevel level, Settlement s) {
        List<IItemHandler> out = new ArrayList<>();
        for (long packed : s.claimedChunks()) {
            ChunkPos cp = new ChunkPos(packed);
            LevelChunk chunk = level.getChunkSource().getChunkNow(cp.x, cp.z);
            if (chunk == null) continue;
            for (BlockPos pos : chunk.getBlockEntities().keySet()) {
                if (DropOffContainers.isSecondaryChestHalf(level, pos)) continue;
                IItemHandler h = level.getCapability(Capabilities.ItemHandler.BLOCK, pos, null);
                // Prefer a writable view so the spoilage stamp can persist; fall back to InvWrapper.
                if (!(h instanceof IItemHandlerModifiable) && level.getBlockEntity(pos) instanceof Container c) {
                    h = new InvWrapper(c);
                }
                if (h != null) out.add(h);
            }
        }
        return out;
    }
}
