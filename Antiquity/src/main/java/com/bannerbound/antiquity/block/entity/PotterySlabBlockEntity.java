package com.bannerbound.antiquity.block.entity;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.antiquity.BannerboundAntiquity;
import com.bannerbound.antiquity.recipe.PotteryRecipe;
import com.bannerbound.antiquity.recipe.PotteryRecipeManager;

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
 * Pottery slab block entity. It mirrors the loose-pile station idiom, but exact matches can have
 * multiple recipe choices (for example, one clay block can become a pot, bucket, brick batch, or
 * crucible). The selected output is the floating preview; browse arrows cycle the exact choices.
 */
@ApiStatus.Internal
public class PotterySlabBlockEntity extends BlockEntity implements GhostRecipeWorkstation {
    public static final int MAX_ITEMS = 9;
    public static final int SLIDE_TICKS = 6;

    private final List<ItemStack> contents = new ArrayList<>();
    private ItemStack cachedResult = ItemStack.EMPTY;
    private ItemStack inProgress = ItemStack.EMPTY;
    private List<ItemStack> ghostIngredients = List.of();
    private ItemStack ghostResult = ItemStack.EMPTY;
    private int ghostCandidateCount = 0;
    private Item ghostChoice = null;
    private boolean ghostLocked = false;
    private int insertAnimTicks = 0;
    private Direction insertDir = Direction.NORTH;
    private int lastSlideCell = -1;

    public PotterySlabBlockEntity(BlockPos pos, BlockState state) {
        super(BannerboundAntiquity.POTTERY_SLAB_BE.get(), pos, state);
    }

    public List<ItemStack> getContents() {
        return contents;
    }

    @Override
    public ItemStack getResult() {
        return cachedResult;
    }

    public ItemStack getInProgress() {
        return inProgress;
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
        return 1.08;
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

    /** The currently selected exact recipe, or null if the pile is incomplete/junk/locked. */
    public PotteryRecipe matchedRecipe() {
        List<PotteryRecipe> exact = researchedExactMatches();
        if (exact.isEmpty()) return null;
        int idx = indexOfChoice(exact);
        return exact.get(Math.max(0, idx));
    }

    public static void tick(net.minecraft.world.level.Level level, BlockPos pos, BlockState state,
                            PotterySlabBlockEntity be) {
        if (be.insertAnimTicks > 0) {
            be.insertAnimTicks--;
        }
    }

    @Override
    public boolean insertOne(ItemStack held, Direction from) {
        if (held.isEmpty() || totalCount() >= MAX_ITEMS || !inProgress.isEmpty()) return false;
        insertAnimTicks = SLIDE_TICKS;
        insertDir = from == null ? Direction.NORTH : from;
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

    @Override
    public ItemStack removeOne() {
        if (contents.isEmpty() || !inProgress.isEmpty()) return ItemStack.EMPTY;
        ItemStack last = contents.get(contents.size() - 1);
        ItemStack out = last.copyWithCount(1);
        last.shrink(1);
        if (last.isEmpty()) contents.remove(contents.size() - 1);
        recomputeResult();
        setChanged();
        return out;
    }

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

    public void setInProgress(ItemStack stack) {
        inProgress = stack;
        setChanged();
    }

    @Override
    public void cycleGhost(int dir) {
        List<PotteryRecipe> exact = researchedExactMatches();
        if (!exact.isEmpty()) {
            int idx = Math.floorMod(Math.max(0, indexOfChoice(exact)) + dir, exact.size());
            applyExact(exact.get(idx), exact.size());
            ghostLocked = true;
            setChanged();
            return;
        }
        if (ghostResult.isEmpty()) return;
        List<PotteryRecipe> researched = researchedCandidates();
        ghostCandidateCount = researched.size();
        if (researched.size() < 2) return;
        int idx = Math.floorMod(Math.max(0, indexOfChoice(researched)) + dir, researched.size());
        applyGhost(researched.get(idx), researched.size());
        ghostLocked = true;
        setChanged();
    }

    @Override
    public void lockGhost() {
        if (!ghostResult.isEmpty()) ghostLocked = true;
    }

    private int totalCount() {
        int n = 0;
        for (ItemStack s : contents) n += s.getCount();
        return n;
    }

    private void recomputeResult() {
        ghostIngredients = List.of();
        ghostResult = ItemStack.EMPTY;
        ghostCandidateCount = 0;
        List<PotteryRecipe> exact = researchedExactMatches();
        if (!exact.isEmpty()) {
            int idx = indexOfChoice(exact);
            applyExact(exact.get(Math.max(0, idx)), exact.size());
            return;
        }
        cachedResult = ItemStack.EMPTY;
        recomputeGhost();
    }

    private List<PotteryRecipe> researchedExactMatches() {
        List<PotteryRecipe> out = new ArrayList<>();
        for (PotteryRecipe candidate : PotteryRecipeManager.exactMatches(contents)) {
            if (com.bannerbound.core.api.research.CraftGating.canProduceAt(
                    level, getBlockPos(), candidate.result().getItem())) {
                out.add(candidate);
            }
        }
        return out;
    }

    private List<PotteryRecipe> researchedCandidates() {
        List<PotteryRecipe> out = new ArrayList<>();
        for (PotteryRecipe candidate : PotteryRecipeManager.candidates(contents)) {
            if (com.bannerbound.core.api.research.CraftGating.canProduceAt(
                    level, getBlockPos(), candidate.result().getItem())) {
                out.add(candidate);
            }
        }
        return out;
    }

    private void recomputeGhost() {
        List<PotteryRecipe> researched = researchedCandidates();
        ghostCandidateCount = researched.size();
        if (contents.isEmpty()) {
            ghostChoice = null;
            ghostLocked = false;
            return;
        }
        if (researched.isEmpty()) return;
        int idx = indexOfChoice(researched);
        if (idx < 0 && ghostLocked) return;
        applyGhost(researched.get(Math.max(0, idx)), researched.size());
    }

    private int indexOfChoice(List<PotteryRecipe> researched) {
        for (int i = 0; i < researched.size(); i++) {
            if (ghostChoice != null && researched.get(i).result().is(ghostChoice)) return i;
        }
        return -1;
    }

    private void applyExact(PotteryRecipe recipe, int choices) {
        ghostChoice = recipe.result().getItem();
        cachedResult = recipe.result().copy();
        ghostResult = recipe.result().copy();
        ghostIngredients = List.of();
        ghostCandidateCount = choices;
    }

    private void applyGhost(PotteryRecipe candidate, int choices) {
        ghostChoice = candidate.result().getItem();
        Map<Item, Integer> placed = new HashMap<>();
        for (ItemStack s : contents) {
            if (!s.isEmpty()) placed.merge(s.getItem(), s.getCount(), Integer::sum);
        }
        Map<Item, Integer> required = candidate.requiredCounts();
        List<ItemStack> missing = new ArrayList<>();
        for (PotteryRecipe.Ing ing : candidate.ingredients()) {
            Integer req = required.remove(ing.item());
            if (req == null) continue;
            int more = req - placed.getOrDefault(ing.item(), 0);
            if (more > 0) missing.add(new ItemStack(ing.item(), more));
        }
        ghostIngredients = List.copyOf(missing);
        ghostResult = candidate.result().copy();
        ghostCandidateCount = choices;
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
        if (!cachedResult.isEmpty()) tag.put("Result", cachedResult.save(provider));
        if (!inProgress.isEmpty()) tag.put("InProgress", inProgress.save(provider));
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
