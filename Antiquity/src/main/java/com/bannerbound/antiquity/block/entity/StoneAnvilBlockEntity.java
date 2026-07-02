package com.bannerbound.antiquity.block.entity;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.bannerbound.antiquity.BannerboundAntiquity;
import com.bannerbound.antiquity.item.CrucibleContents;
import com.bannerbound.antiquity.metalworking.MetalworkingItems;
import com.bannerbound.antiquity.recipe.AnvilRecipe;
import com.bannerbound.antiquity.recipe.AnvilRecipeManager;

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
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The Stone Anvil — a Crafting-Stone-style pile station (place blade + stick, ghost-preview the
 * result, cold-hammer it into a quality tool) that ALSO doubles as the casting bench: place a fired
 * mold instead and pour a molten crucible into it (METALWORKING_PLAN.md). The two modes are mutually
 * exclusive — a placed mold disables the pile and vice-versa.
 *
 * <p>The pile + ghost-picker logic is the Fletching Station's, generic over {@link
 * GhostRecipeWorkstation}; the mold half is the casting state (shape + molten fill + cooling).
 */
public class StoneAnvilBlockEntity extends BlockEntity implements GhostRecipeWorkstation {
    public static final int MAX_ITEMS = 9;
    public static final int SLIDE_TICKS = 6;
    /** Ticks a freshly-poured charge stays molten before it solidifies (≈5s). */
    public static final int COOL_TICKS = 100;

    // ── Pile + ghost (Fletching-station clone) ──────────────────────────────────────────────────
    private final List<ItemStack> contents = new ArrayList<>();
    private ItemStack cachedResult = ItemStack.EMPTY;
    private List<ItemStack> ghostIngredients = List.of();
    private ItemStack ghostResult = ItemStack.EMPTY;
    private int ghostCandidateCount = 0;
    private Item ghostChoice = null;
    private boolean ghostLocked = false;
    private int insertAnimTicks = 0;
    private Direction insertDir = Direction.NORTH;
    private int lastSlideCell = -1;

    // ── Mold casting mode ───────────────────────────────────────────────────────────────────────
    private String moldShape = "";
    private int fillMb = 0;
    private String metalId = "";
    private int tintColor = 0xFFFFFF;
    private boolean molten = false;
    private int coolTicks = 0;

    // ── Forging (transient: the glowing workpiece on the anvil during the cold-hammer minigame) ────
    private ItemStack forgeItem = ItemStack.EMPTY;
    private int forgeStrikesDone = 0;
    private int forgeStrikesTotal = 0;
    private int forgeMetalColor = 0xFFFFFF;
    private long lastStruckGameTime = 0L;

    public StoneAnvilBlockEntity(BlockPos pos, BlockState state) {
        super(BannerboundAntiquity.STONE_ANVIL_BE.get(), pos, state);
    }

    // ── Pile ────────────────────────────────────────────────────────────────────────────────────
    public List<ItemStack> getContents() { return contents; }
    @Override public ItemStack getResult() { return cachedResult; }
    @Override public List<ItemStack> getGhostIngredients() { return ghostIngredients; }
    @Override public ItemStack getGhostResult() { return ghostResult; }
    @Override public int getGhostCandidateCount() { return ghostCandidateCount; }
    @Override public double ghostPreviewY() { return 1.3; }
    public int getInsertAnimTicks() { return insertAnimTicks; }
    public Direction getInsertDir() { return insertDir; }
    public int getLastSlideCell() { return lastSlideCell; }
    public boolean pileEmpty() { return contents.isEmpty(); }

    /** The recipe currently matched by the pile (null if none / output not researched). */
    public AnvilRecipe matchedRecipe() {
        AnvilRecipe recipe = AnvilRecipeManager.find(contents);
        if (recipe == null) return null;
        if (!com.bannerbound.core.api.research.CraftGating.canProduceAt(
                level, getBlockPos(), recipe.result().getItem())) {
            return null;
        }
        return recipe;
    }

    private int totalCount() {
        int n = 0;
        for (ItemStack s : contents) n += s.getCount();
        return n;
    }

    @Override
    public boolean insertOne(ItemStack held, Direction from) {
        if (hasMold() || held.isEmpty() || totalCount() >= MAX_ITEMS) return false;
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
        if (contents.isEmpty()) return ItemStack.EMPTY;
        ItemStack last = contents.get(contents.size() - 1);
        ItemStack out = last.copyWithCount(1);
        last.shrink(1);
        if (last.isEmpty()) contents.remove(contents.size() - 1);
        recomputeResult();
        setChanged();
        return out;
    }

    /** Consumes the whole pile (the cold-hammer commit point). */
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

    private void recomputeResult() {
        ghostIngredients = List.of();
        ghostResult = ItemStack.EMPTY;
        ghostCandidateCount = 0;
        AnvilRecipe recipe = matchedRecipe();
        cachedResult = recipe == null ? ItemStack.EMPTY : recipe.result().copy();
        if (cachedResult.isEmpty()) {
            recomputeGhost();
            return;
        }
        if (ghostLocked && ghostChoice != null && !cachedResult.is(ghostChoice)) {
            recomputeGhost();
        }
    }

    private List<AnvilRecipe> researchedCandidates() {
        List<AnvilRecipe> out = new ArrayList<>();
        for (AnvilRecipe candidate : AnvilRecipeManager.candidates(contents)) {
            if (com.bannerbound.core.api.research.CraftGating.canProduceAt(
                    level, getBlockPos(), candidate.result().getItem())) {
                out.add(candidate);
            }
        }
        return out;
    }

    private void recomputeGhost() {
        List<AnvilRecipe> researched = researchedCandidates();
        ghostCandidateCount = researched.size();
        if (contents.isEmpty()) {
            ghostChoice = null;
            ghostLocked = false;
            return;
        }
        if (researched.isEmpty()) return;
        int idx = indexOfChoice(researched);
        if (idx < 0 && ghostLocked) return;
        applyGhost(researched.get(Math.max(0, idx)));
    }

    @Override
    public void cycleGhost(int dir) {
        if (ghostResult.isEmpty()) return;
        List<AnvilRecipe> researched = researchedCandidates();
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

    private int indexOfChoice(List<AnvilRecipe> researched) {
        for (int i = 0; i < researched.size(); i++) {
            if (ghostChoice != null && researched.get(i).result().is(ghostChoice)) return i;
        }
        return -1;
    }

    private void applyGhost(AnvilRecipe candidate) {
        ghostChoice = candidate.result().getItem();
        Map<Item, Integer> placed = new HashMap<>();
        for (ItemStack s : contents) {
            if (!s.isEmpty()) placed.merge(s.getItem(), s.getCount(), Integer::sum);
        }
        Map<Item, Integer> required = candidate.requiredCounts();
        List<ItemStack> missing = new ArrayList<>();
        for (AnvilRecipe.Ing ing : candidate.ingredients()) {
            Integer req = required.remove(ing.item());
            if (req == null) continue;
            int more = req - placed.getOrDefault(ing.item(), 0);
            if (more > 0) missing.add(new ItemStack(ing.item(), more));
        }
        ghostIngredients = List.copyOf(missing);
        ghostResult = candidate.result().copy();
    }

    // ── Mold casting mode ───────────────────────────────────────────────────────────────────────
    public boolean hasMold() { return !moldShape.isEmpty(); }
    public String moldShape() { return moldShape; }
    public int fillMb() { return fillMb; }
    public int requiredMb() { return hasMold() ? MetalworkingItems.requiredMb(moldShape) : 0; }
    public String metalId() { return metalId; }
    public int tintColor() { return tintColor; }
    public boolean molten() { return molten; }

    public float fillFraction() {
        int req = requiredMb();
        return req <= 0 ? 0f : Math.min(1f, (float) fillMb / req);
    }

    public boolean isCastReady() { return hasMold() && !molten && fillMb >= requiredMb(); }
    public boolean isDud() { return hasMold() && !molten && fillMb > 0 && fillMb < requiredMb(); }

    /** Put an (empty) fired mold of {@code shape} on the anvil (only when the pile is empty). */
    public void placeMold(String shape) {
        this.moldShape = shape;
        this.fillMb = 0;
        this.metalId = "";
        this.molten = false;
        this.coolTicks = 0;
        sync();
    }

    public int pour(CrucibleContents charge) {
        if (!hasMold() || charge.isEmpty() || !charge.molten()) return 0;
        int need = requiredMb() - fillMb;
        if (need <= 0) return 0;
        int moved = Math.min(need, charge.totalMb());
        if (moved <= 0) return 0;
        this.metalId = charge.dominantMetal();
        this.tintColor = charge.tintColor();
        this.fillMb += moved;
        this.molten = true;
        this.coolTicks = COOL_TICKS;
        sync();
        return moved;
    }

    /** Pour up to {@code maxMb} of {@code metalId} into the mold (capped at what it still needs);
     *  returns the mB actually added. Used by the hold-to-pour crucible for a gradual rise. */
    public int pourInto(String metal, int tint, int maxMb) {
        if (!hasMold()) return 0;
        int need = requiredMb() - fillMb;
        if (need <= 0 || maxMb <= 0) return 0;
        int moved = Math.min(need, maxMb);
        this.metalId = metal;
        this.tintColor = tint;
        this.fillMb += moved;
        this.molten = true;
        this.coolTicks = COOL_TICKS;
        sync();
        return moved;
    }

    public ItemStack extractCasting() {
        if (!isCastReady()) return ItemStack.EMPTY;
        ItemStack out = MetalworkingItems.castingFor(moldShape, metalId);
        clearMold();
        return out;
    }

    /** Return the placed fired mold itself (when empty / before pouring). */
    public ItemStack takeMold() {
        if (!hasMold() || fillMb > 0) return ItemStack.EMPTY;
        var item = MetalworkingItems.MOLDS.get("fired_clay_mold_" + moldShape);
        clearMold();
        return item != null ? new ItemStack(item.get()) : ItemStack.EMPTY;
    }

    public void clearMold() {
        this.moldShape = "";
        this.fillMb = 0;
        this.metalId = "";
        this.molten = false;
        this.coolTicks = 0;
        sync();
    }

    // ── Forging (glowing workpiece) ───────────────────────────────────────────────────────────────
    public boolean isForging() { return !forgeItem.isEmpty(); }
    public ItemStack forgeItem() { return forgeItem; }
    public int forgeMetalColor() { return forgeMetalColor; }
    public long lastStruckGameTime() { return lastStruckGameTime; }

    /** Heat 1..0 — white-hot when fresh, cooling toward the metal tint as strikes land. */
    public float forgeHeat() {
        if (forgeStrikesTotal <= 0) return 1f;
        return Math.max(0f, 1f - (forgeStrikesDone / (float) forgeStrikesTotal) * 0.85f);
    }

    /** Begin showing the glowing workpiece (on the cold-hammer commit). */
    public void beginForging(ItemStack workpiece, int strikesTotal, int metalColor) {
        this.forgeItem = workpiece.copyWithCount(1);
        this.forgeStrikesDone = 0;
        this.forgeStrikesTotal = Math.max(1, strikesTotal);
        this.forgeMetalColor = metalColor;
        this.lastStruckGameTime = level != null ? level.getGameTime() : 0L;
        sync();
    }

    /** Register a landed strike (advances cooling + the recoil-bob timestamp). */
    public void forgeStrike() {
        if (!isForging()) return;
        forgeStrikesDone++;
        lastStruckGameTime = level != null ? level.getGameTime() : 0L;
        sync();
    }

    public void endForging() {
        forgeItem = ItemStack.EMPTY;
        forgeStrikesDone = 0;
        forgeStrikesTotal = 0;
        sync();
    }

    // ── Ticking ─────────────────────────────────────────────────────────────────────────────────
    public static void tick(Level level, BlockPos pos, BlockState state, StoneAnvilBlockEntity be) {
        if (be.insertAnimTicks > 0) be.insertAnimTicks--;
        if (level.isClientSide) {
            if (be.isForging()) spawnForgeEmbers(level, pos, be);
            return;
        }
        if (be.molten && be.fillMb > 0) {
            if (be.coolTicks > 0) {
                be.coolTicks--;
            } else {
                be.molten = false; // solidified — castable (or a dud if under-filled)
                be.sync();
            }
        }
    }

    /** Lazy heat shimmer rising off the hot workpiece while it's being forged (client only). */
    private static void spawnForgeEmbers(Level level, BlockPos pos, StoneAnvilBlockEntity be) {
        long t = level.getGameTime();
        if ((t & 3L) != 0L) return; // every 4th tick
        float heat = be.forgeHeat();
        var rand = level.random;
        double x = pos.getX() + 0.5 + (rand.nextDouble() - 0.5) * 0.25;
        double y = pos.getY() + 0.9;
        double z = pos.getZ() + 0.5 + (rand.nextDouble() - 0.5) * 0.25;
        // A rising ember plus a wisp of heat-haze smoke; embers thin out as the piece cools.
        if (rand.nextFloat() < 0.4f + heat * 0.5f) {
            level.addParticle(net.minecraft.core.particles.ParticleTypes.LAVA, x, y, z, 0.0, 0.02, 0.0);
        }
        level.addParticle(net.minecraft.core.particles.ParticleTypes.SMOKE,
            x, y + 0.05, z, 0.0, 0.03 + heat * 0.03, 0.0);
    }

    private void sync() {
        setChanged();
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
        tag.putString("MoldShape", moldShape);
        tag.putInt("FillMb", fillMb);
        tag.putString("MetalId", metalId);
        tag.putInt("TintColor", tintColor);
        tag.putBoolean("Molten", molten);
        tag.putInt("CoolTicks", coolTicks);
        if (!forgeItem.isEmpty()) tag.put("ForgeItem", forgeItem.save(provider));
        tag.putInt("ForgeDone", forgeStrikesDone);
        tag.putInt("ForgeTotal", forgeStrikesTotal);
        tag.putInt("ForgeColor", forgeMetalColor);
        tag.putLong("ForgeStruck", lastStruckGameTime);
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
        moldShape = tag.getString("MoldShape");
        fillMb = tag.getInt("FillMb");
        metalId = tag.getString("MetalId");
        tintColor = tag.contains("TintColor") ? tag.getInt("TintColor") : 0xFFFFFF;
        molten = tag.getBoolean("Molten");
        coolTicks = tag.getInt("CoolTicks");
        forgeItem = tag.contains("ForgeItem")
            ? ItemStack.parse(provider, tag.getCompound("ForgeItem")).orElse(ItemStack.EMPTY)
            : ItemStack.EMPTY;
        forgeStrikesDone = tag.getInt("ForgeDone");
        forgeStrikesTotal = tag.getInt("ForgeTotal");
        forgeMetalColor = tag.contains("ForgeColor") ? tag.getInt("ForgeColor") : 0xFFFFFF;
        lastStruckGameTime = tag.getLong("ForgeStruck");
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
