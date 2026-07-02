package com.bannerbound.antiquity.block.entity;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.antiquity.BannerboundAntiquity;
import com.bannerbound.antiquity.recipe.FletchingRecipe;
import com.bannerbound.antiquity.recipe.FletchingRecipeManager;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Block entity for the Fletching Station. Holds a loose pile of items placed one-at-a-time (mixed
 * types allowed, capped at {@link #MAX_ITEMS}), exactly like the Crafting Stone. When the pile
 * matches a {@link FletchingRecipe}, {@link #cachedResult} holds the BASE output (no quality — that
 * is rolled by the stretch minigame at completion) so the renderer shows a floating spinning
 * preview; shift-clicking opens the minigame instead of crafting instantly.
 */
@ApiStatus.Internal
public class FletchingStationBlockEntity extends BlockEntity implements GhostRecipeWorkstation {
    /** Max total items (sum of counts) the station can hold. */
    public static final int MAX_ITEMS = 9;
    /** Ticks the just-placed item's slide-in animation runs (matches the crafting stone). */
    public static final int SLIDE_TICKS = 6;

    private final List<ItemStack> contents = new ArrayList<>();
    /** Server-computed BASE result for the current pile (EMPTY = no valid recipe). Synced for the renderer. */
    private ItemStack cachedResult = ItemStack.EMPTY;
    /** Display-only "work in progress" sprite lying on the table while a minigame session runs
     *  (set on commit from the recipe's {@code in_progress} item, cleared on complete/cancel). */
    private ItemStack inProgress = ItemStack.EMPTY;
    /** Server-picked "could become" preview: the STILL-MISSING ingredients of the first researched
     *  candidate recipe (count = how many more of that item), plus its result. Both empty whenever
     *  the pile is empty, junk, or an exact match. Synced for the renderer's ghost silhouettes. */
    private List<ItemStack> ghostIngredients = List.of();
    private ItemStack ghostResult = ItemStack.EMPTY;
    /** How many researched candidates the pile could become (synced; browse arrows show when ≥2). */
    private int ghostCandidateCount = 0;
    /** Sticky browse selection — result item of the candidate the player last cycled to. Keeps the
     *  chosen recipe selected while ingredients land (the candidate list shrinks underneath it). */
    private Item ghostChoice = null;
    /** True once the player explicitly picked (cycled the arrows / clicked the ghost result): the
     *  selection stops auto-switching, and the preview hides instead of jumping recipes if the
     *  pile turns incompatible. Cleared when the pile empties. */
    private boolean ghostLocked = false;
    private int insertAnimTicks = 0;
    private Direction insertDir = Direction.NORTH;
    private int lastSlideCell = -1;

    public FletchingStationBlockEntity(BlockPos pos, BlockState state) {
        super(BannerboundAntiquity.FLETCHING_STATION_BE.get(), pos, state);
    }

    public List<ItemStack> getContents() {
        return contents;
    }

    public ItemStack getResult() {
        return cachedResult;
    }

    @Override
    public List<ItemStack> getGhostIngredients() {
        return ghostIngredients;
    }

    @Override
    public ItemStack getGhostResult() {
        return ghostResult;
    }

    @Override
    public int getGhostCandidateCount() {
        return ghostCandidateCount;
    }

    @Override
    public double ghostPreviewY() {
        return 1.35;
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

    /** The recipe currently matched by the pile (null if none / output not researched). Falls back to
     *  the free-mix modular arrow assembly (one tip + one shaft + one back) when no JSON recipe matches. */
    public FletchingRecipe matchedRecipe() {
        FletchingRecipe recipe = FletchingRecipeManager.find(contents);
        if (recipe == null) recipe = com.bannerbound.antiquity.recipe.ModularArrow.tryMatch(contents);
        if (recipe == null) return null;
        if (!com.bannerbound.core.api.research.CraftGating.canProduceAt(
                level, getBlockPos(), recipe.result().getItem())) {
            return null;
        }
        return recipe;
    }

    /** Ticker — drains the slide-in timer so the freshly placed item settles. Both sides. */
    public static void tick(net.minecraft.world.level.Level level, BlockPos pos, BlockState state,
                            FletchingStationBlockEntity be) {
        if (be.insertAnimTicks > 0) {
            be.insertAnimTicks--;
        }
    }

    private int totalCount() {
        int n = 0;
        for (ItemStack s : contents) n += s.getCount();
        return n;
    }

    /** Adds ONE of {@code held} to the pile (merging into an existing same-item stack). Returns
     *  true if it fit. Server-side only. */
    public boolean insertOne(ItemStack held, Direction from) {
        if (held.isEmpty() || totalCount() >= MAX_ITEMS) return false;
        insertAnimTicks = SLIDE_TICKS;
        insertDir = from == null ? Direction.NORTH : from;
        // One grid cell per distinct stack: merging grows the existing pile in place; a new kind
        // takes the next free cell. The slide animates the touched stack's top item.
        for (int i = 0; i < contents.size(); i++) {
            ItemStack s = contents.get(i);
            if (ItemStack.isSameItemSameComponents(s, held)) {
                s.grow(1);
                lastSlideCell = i;
                recomputeResult();
                setChanged();
                return true;
            }
        }
        contents.add(held.copyWithCount(1));
        lastSlideCell = contents.size() - 1;
        recomputeResult();
        setChanged();
        return true;
    }

    /** Removes ONE item from the most-recently-touched stack and returns it. Server-side only. */
    public ItemStack removeOne() {
        if (contents.isEmpty()) return ItemStack.EMPTY;
        ItemStack last = contents.get(contents.size() - 1);
        ItemStack out = last.copyWithCount(1);
        last.shrink(1);
        if (last.isEmpty()) contents.remove(contents.size() - 1);
        recomputeResult();
        setChanged();
        return out;
    }

    /** Consumes the whole pile (called when the minigame commits on the first stretch). */
    public void consumePile() {
        contents.clear();
        cachedResult = ItemStack.EMPTY;
        ghostIngredients = List.of();
        ghostResult = ItemStack.EMPTY;
        ghostCandidateCount = 0;
        ghostChoice = null;
        ghostLocked = false;
        setChanged();
    }

    public ItemStack getInProgress() {
        return inProgress;
    }

    /** Sets/clears the work-in-progress display sprite (EMPTY clears). Server-side only. */
    public void setInProgress(ItemStack stack) {
        inProgress = stack;
        setChanged();
    }

    private void recomputeResult() {
        ghostIngredients = List.of();
        ghostResult = ItemStack.EMPTY;
        ghostCandidateCount = 0;
        FletchingRecipe recipe = matchedRecipe();
        cachedResult = recipe == null ? ItemStack.EMPTY : recipe.result().copy();
        if (cachedResult.isEmpty()) {
            recomputeGhost();
            return;
        }
        // The pile incidentally matches a DIFFERENT recipe than the locked one: keep the locked
        // ghost showing — the renderer floats the incidental result ABOVE it (see crafting stone).
        if (ghostLocked && ghostChoice != null && !cachedResult.is(ghostChoice)) {
            recomputeGhost();
        }
    }

    /** All candidates the pile could still become whose output the owning civ has researched. */
    private List<FletchingRecipe> researchedCandidates() {
        List<FletchingRecipe> out = new ArrayList<>();
        for (FletchingRecipe candidate : FletchingRecipeManager.candidates(contents)) {
            if (com.bannerbound.core.api.research.CraftGating.canProduceAt(
                    level, getBlockPos(), candidate.result().getItem())) {
                out.add(candidate);
            }
        }
        // Free-mix arrow assembly: a "could become an arrow" ghost for a partial part pile.
        FletchingRecipe modular = com.bannerbound.antiquity.recipe.ModularArrow.ghostCandidate(contents);
        if (modular != null && com.bannerbound.core.api.research.CraftGating.canProduceAt(
                level, getBlockPos(), modular.result().getItem())) {
            out.add(modular);
        }
        return out;
    }

    /** Re-selects a candidate for the ghost preview: keeps the player's sticky choice if it's
     *  still reachable from the pile, else falls back to the first candidate — unless the choice
     *  is LOCKED, in which case the preview just hides rather than jumping to another recipe. */
    private void recomputeGhost() {
        List<FletchingRecipe> researched = researchedCandidates();
        ghostCandidateCount = researched.size();
        if (contents.isEmpty()) {
            ghostChoice = null;
            ghostLocked = false;
            return;
        }
        // Junk pile: keep the choice + lock — removing the offending item restores the preview.
        if (researched.isEmpty()) return;
        int idx = indexOfChoice(researched);
        if (idx < 0 && ghostLocked) return;
        applyGhost(researched.get(Math.max(0, idx)));
    }

    /** Cycles the ghost preview to the previous/next researched candidate (browse-arrow click).
     *  Cycling is an explicit choice, so it locks the selection. */
    @Override
    public void cycleGhost(int dir) {
        if (ghostResult.isEmpty()) return;
        List<FletchingRecipe> researched = researchedCandidates();
        ghostCandidateCount = researched.size();
        if (researched.size() < 2) return;
        int idx = Math.floorMod(Math.max(0, indexOfChoice(researched)) + dir, researched.size());
        applyGhost(researched.get(idx));
        ghostLocked = true;
        setChanged();
    }

    @Override
    public void lockGhost() {
        if (!ghostResult.isEmpty()) ghostLocked = true;
    }

    private int indexOfChoice(List<FletchingRecipe> researched) {
        for (int i = 0; i < researched.size(); i++) {
            if (ghostChoice != null && researched.get(i).result().is(ghostChoice)) return i;
        }
        return -1;
    }

    /** Stores {@code candidate}'s missing ingredients + result for the renderer's ghost preview. */
    private void applyGhost(FletchingRecipe candidate) {
        ghostChoice = candidate.result().getItem();
        Map<Item, Integer> placed = new HashMap<>();
        for (ItemStack s : contents) {
            if (!s.isEmpty()) placed.merge(s.getItem(), s.getCount(), Integer::sum);
        }
        // Walk the JSON ingredient order (not the merged map) so ghost cells don't shuffle
        // between recomputes; the map only supplies merged counts for duplicate entries.
        Map<Item, Integer> required = candidate.requiredCounts();
        List<ItemStack> missing = new ArrayList<>();
        for (FletchingRecipe.Ing ing : candidate.ingredients()) {
            Integer req = required.remove(ing.item());
            if (req == null) continue;
            int more = req - placed.getOrDefault(ing.item(), 0);
            if (more > 0) missing.add(new ItemStack(ing.item(), more));
        }
        ghostIngredients = List.copyOf(missing);
        ghostResult = candidate.result().copy();
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
        ListTag list = new ListTag();
        for (ItemStack s : contents) {
            if (!s.isEmpty()) list.add(s.save(provider));
        }
        tag.put("Contents", list);
        tag.putInt("InsertAnimTicks", insertAnimTicks);
        tag.putInt("InsertDir", insertDir.get3DDataValue());
        tag.putInt("LastSlideCell", lastSlideCell);
        if (!cachedResult.isEmpty()) {
            tag.put("Result", cachedResult.save(provider));
        }
        if (!inProgress.isEmpty()) {
            tag.put("InProgress", inProgress.save(provider));
        }
        if (!ghostResult.isEmpty()) {
            tag.put("GhostResult", ghostResult.save(provider));
            ListTag ghosts = new ListTag();
            for (ItemStack s : ghostIngredients) {
                if (!s.isEmpty()) ghosts.add(s.save(provider));
            }
            tag.put("GhostIngredients", ghosts);
            tag.putInt("GhostCandidates", ghostCandidateCount);
            tag.putBoolean("GhostLocked", ghostLocked);
        }
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider provider) {
        super.loadAdditional(tag, provider);
        contents.clear();
        ListTag list = tag.getList("Contents", Tag.TAG_COMPOUND);
        for (int i = 0; i < list.size(); i++) {
            ItemStack.parse(provider, list.getCompound(i)).ifPresent(contents::add);
        }
        insertAnimTicks = tag.getInt("InsertAnimTicks");
        insertDir = Direction.from3DDataValue(tag.getInt("InsertDir"));
        lastSlideCell = tag.getInt("LastSlideCell");
        cachedResult = tag.contains("Result")
            ? ItemStack.parse(provider, tag.getCompound("Result")).orElse(ItemStack.EMPTY)
            : ItemStack.EMPTY;
        inProgress = tag.contains("InProgress")
            ? ItemStack.parse(provider, tag.getCompound("InProgress")).orElse(ItemStack.EMPTY)
            : ItemStack.EMPTY;
        ghostResult = tag.contains("GhostResult")
            ? ItemStack.parse(provider, tag.getCompound("GhostResult")).orElse(ItemStack.EMPTY)
            : ItemStack.EMPTY;
        List<ItemStack> ghosts = new ArrayList<>();
        ListTag ghostList = tag.getList("GhostIngredients", Tag.TAG_COMPOUND);
        for (int i = 0; i < ghostList.size(); i++) {
            ItemStack.parse(provider, ghostList.getCompound(i)).ifPresent(ghosts::add);
        }
        ghostIngredients = List.copyOf(ghosts);
        ghostCandidateCount = tag.getInt("GhostCandidates");
        ghostChoice = ghostResult.isEmpty() ? null : ghostResult.getItem();
        ghostLocked = tag.getBoolean("GhostLocked");
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
