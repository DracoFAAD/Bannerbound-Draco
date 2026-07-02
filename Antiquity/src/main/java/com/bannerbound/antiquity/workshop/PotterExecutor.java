package com.bannerbound.antiquity.workshop;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nullable;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.antiquity.BannerboundAntiquity;
import com.bannerbound.antiquity.block.entity.KilnBlockEntity;
import com.bannerbound.antiquity.block.entity.PotterySlabBlockEntity;
import com.bannerbound.antiquity.recipe.KilnRecipe;
import com.bannerbound.antiquity.recipe.KilnRecipeManager;
import com.bannerbound.antiquity.recipe.PotteryRecipe;
import com.bannerbound.antiquity.recipe.PotteryRecipeManager;
import com.bannerbound.core.api.research.CraftGating;
import com.bannerbound.core.api.settlement.Settlement;
import com.bannerbound.core.api.settlement.Workshop;
import com.bannerbound.core.api.workshop.WorkExecutor;
import com.bannerbound.core.api.workshop.WorkshopStorage;
import com.bannerbound.core.api.workshop.Workshops;
import com.bannerbound.core.entity.CitizenEntity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;

/**
 * The Potter NPC's craft driver at a Pottery Slab. Unfired outputs are shaped from clay recipes;
 * fired outputs consume an already-shaped unfired item plus fire-starting/fuel supplies. If that
 * unfired item is missing, the stocker chain queues the shaping recipe as a normal intermediate.
 */
@ApiStatus.Internal
public class PotterExecutor implements WorkExecutor {
    public static final String XP_KEY = "pottery";
    private static final int TICKS_PER_SPIN = 32;
    private static final int INPUT_BUFFER_CRAFTS = 4;
    private static final int FIRE_STICKS_USE_TICKS = 40;
    private static final int FIRE_STICKS_SWING_INTERVAL_TICKS = 10;
    private static final int FIRING_FINISH_MARGIN_TICKS = 10;
    private static final int STOKE_WHEN_LIT_TICKS_AT_OR_BELOW = 20;
    private final Map<BlockPos, Integer> firedStokesUsed = new HashMap<>();
    private final Map<BlockPos, Integer> firedIgniteTicksLeft = new HashMap<>();

    private record Plan(PotteryRecipe shaping, @Nullable KilnRecipe firing, ItemStack result,
                        List<ItemStack> inputs, int workTicks, int beats) {
        boolean fired() {
            return firing != null;
        }
    }

    @Override
    @Nullable
    public Craft chooseCraft(ServerLevel sl, Settlement settlement, Workshop workshop, BlockPos workBlock) {
        pruneStaleSelfIntermediateAutoOrders(sl, settlement, workshop, workBlock);
        List<Plan> plans = availablePlans(sl, workBlock);
        for (Plan plan : plans) {
            if (plan.fired()) continue;
            if (Workshops.orderedCraftCount(workshop, plan.result().getItem()) <= 0) continue;
            Craft c = tryCraft(sl, settlement, workshop, workBlock, plan);
            if (c != null) return c;
        }
        for (Item wanted : Workshops.orderedItems(workshop)) {
            for (Plan plan : plans) {
                if (plan.result().getItem() != wanted) continue;
                Craft c = tryCraft(sl, settlement, workshop, workBlock, plan);
                if (c != null) return c;
            }
        }
        for (Plan plan : plans) {
            if (!Workshops.wantedByMinStock(sl, settlement, workshop, plan.result())) continue;
            Craft c = tryCraft(sl, settlement, workshop, workBlock, plan);
            if (c != null) return c;
        }
        return null;
    }

    @Override
    public BlockPos workTarget(ServerLevel sl, Settlement settlement, Workshop workshop,
                               BlockPos workBlock, Craft craft) {
        if (isFiredOutput(sl, workBlock, craft.result())) {
            BlockPos kiln = PotteryWorkshopRules.findCompleteKilnController(sl, workshop);
            if (kiln != null) return kiln;
        }
        return workBlock;
    }

    private static List<Plan> wantedPlans(ServerLevel sl, Settlement settlement,
                                           Workshop workshop, BlockPos workBlock) {
        List<Plan> plans = availablePlans(sl, workBlock);
        List<Plan> out = new ArrayList<>();
        for (Item wanted : Workshops.orderedItems(workshop)) {
            for (Plan plan : plans) {
                if (plan.result().getItem() == wanted && !out.contains(plan)) {
                    out.add(plan);
                }
            }
        }
        for (Plan plan : plans) {
            if (!Workshops.wantedByMinStock(sl, settlement, workshop, plan.result())) continue;
            if (!out.contains(plan)) out.add(plan);
        }
        return out;
    }

    private static void pruneStaleSelfIntermediateAutoOrders(ServerLevel sl, Settlement settlement,
                                                             Workshop workshop, BlockPos workBlock) {
        Set<Item> stillNeeded = new java.util.HashSet<>();
        for (Plan plan : availablePlans(sl, workBlock)) {
            if (!plan.fired()) continue;
            int crafts = Workshops.orderedCraftCount(workshop, plan.result().getItem());
            if (crafts <= 0 && Workshops.wantedByMinStock(sl, settlement, workshop, plan.result())) {
                crafts = INPUT_BUFFER_CRAFTS;
            }
            crafts = Math.max(0, crafts - kilnResultCraftsInProgress(sl, workshop, plan));
            if (crafts <= 0) continue;
            for (ItemStack input : plan.inputs()) {
                if (!isKilnSupply(input)) stillNeeded.add(input.getItem());
            }
        }

        String self = workshop.id().toString();
        boolean dirty = false;
        for (String itemId : new ArrayList<>(workshop.autoOrders().keySet())) {
            if (!self.equals(workshop.autoOrderSources().get(itemId))) continue;
            Item item = itemFromId(itemId);
            if (item != Items.AIR && stillNeeded.contains(item)) continue;
            workshop.autoOrders().remove(itemId);
            workshop.autoOrderSources().remove(itemId);
            dirty = true;
        }
        if (dirty) {
            com.bannerbound.core.api.settlement.SettlementData
                .get(sl.getServer().overworld()).setDirty();
        }
    }

    @Override
    public List<ItemStack> missingInputs(ServerLevel sl, Settlement settlement, Workshop workshop,
                                         BlockPos workBlock) {
        return demandStacks(sl, settlement, workshop, workBlock, true);
    }

    @Override
    public List<ItemStack> trueInputDemand(ServerLevel sl, Settlement settlement, Workshop workshop,
                                           BlockPos workBlock) {
        // Production sizing: no rolling buffer — if an input has to be chain-crafted, the producer
        // makes only what the orders/min-stock truly need. (Kiln in-progress gating still applies.)
        return demandStacks(sl, settlement, workshop, workBlock, false);
    }

    /** Per-input deficit for every wanted plan. {@code bufferRaws} = the haul surface (inputs
     *  pre-stocked to {@link #INPUT_BUFFER_CRAFTS} crafts); without it inputs are sized at the TRUE
     *  need (orders + min-stock deficit) for chain production. The fired-plan kiln in-progress
     *  subtraction (a waiting-stage guard) applies to both. */
    private List<ItemStack> demandStacks(ServerLevel sl, Settlement settlement, Workshop workshop,
                                         BlockPos workBlock, boolean bufferRaws) {
        Map<Item, Integer> desired = new LinkedHashMap<>();
        for (Plan plan : wantedPlans(sl, settlement, workshop, workBlock)) {
            int orders = Workshops.orderedCraftCount(workshop, plan.result().getItem());
            int crafts = bufferRaws
                ? (orders > 0 ? orders
                    : Workshops.wantedByMinStock(sl, settlement, workshop, plan.result())
                        ? INPUT_BUFFER_CRAFTS : 0)
                : orders + Workshops.minStockDeficit(sl, settlement, workshop, plan.result());
            if (plan.fired()) {
                crafts = Math.max(0, crafts - kilnResultCraftsInProgress(sl, workshop, plan));
            }
            if (crafts <= 0) continue;
            if (plan.fired()) {
                addFiredDesiredInputs(sl, workshop, workBlock, plan, crafts, desired);
                continue;
            }
            for (ItemStack input : plan.inputs()) {
                int count = input.getCount() * crafts;
                if (count <= 0) continue;
                if (input.is(BannerboundAntiquity.FIRE_STICKS.get())) {
                    desired.merge(input.getItem(), input.getCount(), Math::max);
                } else {
                    desired.merge(input.getItem(), count, Integer::sum);
                }
            }
        }
        Map<Item, Integer> deficit = deficits(sl, workshop, desired);
        List<ItemStack> out = new ArrayList<>(deficit.size());
        for (Map.Entry<Item, Integer> e : deficit.entrySet()) {
            out.add(new ItemStack(e.getKey(), e.getValue()));
        }
        return out;
    }

    private static void addFiredDesiredInputs(ServerLevel sl, Workshop workshop, BlockPos workBlock,
                                              Plan plan, int units, Map<Item, Integer> desired) {
        ItemStack ingredientUnit = planFiringIngredient(plan);
        if (ingredientUnit == null || ingredientUnit.isEmpty()) return;
        int ingredientCount = Math.max(1, ingredientUnit.getCount()) * Math.max(1, units);
        int inKilnIngredient = kilnIngredientCountInProgress(sl, workshop, ingredientUnit);
        ingredientCount = Math.max(0, ingredientCount - inKilnIngredient);
        if (ingredientCount > 0) {
            desired.merge(ingredientUnit.getItem(), ingredientCount, Integer::sum);
        }

        if (inKilnIngredient <= 0) {
            desired.merge(BannerboundAntiquity.FIRE_STICKS.get(), 1, Math::max);
        }
        int firingTicks = firingTicksForIngredientCount(plan.firing(),
            Math.max(1, ingredientUnit.getCount()) * Math.max(1, units));
        KilnBlockEntity kiln = kilnFor(sl, workshop);
        int initialBurnTicks = kiln != null && kiln.isLit()
            ? kiln.getLitTicks() : KilnBlockEntity.MAX_LIT_TICKS;
        int charcoal = charcoalNeeded(firingTicks, initialBurnTicks);
        if (charcoal > 0) {
            desired.merge(Items.CHARCOAL, charcoal, Integer::sum);
        }
    }

    @Override
    public Set<Item> retainedItems(ServerLevel sl, Settlement settlement, Workshop workshop,
                                   BlockPos workBlock) {
        Set<Item> keep = new java.util.HashSet<>();
        for (Plan plan : wantedPlans(sl, settlement, workshop, workBlock)) {
            for (ItemStack input : plan.inputs()) {
                keep.add(input.getItem());
            }
        }
        return keep;
    }

    @Nullable
    private static Craft tryCraft(ServerLevel sl, Settlement settlement, Workshop workshop,
                                  BlockPos workBlock, Plan plan) {
        if (plan.fired()) {
            return tryFiredCraft(sl, settlement, workshop, workBlock, plan);
        }
        List<ItemStack> inputs = new ArrayList<>();
        for (ItemStack input : plan.inputs()) {
            if (WorkshopStorage.count(sl, workshop, input.getItem()) < input.getCount()) return null;
            inputs.add(input.copy());
        }
        return new Craft(inputs, plan.result().copy(), plan.workTicks(), plan.beats());
    }

    @Nullable
    private static Craft tryFiredCraft(ServerLevel sl, Settlement settlement, Workshop workshop,
                                       BlockPos workBlock, Plan plan) {
        KilnBlockEntity kiln = kilnFor(sl, workshop);
        if (kiln == null || !kiln.getHeldItem().isEmpty()) return null;
        ItemStack ingredientUnit = planFiringIngredient(plan);
        if (ingredientUnit == null || ingredientUnit.isEmpty()) return null;

        int wantedUnits = wantedCraftUnits(sl, settlement, workshop, plan);
        if (wantedUnits <= 0) return null;
        int unitIngredientCount = Math.max(1, ingredientUnit.getCount());
        int unitResultCount = Math.max(1, plan.result().getCount());
        int storedIngredientUnits =
            WorkshopStorage.count(sl, workshop, ingredientUnit.getItem()) / unitIngredientCount;
        int ingredientStackUnits = Math.max(1, ingredientUnit.getMaxStackSize() / unitIngredientCount);
        int resultStackUnits = Math.max(1, plan.result().getMaxStackSize() / unitResultCount);
        int maxUnits = Math.min(Math.min(wantedUnits, storedIngredientUnits),
            Math.min(ingredientStackUnits, resultStackUnits));

        for (int units = maxUnits; units >= 1; units--) {
            Craft craft = firedCraftForUnits(sl, workBlock, plan, ingredientUnit, units, kiln.isLit());
            if (hasInputs(sl, workshop, craft.inputs())) return craft;
        }
        return null;
    }

    private static int wantedCraftUnits(ServerLevel sl, Settlement settlement, Workshop workshop,
                                        Plan plan) {
        int units = Workshops.orderedCraftCount(workshop, plan.result().getItem());
        if (units <= 0 && Workshops.wantedByMinStock(sl, settlement, workshop, plan.result())) {
            units = INPUT_BUFFER_CRAFTS;
        }
        return units;
    }

    private static Craft firedCraftForUnits(ServerLevel sl, BlockPos workBlock, Plan plan,
                                            ItemStack ingredientUnit, int units,
                                            boolean kilnAlreadyLit) {
        int ingredientCount = Math.max(1, ingredientUnit.getCount()) * Math.max(1, units);
        int firingTicks = firingTicksForIngredientCount(plan.firing(), ingredientCount);
        List<ItemStack> inputs = firingInputsFor(sl, workBlock,
            ingredientUnit.copyWithCount(ingredientCount), firingTicks);
        ItemStack result = plan.result().copy();
        result.setCount(Math.min(result.getMaxStackSize(), result.getCount() * Math.max(1, units)));
        int igniteTicks = kilnAlreadyLit ? 0 : FIRE_STICKS_USE_TICKS;
        return new Craft(inputs, result, igniteTicks + firingTicks + FIRING_FINISH_MARGIN_TICKS,
            Math.max(2, charcoalCount(inputs) + 2));
    }

    private static boolean hasInputs(ServerLevel sl, Workshop workshop, List<ItemStack> inputs) {
        for (ItemStack input : inputs) {
            if (WorkshopStorage.count(sl, workshop, input.getItem()) < input.getCount()) return false;
        }
        return true;
    }

    private static List<Plan> availablePlans(ServerLevel sl, BlockPos workBlock) {
        List<Plan> out = new ArrayList<>();
        Set<Item> seenResults = new java.util.HashSet<>();
        for (PotteryRecipe shaping : PotteryRecipeManager.all()) {
            if (!CraftGating.canProduceAt(sl, workBlock, shaping.result().getItem())) continue;
            addPlan(out, seenResults, directPlan(shaping));
        }
        for (KilnRecipe firing : KilnRecipeManager.all()) {
            if (!CraftGating.canProduceAt(sl, workBlock, firing.result().getItem())) continue;
            for (PotteryRecipe shaping : PotteryRecipeManager.all()) {
                if (!CraftGating.canProduceAt(sl, workBlock, shaping.result().getItem())) continue;
                if (!firing.matches(shaping.result())) continue;
                addPlan(out, seenResults, firedPlan(sl, workBlock, shaping, firing));
            }
        }
        return out;
    }

    private static void addPlan(List<Plan> plans, Set<Item> seenResults, Plan plan) {
        if (seenResults.add(plan.result().getItem())) {
            plans.add(plan);
        }
    }

    private static Plan directPlan(PotteryRecipe shaping) {
        int beats = Math.max(1, shaping.spins());
        return new Plan(shaping, null, shaping.result().copy(), shapingInputsFor(shaping),
            beats * TICKS_PER_SPIN, beats);
    }

    private static Plan firedPlan(ServerLevel sl, BlockPos workBlock, PotteryRecipe shaping,
                                  KilnRecipe firing) {
        int firingTicks = firingTicksForIngredientCount(firing, shaping.result().getCount());
        ItemStack result = firing.result().copy();
        int produced = result.getCount() * Math.max(1, shaping.result().getCount());
        result.setCount(Math.min(produced, result.getMaxStackSize()));
        List<ItemStack> inputs = firingInputsFor(sl, workBlock, shaping.result().copy(), firingTicks);
        return new Plan(shaping, firing, result, inputs,
            FIRE_STICKS_USE_TICKS + firingTicks + FIRING_FINISH_MARGIN_TICKS,
            Math.max(2, charcoalCount(inputs) + 2));
    }

    private static List<ItemStack> shapingInputsFor(PotteryRecipe shaping) {
        Map<Item, Integer> counts = new LinkedHashMap<>();
        for (Map.Entry<Item, Integer> e : shaping.requiredCounts().entrySet()) {
            counts.merge(e.getKey(), e.getValue(), Integer::sum);
        }
        List<ItemStack> inputs = new ArrayList<>(counts.size());
        for (Map.Entry<Item, Integer> e : counts.entrySet()) {
            inputs.add(new ItemStack(e.getKey(), e.getValue()));
        }
        return inputs;
    }

    private static int firingTicksForIngredientCount(@Nullable KilnRecipe firing,
                                                     int ingredientCount) {
        return Math.max(1, firing == null ? 1 : firing.ticks()) * Math.max(1, ingredientCount);
    }

    private static List<ItemStack> firingInputsFor(ServerLevel sl, BlockPos workBlock,
                                                   ItemStack ingredient, int firingTicks) {
        List<ItemStack> inputs = new ArrayList<>(3);
        inputs.add(ingredient.copy());
        inputs.add(new ItemStack(BannerboundAntiquity.FIRE_STICKS.get()));
        KilnBlockEntity kiln = kilnFor(sl, workBlock);
        int initialBurnTicks;
        if (kiln != null && kiln.isLit()) {
            initialBurnTicks = kiln.getLitTicks();
        } else {
            initialBurnTicks = KilnBlockEntity.MAX_LIT_TICKS;
        }
        int charcoal = charcoalNeeded(firingTicks, initialBurnTicks);
        if (charcoal > 0) {
            inputs.add(new ItemStack(Items.CHARCOAL, charcoal));
        }
        return inputs;
    }

    private static int charcoalNeeded(int firingTicks, int initialBurnTicks) {
        int remaining = firingTicks - Math.max(0, initialBurnTicks - 1);
        if (remaining <= 0) return 0;
        int stokedBurnTicks = Math.max(1,
            KilnBlockEntity.MAX_LIT_TICKS - STOKE_WHEN_LIT_TICKS_AT_OR_BELOW);
        return (remaining + stokedBurnTicks - 1) / stokedBurnTicks;
    }

    private static Map<Item, Integer> deficits(ServerLevel sl, Workshop workshop,
                                               Map<Item, Integer> desired) {
        Map<Item, Integer> deficit = new LinkedHashMap<>();
        for (Map.Entry<Item, Integer> e : desired.entrySet()) {
            int have = WorkshopStorage.count(sl, workshop, e.getKey());
            if (have < e.getValue()) {
                deficit.put(e.getKey(), e.getValue() - have);
            }
        }
        return deficit;
    }

    private static boolean isKilnSupply(ItemStack stack) {
        return stack.is(BannerboundAntiquity.FIRE_STICKS.get()) || stack.is(Items.CHARCOAL);
    }

    private static int charcoalCount(List<ItemStack> inputs) {
        int count = 0;
        for (ItemStack input : inputs) {
            if (input.is(Items.CHARCOAL)) count += input.getCount();
        }
        return count;
    }

    private static int charcoalCount(Craft craft) {
        int count = 0;
        for (ItemStack input : craft.inputs()) {
            if (input.is(Items.CHARCOAL)) count += input.getCount();
        }
        return count;
    }

    private static int kilnResultCraftsInProgress(ServerLevel sl, Workshop workshop, Plan plan) {
        KilnBlockEntity kiln = kilnFor(sl, workshop);
        if (kiln == null) return 0;
        ItemStack held = kiln.getHeldItem();
        if (held.isEmpty() || !held.is(plan.result().getItem())) return 0;
        int perCraft = Math.max(1, plan.result().getCount());
        return (held.getCount() + perCraft - 1) / perCraft;
    }

    private static int kilnIngredientCountInProgress(ServerLevel sl, Workshop workshop,
                                                     ItemStack input) {
        if (isKilnSupply(input)) return 0;
        KilnBlockEntity kiln = kilnFor(sl, workshop);
        if (kiln == null) return 0;
        ItemStack held = kiln.getHeldItem();
        return held.is(input.getItem()) ? held.getCount() : 0;
    }

    private static Item itemFromId(String itemId) {
        net.minecraft.resources.ResourceLocation rl =
            net.minecraft.resources.ResourceLocation.tryParse(itemId);
        if (rl == null) return Items.AIR;
        return net.minecraft.core.registries.BuiltInRegistries.ITEM.get(rl);
    }

    @Nullable
    private static ItemStack planFiringIngredient(Plan plan) {
        for (ItemStack input : plan.inputs()) {
            if (!isKilnSupply(input)) return input;
        }
        return null;
    }

    @Nullable
    private static ItemStack firingIngredient(Craft craft) {
        for (ItemStack input : craft.inputs()) {
            if (!isKilnSupply(input)) return input;
        }
        return null;
    }

    private static int firedOrderUnitsForOutput(ServerLevel sl, BlockPos workBlock,
                                                Craft craft, ItemStack output) {
        if (output.isEmpty() || !isFiredOutput(sl, workBlock, craft.result())) return 1;
        int unitResultCount = 1;
        for (Plan plan : availablePlans(sl, workBlock)) {
            if (plan.fired() && plan.result().getItem() == output.getItem()) {
                unitResultCount = Math.max(1, plan.result().getCount());
                break;
            }
        }
        return Math.max(1, (output.getCount() + unitResultCount - 1) / unitResultCount);
    }

    @Nullable
    private static KilnBlockEntity kilnFor(ServerLevel sl, BlockPos workBlock) {
        Workshops.Hit hit = Workshops.findAt(sl, workBlock);
        return hit == null ? null : kilnFor(sl, hit.workshop());
    }

    @Nullable
    private static KilnBlockEntity kilnFor(ServerLevel sl, Workshop workshop) {
        BlockPos kiln = PotteryWorkshopRules.findCompleteKilnController(sl, workshop);
        return kiln != null && sl.getBlockEntity(kiln) instanceof KilnBlockEntity be ? be : null;
    }

    @Override
    public List<ItemStack> possibleOutputs(ServerLevel sl, BlockPos workBlock) {
        List<ItemStack> out = new ArrayList<>();
        for (Plan plan : availablePlans(sl, workBlock)) {
            out.add(plan.result().copy());
        }
        return out;
    }

    @Override
    public void onStart(ServerLevel sl, CitizenEntity citizen, BlockPos workBlock, Craft craft) {
        if (isFiredOutput(sl, workBlock, craft.result())) {
            KilnBlockEntity kiln = kilnFor(sl, workBlock);
            ItemStack ingredient = firingIngredient(craft);
            if (kiln != null && ingredient != null && kiln.getHeldItem().isEmpty()) {
                kiln.insert(ingredient.copy());
                if (!kiln.isLit()) {
                    firedIgniteTicksLeft.put(kiln.getBlockPos().immutable(), FIRE_STICKS_USE_TICKS);
                } else {
                    firedIgniteTicksLeft.remove(kiln.getBlockPos());
                }
                firedStokesUsed.put(kiln.getBlockPos().immutable(), 0);
            }
            return;
        }
        if (sl.getBlockEntity(workBlock) instanceof PotterySlabBlockEntity be) {
            for (ItemStack input : craft.inputs()) {
                if (isKilnSupply(input)) continue;
                for (int i = 0; i < input.getCount(); i++) {
                    be.insertOne(input.copyWithCount(1), Direction.NORTH);
                }
            }
            be.setInProgress(new ItemStack(Blocks.CLAY));
        }
    }

    @Override
    public void onWorkTick(ServerLevel sl, CitizenEntity citizen, BlockPos workBlock, Craft craft,
                           int ticksLeft) {
        if (!isFiredOutput(sl, workBlock, craft.result())) return;
        KilnBlockEntity kiln = kilnFor(sl, workBlock);
        if (kiln == null) return;
        BlockPos key = kiln.getBlockPos().immutable();
        Integer igniteTicks = firedIgniteTicksLeft.get(key);
        if (igniteTicks != null) {
            if (igniteTicks % FIRE_STICKS_SWING_INTERVAL_TICKS == 0) {
                citizen.swing(InteractionHand.MAIN_HAND);
                sl.playSound(null, key, SoundEvents.FLINTANDSTEEL_USE, SoundSource.BLOCKS, 0.5F, 1.4F);
                sl.sendParticles(ParticleTypes.SMOKE,
                    key.getX() + 1.0, key.getY() + 0.45, key.getZ() + 1.0,
                    3, 0.2, 0.12, 0.2, 0.01);
            }
            if (igniteTicks <= 1) {
                firedIgniteTicksLeft.remove(key);
                if (!kiln.isLit()) {
                    kiln.ignite();
                }
            } else {
                firedIgniteTicksLeft.put(key, igniteTicks - 1);
            }
            return;
        }
        if (!kiln.isLit()) return;
        int allowedStokes = charcoalCount(craft);
        if (allowedStokes <= 0 || kiln.getLitTicks() > STOKE_WHEN_LIT_TICKS_AT_OR_BELOW) return;
        int used = firedStokesUsed.getOrDefault(key, 0);
        if (used >= allowedStokes) return;
        if (kiln.stoke()) {
            firedStokesUsed.put(key, used + 1);
        }
    }

    @Override
    public boolean externallyComplete(ServerLevel sl, CitizenEntity citizen, BlockPos workBlock,
                                      Craft craft, int ticksLeft) {
        if (!isFiredOutput(sl, workBlock, craft.result())) return false;
        KilnBlockEntity kiln = kilnFor(sl, workBlock);
        return kiln != null && kiln.getHeldItem().is(craft.result().getItem());
    }

    @Override
    public void onBeat(ServerLevel sl, CitizenEntity citizen, BlockPos workBlock, Craft craft,
                       int beatIndex) {
        if (isFiredOutput(sl, workBlock, craft.result())) {
            KilnBlockEntity kiln = kilnFor(sl, workBlock);
            BlockPos pos = kiln != null ? kiln.getBlockPos() : workBlock;
            sl.playSound(null, pos, SoundEvents.FURNACE_FIRE_CRACKLE, SoundSource.BLOCKS, 0.8F, 1.0F);
            sl.sendParticles(ParticleTypes.FLAME,
                pos.getX() + 1.0, pos.getY() + 0.45, pos.getZ() + 1.0,
                6, 0.35, 0.2, 0.35, 0.02);
            sl.sendParticles(ParticleTypes.SMOKE,
                pos.getX() + 1.0, pos.getY() + 1.8, pos.getZ() + 1.0,
                5, 0.25, 0.15, 0.25, 0.01);
            return;
        }
        sl.playSound(null, workBlock, SoundEvents.GRAVEL_PLACE, SoundSource.BLOCKS, 0.6F, 1.4F);
        sl.sendParticles(new BlockParticleOption(ParticleTypes.BLOCK, Blocks.CLAY.defaultBlockState()),
            workBlock.getX() + 0.5, workBlock.getY() + 0.85, workBlock.getZ() + 0.5,
            5, 0.25, 0.1, 0.25, 0.02);
    }

    @Override
    public ItemStack finish(ServerLevel sl, CitizenEntity citizen, BlockPos workBlock, Craft craft) {
        boolean fired = isFiredOutput(sl, workBlock, craft.result());
        if (sl.getBlockEntity(workBlock) instanceof PotterySlabBlockEntity be) {
            if (!fired) {
                be.consumePile();
            }
            be.setInProgress(ItemStack.EMPTY);
        }
        if (fired) {
            KilnBlockEntity kiln = kilnFor(sl, workBlock);
            ItemStack out = kiln != null ? kiln.extract() : ItemStack.EMPTY;
            BlockPos soundPos = kiln != null ? kiln.getBlockPos() : workBlock;
            if (kiln != null) {
                firedStokesUsed.remove(kiln.getBlockPos());
                firedIgniteTicksLeft.remove(kiln.getBlockPos());
            }
            clearSelfIntermediateAutoOrderIfLastFiredCraft(sl, workBlock, craft, out);
            sl.playSound(null, soundPos, BannerboundAntiquity.SMELTING_DONE_SOUND.get(),
                SoundSource.BLOCKS, 0.9F, 1.0F);
            sl.sendParticles(ParticleTypes.FLAME,
                soundPos.getX() + 1.0, soundPos.getY() + 0.45, soundPos.getZ() + 1.0,
                8, 0.25, 0.15, 0.25, 0.02);
            sl.sendParticles(ParticleTypes.LARGE_SMOKE,
                soundPos.getX() + 1.0, soundPos.getY() + 1.8, soundPos.getZ() + 1.0,
                6, 0.18, 0.12, 0.18, 0.01);
            return out;
        } else {
            sl.playSound(null, workBlock, SoundEvents.GRAVEL_PLACE, SoundSource.BLOCKS, 0.8F, 0.85F);
        }
        return craft.result();
    }

    private static void clearSelfIntermediateAutoOrderIfLastFiredCraft(ServerLevel sl, BlockPos workBlock,
                                                                       Craft craft, ItemStack output) {
        Workshops.Hit hit = Workshops.findAt(sl, workBlock);
        ItemStack ingredient = firingIngredient(craft);
        if (hit == null || ingredient == null) return;
        int remainingAfterThisCraft = Math.max(0,
            Workshops.orderedCraftCount(hit.workshop(), craft.result().getItem())
                - firedOrderUnitsForOutput(sl, workBlock, craft, output));
        if (remainingAfterThisCraft > 0) return;

        String ingredientId = net.minecraft.core.registries.BuiltInRegistries.ITEM
            .getKey(ingredient.getItem()).toString();
        if (!hit.workshop().id().toString().equals(hit.workshop().autoOrderSources().get(ingredientId))) {
            return;
        }
        hit.workshop().autoOrders().remove(ingredientId);
        hit.workshop().autoOrderSources().remove(ingredientId);
        com.bannerbound.core.api.settlement.SettlementData
            .get(sl.getServer().overworld()).setDirty();
    }

    private static boolean isFiredOutput(ServerLevel sl, BlockPos workBlock, ItemStack result) {
        for (Plan plan : availablePlans(sl, workBlock)) {
            if (plan.fired() && plan.result().getItem() == result.getItem()) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void onAbort(ServerLevel sl, CitizenEntity citizen, BlockPos workBlock, Craft craft) {
        boolean fired = isFiredOutput(sl, workBlock, craft.result());
        if (fired) {
            KilnBlockEntity kiln = kilnFor(sl, workBlock);
            if (kiln != null) {
                kiln.extract();
                firedStokesUsed.remove(kiln.getBlockPos());
                firedIgniteTicksLeft.remove(kiln.getBlockPos());
            }
            return;
        }
        if (sl.getBlockEntity(workBlock) instanceof PotterySlabBlockEntity be) {
            if (!fired) {
                be.consumePile();
            }
            be.setInProgress(ItemStack.EMPTY);
        }
    }

    @Override
    public int fulfilledOrderUnits(ServerLevel sl, BlockPos workBlock, Craft craft, ItemStack output) {
        return firedOrderUnitsForOutput(sl, workBlock, craft, output);
    }

    @Override
    public boolean consumesInput(Craft craft, ItemStack input) {
        return !input.is(BannerboundAntiquity.FIRE_STICKS.get());
    }
}
