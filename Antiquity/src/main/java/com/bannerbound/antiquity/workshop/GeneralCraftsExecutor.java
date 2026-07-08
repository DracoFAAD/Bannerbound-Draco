package com.bannerbound.antiquity.workshop;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.annotation.Nullable;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.antiquity.BannerboundAntiquity;
import com.bannerbound.antiquity.block.entity.CraftingStoneBlockEntity;
import com.bannerbound.antiquity.recipe.CraftingStoneRecipe;
import com.bannerbound.antiquity.recipe.CraftingStoneRecipeManager;
import com.bannerbound.core.api.research.CraftGating;
import com.bannerbound.core.api.settlement.Workshop;
import com.bannerbound.core.api.workshop.WorkExecutor;
import com.bannerbound.core.api.workshop.WorkshopStorage;
import com.bannerbound.core.entity.CitizenEntity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import com.bannerbound.antiquity.event.AntiquityEvents;
import com.bannerbound.antiquity.craft.Fletching;
import com.bannerbound.antiquity.craft.Tannery;

/**
 * The crafter's driver at a Crafting Stone ("General Crafts" workshops). chooseCraft runs, in
 * order: RACK-TAKE (lift a finished CRAFT-category dry off a workshop drying rack, ungated - it
 * jams the slot until lifted; food slots belong to the Cook, the cured-hide "none" line to the
 * Tannery) -> player orders (FIFO; orders outrank and ignore min-stock; an order missing
 * ingredients is skipped, never blocking the queue) -> min-stock deficits -> RACK-HANG (start a
 * new drying unit, gated on NET demand: hanging units count as in-flight per the
 * Workshops.wantsAnother waiting-stage contract). Rack steps are identified structurally
 * (RACK-TAKE = result with no inputs; RACK-HANG = the only resultless craft this executor makes),
 * execute at the rack (workTarget) and touch the world only in finish(); a rack that raced full
 * hands the input back, and the rack's own timer does the drying.
 *
 * <p>handRecipes are the NPC equivalents of the player's two-rocks / hard-surface knapping
 * gestures (AntiquityEvents.onKnapHardSurface): flint -> flint blade, bone -> 2 bone blades, the
 * gravel sift at its deterministic expected rate (4 gravel -> 1 flint), and the seven stone tool
 * heads (one rock -> one head). They are modelled as {@link CraftingStoneRecipe}s ONLY so every
 * planning surface (chooseCraft, possibleOutputs, the stocker's supply/keep views, min-stock rows)
 * iterates them like the data-driven recipes - but they are NOT performed on the Crafting Stone:
 * knapping is a HAND-CRAFT (KNAPPING_PLAN.md; the stone is for hafting/assembly only), so isKnap
 * crafts show render-copy rocks in the crafter's hands (the crafter carries no real tool,
 * toolRequired=false) instead of a pile. Stone crafts place the real pile and resolve via
 * be.craft(), which runs the same gating recompute as the player path; if the pile was disturbed
 * despite the lock, the planned result is used and the pile drained.
 *
 * <p>Quality is the NPC's edge: a player knapping at the stone gets the plain item (no minigame,
 * no roll), but damageable outputs here get the shared XP-driven QualityMath.simulateNpcTier roll,
 * keeping the HIGHER of the rolled tier and any tier the input carried (be.craft() transfers a
 * hafted head's tier onto the result) so a player-knapped FINE head is never wasted. NPC-knapped
 * heads stay plain; their quality is rolled at the haft step. Stocker surfaces: missingInputs
 * buffers raws to INPUT_BUFFER_CRAFTS crafts, but chain intermediates (anything producedHere) are
 * ALWAYS sized at TRUE need so one wanted sword pulls one blade, not four; trueInputDemand drops
 * the buffer entirely; drying-input demand subtracts units already hanging in flight.
 */
@ApiStatus.Internal
public class GeneralCraftsExecutor implements WorkExecutor {
    public static final String XP_KEY = "general_crafts";
    private static final int WORK_TICKS = 80;
    private static final int BEATS = 4;

    // Built lazily: the item holders are not resolvable at class-init time.
    private static List<CraftingStoneRecipe> handRecipes;
    private static java.util.Set<Item> knapResults;

    private static void ensureHandRecipes() {
        if (handRecipes != null) return;
        handRecipes = List.of(
            new CraftingStoneRecipe(
                List.of(new CraftingStoneRecipe.Ing(net.minecraft.world.item.Items.FLINT, 1)),
                new ItemStack(com.bannerbound.antiquity.BannerboundAntiquity.FLINT_BLADE.get()), false),
            new CraftingStoneRecipe(
                List.of(new CraftingStoneRecipe.Ing(net.minecraft.world.item.Items.BONE, 1)),
                new ItemStack(com.bannerbound.antiquity.BannerboundAntiquity.BONE_BLADE.get(), 2), false),
            new CraftingStoneRecipe(
                List.of(new CraftingStoneRecipe.Ing(net.minecraft.world.item.Items.GRAVEL, 4)),
                new ItemStack(net.minecraft.world.item.Items.FLINT), false),
            stoneHead(com.bannerbound.antiquity.BannerboundAntiquity.STONE_PICK_HEAD.get()),
            stoneHead(com.bannerbound.antiquity.BannerboundAntiquity.STONE_AXE_HEAD.get()),
            stoneHead(com.bannerbound.antiquity.BannerboundAntiquity.STONE_SHOVEL_HEAD.get()),
            stoneHead(com.bannerbound.antiquity.BannerboundAntiquity.STONE_HOE_HEAD.get()),
            stoneHead(com.bannerbound.antiquity.BannerboundAntiquity.STONE_SWORD_BLADE.get()),
            stoneHead(com.bannerbound.antiquity.BannerboundAntiquity.STONE_SPEAR_POINT.get()),
            stoneHead(com.bannerbound.antiquity.BannerboundAntiquity.STONE_KNIFE_HEAD.get()));
        java.util.Set<Item> set = new java.util.HashSet<>();
        for (CraftingStoneRecipe r : handRecipes) set.add(r.result().getItem());
        knapResults = set;
    }

    private static List<CraftingStoneRecipe> allRecipes() {
        ensureHandRecipes();
        List<CraftingStoneRecipe> out = new ArrayList<>(CraftingStoneRecipeManager.all());
        out.addAll(handRecipes);
        return out;
    }

    private static boolean isKnap(Craft craft) {
        ensureHandRecipes();
        return knapResults.contains(craft.result().getItem());
    }

    private static boolean producedHere(Item item) {
        for (CraftingStoneRecipe r : allRecipes()) {
            if (r.result().getItem() == item) return true;
        }
        return false;
    }

    private static CraftingStoneRecipe stoneHead(Item head) {
        return new CraftingStoneRecipe(
            List.of(new CraftingStoneRecipe.Ing(
                com.bannerbound.antiquity.BannerboundAntiquity.STONE_ROCK_ITEM.get(), 1)),
            new ItemStack(head), false);
    }

    @Override
    @Nullable
    public Craft chooseCraft(ServerLevel sl, com.bannerbound.core.api.settlement.Settlement settlement,
                             Workshop workshop, BlockPos workBlock) {
        List<BlockPos> racks = RackTending.racks(sl, workshop);
        if (!racks.isEmpty()) {
            BlockPos dry = RackTending.rackWithDry(sl, racks,
                com.bannerbound.antiquity.recipe.DryingRackRecipe.CRAFT);
            if (dry != null) {
                ItemStack dried = RackTending.dryResultAt(sl, dry,
                    com.bannerbound.antiquity.recipe.DryingRackRecipe.CRAFT);
                if (!dried.isEmpty()) {
                    return new Craft(List.of(), dried, RACK_HANDLE_TICKS, 1);
                }
            }
        }
        for (Item wanted : com.bannerbound.core.api.workshop.Workshops.orderedItems(workshop)) {
            for (CraftingStoneRecipe recipe : allRecipes()) {
                if (recipe.result().getItem() != wanted) continue;
                Craft c = tryCraft(sl, workshop, workBlock, recipe);
                if (c != null) return c;
            }
        }
        for (CraftingStoneRecipe recipe : allRecipes()) {
            if (!com.bannerbound.core.api.workshop.Workshops.wantedByMinStock(
                    sl, settlement, workshop, recipe.result())) continue;
            Craft c = tryCraft(sl, workshop, workBlock, recipe);
            if (c != null) return c;
        }
        if (!racks.isEmpty() && RackTending.rackWithRoom(sl, racks) != null) {
            for (boolean ordersOnly : new boolean[] { true, false }) {
                for (var recipe : RackTending.recipes(
                        com.bannerbound.antiquity.recipe.DryingRackRecipe.CRAFT)) {
                    ItemStack dried = recipe.result();
                    if (dried.isEmpty()
                            || !CraftGating.canProduceAt(sl, workBlock, dried.getItem())) continue;
                    int inFlight = RackTending.inFlightForResult(sl, racks,
                        com.bannerbound.antiquity.recipe.DryingRackRecipe.CRAFT, dried.getItem());
                    boolean wanted = ordersOnly
                        ? com.bannerbound.core.api.workshop.Workshops
                            .orderedCraftCount(workshop, dried.getItem()) - inFlight > 0
                        : com.bannerbound.core.api.workshop.Workshops
                            .wantsAnother(sl, settlement, workshop, dried, inFlight);
                    if (!wanted) continue;
                    if (WorkshopStorage.count(sl, workshop, recipe.input()) <= 0) continue;
                    return new Craft(List.of(new ItemStack(recipe.input())),
                        ItemStack.EMPTY, RACK_HANDLE_TICKS, 2);
                }
            }
        }
        return null;
    }

    private static final int RACK_HANDLE_TICKS = 30;

    private static boolean isRackTake(Craft craft) {
        return !craft.result().isEmpty() && craft.inputs().isEmpty();
    }

    private static boolean isRackHang(Craft craft) {
        return craft.result().isEmpty();
    }

    private static final int INPUT_BUFFER_CRAFTS = 4;

    private static List<CraftingStoneRecipe> wantedRecipes(ServerLevel sl,
            com.bannerbound.core.api.settlement.Settlement settlement,
            Workshop workshop, BlockPos workBlock) {
        List<CraftingStoneRecipe> out = new ArrayList<>();
        for (Item wanted : com.bannerbound.core.api.workshop.Workshops.orderedItems(workshop)) {
            for (CraftingStoneRecipe r : allRecipes()) {
                if (r.result().getItem() == wanted
                        && CraftGating.canProduceAt(sl, workBlock, r.result().getItem())
                        && !out.contains(r)) {
                    out.add(r);
                }
            }
        }
        for (CraftingStoneRecipe r : allRecipes()) {
            if (!CraftGating.canProduceAt(sl, workBlock, r.result().getItem())) continue;
            if (!com.bannerbound.core.api.workshop.Workshops.wantedByMinStock(
                    sl, settlement, workshop, r.result())) continue;
            if (!out.contains(r)) out.add(r);
        }
        return out;
    }

    @Override
    public List<ItemStack> missingInputs(ServerLevel sl,
            com.bannerbound.core.api.settlement.Settlement settlement,
            Workshop workshop, BlockPos workBlock) {
        return demandStacks(sl, settlement, workshop, workBlock, true);
    }

    @Override
    public List<ItemStack> trueInputDemand(ServerLevel sl,
            com.bannerbound.core.api.settlement.Settlement settlement,
            Workshop workshop, BlockPos workBlock) {
        return demandStacks(sl, settlement, workshop, workBlock, false);
    }

    private List<ItemStack> demandStacks(ServerLevel sl,
            com.bannerbound.core.api.settlement.Settlement settlement,
            Workshop workshop, BlockPos workBlock, boolean bufferRaws) {
        Map<Item, Integer> desired = new java.util.LinkedHashMap<>();
        for (CraftingStoneRecipe r : wantedRecipes(sl, settlement, workshop, workBlock)) {
            int orders = com.bannerbound.core.api.workshop.Workshops
                .orderedCraftCount(workshop, r.result().getItem());
            int trueNeed = orders + com.bannerbound.core.api.workshop.Workshops
                .minStockDeficit(sl, settlement, workshop, r.result());
            if (trueNeed <= 0) continue;
            int rawCrafts = !bufferRaws ? trueNeed : orders > 0 ? orders : INPUT_BUFFER_CRAFTS;
            for (Map.Entry<Item, Integer> e : r.requiredCounts().entrySet()) {
                int crafts = producedHere(e.getKey()) ? trueNeed : rawCrafts;
                desired.merge(e.getKey(), e.getValue() * crafts, Integer::sum);
            }
        }
        List<BlockPos> racks = RackTending.racks(sl, workshop);
        for (var r : RackTending.recipes(com.bannerbound.antiquity.recipe.DryingRackRecipe.CRAFT)) {
            ItemStack dried = r.result();
            if (dried.isEmpty() || !CraftGating.canProduceAt(sl, workBlock, dried.getItem())) continue;
            int trueNeed = com.bannerbound.core.api.workshop.Workshops
                    .orderedCraftCount(workshop, dried.getItem())
                + com.bannerbound.core.api.workshop.Workshops
                    .minStockDeficit(sl, settlement, workshop, dried)
                - RackTending.inFlightForResult(sl, racks,
                    com.bannerbound.antiquity.recipe.DryingRackRecipe.CRAFT, dried.getItem());
            if (trueNeed <= 0) continue;
            desired.merge(r.input(), bufferRaws ? Math.max(trueNeed, INPUT_BUFFER_CRAFTS) : trueNeed,
                Integer::sum);
        }
        Map<Item, Integer> deficit = deficits(sl, workshop, desired);
        List<ItemStack> out = new ArrayList<>(deficit.size());
        for (var e : deficit.entrySet()) out.add(new ItemStack(e.getKey(), e.getValue()));
        return out;
    }

    private static Map<Item, Integer> deficits(ServerLevel sl, Workshop workshop,
                                               Map<Item, Integer> desired) {
        Map<Item, Integer> deficit = new java.util.LinkedHashMap<>();
        for (Map.Entry<Item, Integer> e : desired.entrySet()) {
            int have = WorkshopStorage.count(sl, workshop, e.getKey());
            if (have < e.getValue()) deficit.put(e.getKey(), e.getValue() - have);
        }
        return deficit;
    }

    @Override
    public java.util.Set<Item> retainedItems(ServerLevel sl,
            com.bannerbound.core.api.settlement.Settlement settlement,
            Workshop workshop, BlockPos workBlock) {
        java.util.Set<Item> keep = new java.util.HashSet<>();
        for (CraftingStoneRecipe r : wantedRecipes(sl, settlement, workshop, workBlock)) {
            keep.addAll(r.requiredCounts().keySet());
        }
        for (var r : RackTending.recipes(com.bannerbound.antiquity.recipe.DryingRackRecipe.CRAFT)) {
            keep.add(r.input());
        }
        return keep;
    }

    @Nullable
    private static Craft tryCraft(ServerLevel sl, Workshop workshop, BlockPos workBlock,
                                  CraftingStoneRecipe recipe) {
        if (!CraftGating.canProduceAt(sl, workBlock, recipe.result().getItem())) return null;
        List<ItemStack> inputs = new ArrayList<>();
        for (Map.Entry<Item, Integer> e : recipe.requiredCounts().entrySet()) {
            if (WorkshopStorage.count(sl, workshop, e.getKey()) < e.getValue()) return null;
            inputs.add(new ItemStack(e.getKey(), e.getValue()));
        }
        return new Craft(inputs, recipe.result().copy(), WORK_TICKS, BEATS);
    }

    @Override
    public List<ItemStack> possibleOutputs(ServerLevel sl, BlockPos workBlock) {
        List<ItemStack> out = new ArrayList<>();
        for (CraftingStoneRecipe recipe : allRecipes()) {
            if (CraftGating.canProduceAt(sl, workBlock, recipe.result().getItem())) {
                out.add(recipe.result().copy());
            }
        }
        for (var recipe : RackTending.recipes(com.bannerbound.antiquity.recipe.DryingRackRecipe.CRAFT)) {
            if (!recipe.result().isEmpty()
                    && CraftGating.canProduceAt(sl, workBlock, recipe.result().getItem())) {
                out.add(recipe.result().copyWithCount(1));
            }
        }
        return out;
    }

    @Override
    public BlockPos workTarget(ServerLevel sl, com.bannerbound.core.api.settlement.Settlement settlement,
                               Workshop workshop, BlockPos workBlock, Craft craft) {
        if (isRackTake(craft) || isRackHang(craft)) {
            List<BlockPos> racks = RackTending.racks(sl, workshop);
            BlockPos target = isRackTake(craft)
                ? RackTending.rackWithDry(sl, racks, com.bannerbound.antiquity.recipe.DryingRackRecipe.CRAFT)
                : RackTending.rackWithRoom(sl, racks);
            return target != null ? target : workBlock;
        }
        return workBlock;
    }

    @Override
    public void onStart(ServerLevel sl, CitizenEntity citizen, BlockPos workBlock, Craft craft) {
        if (isRackTake(craft) || isRackHang(craft)) {
            return;
        }
        if (isKnap(craft)) {
            holdRocks(citizen);
            return;
        }
        if (sl.getBlockEntity(workBlock) instanceof CraftingStoneBlockEntity be) {
            for (ItemStack input : craft.inputs()) {
                for (int i = 0; i < input.getCount(); i++) {
                    be.insertOne(input.copyWithCount(1), Direction.NORTH);
                }
            }
        }
    }

    @Override
    public void onBeat(ServerLevel sl, CitizenEntity citizen, BlockPos workBlock, Craft craft,
                       int beatIndex) {
        if (isRackTake(craft) || isRackHang(craft)) {
            sl.playSound(null, citizen.blockPosition(), net.minecraft.sounds.SoundEvents.LEASH_KNOT_PLACE,
                SoundSource.BLOCKS, 0.5F, 1.0F);
            return;
        }
        double x, y, z;
        if (isKnap(craft)) {
            x = citizen.getX();
            y = citizen.getY() + citizen.getBbHeight() * 0.6;
            z = citizen.getZ();
        } else {
            x = workBlock.getX() + 0.5;
            y = workBlock.getY() + 0.7;
            z = workBlock.getZ() + 0.5;
        }
        sl.playSound(null, x, y, z, BannerboundAntiquity.KNAPPING_SOUND.get(),
            SoundSource.BLOCKS, 0.8F, 1.1F);
        sl.sendParticles(ParticleTypes.CRIT, x, y, z, 3, 0.2, 0.1, 0.2, 0.0);
    }

    @Override
    public ItemStack finish(ServerLevel sl, CitizenEntity citizen, BlockPos workBlock, Craft craft) {
        if (isRackTake(craft) || isRackHang(craft)) {
            com.bannerbound.core.api.settlement.Settlement s = citizen.getSettlement();
            Workshop w = s == null ? null : s.getWorkshop(citizen.getAssignedWorkshopId());
            if (w == null) {
                return isRackHang(craft) ? craft.inputs().get(0).copy() : ItemStack.EMPTY;
            }
            List<BlockPos> racks = RackTending.racks(sl, w);
            if (isRackTake(craft)) {
                BlockPos rack = RackTending.rackWithDry(sl, racks,
                    com.bannerbound.antiquity.recipe.DryingRackRecipe.CRAFT);
                return rack == null ? ItemStack.EMPTY : RackTending.takeDry(sl, rack,
                    com.bannerbound.antiquity.recipe.DryingRackRecipe.CRAFT);
            }
            BlockPos rack = RackTending.rackWithRoom(sl, racks);
            if (rack != null && sl.getBlockEntity(rack)
                    instanceof com.bannerbound.antiquity.block.entity.DryingRackBlockEntity be
                    && be.hang(craft.inputs().get(0))) {
                return ItemStack.EMPTY;
            }
            return craft.inputs().get(0).copy();
        }
        ItemStack out = craft.result().copy();
        if (isKnap(craft)) {
            clearHands(citizen);
        } else if (sl.getBlockEntity(workBlock) instanceof CraftingStoneBlockEntity be) {
            ItemStack crafted = be.craft();
            if (!crafted.isEmpty()) {
                out = crafted;
            } else {
                while (!be.removeOne().isEmpty()) {
                }
            }
        }
        if (out.has(net.minecraft.core.component.DataComponents.MAX_DAMAGE)) {
            com.bannerbound.core.api.quality.QualityTier npcTier =
                com.bannerbound.core.api.quality.QualityMath.simulateNpcTier(
                    sl.random, citizen.getJobXp(XP_KEY), BEATS);
            com.bannerbound.core.api.quality.QualityTier inputTier =
                com.bannerbound.core.api.quality.QualityTier.of(out);
            com.bannerbound.core.api.quality.QualityTier tier =
                npcTier.ordinal() >= inputTier.ordinal() ? npcTier : inputTier;
            out = com.bannerbound.antiquity.craft.Fletching.applyQuality(out, tier);
        }
        return out;
    }

    @Override
    public void onAbort(ServerLevel sl, CitizenEntity citizen, BlockPos workBlock, Craft craft) {
        if (isRackTake(craft) || isRackHang(craft)) {
            return;
        }
        if (isKnap(craft)) {
            clearHands(citizen);
            return;
        }
        if (sl.getBlockEntity(workBlock) instanceof CraftingStoneBlockEntity be) {
            // Pile holds display copies; the goal returns the real withdrawn inputs -- drain, never drop.
            while (!be.removeOne().isEmpty()) {
            }
        }
    }

    private static void holdRocks(CitizenEntity citizen) {
        ItemStack rock = new ItemStack(BannerboundAntiquity.STONE_ROCK_ITEM.get());
        citizen.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND, rock.copy());
        citizen.setItemSlot(net.minecraft.world.entity.EquipmentSlot.OFFHAND, rock.copy());
    }

    private static void clearHands(CitizenEntity citizen) {
        citizen.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND, ItemStack.EMPTY);
        citizen.setItemSlot(net.minecraft.world.entity.EquipmentSlot.OFFHAND, ItemStack.EMPTY);
    }
}
