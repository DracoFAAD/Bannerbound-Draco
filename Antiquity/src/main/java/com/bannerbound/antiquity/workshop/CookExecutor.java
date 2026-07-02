package com.bannerbound.antiquity.workshop;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nullable;

import com.bannerbound.antiquity.BannerboundAntiquity;
import com.bannerbound.antiquity.block.entity.StoneCookingPotBlockEntity;
import com.bannerbound.antiquity.recipe.StewRecipe;
import com.bannerbound.antiquity.recipe.StewRecipeManager;
import com.bannerbound.core.api.research.CraftGating;
import com.bannerbound.core.api.settlement.Settlement;
import com.bannerbound.core.api.settlement.Workshop;
import com.bannerbound.core.api.settlement.data.FoodValueLoader;
import com.bannerbound.core.api.workshop.WorkExecutor;
import com.bannerbound.core.api.workshop.WorkshopStorage;
import com.bannerbound.core.api.workshop.Workshops;
import com.bannerbound.core.entity.CitizenEntity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.crafting.CampfireCookingRecipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.block.entity.CampfireBlockEntity;
import net.minecraft.world.phys.AABB;

/**
 * NPC driver for the Kitchen workshop (the generic Crafter staffs it). The work block is a stone
 * cooking pot; the cook runs three product lines:
 *
 * <ul>
 *   <li><b>Stew</b> (standing behavior): FILL the fired clay bucket at open water, POUR it into an
 *       empty pot, ADD the best fully-stockable named stew's ingredients — the pot's own campfire
 *       timer simmers it (a walk-away wait) and citizens eat straight from the pot
 *       ({@code StewEatGoal}) while the larder draws its value down. A pot holding stew or already
 *       simmering is skipped, so one pot holds at most one batch — that pot state IS the in-flight
 *       gate, and stew never appears as an item.</li>
 *   <li><b>Roasting</b> (orders/min-stock): lay raw food on the kitchen's open campfires'
 *       vanilla roast slots (ROAST-PLACE, gated by {@code Workshops.wantsAnother} counting
 *       slots + uncollected drops as in-flight); the vanilla timer cooks and EJECTS the food to
 *       the ground, and ROAST-COLLECT (ungated, top priority) sweeps it into storage.</li>
 * </ul>
 *
 * <p>Steps are tagged in an identity map at {@link #chooseCraft} (ADD and ROAST-PLACE share the
 * same input/result shape, so the Tannery shape idiom alone can't discriminate here); the goal
 * holds one Craft instance through its whole lifecycle, and finish/onAbort clear the tag.
 */
public class CookExecutor implements WorkExecutor {
    private static final int BEATS = 3;
    private static final int FILL_TICKS = 40;      // scooping the bucket full at the water source
    private static final int POUR_TICKS = 40;      // emptying it into the pot
    private static final int ADD_TICKS = 60;       // dropping the ingredients in, one plop per beat
    private static final int ROAST_HANDLE_TICKS = 30;  // laying food on / sweeping food off a fire
    /** How far off a fire an ejected roast can land and still be swept (vanilla pops them just on top). */
    private static final double SWEEP_RANGE = 2.0;
    /** Most raws laid per ROAST-PLACE trip (a campfire has 4 slots; demand caps it below this). */
    private static final int MAX_PLACE_BATCH = 4;

    private enum Step { FILL, POUR, ADD, ROAST_PLACE, ROAST_COLLECT, RACK_HANG, RACK_TAKE }

    /** The active step per in-flight Craft (assigned in chooseCraft, cleared in finish/onAbort). */
    private final Map<Craft, Step> steps = new IdentityHashMap<>();

    @Nullable
    @Override
    public Craft chooseCraft(ServerLevel sl, Settlement settlement, Workshop workshop, BlockPos workBlock) {
        StoneCookingPotBlockEntity pot =
            sl.getBlockEntity(workBlock) instanceof StoneCookingPotBlockEntity p ? p : null;
        if (pot == null) return null;
        List<BlockPos> fires = CookingWorkshopRules.roastingFires(sl, settlement, workshop);
        // ROAST-COLLECT — ungated: the food is already cooked and despawns if left on the ground.
        ItemStack sweep = sweepableRoast(sl, fires);
        if (!sweep.isEmpty()) {
            return tag(new Craft(List.of(), sweep, ROAST_HANDLE_TICKS, 1), Step.ROAST_COLLECT);
        }
        // RACK-TAKE — ungated: a finished FOOD slot on a kitchen rack (jerky, dried fish) jams the
        // spot until lifted. Craft-category slots (thatch) belong to General Crafts, not the cook.
        List<BlockPos> racks = RackTending.racks(sl, workshop);
        BlockPos dryRack = RackTending.rackWithDry(sl, racks,
            com.bannerbound.antiquity.recipe.DryingRackRecipe.FOOD);
        if (dryRack != null) {
            ItemStack dried = RackTending.dryResultAt(sl, dryRack,
                com.bannerbound.antiquity.recipe.DryingRackRecipe.FOOD);
            if (!dried.isEmpty()) {
                return tag(new Craft(List.of(), dried, ROAST_HANDLE_TICKS, 1), Step.RACK_TAKE);
            }
        }
        // Stew line — a pot holding stew (citizens eat it down) or already simmering needs nothing.
        if (!pot.hasStew() && !pot.isCooking()) {
            if (!pot.hasWater()) {
                // POUR: a filled bucket is on hand → empty it into the pot; the empty one cycles back.
                if (WorkshopStorage.count(sl, workshop, BannerboundAntiquity.CLAY_FIRED_WATER_BUCKET.get()) > 0) {
                    return tag(new Craft(
                        List.of(new ItemStack(BannerboundAntiquity.CLAY_FIRED_WATER_BUCKET.get())),
                        new ItemStack(BannerboundAntiquity.CLAY_FIRED_BUCKET.get()), POUR_TICKS, BEATS),
                        Step.POUR);
                }
                // FILL: carry the empty bucket to open water — only worth it when a stew can follow.
                if (WorkshopStorage.count(sl, workshop, BannerboundAntiquity.CLAY_FIRED_BUCKET.get()) > 0
                        && CookingWorkshopRules.findWaterSource(sl, workBlock) != null
                        && bestStockedStew(sl, workshop) != null) {
                    return tag(new Craft(
                        List.of(new ItemStack(BannerboundAntiquity.CLAY_FIRED_BUCKET.get())),
                        new ItemStack(BannerboundAntiquity.CLAY_FIRED_WATER_BUCKET.get()), FILL_TICKS, BEATS),
                        Step.FILL);
                }
            } else if (pot.ingredientCount() == 0 && pot.isHeated()) {
                // ADD-INGREDIENTS: watered, empty and on a live fire → load the best stockable stew;
                // the pot cooks it on its own timer while the cook walks off.
                List<ItemStack> inputs = ingredientsFor(sl, workshop, bestStockedStew(sl, workshop));
                if (!inputs.isEmpty()) {
                    return tag(new Craft(inputs, ItemStack.EMPTY, ADD_TICKS, BEATS), Step.ADD);
                }
            }
        }
        // ROAST-PLACE — orders FIFO, then min-stock (the standard two passes).
        if (!fires.isEmpty()) {
            Craft roast = tryRoastPlace(sl, settlement, workshop, workBlock, fires, true);
            if (roast != null) return roast;
            roast = tryRoastPlace(sl, settlement, workshop, workBlock, fires, false);
            if (roast != null) return roast;
        }
        // RACK-HANG — food drying (same two passes, gated on net demand incl. hanging in-flight).
        if (!racks.isEmpty() && RackTending.rackWithRoom(sl, racks) != null) {
            Craft hang = tryRackHang(sl, settlement, workshop, workBlock, racks, true);
            if (hang != null) return hang;
            hang = tryRackHang(sl, settlement, workshop, workBlock, racks, false);
            if (hang != null) return hang;
        }
        return null;
    }

    @Nullable
    private Craft tryRackHang(ServerLevel sl, Settlement settlement, Workshop workshop,
                              BlockPos workBlock, List<BlockPos> racks, boolean ordersOnly) {
        for (var recipe : RackTending.recipes(com.bannerbound.antiquity.recipe.DryingRackRecipe.FOOD)) {
            ItemStack dried = recipe.result();
            if (dried.isEmpty() || !CraftGating.canProduceAt(sl, workBlock, dried.getItem())) continue;
            int inFlight = RackTending.inFlight(sl, racks, recipe);
            boolean wanted = ordersOnly
                ? Workshops.orderedCraftCount(workshop, dried.getItem()) - inFlight > 0
                : Workshops.wantsAnother(sl, settlement, workshop, dried, inFlight);
            if (!wanted) continue;
            if (WorkshopStorage.count(sl, workshop, recipe.input()) <= 0) continue;
            return tag(new Craft(List.of(new ItemStack(recipe.input())),
                ItemStack.EMPTY, ROAST_HANDLE_TICKS, 2), Step.RACK_HANG);
        }
        return null;
    }

    private Craft tag(Craft craft, Step step) {
        steps.put(craft, step);
        return craft;
    }

    private Step stepOf(Craft craft) {
        Step step = steps.get(craft);
        if (step != null) return step;
        // Shape fallback (a craft that somehow outlived its tag): the unambiguous shapes.
        if (craft.result().is(BannerboundAntiquity.CLAY_FIRED_WATER_BUCKET.get())) return Step.FILL;
        if (craft.result().is(BannerboundAntiquity.CLAY_FIRED_BUCKET.get())) return Step.POUR;
        if (!craft.result().isEmpty() && craft.inputs().isEmpty()) return Step.ROAST_COLLECT;
        return Step.ADD;
    }

    // ── Roasting line ───────────────────────────────────────────────────────────────────────────

    /** One cooked-food drop type waiting near the kitchen's fires (item + total count), or EMPTY. */
    private static ItemStack sweepableRoast(ServerLevel sl, List<BlockPos> fires) {
        Item found = null;
        int count = 0;
        for (BlockPos fire : fires) {
            for (ItemEntity drop : dropsNear(sl, fire)) {
                Item item = drop.getItem().getItem();
                if (!isCampfireResult(sl, item)) continue;
                if (found == null) found = item;
                if (drop.getItem().is(found)) count += drop.getItem().getCount();
            }
        }
        return found == null ? ItemStack.EMPTY : new ItemStack(found, Math.min(count, 64));
    }

    private static List<ItemEntity> dropsNear(ServerLevel sl, BlockPos fire) {
        return sl.getEntitiesOfClass(ItemEntity.class,
            new AABB(fire).inflate(SWEEP_RANGE, 1.5, SWEEP_RANGE));
    }

    private static boolean isCampfireResult(ServerLevel sl, Item item) {
        for (RecipeHolder<CampfireCookingRecipe> holder
                : sl.getRecipeManager().getAllRecipesFor(RecipeType.CAMPFIRE_COOKING)) {
            if (holder.value().getResultItem(sl.registryAccess()).is(item)) return true;
        }
        return false;
    }

    @Nullable
    private Craft tryRoastPlace(ServerLevel sl, Settlement settlement, Workshop workshop,
                                BlockPos workBlock, List<BlockPos> fires, boolean ordersOnly) {
        int freeSlots = 0;
        for (BlockPos fire : fires) {
            freeSlots += freeSlotsAt(sl, fire);
        }
        if (freeSlots <= 0) return null;
        for (RecipeHolder<CampfireCookingRecipe> holder
                : sl.getRecipeManager().getAllRecipesFor(RecipeType.CAMPFIRE_COOKING)) {
            CampfireCookingRecipe recipe = holder.value();
            ItemStack cooked = recipe.getResultItem(sl.registryAccess());
            if (cooked.isEmpty() || !CraftGating.canProduceAt(sl, workBlock, cooked.getItem())) continue;
            // Demand check with the waiting-stage in-flight count: matching raws already on fires
            // plus matching cooked drops not yet swept — a single order never double-places.
            int inFlight = roastsInFlight(sl, fires, recipe, cooked);
            boolean wanted = ordersOnly
                ? Workshops.orderedCraftCount(workshop, cooked.getItem()) - inFlight > 0
                : Workshops.wantsAnother(sl, settlement, workshop, cooked, inFlight);
            if (!wanted) continue;
            Item raw = stockedRaw(sl, workshop, recipe);
            if (raw == null) continue;
            int batch = Math.min(MAX_PLACE_BATCH,
                Math.min(freeSlots, WorkshopStorage.count(sl, workshop, raw)));
            return tag(new Craft(List.of(new ItemStack(raw, Math.max(1, batch))),
                ItemStack.EMPTY, ROAST_HANDLE_TICKS, 2), Step.ROAST_PLACE);
        }
        return null;
    }

    private static int freeSlotsAt(ServerLevel sl, BlockPos fire) {
        if (!(sl.getBlockEntity(fire) instanceof CampfireBlockEntity be)) return 0;
        int free = 0;
        for (ItemStack slot : be.getItems()) {
            if (slot.isEmpty()) free++;
        }
        return free;
    }

    /** Raws of this recipe cooking on the fires + its cooked results lying uncollected beside them. */
    private static int roastsInFlight(ServerLevel sl, List<BlockPos> fires,
                                      CampfireCookingRecipe recipe, ItemStack cooked) {
        int count = 0;
        for (BlockPos fire : fires) {
            if (sl.getBlockEntity(fire) instanceof CampfireBlockEntity be) {
                for (ItemStack slot : be.getItems()) {
                    if (!slot.isEmpty() && recipe.getIngredients().get(0).test(slot)) count++;
                }
            }
            for (ItemEntity drop : dropsNear(sl, fire)) {
                if (drop.getItem().is(cooked.getItem())) count += drop.getItem().getCount();
            }
        }
        return count;
    }

    /** A stocked item this campfire recipe accepts, or {@code null}. */
    @Nullable
    private static Item stockedRaw(ServerLevel sl, Workshop workshop, CampfireCookingRecipe recipe) {
        if (recipe.getIngredients().isEmpty()) return null;
        for (ItemStack option : recipe.getIngredients().get(0).getItems()) {
            if (WorkshopStorage.count(sl, workshop, option.getItem()) > 0) return option.getItem();
        }
        return null;
    }

    // ── Stew line helpers ───────────────────────────────────────────────────────────────────────

    /** The stockable named stew worth the most cooked value right now, or {@code null}. Value =
     *  Σ(each planned ingredient's cooking value × count) × the recipe's bonus. */
    @Nullable
    private static StewRecipe bestStockedStew(ServerLevel sl, Workshop workshop) {
        StewRecipe best = null;
        double bestValue = 0.0;
        for (StewRecipe recipe : StewRecipeManager.all()) {
            List<ItemStack> plan = planIngredients(sl, workshop, recipe);
            if (plan.isEmpty()) continue;
            double value = 0.0;
            for (ItemStack s : plan) {
                value += ingredientValue(recipe, s.getItem()) * s.getCount();
            }
            value *= recipe.bonus();
            if (value > bestValue) {
                bestValue = value;
                best = recipe;
            }
        }
        return best;
    }

    private static List<ItemStack> ingredientsFor(ServerLevel sl, Workshop workshop, @Nullable StewRecipe recipe) {
        return recipe == null ? List.of() : planIngredients(sl, workshop, recipe);
    }

    /** The stacks to withdraw for one pot-load of {@code recipe}: one stocked item per ingredient,
     *  multiplied up toward {@link StoneCookingPotBlockEntity#MAX_INGREDIENTS} (evenly split across
     *  the ingredient types, capped by stock) — richer pots feed more citizens per trip. Empty when
     *  any ingredient has no stocked match (the recipe isn't fully stockable). */
    private static List<ItemStack> planIngredients(ServerLevel sl, Workshop workshop, StewRecipe recipe) {
        int types = recipe.ingredients().size();
        if (types == 0 || types > StoneCookingPotBlockEntity.MAX_INGREDIENTS) return List.of();
        int perType = Math.max(1, StoneCookingPotBlockEntity.MAX_INGREDIENTS / types);
        List<ItemStack> plan = new ArrayList<>();
        for (StewRecipe.Ing ing : recipe.ingredients()) {
            Item match = stockedMatch(sl, workshop, ing);
            if (match == null) return List.of();
            int count = Math.min(perType, WorkshopStorage.count(sl, workshop, match));
            plan.add(new ItemStack(match, count));
        }
        return plan;
    }

    /** A stocked item satisfying this ingredient (exact item, or any stocked member of its tag). */
    @Nullable
    private static Item stockedMatch(ServerLevel sl, Workshop workshop, StewRecipe.Ing ing) {
        if (ing.item().isPresent()) {
            Item item = ing.item().get();
            return WorkshopStorage.count(sl, workshop, item) > 0 ? item : null;
        }
        if (ing.tag().isPresent()) {
            for (var holder : BuiltInRegistries.ITEM.getTagOrEmpty(ing.tag().get())) {
                if (WorkshopStorage.count(sl, workshop, holder.value()) > 0) return holder.value();
            }
        }
        return null;
    }

    /** An ingredient's cooking value: the recipe's per-ingredient override, else its food value. */
    private static double ingredientValue(StewRecipe recipe, Item item) {
        double v = recipe.valueFor(item);
        return v >= 0.0 ? v : FoodValueLoader.base(item);
    }

    // ── Work placement & lifecycle ──────────────────────────────────────────────────────────────

    /** FILL happens at the water source; ROAST steps at a kitchen fire; POUR/ADD at the pot. */
    @Override
    public BlockPos workTarget(ServerLevel sl, Settlement settlement, Workshop workshop,
                               BlockPos workBlock, Craft craft) {
        Step step = stepOf(craft);
        if (step == Step.FILL) {
            BlockPos water = CookingWorkshopRules.findWaterSource(sl, workBlock);
            return water != null ? water : workBlock;
        }
        if (step == Step.ROAST_PLACE || step == Step.ROAST_COLLECT) {
            List<BlockPos> fires = CookingWorkshopRules.roastingFires(sl, settlement, workshop);
            return fires.isEmpty() ? workBlock : fires.get(0);
        }
        if (step == Step.RACK_TAKE || step == Step.RACK_HANG) {
            List<BlockPos> racks = RackTending.racks(sl, workshop);
            BlockPos target = step == Step.RACK_TAKE
                ? RackTending.rackWithDry(sl, racks, com.bannerbound.antiquity.recipe.DryingRackRecipe.FOOD)
                : RackTending.rackWithRoom(sl, racks);
            return target != null ? target : workBlock;
        }
        return workBlock;
    }

    @Override
    public ItemStack finish(ServerLevel sl, CitizenEntity citizen, BlockPos workBlock, Craft craft) {
        Step step = steps.remove(craft);
        if (step == null) step = stepOf(craft);
        Settlement settlement = citizen.getSettlement();
        Workshop workshop = settlement == null ? null
            : settlement.getWorkshop(citizen.getAssignedWorkshopId());
        switch (step) {
            case POUR -> {
                if (sl.getBlockEntity(workBlock) instanceof StoneCookingPotBlockEntity pot
                        && !pot.hasStew()) {
                    pot.setWater(true);
                }
                return craft.result().copy();   // the emptied bucket cycles back to storage
            }
            case ADD -> {
                if (sl.getBlockEntity(workBlock) instanceof StoneCookingPotBlockEntity pot) {
                    for (ItemStack input : craft.inputs()) {
                        for (int i = 0; i < input.getCount(); i++) {
                            if (!pot.addIngredient(input)) break;   // pot full / raced — stop plopping
                        }
                    }
                }
                return ItemStack.EMPTY;
            }
            case ROAST_PLACE -> {
                if (workshop == null) return craft.inputs().get(0).copy();
                ItemStack raws = craft.inputs().get(0).copy();
                CampfireCookingRecipe recipe = recipeForRaw(sl, raws);
                for (BlockPos fire : CookingWorkshopRules.roastingFires(sl, settlement, workshop)) {
                    if (raws.isEmpty()) break;
                    if (!(sl.getBlockEntity(fire) instanceof CampfireBlockEntity be)) continue;
                    while (!raws.isEmpty() && recipe != null
                            && be.placeFood(citizen, raws, recipe.getCookingTime())) {
                        // placeFood split one off; keep loading this fire's free slots.
                    }
                }
                return raws;   // any un-placed remainder goes back to storage, not into the void
            }
            case ROAST_COLLECT -> {
                if (workshop == null) return ItemStack.EMPTY;
                ItemStack want = craft.result();
                int collected = 0;
                for (BlockPos fire : CookingWorkshopRules.roastingFires(sl, settlement, workshop)) {
                    for (ItemEntity drop : dropsNear(sl, fire)) {
                        if (drop.getItem().is(want.getItem())) {
                            collected += drop.getItem().getCount();
                            drop.discard();
                        }
                    }
                }
                return collected <= 0 ? ItemStack.EMPTY
                    : new ItemStack(want.getItem(), Math.min(collected, 64));
            }
            case RACK_TAKE -> {
                if (workshop == null) return ItemStack.EMPTY;
                List<BlockPos> racks = RackTending.racks(sl, workshop);
                BlockPos rack = RackTending.rackWithDry(sl, racks,
                    com.bannerbound.antiquity.recipe.DryingRackRecipe.FOOD);
                return rack == null ? ItemStack.EMPTY : RackTending.takeDry(sl, rack,
                    com.bannerbound.antiquity.recipe.DryingRackRecipe.FOOD);
            }
            case RACK_HANG -> {
                if (workshop == null) return craft.inputs().get(0).copy();
                BlockPos rack = RackTending.rackWithRoom(sl, RackTending.racks(sl, workshop));
                if (rack != null && sl.getBlockEntity(rack)
                        instanceof com.bannerbound.antiquity.block.entity.DryingRackBlockEntity be
                        && be.hang(craft.inputs().get(0))) {
                    return ItemStack.EMPTY;   // the rack's own timer dries it; RACK-TAKE collects
                }
                return craft.inputs().get(0).copy();   // raced full — hand the input back
            }
            default -> {
                return craft.result().copy();   // FILL: the now-filled bucket back to storage
            }
        }
    }

    @Nullable
    private static CampfireCookingRecipe recipeForRaw(ServerLevel sl, ItemStack raw) {
        for (RecipeHolder<CampfireCookingRecipe> holder
                : sl.getRecipeManager().getAllRecipesFor(RecipeType.CAMPFIRE_COOKING)) {
            if (!holder.value().getIngredients().isEmpty()
                    && holder.value().getIngredients().get(0).test(raw)) {
                return holder.value();
            }
        }
        return null;
    }

    /** A swept roast batch fulfils one queued unit per item collected (the place step is orderless). */
    @Override
    public int fulfilledOrderUnits(ServerLevel sl, BlockPos workBlock, Craft craft, ItemStack output) {
        return Math.max(1, output.getCount());
    }

    @Override
    public void onAbort(ServerLevel sl, CitizenEntity citizen, BlockPos workBlock, Craft craft) {
        steps.remove(craft);   // nothing world-side is committed before finish() on any step
    }

    @Override
    public void onBeat(ServerLevel sl, CitizenEntity citizen, BlockPos workBlock, Craft craft, int beatIndex) {
        BlockPos at = citizen.blockPosition();
        switch (stepOf(craft)) {
            case FILL -> {
                sl.playSound(null, at, SoundEvents.BUCKET_FILL, SoundSource.BLOCKS, 0.6F, 1.0F);
                splash(sl, citizen, 6);
            }
            case POUR -> {
                sl.playSound(null, at, SoundEvents.BUCKET_EMPTY, SoundSource.BLOCKS, 0.7F, 0.9F);
                splash(sl, citizen, 8);
            }
            case ROAST_PLACE, ROAST_COLLECT ->
                sl.playSound(null, at, SoundEvents.ITEM_FRAME_ADD_ITEM, SoundSource.BLOCKS, 0.6F, 1.0F);
            case RACK_HANG, RACK_TAKE ->
                sl.playSound(null, at, SoundEvents.LEASH_KNOT_PLACE, SoundSource.BLOCKS, 0.5F, 1.0F);
            default ->
                // Prepping ingredients over the pot — chopping-board knocks; the plops themselves
                // play when finish() drops each ingredient in (the pot's own addIngredient splash).
                sl.playSound(null, workBlock, SoundEvents.WOOD_HIT, SoundSource.BLOCKS, 0.5F, 1.2F);
        }
    }

    private static void splash(ServerLevel sl, CitizenEntity citizen, int count) {
        sl.sendParticles(net.minecraft.core.particles.ParticleTypes.SPLASH,
            citizen.getX(), citizen.getY() + 0.8, citizen.getZ(), count, 0.25, 0.1, 0.25, 0.0);
    }

    // ── Stocker surfaces ────────────────────────────────────────────────────────────────────────

    /** Min-stock rows: every campfire-roastable result + every food drying result. Stew never
     *  leaves the pot as an item, so the stew line deliberately contributes nothing here. */
    @Override
    public List<ItemStack> possibleOutputs(ServerLevel sl, BlockPos workBlock) {
        List<ItemStack> out = new ArrayList<>();
        for (RecipeHolder<CampfireCookingRecipe> holder
                : sl.getRecipeManager().getAllRecipesFor(RecipeType.CAMPFIRE_COOKING)) {
            ItemStack cooked = holder.value().getResultItem(sl.registryAccess());
            if (!cooked.isEmpty() && CraftGating.canProduceAt(sl, workBlock, cooked.getItem())) {
                out.add(cooked.copyWithCount(1));
            }
        }
        for (var recipe : RackTending.recipes(com.bannerbound.antiquity.recipe.DryingRackRecipe.FOOD)) {
            if (!recipe.result().isEmpty()
                    && CraftGating.canProduceAt(sl, workBlock, recipe.result().getItem())) {
                out.add(recipe.result().copyWithCount(1));
            }
        }
        return out;
    }

    /** Stocker SUPPLY: while any pot sits idle, the concrete ingredients of every named stew (small
     *  buffer each) plus the kitchen's one conserved fired clay bucket (the tannery bucket rule:
     *  a filled bucket still counts — only order when neither variant is on hand); plus the raws
     *  behind every wanted roast. */
    @Override
    public List<ItemStack> missingInputs(ServerLevel sl, Settlement settlement, Workshop workshop,
                                         BlockPos workBlock) {
        List<ItemStack> out = new ArrayList<>();
        if (CookingWorkshopRules.idlePots(sl, workshop) > 0) {
            int buckets = WorkshopStorage.count(sl, workshop, BannerboundAntiquity.CLAY_FIRED_BUCKET.get())
                + WorkshopStorage.count(sl, workshop, BannerboundAntiquity.CLAY_FIRED_WATER_BUCKET.get());
            if (buckets < 1) {
                out.add(new ItemStack(BannerboundAntiquity.CLAY_FIRED_BUCKET.get()));
            }
            for (StewRecipe recipe : StewRecipeManager.all()) {
                for (StewRecipe.Ing ing : recipe.ingredients()) {
                    if (ing.item().isEmpty()) continue;   // tag ingredients: cook uses what's stocked
                    addDeficit(out, sl, workshop, ing.item().get(), 2);
                }
            }
        }
        for (RecipeHolder<CampfireCookingRecipe> holder
                : sl.getRecipeManager().getAllRecipesFor(RecipeType.CAMPFIRE_COOKING)) {
            CampfireCookingRecipe recipe = holder.value();
            ItemStack cooked = recipe.getResultItem(sl.registryAccess());
            if (cooked.isEmpty() || !CraftGating.canProduceAt(sl, workBlock, cooked.getItem())) continue;
            if (Workshops.orderedCraftCount(workshop, cooked.getItem()) <= 0
                    && !Workshops.wantedByMinStock(sl, settlement, workshop, cooked)) continue;
            for (ItemStack option : recipe.getIngredients().get(0).getItems()) {
                addDeficit(out, sl, workshop, option.getItem(), 4);
                break;   // one representative raw per recipe is enough to keep the fires fed
            }
        }
        for (var recipe : RackTending.recipes(com.bannerbound.antiquity.recipe.DryingRackRecipe.FOOD)) {
            ItemStack dried = recipe.result();
            if (dried.isEmpty() || !CraftGating.canProduceAt(sl, workBlock, dried.getItem())) continue;
            if (Workshops.orderedCraftCount(workshop, dried.getItem()) <= 0
                    && !Workshops.wantedByMinStock(sl, settlement, workshop, dried)) continue;
            addDeficit(out, sl, workshop, recipe.input(), 4);
        }
        return out;
    }

    private static void addDeficit(List<ItemStack> out, ServerLevel sl, Workshop workshop, Item item, int buffer) {
        int have = WorkshopStorage.count(sl, workshop, item);
        if (have < buffer) out.add(new ItemStack(item, buffer - have));
    }

    /** Stocker KEEP: the bucket pair, every stew ingredient (concrete items and tag members), and
     *  every campfire-roastable raw — a pot-load or roast batch waiting its turn must not be hauled
     *  back out between crafts. */
    @Override
    public Set<Item> retainedItems(ServerLevel sl, Settlement settlement, Workshop workshop, BlockPos workBlock) {
        Set<Item> keep = new LinkedHashSet<>();
        keep.add(BannerboundAntiquity.CLAY_FIRED_BUCKET.get());
        keep.add(BannerboundAntiquity.CLAY_FIRED_WATER_BUCKET.get());
        for (StewRecipe recipe : StewRecipeManager.all()) {
            for (StewRecipe.Ing ing : recipe.ingredients()) {
                if (ing.item().isPresent()) {
                    keep.add(ing.item().get());
                } else if (ing.tag().isPresent()) {
                    for (var holder : BuiltInRegistries.ITEM.getTagOrEmpty(ing.tag().get())) {
                        keep.add(holder.value());
                    }
                }
            }
        }
        for (RecipeHolder<CampfireCookingRecipe> holder
                : sl.getRecipeManager().getAllRecipesFor(RecipeType.CAMPFIRE_COOKING)) {
            if (holder.value().getIngredients().isEmpty()) continue;
            for (ItemStack option : holder.value().getIngredients().get(0).getItems()) {
                keep.add(option.getItem());
            }
        }
        for (var recipe : RackTending.recipes(com.bannerbound.antiquity.recipe.DryingRackRecipe.FOOD)) {
            keep.add(recipe.input());
        }
        return keep;
    }
}
