package com.bannerbound.antiquity.block.entity;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.antiquity.BannerboundAntiquity;
import com.bannerbound.antiquity.masonry.MasonryOutput;
import com.bannerbound.antiquity.masonry.MasonryOutputManager;
import com.bannerbound.antiquity.masonry.StoneFamily;

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
 * Block entity for the Mason's Bench — the batch-stoneworking workstation, the stone analogue of the
 * Carpenter's Table. The deposited base stones (cobblestone, stone, sandstone, …) are a real,
 * removable <b>pile</b> (placed one at a time, taken back one at a time, rendered in a grid) which
 * doubles as the masonry "budget"; on top of it sits an ordered <b>build list</b> of queued outputs.
 * The picker reuses the ghost-preview infrastructure ({@link GhostRecipeWorkstation}): the floating
 * ghost is the selected affordable output and the browse arrows cycle offers; clicking the result
 * (a {@code GhostActionPayload.FILL}) appends one unit to the list. Nothing is consumed until the
 * chisel minigame completes — it then turns the list into items and removes only the stone it cost,
 * leaving any leftover pile on the bench.
 *
 * <p>Each output costs {@code baseCost} of exactly one {@link StoneFamily}'s base stone, so the cost
 * model is far simpler than carpentry's three-category budget: one family key + a per-unit count.
 * There is no quality (building materials).
 */
@ApiStatus.Internal
public class MasonsBenchBlockEntity extends BlockEntity implements GhostRecipeWorkstation {
    /** Total base stones the bench can hold/budget at once. */
    public static final int MAX_BUDGET = 64;
    /** Ticks the just-placed stone's slide-in animation runs (matches the crafting stone). */
    public static final int SLIDE_TICKS = 6;

    /** One queued output: how many recipe-yields of {@code output}, with the consuming stone family +
     *  per-unit base cost captured at queue time so the chisel step is self-contained. */
    public record ListEntry(Item output, int units, int yieldPerUnit, String familyKey, int baseCost) {}

    /** A resolved, affordable picker offer (transient — recomputed, never persisted). */
    private record Offer(Item output, int yield, String familyKey, int baseCost) {}

    /** The deposited base stones — one stack (= one grid cell) per distinct item; multiples pile up.
     *  This IS the budget. */
    private final List<ItemStack> stones = new ArrayList<>();
    /** Queued outputs, in the order the player added them. */
    private final List<ListEntry> buildList = new ArrayList<>();

    // Sticky picker selection — kept across recomputes so landing materials don't yank the choice.
    private Item selectedOutput = null;

    // Synced ghost fields (computed server-side; the client just renders these).
    private ItemStack ghostResult = ItemStack.EMPTY;
    private int offerCount = 0;

    // Slide-in animation for the most-recently-touched pile cell.
    private int insertAnimTicks = 0;
    private Direction insertDir = Direction.NORTH;
    private int lastSlideCell = -1;
    private boolean needsRecompute = false;

    public MasonsBenchBlockEntity(BlockPos pos, BlockState state) {
        super(BannerboundAntiquity.MASONS_BENCH_BE.get(), pos, state);
    }

    // ── Accessors for the renderer ────────────────────────────────────────────────────────────

    /** The deposited stone pile (read-only view for the renderer's grid). */
    public List<ItemStack> getStones() {
        return stones;
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

    /** A representative stone item to draw on the bench during the chisel minigame: the first pile
     *  cell (falling back to cobblestone for an empty bench). */
    public ItemStack representativeBudgetStone() {
        return stones.isEmpty() ? new ItemStack(net.minecraft.world.level.block.Blocks.COBBLESTONE)
            : new ItemStack(stones.get(0).getItem());
    }

    /** Total uncommitted base stone left on the bench (for the tabletop readout). */
    public int remainingTotal() {
        int total = 0;
        for (ItemStack s : stones) total += s.getCount();
        for (ListEntry e : buildList) total -= e.units() * e.baseCost();
        return Math.max(0, total);
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
        return List.of(); // masonry has no missing-ingredient silhouettes
    }

    @Override
    public int getGhostCandidateCount() {
        return offerCount;
    }

    @Override
    public double ghostPreviewY() {
        return 1.62; // compact in-world picker band just above the bench top
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
        // Masonry's selection is always sticky — nothing to lock.
    }

    // ── Pile mutation (server-side) — mirrors the carpenter's table ────────────────────────────

    @Override
    public boolean insertOne(ItemStack held, Direction from) {
        if (!isBudgetMaterial(held) || totalStones() >= MAX_BUDGET) return false;
        insertAnimTicks = SLIDE_TICKS;
        insertDir = from == null ? Direction.NORTH : from;
        for (int i = 0; i < stones.size(); i++) {
            ItemStack s = stones.get(i);
            if (ItemStack.isSameItemSameComponents(s, held)) {
                s.grow(1);
                lastSlideCell = i;
                recompute();
                return true;
            }
        }
        stones.add(held.copyWithCount(1));
        lastSlideCell = stones.size() - 1;
        recompute();
        return true;
    }

    /** True if {@code stack} can be deposited as budget material: a recognised base stone. */
    public static boolean isBudgetMaterial(ItemStack stack) {
        return StoneFamily.isBudgetStone(stack);
    }

    /** Deposits as much of {@code stack} as fits (whole-stack convenience, sneak-place). Returns how
     *  many materials were deposited. Server-side only. */
    public int insertStack(ItemStack stack, Direction from) {
        int added = 0;
        while (added < stack.getCount() && insertOne(stack, from)) added++;
        return added;
    }

    @Override
    public ItemStack removeOne() {
        if (stones.isEmpty()) return ItemStack.EMPTY;
        for (int i = stones.size() - 1; i >= 0; i--) {
            ItemStack cell = stones.get(i);
            if (!isRemovable(cell)) continue;
            ItemStack out = cell.copyWithCount(1);
            cell.shrink(1);
            if (cell.isEmpty()) stones.remove(i);
            lastSlideCell = Math.min(lastSlideCell, stones.size() - 1);
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
        if (offer.baseCost() > remaining(offer.familyKey())) return false;
        for (int i = 0; i < buildList.size(); i++) {
            ListEntry e = buildList.get(i);
            if (e.output() == offer.output() && e.familyKey().equals(offer.familyKey())) {
                buildList.set(i, new ListEntry(e.output(), e.units() + 1, e.yieldPerUnit(),
                    e.familyKey(), e.baseCost()));
                applySelection(offer);
                recompute();
                return true;
            }
        }
        buildList.add(new ListEntry(offer.output(), 1, offer.yield(), offer.familyKey(), offer.baseCost()));
        applySelection(offer);
        recompute();
        return true;
    }

    /** Removes the whole queued entry at {@code index}. Returns true if anything was removed. */
    public boolean removeEntryAt(int index) {
        if (index < 0 || index >= buildList.size()) return false;
        buildList.remove(index);
        recompute();
        return true;
    }

    /** Removes one unit of the most-recently-added build-list entry. Returns true if anything went. */
    public boolean removeLastEntry() {
        if (buildList.isEmpty()) return false;
        int last = buildList.size() - 1;
        ListEntry e = buildList.get(last);
        if (e.units() <= 1) {
            buildList.remove(last);
        } else {
            buildList.set(last, new ListEntry(e.output(), e.units() - 1, e.yieldPerUnit(),
                e.familyKey(), e.baseCost()));
        }
        recompute();
        return true;
    }

    /** Chisel minigame finished: pops every queued output above the bench and removes only the base
     *  stone the list cost from the pile — any uncommitted stone stays for next time. */
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
        for (ListEntry e : buildList) {
            removePileMatching(e.familyKey(), e.units() * e.baseCost());
        }
        buildList.clear();
        recompute();
    }

    /** Drops the deposited stones (real items) when the bench is broken. */
    public void dropStones(net.minecraft.world.level.Level level) {
        for (ItemStack s : stones) {
            Block.popResource(level, getBlockPos(), s);
        }
    }

    /** Removes {@code count} pile items of {@code familyKey}'s base stone (consumed by a batch). */
    private void removePileMatching(String familyKey, int count) {
        StoneFamily fam = StoneFamily.fromKey(familyKey);
        if (fam == null) return;
        Item base = fam.baseItem();
        for (int i = stones.size() - 1; i >= 0 && count > 0; i--) {
            ItemStack cell = stones.get(i);
            if (cell.getItem() != base) continue;
            int take = Math.min(count, cell.getCount());
            cell.shrink(take);
            count -= take;
            if (cell.isEmpty()) stones.remove(i);
        }
        lastSlideCell = Math.min(lastSlideCell, stones.size() - 1);
    }

    // ── Internals ─────────────────────────────────────────────────────────────────────────────

    private int totalStones() {
        int n = 0;
        for (ItemStack s : stones) n += s.getCount();
        return n;
    }

    /** Base-stone counts per family present in the pile (insertion-ordered for stable browse order). */
    private Map<String, Integer> familyCounts() {
        Map<String, Integer> m = new LinkedHashMap<>();
        for (ItemStack s : stones) {
            StoneFamily fam = StoneFamily.fromBase(s.getItem());
            if (fam != null) m.merge(fam.key(), s.getCount(), Integer::sum);
        }
        return m;
    }

    /** Base stone of {@code familyKey} present in the pile. */
    private int pileMatching(String familyKey) {
        StoneFamily fam = StoneFamily.fromKey(familyKey);
        if (fam == null) return 0;
        Item base = fam.baseItem();
        int n = 0;
        for (ItemStack s : stones) {
            if (s.getItem() == base) n += s.getCount();
        }
        return n;
    }

    /** Base stone of {@code familyKey} already reserved by the build list. */
    private int committed(String familyKey) {
        int n = 0;
        for (ListEntry e : buildList) {
            if (e.familyKey().equals(familyKey)) n += e.units() * e.baseCost();
        }
        return n;
    }

    /** Base stone of {@code familyKey} still available to queue against. */
    private int remaining(String familyKey) {
        return pileMatching(familyKey) - committed(familyKey);
    }

    /** True if one of {@code cell} can be taken back: its family pool still has slack. */
    private boolean isRemovable(ItemStack cell) {
        StoneFamily fam = StoneFamily.fromBase(cell.getItem());
        if (fam == null) return true;
        return remaining(fam.key()) > 0;
    }

    /** All affordable, researched offers — every (family present in pile) × (output variant). Stable
     *  order so the picker browses predictably. */
    private List<Offer> computeOffers() {
        List<Offer> out = new ArrayList<>();
        if (level == null) return out; // CraftGating needs a level; no offers without one
        for (String key : familyCounts().keySet()) {
            StoneFamily fam = StoneFamily.fromKey(key);
            if (fam == null) continue;
            for (MasonryOutput o : MasonryOutputManager.all()) {
                if (o.baseCost() > remaining(key)) continue;
                Item item = fam.variant(o.variant());
                if (item == null) continue;
                if (!com.bannerbound.core.api.research.CraftGating.canProduceAt(
                        level, getBlockPos(), item)) continue;
                out.add(new Offer(item, o.yield(), key, o.baseCost()));
            }
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

    /** Ticker — drains the slide-in timer, and (server, once after load) recomputes the picker. */
    public static void tick(net.minecraft.world.level.Level level, BlockPos pos, BlockState state,
                            MasonsBenchBlockEntity be) {
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
        ListTag stonesTag = new ListTag();
        for (ItemStack s : stones) {
            if (!s.isEmpty()) stonesTag.add(s.save(provider));
        }
        tag.put("Stones", stonesTag);

        ListTag listTag = new ListTag();
        for (ListEntry e : buildList) {
            CompoundTag c = new CompoundTag();
            c.putString("Item", BuiltInRegistries.ITEM.getKey(e.output()).toString());
            c.putInt("Units", e.units());
            c.putInt("Yield", e.yieldPerUnit());
            c.putString("Family", e.familyKey());
            c.putInt("BaseCost", e.baseCost());
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
        stones.clear();
        ListTag stonesTag = tag.getList("Stones", Tag.TAG_COMPOUND);
        for (int i = 0; i < stonesTag.size(); i++) {
            ItemStack.parse(provider, stonesTag.getCompound(i)).ifPresent(stones::add);
        }
        buildList.clear();
        ListTag listTag = tag.getList("BuildList", Tag.TAG_COMPOUND);
        for (int i = 0; i < listTag.size(); i++) {
            CompoundTag c = listTag.getCompound(i);
            if (!c.contains("Family")) continue; // drop malformed entries
            Item item = itemOf(c.getString("Item"));
            if (item == null) continue;
            buildList.add(new ListEntry(item, c.getInt("Units"), c.getInt("Yield"),
                c.getString("Family"), c.getInt("BaseCost")));
        }
        insertAnimTicks = tag.getInt("InsertAnimTicks");
        insertDir = Direction.from3DDataValue(tag.getInt("InsertDir"));
        lastSlideCell = Math.min(tag.getInt("LastSlideCell"), stones.size() - 1);
        needsRecompute = true;
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
