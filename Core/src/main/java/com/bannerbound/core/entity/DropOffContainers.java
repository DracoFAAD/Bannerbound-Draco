package com.bannerbound.core.entity;

import java.util.ArrayList;
import java.util.List;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

import com.bannerbound.core.BannerboundCore;
import com.bannerbound.core.api.settlement.Settlement;
import com.bannerbound.core.api.settlement.SettlementData;
import com.bannerbound.core.api.settlement.Stockpile;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.Container;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.ChestBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.ChestType;

/**
 * Generic storage-container access for forester drop-offs. The forester used to route its yield
 * into a workstation block entity; now the player marks a vanilla chest or a Bannerbound Antiquity
 * basket as the drop-off, and the forester inserts into whatever {@link Container} backs it.
 * <p>
 * The Core mod has <b>no</b> compile dependency on the Antiquity mod, so the basket is matched by
 * registry id ({@code bannerboundantiquity:basket}) rather than {@code instanceof}; both the chest
 * and the basket expose a vanilla {@link Container} via their block entity, which is all the insert
 * / room math needs. Double chests are resolved to their combined container so a forester can fill
 * both halves.
 */
@ApiStatus.Internal
public final class DropOffContainers {
    /** Registry id of the Antiquity storage basket — a valid drop-off target. Matched by id to
     *  avoid a cross-mod class dependency. */
    private static final ResourceLocation BASKET_ID =
        ResourceLocation.fromNamespaceAndPath("bannerboundantiquity", "basket");

    private DropOffContainers() {
    }

    /**
     * Returns the {@link Container} for a valid drop-off block at {@code pos} (a chest or an
     * Antiquity basket), or {@code null} if the block isn't a valid storage block, the chunk is
     * unloaded, or no container backs it. Double chests resolve to the combined container.
     */
    @Nullable
    public static Container resolveDropOff(Level level, @Nullable BlockPos pos) {
        if (pos == null) return null;
        BlockState state = level.getBlockState(pos);
        // A Stockpile block fans out to every container its enclosure scan found — one aggregate
        // inventory, so a worker assigned here fills the whole stockpile (no Stocker needed).
        if (state.is(BannerboundCore.STOCKPILE.get()) && level instanceof ServerLevel sl) {
            return resolveStockpile(sl, pos);
        }
        if (state.getBlock() instanceof ChestBlock chestBlock) {
            // Combined container for double chests; single for a lone chest. null when blocked
            // above (chest can't open) — treat that as no drop-off this tick.
            return ChestBlock.getContainer(chestBlock, state, level, pos, true);
        }
        ResourceLocation id = BuiltInRegistries.BLOCK.getKey(state.getBlock());
        if (BASKET_ID.equals(id)) {
            BlockEntity be = level.getBlockEntity(pos);
            if (be instanceof Container c) return c;
        }
        return null;
    }

    /**
     * The depot a citizen's job should deposit into: its marked drop-off container if that resolves,
     * otherwise — for an anarchy citizen whose drop-off is the town-hall carry-home sentinel — the
     * citizen's {@link CitizenEntity#getAnarchyHaul() carry pack} (a 64-item bundle). Deposits then
     * fill the pack until it's full, overflow spills at the work site (the goal's existing leftover
     * handling), and the worker physically hauls the pack to the town hall and dumps it (see
     * {@code DeliverHaulGoal}) — no teleporting. Null when there's neither a real container nor the
     * carry-home sentinel. Used by the self-directed gatherers in place of {@link #resolveDropOff}.
     */
    @Nullable
    public static Container resolveJobDepot(CitizenEntity citizen) {
        // Own marked drop-off (outpost auto-wired, or the anarchy sink) → the settlement deposit pool.
        Container c = resolveOrPreferred(citizen, citizen.getDropOff());
        if (c != null) return c;
        if (citizen.isAnarchyHaulDropOff()) return citizen.getAnarchyHaul();
        return null;
    }

    /**
     * Resolve a citizen's marked DEPOSIT storage at {@code pos}, falling back to the settlement
     * {@linkplain SettlementStorage storage pool} (nearest deposit-open container) when nothing is
     * marked there (or it doesn't resolve). A marked pos survives only for the contexts that still
     * set one — an outpost worker auto-wired to its in-chunk chest, the anarchy town-hall sink — so
     * ordinary settlement workers, who no longer mark anything, always land on the pool. {@code null}
     * when neither the marked storage nor any deposit-open pooled container resolves (and {@code null}
     * in anarchy, where the pool is government-only).
     */
    @Nullable
    public static Container resolveOrPreferred(CitizenEntity citizen, @Nullable BlockPos pos) {
        Container real = resolveDropOff(citizen.level(), pos);
        if (real != null) return real;
        return depotPool(citizen);
    }

    /**
     * The TAKE counterpart of {@link #resolveOrPreferred}: a citizen's marked source at {@code pos}
     * (only outpost workers still set one), else the settlement {@linkplain SettlementStorage storage
     * pool}'s nearest TAKE-open container. Used to pull seeds, tools and breeding food without
     * per-worker marking. {@code null} in anarchy / when nothing open holds anything.
     */
    @Nullable
    public static Container resolveSupply(CitizenEntity citizen, @Nullable BlockPos pos) {
        Container real = resolveDropOff(citizen.level(), pos);
        if (real != null) return real;
        return supplyPool(citizen);
    }

    /** The settlement deposit pool for {@code citizen} (nearest deposit-open container with room
     *  first), or {@code null} in anarchy / when no deposit-open storage exists. */
    @Nullable
    public static Container depotPool(CitizenEntity citizen) {
        Settlement s = poolSettlement(citizen);
        if (s == null || !(citizen.level() instanceof ServerLevel sl)) return null;
        return SettlementStorage.depotAggregate(sl, s, citizen.blockPosition());
    }

    /** The settlement supply pool for {@code citizen} (nearest take-open container holding the item
     *  first), or {@code null} in anarchy / when no take-open storage exists. */
    @Nullable
    public static Container supplyPool(CitizenEntity citizen) {
        Settlement s = poolSettlement(citizen);
        if (s == null || !(citizen.level() instanceof ServerLevel sl)) return null;
        return SettlementStorage.supplyAggregate(sl, s, citizen.blockPosition());
    }

    /** The citizen's settlement, or {@code null} when there's no pool to consult (no settlement,
     *  client side). The pool works in anarchy too — a tribe's open baskets/stockpiles are used the
     *  same way; only when the pool is empty does an anarchy gatherer fall back to the town-hall
     *  carry pack (see {@link #resolveJobDepot}). */
    @Nullable
    private static Settlement poolSettlement(CitizenEntity citizen) {
        if (citizen.getSettlementId() == null
                || !(citizen.level() instanceof ServerLevel sl)) return null;
        MinecraftServer server = sl.getServer();
        if (server == null) return null;
        return SettlementData.get(server.overworld()).getById(citizen.getSettlementId());
    }

    /** @deprecated The per-settlement preferred-storage depot is subsumed by the
     *  {@linkplain SettlementStorage storage pool} (a set preferred container is itself a pooled
     *  member). Retained for the few legacy readers (ghost-stocker road planning); new code should
     *  use {@link #depotPool}/{@link #supplyPool}. */
    @Deprecated
    @Nullable
    public static Container resolvePreferredStorage(CitizenEntity citizen) {
        return supplyPool(citizen);
    }

    /** Builds the aggregate inventory for a Stockpile block: every valid enclosed container the
     *  record currently holds, fanned into one {@link Container}. Returns a (possibly empty) aggregate
     *  whenever the block has a registered stockpile — so it's always a markable drop-off target, and
     *  workers simply wait when it has no room. Null only when no settlement/record owns the pos. */
    @Nullable
    private static Container resolveStockpile(ServerLevel level, BlockPos pos) {
        MinecraftServer server = level.getServer();
        if (server == null) return null;
        Settlement owner = SettlementData.get(server.overworld())
            .getByChunk(new ChunkPos(pos).toLong());
        if (owner == null) return null;
        Stockpile sp = owner.getStockpile(pos);
        if (sp == null) return null;
        List<Container> parts = new ArrayList<>();
        if (sp.valid()) {
            for (BlockPos cpos : sp.containers()) {
                if (isSecondaryChestHalf(level, cpos)) continue;
                Container c = resolveContainerAt(level, cpos);
                if (c != null) parts.add(c);
            }
        }
        return new AggregateContainer(parts);
    }

    /** True when {@code pos} is the non-canonical half of a connected double chest. Both halves
     *  resolve to the same combined {@link Container}, so anything aggregating or counting over
     *  container positions must skip this half — the other half already contributes the shared
     *  inventory once. */
    public static boolean isSecondaryChestHalf(Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (!(state.getBlock() instanceof ChestBlock)) return false;
        if (state.getValue(ChestBlock.TYPE) == ChestType.SINGLE) return false;
        return pos.relative(ChestBlock.getConnectedDirection(state)).asLong() < pos.asLong();
    }

    /** Resolves a single container block to its {@link Container} — double-chest aware, and accepts
     *  any block entity that is a {@code Container} (barrels, Antiquity baskets, modded blocks). */
    @Nullable
    private static Container resolveContainerAt(Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (state.getBlock() instanceof ChestBlock chestBlock) {
            return ChestBlock.getContainer(chestBlock, state, level, pos, true);
        }
        BlockEntity be = level.getBlockEntity(pos);
        return be instanceof Container c ? c : null;
    }

    /**
     * True when the container at {@code pos} is WILD storage that AUTOMATIC detection must skip —
     * the stocker's loose-source scan and the settlement item census, NOT explicitly marked
     * storage (drop-offs, stockpiles, workshop storage — the player pointed at those, they always
     * count). Wild means:
     * <ul>
     *   <li>an unopened loot container (pending {@code LootTable}) — dungeon/mineshaft chests
     *       waiting for a player, or</li>
     *   <li>buried deep below the surface — a mineshaft chest under a claimed chunk is not the
     *       settlement's pantry. The threshold leaves room for player cellars: anything within
     *       {@value #CELLAR_DEPTH} blocks of the surface heightmap still counts.</li>
     * </ul>
     */
    public static boolean isWildStorage(ServerLevel sl, BlockPos pos) {
        BlockEntity be = sl.getBlockEntity(pos);
        if (be instanceof net.minecraft.world.level.block.entity.RandomizableContainerBlockEntity r
                && r.getLootTable() != null) {
            return true;
        }
        int surface = sl.getHeight(net.minecraft.world.level.levelgen.Heightmap.Types.MOTION_BLOCKING,
            pos.getX(), pos.getZ());
        return pos.getY() < surface - CELLAR_DEPTH;
    }

    /** How far below the surface a container may sit and still count as the settlement's own
     *  storage (cellars / basements). Deeper = generated structure territory. */
    public static final int CELLAR_DEPTH = 12;

    /** True if the block at {@code pos} is a valid drop-off (chest, Antiquity basket, or Stockpile).
     *  Cheap state-only check usable client-side; doesn't require the container to be openable. */
    public static boolean isDropOffBlock(Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (state.is(BannerboundCore.STOCKPILE.get())) return true;
        if (state.getBlock() instanceof ChestBlock) return true;
        return BASKET_ID.equals(BuiltInRegistries.BLOCK.getKey(state.getBlock()));
    }

    /**
     * Withdraws up to {@code maxCount} of {@code item} from {@code container} (as much as it
     * holds, possibly less than asked — the Stocker hauls what it finds). Returns the extracted
     * stack, {@link ItemStack#EMPTY} when the container holds none. Marks the container changed
     * when anything moved.
     */
    public static ItemStack extract(Container container, net.minecraft.world.item.Item item, int maxCount) {
        int taken = 0;
        for (int i = 0; i < container.getContainerSize() && taken < maxCount; i++) {
            ItemStack slot = container.getItem(i);
            if (slot.isEmpty() || !slot.is(item)) continue;
            int move = Math.min(maxCount - taken, slot.getCount());
            slot.shrink(move);
            if (slot.isEmpty()) container.setItem(i, ItemStack.EMPTY);
            taken += move;
        }
        if (taken == 0) return ItemStack.EMPTY;
        container.setChanged();
        return new ItemStack(item, taken);
    }

    /**
     * Inserts {@code stack} into {@code container}, merging into matching stacks first and then
     * filling empty slots, honoring each slot's {@link Container#canPlaceItem} and max stack size.
     * Returns whatever didn't fit (empty when fully absorbed). Marks the container changed when
     * anything moved.
     */
    public static ItemStack insert(Container container, ItemStack stack) {
        if (stack.isEmpty()) return stack;
        ItemStack remaining = stack.copy();
        boolean changed = false;
        int size = container.getContainerSize();
        // Pass 1: merge into matching stacks.
        for (int i = 0; i < size && !remaining.isEmpty(); i++) {
            ItemStack slot = container.getItem(i);
            if (slot.isEmpty()) continue;
            if (!ItemStack.isSameItemSameComponents(slot, remaining)) continue;
            int cap = Math.min(container.getMaxStackSize(), slot.getMaxStackSize());
            int room = cap - slot.getCount();
            if (room <= 0) continue;
            int move = Math.min(room, remaining.getCount());
            slot.grow(move);
            remaining.shrink(move);
            changed = true;
        }
        // Pass 2: fill empty slots.
        for (int i = 0; i < size && !remaining.isEmpty(); i++) {
            if (!container.getItem(i).isEmpty()) continue;
            if (!container.canPlaceItem(i, remaining)) continue;
            int cap = Math.min(container.getMaxStackSize(), remaining.getMaxStackSize());
            int move = Math.min(cap, remaining.getCount());
            ItemStack placed = remaining.copy();
            placed.setCount(move);
            container.setItem(i, placed);
            remaining.shrink(move);
            changed = true;
        }
        if (changed) container.setChanged();
        return remaining;
    }

    /**
     * Removes and returns ONE item of {@code item} from the container (a single unit, count 1), or
     * {@link ItemStack#EMPTY} if none is present. Used by the farmer to pull a seed to plant from its
     * marked seed source.
     */
    public static ItemStack extractOne(Container container, net.minecraft.world.item.Item item) {
        int size = container.getContainerSize();
        for (int i = 0; i < size; i++) {
            ItemStack slot = container.getItem(i);
            if (!slot.isEmpty() && slot.is(item)) {
                ItemStack one = slot.copy();
                one.setCount(1);
                slot.shrink(1);
                container.setItem(i, slot.isEmpty() ? ItemStack.EMPTY : slot);
                container.setChanged();
                return one;
            }
        }
        return ItemStack.EMPTY;
    }

    /** True if the container holds at least one of {@code item}. */
    public static boolean contains(Container container, net.minecraft.world.item.Item item) {
        int size = container.getContainerSize();
        for (int i = 0; i < size; i++) {
            ItemStack slot = container.getItem(i);
            if (!slot.isEmpty() && slot.is(item)) return true;
        }
        return false;
    }

    /**
     * Total room across all slots for an item matching {@code template} — empty slots contribute a
     * full (clamped) stack, slots already holding a matching stack contribute their leftover. Slots
     * holding any other item contribute zero. Used by the forester to gate a chop on whether the
     * tree's logs will fully fit before committing.
     */
    /** True if {@code container} has at least one empty slot — i.e. guaranteed room for one of ANY
     *  item. Used by workers whose yield is unknown ahead of time (a fisher's catch, a forager's
     *  drop): they can't ask {@link #roomFor} for a specific item, so "is there a free slot at all?"
     *  is the safe gate to keep working / harvest. */
    public static boolean hasFreeSlot(Container container) {
        int size = container.getContainerSize();
        for (int i = 0; i < size; i++) {
            if (container.getItem(i).isEmpty()) return true;
        }
        return false;
    }

    public static int roomFor(Container container, ItemStack template) {
        if (template.isEmpty()) return 0;
        int cap = Math.min(container.getMaxStackSize(), template.getMaxStackSize());
        int room = 0;
        int size = container.getContainerSize();
        for (int i = 0; i < size; i++) {
            ItemStack slot = container.getItem(i);
            if (slot.isEmpty()) {
                if (container.canPlaceItem(i, template)) room += cap;
            } else if (ItemStack.isSameItemSameComponents(slot, template)) {
                room += Math.max(0, cap - slot.getCount());
            }
        }
        return room;
    }
}
