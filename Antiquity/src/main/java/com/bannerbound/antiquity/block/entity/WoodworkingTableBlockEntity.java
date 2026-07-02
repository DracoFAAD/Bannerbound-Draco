package com.bannerbound.antiquity.block.entity;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.antiquity.BannerboundAntiquity;
import com.bannerbound.antiquity.carpentry.CarpentryAssembly;
import com.bannerbound.antiquity.carpentry.CarpentryAssemblyManager;
import com.bannerbound.antiquity.carpentry.CarpentryOutput;
import com.bannerbound.antiquity.carpentry.CarpentryOutputManager;
import com.bannerbound.antiquity.carpentry.Cost;
import com.bannerbound.antiquity.carpentry.WoodFamily;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Block entity for the Carpenter's Table — the batch-woodworking workstation. The deposited logs are
 * a real, removable <b>pile</b> (the same idiom as the Crafting Stone's {@code contents}: placed one
 * at a time, taken back one at a time, rendered in a grid), which doubles as the wood "budget". On
 * top of that it holds an ordered <b>build list</b> of queued outputs. The picker reuses the
 * ghost-preview infrastructure ({@link GhostRecipeWorkstation}): the floating "ghost result" is the
 * currently-selected affordable output and the browse arrows cycle offers; clicking the result
 * (a {@code GhostActionPayload.FILL} special-case for this BE) appends one unit to the list. Nothing
 * is consumed until the saw minigame completes — it then turns the list into items and removes only
 * the logs it cost, leaving any leftover pile on the table.
 */
@ApiStatus.Internal
public class WoodworkingTableBlockEntity extends BlockEntity implements GhostRecipeWorkstation {
    /** Total logs the table can hold/budget at once (the user's "64 oak logs (the max)"). */
    public static final int MAX_BUDGET = 64;
    /** Ticks the just-placed log's slide-in animation runs (matches the crafting stone). */
    public static final int SLIDE_TICKS = 6;

    /** One queued output: how many recipe-yields of {@code output}, with the per-unit input
     *  {@code costs} and yield captured at queue time so the saw step is self-contained. A family
     *  variant carries one {@code FAMILY} cost (its logs); an assembly recipe carries its
     *  {@code TAG}/{@code ITEM} costs (planks, sticks). */
    public record ListEntry(Item output, int units, int yieldPerUnit, List<Cost> costs) {}

    /** A resolved, affordable picker offer (transient — recomputed, never persisted). */
    private record Offer(Item output, int yield, List<Cost> costs) {}

    /** The deposited materials — one stack (= one grid cell) per distinct item; multiples pile up in
     *  that cell. Holds logs (per wood family), planks and sticks. This IS the budget. */
    private final List<ItemStack> logs = new ArrayList<>();
    /** Queued outputs, in the order the player added them. */
    private final List<ListEntry> buildList = new ArrayList<>();

    // Sticky picker selection — kept across recomputes so landing materials don't yank the choice.
    // Offer output items are unique across families + assembly recipes, so the item alone identifies it.
    private Item selectedOutput = null;

    // Synced ghost fields (computed server-side; the client just renders these).
    private ItemStack ghostResult = ItemStack.EMPTY;
    private int offerCount = 0;

    // Slide-in animation for the most-recently-touched pile cell (matches the crafting stone).
    private int insertAnimTicks = 0;
    private Direction insertDir = Direction.NORTH;
    private int lastSlideCell = -1;
    /** Set on load — the ghost offers are recomputed once on the first server tick (when {@code level}
     *  is available), so the picker refreshes after a chunk reload and migrates pre-pile saves. */
    private boolean needsRecompute = false;

    public WoodworkingTableBlockEntity(BlockPos pos, BlockState state) {
        super(BannerboundAntiquity.WOODWORKING_TABLE_BE.get(), pos, state);
    }

    // ── Accessors for the renderer ────────────────────────────────────────────────────────────

    /** The deposited log pile (read-only view for the renderer's grid). */
    public List<ItemStack> getLogs() {
        return logs;
    }

    /** The queued outputs (read-only view for the renderer's floating list). */
    public List<ListEntry> getBuildList() {
        return buildList;
    }

    public boolean hasBuildList() {
        return !buildList.isEmpty();
    }

    public int getInsertAnimTicks() {
        return insertAnimTicks;
    }

    public Direction getInsertDir() {
        return insertDir;
    }

    public int getLastSlideCell() {
        return lastSlideCell;
    }

    /** A representative log item for the saw animation: the first log cell (falling back to the first
     *  pile cell, e.g. a planks-only budget). */
    public ItemStack representativeBudgetLog() {
        for (ItemStack s : logs) {
            if (WoodFamily.isBudgetLog(s)) return new ItemStack(s.getItem());
        }
        return logs.isEmpty() ? ItemStack.EMPTY : new ItemStack(logs.get(0).getItem());
    }

    // ── GhostRecipeWorkstation (picker rendering + ray-pick reuse) ─────────────────────────────

    @Override
    public ItemStack getResult() {
        return ItemStack.EMPTY; // the picker IS the ghost; there's never a separate solid result
    }

    @Override
    public ItemStack getGhostResult() {
        return ghostResult;
    }

    @Override
    public List<ItemStack> getGhostIngredients() {
        return List.of(); // carpentry has no missing-ingredient silhouettes
    }

    @Override
    public int getGhostCandidateCount() {
        return offerCount;
    }

    @Override
    public double ghostPreviewY() {
        return 1.62; // compact in-world picker band just above the tabletop; see the readout renderer
    }

    /** Net uncommitted budget per category — {@code [LOG, PLANK, STICK]} — for the tabletop's
     *  per-type readout (deposited materials of each pool minus what the build list reserves). */
    public int[] remainingByCategory() {
        int[] net = new int[3];
        for (ItemStack s : logs) {
            int idx = catIndex(Cost.categoryOf(s));
            if (idx >= 0) net[idx] += s.getCount();
        }
        for (ListEntry e : buildList) {
            for (Cost c : e.costs()) {
                int idx = catIndex(c.category());
                if (idx >= 0) net[idx] -= e.units() * c.perUnit();
            }
        }
        for (int i = 0; i < net.length; i++) net[i] = Math.max(0, net[i]);
        return net;
    }

    /** Maps a budget category to its {@link #remainingByCategory()} slot ({@code OTHER} → -1). */
    private static int catIndex(Cost.Category cat) {
        return switch (cat) {
            case LOG -> 0;
            case PLANK -> 1;
            case STICK -> 2;
            case OTHER -> -1;
        };
    }

    @Override
    public void cycleGhost(int dir) {
        List<Offer> offers = computeOffers();
        if (offers.size() < 2) return;
        int idx = Math.floorMod(Math.max(0, indexOfSelection(offers)) + dir, offers.size());
        applySelection(offers.get(idx));
        recompute();
    }

    @Override
    public void lockGhost() {
        // Carpentry's selection is always sticky — nothing to lock.
    }

    // ── Pile mutation (server-side) — mirrors CraftingStoneBlockEntity ─────────────────────────

    /** Adds ONE material to the pile (merging into an existing same-item cell). Returns true if it
     *  fit. Accepts logs (any {@link WoodFamily}) plus any ingredient of a loaded assembly recipe
     *  (planks, sticks). Server-side only. */
    @Override
    public boolean insertOne(ItemStack held, Direction from) {
        if (!isBudgetMaterial(held) || totalLogs() >= MAX_BUDGET) return false;
        insertAnimTicks = SLIDE_TICKS;
        insertDir = from == null ? Direction.NORTH : from;
        for (int i = 0; i < logs.size(); i++) {
            ItemStack s = logs.get(i);
            if (ItemStack.isSameItemSameComponents(s, held)) {
                s.grow(1);
                lastSlideCell = i;
                recompute();
                return true;
            }
        }
        logs.add(held.copyWithCount(1));
        lastSlideCell = logs.size() - 1;
        recompute();
        return true;
    }

    /** True if {@code stack} can be deposited as budget material: a log (any wood family) or an
     *  ingredient of any loaded assembly recipe (planks, sticks). */
    public static boolean isBudgetMaterial(ItemStack stack) {
        return WoodFamily.isBudgetLog(stack) || CarpentryAssemblyManager.isIngredient(stack);
    }

    /** Deposits as much of {@code stack} as fits (whole-stack convenience, sneak-place). Returns how
     *  many materials were deposited. Server-side only. */
    public int insertStack(ItemStack stack, Direction from) {
        int added = 0;
        while (added < stack.getCount() && insertOne(stack, from)) added++;
        return added;
    }

    /** Removes ONE material from the most-recently-touched cell and returns it. Materials already
     *  reserved by the build list can't be pulled out — returns EMPTY then. Server-side only. */
    public ItemStack removeOne() {
        if (logs.isEmpty()) return ItemStack.EMPTY;
        // Walk from the most-recent cell back, skipping anything fully reserved by the build list.
        for (int i = logs.size() - 1; i >= 0; i--) {
            ItemStack cell = logs.get(i);
            if (!isRemovable(cell)) continue;
            ItemStack out = cell.copyWithCount(1);
            cell.shrink(1);
            if (cell.isEmpty()) logs.remove(i);
            lastSlideCell = Math.min(lastSlideCell, logs.size() - 1);
            recompute();
            return out;
        }
        return ItemStack.EMPTY;
    }

    // ── Build-list mutation (server-side) ──────────────────────────────────────────────────────

    /** Appends one unit of the current picker selection to the build list. Returns true if it fit
     *  (the selection exists and is still affordable from the remaining budget). */
    public boolean addSelected() {
        List<Offer> offers = computeOffers();
        int idx = indexOfSelection(offers);
        if (idx < 0) {
            if (offers.isEmpty()) return false;
            idx = 0;
        }
        Offer offer = offers.get(idx);
        if (!affordable(offer.costs())) return false;
        for (int i = 0; i < buildList.size(); i++) {
            ListEntry e = buildList.get(i);
            if (e.output() == offer.output() && e.costs().equals(offer.costs())) {
                buildList.set(i, new ListEntry(e.output(), e.units() + 1, e.yieldPerUnit(), e.costs()));
                applySelection(offer);
                recompute();
                return true;
            }
        }
        buildList.add(new ListEntry(offer.output(), 1, offer.yield(), offer.costs()));
        applySelection(offer);
        recompute();
        return true;
    }

    /** True if one more unit of every cost still fits the uncommitted budget. */
    private boolean affordable(List<Cost> costs) {
        for (Cost c : costs) {
            if (c.perUnit() > remaining(c)) return false;
        }
        return true;
    }

    /** Removes the whole queued entry at {@code index} (right-clicking that queue item in-world). The
     *  budget it reserved frees up implicitly. Returns true if anything was removed. */
    public boolean removeEntryAt(int index) {
        if (index < 0 || index >= buildList.size()) return false;
        buildList.remove(index);
        recompute();
        return true;
    }

    /** Removes one unit of the most-recently-added build-list entry (the budget it reserved frees up
     *  implicitly — "remaining" is derived). Returns true if anything was removed. */
    public boolean removeLastEntry() {
        if (buildList.isEmpty()) return false;
        int last = buildList.size() - 1;
        ListEntry e = buildList.get(last);
        if (e.units() <= 1) {
            buildList.remove(last);
        } else {
            buildList.set(last, new ListEntry(e.output(), e.units() - 1, e.yieldPerUnit(), e.costs()));
        }
        recompute();
        return true;
    }

    /** Saw minigame finished: pops every queued output above the table and removes only the materials
     *  the list cost from the pile — any uncommitted materials stay on the table for next time. */
    public void completeAndOutput(ServerLevel level) {
        BlockPos above = getBlockPos().above();
        for (ListEntry e : buildList) {
            int total = e.units() * e.yieldPerUnit();
            while (total > 0) {
                int n = Math.min(total, e.output().getDefaultMaxStackSize());
                Block.popResource(level, above, new ItemStack(e.output(), n));
                total -= n;
            }
        }
        // Consume each committed cost out of the pile (logs, planks, sticks).
        for (ListEntry e : buildList) {
            for (Cost c : e.costs()) {
                removePileMatching(c, e.units() * c.perUnit());
            }
        }
        buildList.clear();
        recompute();
    }

    /** Drops the deposited materials (real items) when the table is broken. */
    public void dropLogs(net.minecraft.world.level.Level level) {
        for (ItemStack s : logs) {
            Block.popResource(level, getBlockPos(), s);
        }
    }

    /** Removes {@code count} pile items matching {@code cost} (consumed by a completed batch). */
    private void removePileMatching(Cost cost, int count) {
        for (int i = logs.size() - 1; i >= 0 && count > 0; i--) {
            ItemStack cell = logs.get(i);
            if (!cost.matches(cell)) continue;
            int take = Math.min(count, cell.getCount());
            cell.shrink(take);
            count -= take;
            if (cell.isEmpty()) logs.remove(i);
        }
        lastSlideCell = Math.min(lastSlideCell, logs.size() - 1);
    }

    // ── Internals ─────────────────────────────────────────────────────────────────────────────

    private int totalLogs() {
        int n = 0;
        for (ItemStack s : logs) n += s.getCount();
        return n;
    }

    /** Logs present per wood family (insertion-ordered for a stable picker browse order). */
    private Map<String, Integer> familyCounts() {
        Map<String, Integer> m = new LinkedHashMap<>();
        for (ItemStack s : logs) {
            WoodFamily fam = WoodFamily.fromLog(s.getItem());
            if (fam != null) m.merge(fam.key(), s.getCount(), Integer::sum);
        }
        return m;
    }

    /** Pile items matching {@code cost} (e.g. all oak logs, or all planks). */
    private int pileMatching(Cost cost) {
        int n = 0;
        for (ItemStack s : logs) {
            if (cost.matches(s)) n += s.getCount();
        }
        return n;
    }

    /** Pile items already spoken-for by the build list, for {@code cost}'s pool. Reservations sharing
     *  the same matcher signature (e.g. every {@code #planks} cost) compete for one pool. */
    private int committed(Cost cost) {
        String sig = signature(cost);
        int n = 0;
        for (ListEntry e : buildList) {
            for (Cost c : e.costs()) {
                if (signature(c).equals(sig)) n += e.units() * c.perUnit();
            }
        }
        return n;
    }

    /** Pile items of {@code cost}'s pool still available to queue against. */
    private int remaining(Cost cost) {
        return pileMatching(cost) - committed(cost);
    }

    private static String signature(Cost cost) {
        return cost.kind() + "|" + cost.ref();
    }

    /** True if one of {@code cell} can be taken back: every reserved pool it belongs to has slack. */
    private boolean isRemovable(ItemStack cell) {
        for (ListEntry e : buildList) {
            for (Cost c : e.costs()) {
                if (c.matches(cell) && remaining(c) <= 0) return false;
            }
        }
        return true;
    }

    /** All affordable, researched offers — family/variant outputs (from logs) followed by assembly
     *  recipes (from planks/sticks). Stable order so the picker browses predictably. */
    private List<Offer> computeOffers() {
        List<Offer> out = new ArrayList<>();
        if (level == null) return out; // CraftGating needs a level; no offers without one
        for (String key : familyCounts().keySet()) {
            WoodFamily fam = WoodFamily.fromKey(key);
            if (fam == null) continue;
            for (CarpentryOutput o : CarpentryOutputManager.all()) {
                Cost cost = new Cost(Cost.Kind.FAMILY, key, o.logCost());
                if (cost.perUnit() > remaining(cost)) continue;
                Item item = fam.variant(o.variant());
                if (item == null) continue;
                if (!com.bannerbound.core.api.research.CraftGating.canProduceAt(
                        level, getBlockPos(), item)) continue;
                out.add(new Offer(item, o.yield(), List.of(cost)));
            }
        }
        for (CarpentryAssembly a : CarpentryAssemblyManager.all()) {
            if (!com.bannerbound.core.api.research.CraftGating.canProduceAt(
                    level, getBlockPos(), a.result())) continue;
            List<Cost> costs = a.costs();
            if (!affordable(costs)) continue;
            out.add(new Offer(a.result(), a.yield(), costs));
        }
        return out;
    }

    private int indexOfSelection(List<Offer> offers) {
        for (int i = 0; i < offers.size(); i++) {
            if (offers.get(i).output() == selectedOutput) return i;
        }
        return -1;
    }

    private void applySelection(Offer offer) {
        selectedOutput = offer.output();
    }

    /** Rebuilds the synced ghost fields from the current offers + sticky selection. Server-side. */
    private void recompute() {
        if (level == null || level.isClientSide) {
            setChanged();
            return;
        }
        List<Offer> offers = computeOffers();
        offerCount = offers.size();
        if (offers.isEmpty()) {
            ghostResult = ItemStack.EMPTY;
            selectedOutput = null;
            setChanged();
            return;
        }
        int idx = indexOfSelection(offers);
        if (idx < 0) {
            applySelection(offers.get(0));
            idx = 0;
        }
        Offer sel = offers.get(idx);
        ghostResult = new ItemStack(sel.output(), sel.yield());
        setChanged();
    }

    /** Ticker — drains the slide-in timer, and (server, once after load) recomputes the picker so it
     *  refreshes on chunk reload and migrates pre-pile saves. Both sides. */
    public static void tick(net.minecraft.world.level.Level level, BlockPos pos, BlockState state,
                            WoodworkingTableBlockEntity be) {
        if (be.insertAnimTicks > 0) be.insertAnimTicks--;
        if (!level.isClientSide && be.needsRecompute) {
            be.needsRecompute = false;
            be.recompute();
        }
    }

    @Override
    public void setChanged() {
        super.setChanged();
        if (level != null && !level.isClientSide) {
            level.sendBlockUpdated(getBlockPos(), getBlockState(), getBlockState(), Block.UPDATE_CLIENTS);
        }
    }

    // ── Persistence + sync ────────────────────────────────────────────────────────────────────

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider provider) {
        super.saveAdditional(tag, provider);
        ListTag logsTag = new ListTag();
        for (ItemStack s : logs) {
            if (!s.isEmpty()) logsTag.add(s.save(provider));
        }
        tag.put("Logs", logsTag);

        ListTag listTag = new ListTag();
        for (ListEntry e : buildList) {
            CompoundTag c = new CompoundTag();
            c.putString("Item", BuiltInRegistries.ITEM.getKey(e.output()).toString());
            c.putInt("Units", e.units());
            c.putInt("Yield", e.yieldPerUnit());
            ListTag costsTag = new ListTag();
            for (Cost cost : e.costs()) costsTag.add(cost.save());
            c.put("Costs", costsTag);
            listTag.add(c);
        }
        tag.put("BuildList", listTag);

        tag.putInt("InsertAnimTicks", insertAnimTicks);
        tag.putInt("InsertDir", insertDir.get3DDataValue());
        tag.putInt("LastSlideCell", lastSlideCell);
        if (selectedOutput != null) {
            tag.putString("SelItem", BuiltInRegistries.ITEM.getKey(selectedOutput).toString());
        }
        if (!ghostResult.isEmpty()) tag.put("Ghost", ghostResult.save(provider));
        tag.putInt("OfferCount", offerCount);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider provider) {
        super.loadAdditional(tag, provider);
        logs.clear();
        ListTag logsTag = tag.getList("Logs", Tag.TAG_COMPOUND);
        for (int i = 0; i < logsTag.size(); i++) {
            ItemStack.parse(provider, logsTag.getCompound(i)).ifPresent(logs::add);
        }
        buildList.clear();
        ListTag listTag = tag.getList("BuildList", Tag.TAG_COMPOUND);
        for (int i = 0; i < listTag.size(); i++) {
            CompoundTag c = listTag.getCompound(i);
            if (!c.contains("Costs")) continue; // drop legacy (pre-cost-model) entries
            Item item = itemOf(c.getString("Item"));
            if (item == null) continue;
            List<Cost> costs = new ArrayList<>();
            ListTag costsTag = c.getList("Costs", Tag.TAG_COMPOUND);
            for (int j = 0; j < costsTag.size(); j++) costs.add(Cost.load(costsTag.getCompound(j)));
            buildList.add(new ListEntry(item, c.getInt("Units"), c.getInt("Yield"), costs));
        }
        insertAnimTicks = tag.getInt("InsertAnimTicks");
        insertDir = Direction.from3DDataValue(tag.getInt("InsertDir"));
        lastSlideCell = Math.min(tag.getInt("LastSlideCell"), logs.size() - 1);
        needsRecompute = true; // refresh the picker on the first server tick after load
        selectedOutput = tag.contains("SelItem") ? itemOf(tag.getString("SelItem")) : null;
        ghostResult = tag.contains("Ghost")
            ? ItemStack.parse(provider, tag.getCompound("Ghost")).orElse(ItemStack.EMPTY)
            : ItemStack.EMPTY;
        offerCount = tag.getInt("OfferCount");
    }

    private static Item itemOf(String id) {
        ResourceLocation rl = ResourceLocation.tryParse(id);
        return rl != null && BuiltInRegistries.ITEM.containsKey(rl) ? BuiltInRegistries.ITEM.get(rl) : null;
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
