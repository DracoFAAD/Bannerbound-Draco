package com.bannerbound.antiquity.block.entity;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.antiquity.BannerboundAntiquity;
import com.bannerbound.antiquity.recipe.CraftingStoneRecipe;
import com.bannerbound.antiquity.recipe.CraftingStoneRecipeManager;

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
 * Block entity for the Crafting Stone. Holds a loose pile of items placed one-at-a-time (mixed
 * types allowed, capped at {@link #MAX_ITEMS} total). Whenever the pile matches a
 * {@link CraftingStoneRecipe}, {@link #cachedResult} holds the output so the renderer can show a
 * floating spinning preview; shift-clicking crafts it. Sync mirrors the bloomery pattern.
 */
@ApiStatus.Internal
public class CraftingStoneBlockEntity extends BlockEntity implements GhostRecipeWorkstation {
    /** Max total items (sum of counts) the stone can hold. */
    public static final int MAX_ITEMS = 9;
    /** Ticks the just-placed item's slide-in animation runs (matches the bloomery). */
    public static final int SLIDE_TICKS = 6;

    private final List<ItemStack> contents = new ArrayList<>();
    /** Server-computed result for the current pile (EMPTY = no valid recipe). Synced for the renderer. */
    private ItemStack cachedResult = ItemStack.EMPTY;
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
    /** Horizontal side the last-placed item slides in from (the side the placing player stood). */
    private Direction insertDir = Direction.NORTH;
    /** Index (in {@link #contents}) of the most-recently-touched stack — each stack owns one grid
     *  cell and piles up visually, and the renderer slides only that stack's TOP item in. */
    private int lastSlideCell = -1;

    public CraftingStoneBlockEntity(BlockPos pos, BlockState state) {
        super(BannerboundAntiquity.CRAFTING_STONE_BE.get(), pos, state);
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
        return 1.05;
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

    /** Ticker — drains the slide-in timer so the freshly placed item settles. Both sides. */
    public static void tick(net.minecraft.world.level.Level level, BlockPos pos, BlockState state,
                            CraftingStoneBlockEntity be) {
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

    /** Consumes the whole pile and returns the recipe result (EMPTY if no valid recipe). Hafting
     *  recipes (flagged {@code transfer_quality}) stamp the result with the best TOOL_QUALITY among
     *  the consumed ingredients — so a knapped head's craftsmanship rides onto the finished tool. */
    public ItemStack craft() {
        if (cachedResult.isEmpty()) return ItemStack.EMPTY;
        ItemStack out = cachedResult.copy();
        CraftingStoneRecipe recipe = resolveRecipe();
        if (recipe != null && recipe.transferQuality()) {
            com.bannerbound.core.api.quality.QualityTier best = bestQualityIn(contents);
            if (best != null) com.bannerbound.antiquity.Fletching.applyQuality(out, best);
        }
        contents.clear();
        cachedResult = ItemStack.EMPTY;
        ghostIngredients = List.of();
        ghostResult = ItemStack.EMPTY;
        ghostCandidateCount = 0;
        ghostChoice = null;
        ghostLocked = false;
        setChanged();
        return out;
    }

    /** Highest {@code TOOL_QUALITY} carried by any item in {@code pile}, or null if none carry one. */
    private static com.bannerbound.core.api.quality.QualityTier bestQualityIn(List<ItemStack> pile) {
        com.bannerbound.core.api.quality.QualityTier best = null;
        for (ItemStack s : pile) {
            com.bannerbound.core.api.quality.QualityTier t =
                s.get(com.bannerbound.core.BannerboundCore.TOOL_QUALITY.get());
            if (t != null && (best == null || t.ordinal() > best.ordinal())) best = t;
        }
        return best;
    }

    /** The recipe the current pile makes: among all exact matches, the one whose result is the
     *  player's browsed/locked ghost choice wins (two recipes can share the same contents). Used by
     *  BOTH the preview ({@link #recomputeResult}) and {@link #craft}, so what's shown — including
     *  its {@code transfer_quality} behaviour — is always what's crafted. Null when nothing matches. */
    private CraftingStoneRecipe resolveRecipe() {
        List<CraftingStoneRecipe> recipes = CraftingStoneRecipeManager.findMatching(contents);
        if (recipes == null || recipes.isEmpty()) return null;
        for (CraftingStoneRecipe c : recipes) {
            if (c.result().is(ghostChoice)) return c;
        }
        return recipes.getFirst();
    }

    private void recomputeResult() {
        ghostIngredients = List.of();
        ghostResult = ItemStack.EMPTY;
        ghostCandidateCount = 0;
        CraftingStoneRecipe recipe = resolveRecipe();

        if (recipe == null) {
            cachedResult = ItemStack.EMPTY;
            recomputeGhost();
            return;
        }

        if (!com.bannerbound.core.api.research.CraftGating.canProduceAt(
                    level, getBlockPos(), recipe.result().getItem())) {
            cachedResult = ItemStack.EMPTY;
            recomputeGhost();
            return;
        }
        cachedResult = recipe.result().copy();
        // The pile incidentally matches a DIFFERENT recipe than the locked one (2 sticks = fire
        // sticks while building a bone axe): keep the locked ghost showing — the renderer floats
        // the incidental craftable result ABOVE it instead of hijacking the player's choice.
        if (ghostLocked && ghostChoice != null && !cachedResult.is(ghostChoice)) {
            recomputeGhost();
        }
    }

    /** All candidates the pile could still become whose output the owning civ has researched. */
    private List<CraftingStoneRecipe> researchedCandidates() {
        List<CraftingStoneRecipe> out = new ArrayList<>();
        for (CraftingStoneRecipe candidate : CraftingStoneRecipeManager.candidates(contents)) {
            if (com.bannerbound.core.api.research.CraftGating.canProduceAt(
                    level, getBlockPos(), candidate.result().getItem())) {
                out.add(candidate);
            }
        }
        return out;
    }

    /** Re-selects a candidate for the ghost preview: keeps the player's sticky choice if it's
     *  still reachable from the pile, else falls back to the first candidate — unless the choice
     *  is LOCKED, in which case the preview just hides rather than jumping to another recipe. */
    private void recomputeGhost() {
        List<CraftingStoneRecipe> researched = researchedCandidates();
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
        List<CraftingStoneRecipe> researched = researchedCandidates();
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

    private int indexOfChoice(List<CraftingStoneRecipe> researched) {
        for (int i = 0; i < researched.size(); i++) {
            if (ghostChoice != null && researched.get(i).result().is(ghostChoice)) return i;
        }
        return -1;
    }

    /** Stores {@code candidate}'s missing ingredients + result for the renderer's ghost preview. */
    private void applyGhost(CraftingStoneRecipe candidate) {
        ghostChoice = candidate.result().getItem();
        Map<Item, Integer> placed = new HashMap<>();
        for (ItemStack s : contents) {
            if (!s.isEmpty()) placed.merge(s.getItem(), s.getCount(), Integer::sum);
        }
        // Walk the JSON ingredient order (not the merged map) so ghost cells don't shuffle
        // between recomputes; the map only supplies merged counts for duplicate entries.
        Map<Item, Integer> required = candidate.requiredCounts();
        List<ItemStack> missing = new ArrayList<>();
        for (CraftingStoneRecipe.Ing ing : candidate.ingredients()) {
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
