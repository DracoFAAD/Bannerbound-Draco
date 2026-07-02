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

/**
 * The crafter's driver at a Crafting Stone ("General Crafts" workshops): picks any researched
 * crafting-stone recipe whose ingredients the workshop storage holds, places the real pile on the
 * stone, knaps through a few beats, and takes the recipe result.
 *
 * <p><b>Quality is the NPC's edge here.</b> A player knapping at the stone gets the plain item —
 * there is no minigame, so no quality roll. The crafter NPC instead rolls the shared XP-driven
 * simulation ({@code QualityMath.simulateNpcTier}) and stamps it on any damageable output (bone
 * spears, knives, every bone tool). That makes staffing a General Crafts workshop a real
 * investment: a veteran's bone tools out-last anything a player can knap by hand.
 */
@ApiStatus.Internal
public class GeneralCraftsExecutor implements WorkExecutor {
    /** XP key — per-profession bucket (CrafterWorkGoal pays into the work block's typeId). */
    public static final String XP_KEY = "general_crafts";
    private static final int WORK_TICKS = 80;
    private static final int BEATS = 4;

    /** The crafter's NPC equivalent of the player's two-rocks / hard-surface knapping gestures
     *  (AntiquityEvents.onKnapHardSurface): flint → flint blade, bone → 2 bone blades, the gravel
     *  sift at its expected 1-in-4 rate (4 gravel → 1 flint, deterministic), and the six stone tool
     *  heads (one rock → one head). Modelled as {@link CraftingStoneRecipe}s ONLY so every planning
     *  pipeline — chooseCraft, possibleOutputs, the stocker's supply/keep surfaces, min-stock rows —
     *  iterates them like the data-driven recipes; they are NOT performed on the Crafting Stone.
     *  Knapping is a HAND-CRAFT (two rocks, off the stone — KNAPPING_PLAN.md); the stone is for
     *  hafting/assembly only. See {@link #isKnap} and onStart/onBeat/finish for the split. Built
     *  lazily: the item holders aren't resolvable at class-init time. */
    private static List<CraftingStoneRecipe> handRecipes;
    /** Result items of {@link #handRecipes} — a craft producing one of these is a hand-knap, not a
     *  stone craft. Built alongside {@code handRecipes}. */
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
            // Stone tool heads — one rock → one head. NPC-only (not data recipes), so players still
            // knap by hand; a knapped head's quality is rolled at the haft step (the head is plain).
            stoneHead(com.bannerbound.antiquity.BannerboundAntiquity.STONE_PICK_HEAD.get()),
            stoneHead(com.bannerbound.antiquity.BannerboundAntiquity.STONE_AXE_HEAD.get()),
            stoneHead(com.bannerbound.antiquity.BannerboundAntiquity.STONE_SHOVEL_HEAD.get()),
            stoneHead(com.bannerbound.antiquity.BannerboundAntiquity.STONE_HOE_HEAD.get()),
            stoneHead(com.bannerbound.antiquity.BannerboundAntiquity.STONE_SWORD_BLADE.get()),
            stoneHead(com.bannerbound.antiquity.BannerboundAntiquity.STONE_SPEAR_POINT.get()));
        java.util.Set<Item> set = new java.util.HashSet<>();
        for (CraftingStoneRecipe r : handRecipes) set.add(r.result().getItem());
        knapResults = set;
    }

    /** Data-driven stone recipes + the knapping hand-recipes, one iteration surface. */
    private static List<CraftingStoneRecipe> allRecipes() {
        ensureHandRecipes();
        List<CraftingStoneRecipe> out = new ArrayList<>(CraftingStoneRecipeManager.all());
        out.addAll(handRecipes);
        return out;
    }

    /** True when {@code craft} is a hand-knap (produces a knapping output) rather than a stone
     *  haft/assembly — drives the off-station hand-craft path in onStart/onBeat/finish/onAbort. */
    private static boolean isKnap(Craft craft) {
        ensureHandRecipes();
        return knapResults.contains(craft.result().getItem());
    }

    /** True when {@code item} is something this workshop itself crafts (a recipe result) — i.e. a
     *  chain intermediate, not a raw material. Such inputs are demanded at true count, never the
     *  rolling input buffer, so a single wanted final doesn't over-produce its sub-assemblies. */
    private static boolean producedHere(Item item) {
        for (CraftingStoneRecipe r : allRecipes()) {
            if (r.result().getItem() == item) return true;
        }
        return false;
    }

    /** One rock → one stone tool head (the crafter's NPC equivalent of player knapping). */
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
        // RACK-TAKE — ungated collect: a finished CRAFT-category slot on a workshop drying rack
        // (plant fiber → thatch) jams the spot until lifted. Food slots belong to the Cook; the
        // cured-hide leather line ("none") stays the Tannery's.
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
        // Player orders first (FIFO; orders outrank + ignore the min-stock governor; an order
        // whose ingredients are missing is skipped, never blocking the rest of the queue).
        for (Item wanted : com.bannerbound.core.api.workshop.Workshops.orderedItems(workshop)) {
            for (CraftingStoneRecipe recipe : allRecipes()) {
                if (recipe.result().getItem() != wanted) continue;
                Craft c = tryCraft(sl, workshop, workBlock, recipe);
                if (c != null) return c;
            }
        }
        // Then positive min-stock deficits.
        for (CraftingStoneRecipe recipe : allRecipes()) {
            if (!com.bannerbound.core.api.workshop.Workshops.wantedByMinStock(
                    sl, settlement, workshop, recipe.result())) continue;
            Craft c = tryCraft(sl, workshop, workBlock, recipe);
            if (c != null) return c;
        }
        // RACK-HANG — start a new craft-category drying unit, gated on NET demand (hanging units
        // count as in-flight — the Workshops.wantsAnother waiting-stage contract).
        if (!racks.isEmpty() && RackTending.rackWithRoom(sl, racks) != null) {
            for (boolean ordersOnly : new boolean[] { true, false }) {
                for (var recipe : RackTending.recipes(
                        com.bannerbound.antiquity.recipe.DryingRackRecipe.CRAFT)) {
                    ItemStack dried = recipe.result();
                    if (dried.isEmpty()
                            || !CraftGating.canProduceAt(sl, workBlock, dried.getItem())) continue;
                    int inFlight = RackTending.inFlight(sl, racks, recipe);
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

    /** Laying an input on a rack / lifting the dried result off it — quick handling. */
    private static final int RACK_HANDLE_TICKS = 30;

    /** RACK-TAKE lifts a finished craft-category dry off a rack — result, no inputs withdrawn. */
    private static boolean isRackTake(Craft craft) {
        return !craft.result().isEmpty() && craft.inputs().isEmpty();
    }

    /** RACK-HANG lays a dryable input out — the only resultless craft this executor makes. */
    private static boolean isRackHang(Craft craft) {
        return craft.result().isEmpty();
    }

    /** Crafts the stocker keeps an input buffer for, per wanted recipe. */
    private static final int INPUT_BUFFER_CRAFTS = 4;

    /** Recipes this workshop currently WANTS (queued orders + positive min-stock deficits),
     *  gating applied, input availability ignored — the stocker's planning view. */
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
        // Production sizing: no rolling buffer at all — a chain producer makes only what's needed.
        return demandStacks(sl, settlement, workshop, workBlock, false);
    }

    /** The per-input deficit for every wanted recipe. {@code bufferRaws} = the haul surface (raws
     *  pre-stocked to {@link #INPUT_BUFFER_CRAFTS} crafts); without it every input is sized at the
     *  TRUE need (orders + min-stock deficit) for chain production. Crafted intermediates (inputs
     *  this workshop itself produces) are ALWAYS true-sized, so a single wanted final never pulls a
     *  buffer's worth of sub-assemblies (one sword → one blade, not four). */
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
        // Craft-category drying (plant fiber → thatch): the input demand behind every wanted dried
        // output, minus the units already hanging in flight on the workshop's racks.
        List<BlockPos> racks = RackTending.racks(sl, workshop);
        for (var r : RackTending.recipes(com.bannerbound.antiquity.recipe.DryingRackRecipe.CRAFT)) {
            ItemStack dried = r.result();
            if (dried.isEmpty() || !CraftGating.canProduceAt(sl, workBlock, dried.getItem())) continue;
            int trueNeed = com.bannerbound.core.api.workshop.Workshops
                    .orderedCraftCount(workshop, dried.getItem())
                + com.bannerbound.core.api.workshop.Workshops
                    .minStockDeficit(sl, settlement, workshop, dried)
                - RackTending.inFlight(sl, racks, r);
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

    /** Gating + ingredient-availability check for one recipe; null when not craftable right now. */
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

    /** Rack steps happen at the rack itself; everything else at the stone. */
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
            return;   // nothing on the stone — the rack is touched in finish()
        }
        if (isKnap(craft)) {
            // Hand-knap: two rocks in the hands, NO pile on the stone (KNAPPING_PLAN.md — the
            // Crafting Stone is for hafting only). The rocks are a render copy, cleared on finish.
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
        // Knapping cracks in the worker's hands; hafting/assembly happens on the stone. Either way
        // it's the same chip sound — only the origin moves so the effect tracks the actual action.
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
                return ItemStack.EMPTY;   // the rack's own timer dries it; RACK-TAKE collects
            }
            return craft.inputs().get(0).copy();   // raced full — hand the input back
        }
        ItemStack out = craft.result().copy();
        if (isKnap(craft)) {
            // Hand-knap: no pile to resolve — the goal already consumed the real inputs. Just put
            // the rocks away and hand back the (plain) head/blade; its quality is rolled at hafting.
            clearHands(citizen);
        } else if (sl.getBlockEntity(workBlock) instanceof CraftingStoneBlockEntity be) {
            // The pile exactly matches the recipe, so the stone's own craft() resolves it (and runs
            // the same gating recompute the player path uses). Fallback to the planned result if the
            // pile was disturbed despite the lock.
            ItemStack crafted = be.craft();
            if (!crafted.isEmpty()) {
                out = crafted;
            } else {
                while (!be.removeOne().isEmpty()) {
                    // drain
                }
            }
        }
        // NPC-only quality: damageable outputs (bone tools/weapons, hafted stone tools) get the
        // XP-driven tier roll players can't access at the stone. Non-damageable outputs (string,
        // thatch, the bare heads) pass through. When the input carried a better quality than the
        // crafter rolls — e.g. a player-knapped FINE stone head being hafted — keep the higher one
        // (be.craft() already transferred the head's tier onto the result) so good heads aren't wasted.
        if (out.has(net.minecraft.core.component.DataComponents.MAX_DAMAGE)) {
            com.bannerbound.core.api.quality.QualityTier npcTier =
                com.bannerbound.core.api.quality.QualityMath.simulateNpcTier(
                    sl.random, citizen.getJobXp(XP_KEY), BEATS);
            com.bannerbound.core.api.quality.QualityTier inputTier =
                com.bannerbound.core.api.quality.QualityTier.of(out);
            com.bannerbound.core.api.quality.QualityTier tier =
                npcTier.ordinal() >= inputTier.ordinal() ? npcTier : inputTier;
            out = com.bannerbound.antiquity.Fletching.applyQuality(out, tier);
        }
        return out;
    }

    @Override
    public void onAbort(ServerLevel sl, CitizenEntity citizen, BlockPos workBlock, Craft craft) {
        if (isRackTake(craft) || isRackHang(craft)) {
            return;   // nothing world-side is committed before finish() on the rack steps
        }
        if (isKnap(craft)) {
            clearHands(citizen);
            return;
        }
        if (sl.getBlockEntity(workBlock) instanceof CraftingStoneBlockEntity be) {
            // Display copies only — the goal returns the withdrawn inputs to storage.
            while (!be.removeOne().isEmpty()) {
                // drain
            }
        }
    }

    /** Show two rocks (hammerstone + core) in the crafter's hands for the duration of a hand-knap.
     *  A render copy only — the goal withdrew/consumed the real rock from workshop storage. */
    private static void holdRocks(CitizenEntity citizen) {
        ItemStack rock = new ItemStack(BannerboundAntiquity.STONE_ROCK_ITEM.get());
        citizen.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND, rock.copy());
        citizen.setItemSlot(net.minecraft.world.entity.EquipmentSlot.OFFHAND, rock.copy());
    }

    /** Clear the render rocks once the knap finishes/aborts. The crafter carries no real tool
     *  (toolRequired=false), so both hands return to empty. */
    private static void clearHands(CitizenEntity citizen) {
        citizen.setItemSlot(net.minecraft.world.entity.EquipmentSlot.MAINHAND, ItemStack.EMPTY);
        citizen.setItemSlot(net.minecraft.world.entity.EquipmentSlot.OFFHAND, ItemStack.EMPTY);
    }
}
