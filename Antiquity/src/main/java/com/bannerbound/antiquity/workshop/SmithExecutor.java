package com.bannerbound.antiquity.workshop;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.annotation.Nullable;

import com.bannerbound.antiquity.BannerboundAntiquity;
import com.bannerbound.antiquity.Fletching;
import com.bannerbound.antiquity.block.entity.BloomeryBlockEntity;
import com.bannerbound.antiquity.block.entity.BloomeryHeat;
import com.bannerbound.antiquity.block.entity.StoneAnvilBlockEntity;
import com.bannerbound.antiquity.item.CrucibleContents;
import com.bannerbound.antiquity.metalworking.MetalworkingData;
import com.bannerbound.antiquity.metalworking.MetalworkingItems;
import com.bannerbound.antiquity.recipe.AnvilRecipe;
import com.bannerbound.antiquity.recipe.AnvilRecipeManager;
import com.bannerbound.core.api.quality.QualityMath;
import com.bannerbound.core.api.quality.QualityTier;
import com.bannerbound.core.api.research.CraftGating;
import com.bannerbound.core.api.settlement.Settlement;
import com.bannerbound.core.api.settlement.Workshop;
import com.bannerbound.core.api.workshop.WorkExecutor;
import com.bannerbound.core.api.workshop.WorkshopStorage;
import com.bannerbound.core.api.workshop.Workshops;
import com.bannerbound.core.entity.CitizenEntity;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * NPC driver for the Smithy workshop (the generic Crafter staffs it). The work block is the Stone
 * Anvil; the smith walks the WHOLE metal chain the player plays by hand: charge a crucible with ore
 * (bronze ratio-mixed from copper + tin), tend the bloomery at the bellows until the melt lands
 * (band-aware pumping stands in for the temperature minigame), place a fired mold, pour, let it
 * cool, extract the casting, then cold-hammer it into the finished tool — per-strike scores rolled
 * through the same XP curve every crafter NPC uses ({@link QualityMath#simulateNpcTier}), capped by
 * the best hammer in storage exactly like the player's hammer-rank gate.
 *
 * <p><b>Waiting-stage contract</b> (see {@code Workshops.wantsAnother}): the melt and the mold's
 * cooling are unattended waits. EXTRACT collects ungated; every start step is gated through
 * {@link #neededCasting}, which counts a filled/cooling mold and the bloomery's charge as in-flight
 * — so one order never melts twice while its casting cools.
 *
 * <p>Step discrimination (the Tannery idiom, by input/result shape):
 * EXTRACT = result, no inputs · HAMMER = result + inputs · POUR = neither ·
 * TEND = only fire sticks in · PLACE-MOLD = a fired mold in · SMELT = ore in.
 */
public class SmithExecutor implements WorkExecutor {
    private static final int EXTRACT_TICKS = 30;
    private static final int PLACE_TICKS = 30;
    private static final int POUR_TICKS = 60;
    private static final int TICKS_PER_STRIKE = 36;
    /** Cap on a melt session; {@link #externallyComplete} ends it the moment the melt lands, and an
     *  unfinished melt just re-enters as TEND (the charge never regresses while the fire holds). */
    private static final int SMELT_TICKS = 1200;
    private static final int TEND_TICKS = 600;
    private static final int IGNITE_TICKS = 40;
    private static final int IGNITE_SWING_INTERVAL = 10;
    /** Direct-cast shapes: the mold's output IS the finished/orderable item (no hammering). */
    private static final List<String> DIRECT_SHAPES = List.of("ingot", "chisel", "spear", "arrow");

    /** Fire-stick ignite wind-up per bloomery (the Potter's kiln idiom). */
    private final Map<BlockPos, Integer> igniteTicksLeft = new HashMap<>();

    /** A casting the chain still has to produce: its mold shape, metal, and the casting item. */
    private record Need(String shape, String metal, Item casting) {
    }

    // ── chooseCraft ─────────────────────────────────────────────────────────────────────────────

    @Nullable
    @Override
    public Craft chooseCraft(ServerLevel sl, Settlement settlement, Workshop workshop, BlockPos workBlock) {
        StoneAnvilBlockEntity anvil =
            sl.getBlockEntity(workBlock) instanceof StoneAnvilBlockEntity a ? a : null;
        if (anvil == null) return null;
        // EXTRACT — ungated collect: the casting is already made; leaving it jams the anvil.
        if (anvil.isCastReady()) {
            ItemStack casting = MetalworkingItems.castingFor(anvil.moldShape(), anvil.metalId());
            if (!casting.isEmpty()) {
                return new Craft(List.of(), casting, EXTRACT_TICKS, 1);
            }
        }
        BloomeryBlockEntity bloomery = SmithyWorkshopRules.findBloomery(sl, workshop);
        // TEND — a charged, un-molten crucible sits in the bloomery: keep the fire in the metal's
        // band until the melt lands, whatever else the demand picture says (finish what's started).
        if (bloomery != null && chargedContents(bloomery) != null
                && WorkshopStorage.count(sl, workshop, BannerboundAntiquity.FIRE_STICKS.get()) > 0) {
            return new Craft(List.of(new ItemStack(BannerboundAntiquity.FIRE_STICKS.get())),
                ItemStack.EMPTY, TEND_TICKS, 8);
        }
        // HAMMER — finish stocked castings into wanted tools (orders FIFO, then min-stock).
        Craft hammer = tryHammer(sl, settlement, workshop, workBlock, anvil, true);
        if (hammer != null) return hammer;
        hammer = tryHammer(sl, settlement, workshop, workBlock, anvil, false);
        if (hammer != null) return hammer;
        // POUR — a placed mold + matching molten metal: fill it (also tops up a same-metal dud).
        CrucibleContents molten = bloomery == null ? null : moltenContents(bloomery);
        if (anvil.hasMold() && !anvil.molten() && molten != null) {
            int missing = anvil.requiredMb() - anvil.fillMb();
            if (missing > 0 && molten.totalMb() >= missing
                    && (anvil.fillMb() == 0 || molten.dominantMetal().equals(anvil.metalId()))) {
                return new Craft(List.of(), ItemStack.EMPTY, POUR_TICKS, 3);
            }
        }
        Need need = neededCasting(sl, settlement, workshop, workBlock, anvil, molten);
        if (need == null || bloomery == null) return null;
        // PLACE-MOLD — the metal for the need is molten and its fired mold is stocked.
        if (!anvil.hasMold() && anvil.pileEmpty() && !anvil.isForging()
                && molten != null && molten.dominantMetal().equals(need.metal())
                && molten.totalMb() >= MetalworkingItems.requiredMb(need.shape())) {
            var mold = MetalworkingItems.MOLDS.get("fired_clay_mold_" + need.shape());
            if (mold != null && WorkshopStorage.count(sl, workshop, mold.get()) > 0) {
                return new Craft(List.of(new ItemStack(mold.get())), ItemStack.EMPTY, PLACE_TICKS, 1);
            }
        }
        // SMELT — start a fresh melt for the need. A useless molten leftover (wrong metal / too
        // little for anything wanted — neededCasting already preferred needs it could serve) is
        // slagged when the new charge overwrites it in onStart.
        if (WorkshopStorage.count(sl, workshop, BannerboundAntiquity.FIRE_STICKS.get()) > 0
                && chargedContents(bloomery) == null) {
            boolean crucibleInside = !bloomery.getHeldItem().isEmpty()
                && bloomery.getHeldItem().is(BannerboundAntiquity.CRUCIBLE.get());
            boolean crucibleStocked =
                WorkshopStorage.count(sl, workshop, BannerboundAntiquity.CRUCIBLE.get()) > 0;
            if (crucibleInside || crucibleStocked) {
                List<ItemStack> ores = chargeFor(sl, workshop, need.metal(),
                    MetalworkingItems.requiredMb(need.shape()));
                if (ores != null) {
                    List<ItemStack> inputs = new ArrayList<>(ores);
                    if (!crucibleInside) inputs.add(new ItemStack(BannerboundAntiquity.CRUCIBLE.get()));
                    inputs.add(new ItemStack(BannerboundAntiquity.FIRE_STICKS.get()));
                    return new Craft(inputs, ItemStack.EMPTY, SMELT_TICKS, 10);
                }
            }
        }
        return null;
    }

    @Nullable
    private static Craft tryHammer(ServerLevel sl, Settlement settlement, Workshop workshop,
                                   BlockPos workBlock, StoneAnvilBlockEntity anvil, boolean ordersOnly) {
        if (anvil.hasMold() || !anvil.pileEmpty() || anvil.isForging()) return null;
        if (SmithyWorkshopRules.bestHammerRank(sl, workshop) < 0) return null;
        for (AnvilRecipe recipe : AnvilRecipeManager.all()) {
            ItemStack result = recipe.result();
            if (!CraftGating.canProduceAt(sl, workBlock, result.getItem())) continue;
            boolean wanted = ordersOnly
                ? Workshops.orderedCraftCount(workshop, result.getItem()) > 0
                : Workshops.wantedByMinStock(sl, settlement, workshop, result);
            if (!wanted) continue;
            List<ItemStack> inputs = new ArrayList<>();
            boolean stocked = true;
            for (AnvilRecipe.Ing ing : recipe.ingredients()) {
                if (WorkshopStorage.count(sl, workshop, ing.item()) < ing.count()) {
                    stocked = false;
                    break;
                }
                inputs.add(new ItemStack(ing.item(), ing.count()));
            }
            if (!stocked) continue;
            return new Craft(inputs, result.copy(), Math.max(1, recipe.strikes()) * TICKS_PER_STRIKE,
                Math.max(1, recipe.strikes()));
        }
        return null;
    }

    // ── Demand: the next casting the chain must produce ────────────────────────────────────────

    /** The casting the chain should produce next: the first wanted-but-unstocked casting (orders
     *  FIFO, then min-stock; hammer-recipe heads before direct casts), skipping anything already in
     *  flight on the anvil (a placed / filled / cooling mold — the waiting-stage in-flight count).
     *  When molten metal already sits in the bloomery, needs it can serve are preferred so a
     *  leftover is poured before a fresh melt slags it. */
    @Nullable
    private static Need neededCasting(ServerLevel sl, Settlement settlement, Workshop workshop,
                                      BlockPos workBlock, StoneAnvilBlockEntity anvil,
                                      @Nullable CrucibleContents molten) {
        List<Need> needs = new ArrayList<>();
        for (boolean ordersOnly : new boolean[] { true, false }) {
            for (AnvilRecipe recipe : AnvilRecipeManager.all()) {
                ItemStack result = recipe.result();
                if (!CraftGating.canProduceAt(sl, workBlock, result.getItem())) continue;
                boolean wanted = ordersOnly
                    ? Workshops.orderedCraftCount(workshop, result.getItem()) > 0
                    : Workshops.wantedByMinStock(sl, settlement, workshop, result);
                if (!wanted) continue;
                for (AnvilRecipe.Ing ing : recipe.ingredients()) {
                    Need need = castingNeed(ing.item());
                    if (need != null && WorkshopStorage.count(sl, workshop, ing.item()) < ing.count()) {
                        needs.add(need);
                    }
                }
            }
            for (String metal : MetalworkingItems.METALS) {
                for (String shape : DIRECT_SHAPES) {
                    ItemStack cast = MetalworkingItems.castingFor(shape, metal);
                    if (cast.isEmpty()) continue;
                    if (!CraftGating.canProduceAt(sl, workBlock, cast.getItem())) continue;
                    boolean wanted = ordersOnly
                        ? Workshops.orderedCraftCount(workshop, cast.getItem()) > 0
                        : Workshops.wantedByMinStock(sl, settlement, workshop, cast);
                    if (wanted && WorkshopStorage.count(sl, workshop, cast.getItem()) <= 0) {
                        needs.add(new Need(shape, metal, cast.getItem()));
                    }
                }
            }
        }
        Need fallback = null;
        for (Need need : needs) {
            if (inFlightOnAnvil(anvil, need)) continue;
            if (molten != null && molten.dominantMetal().equals(need.metal())
                    && molten.totalMb() >= MetalworkingItems.requiredMb(need.shape())) {
                return need;   // the leftover melt can serve this — use it before anything else
            }
            if (fallback == null) fallback = need;
        }
        return fallback;
    }

    /** True when the anvil already carries this need: its mold placed (being filled) or a matching
     *  filled/cooling cast — the committed unit a single order must not melt twice for. */
    private static boolean inFlightOnAnvil(StoneAnvilBlockEntity anvil, Need need) {
        if (!anvil.hasMold() || !anvil.moldShape().equals(need.shape())) return false;
        return anvil.fillMb() == 0 || anvil.metalId().equals(need.metal());
    }

    /** Reverse lookup: casting/finished item → the (shape, metal) mold that casts it. Lazily built
     *  once — item registration is long done by the time any executor runs. */
    private static Map<Item, Need> castingLookup;

    @Nullable
    private static Need castingNeed(Item item) {
        Map<Item, Need> lookup = castingLookup;
        if (lookup == null) {
            lookup = new LinkedHashMap<>();
            for (String metal : MetalworkingItems.METALS) {
                for (String shape : MetalworkingItems.MOLD_SHAPES) {
                    ItemStack cast = MetalworkingItems.castingFor(shape, metal);
                    if (!cast.isEmpty()) lookup.putIfAbsent(cast.getItem(), new Need(shape, metal, cast.getItem()));
                }
            }
            castingLookup = lookup;
        }
        return lookup.get(item);
    }

    /** The ore stacks that melt to ≥ {@code mb} of {@code metal}, from workshop stock — copper/tin
     *  straight, bronze ratio-searched (prefer ~70% copper) against the data-driven alloy bands.
     *  {@code null} when the stock can't cover it. Charge capped at the crucible's 8 units. */
    @Nullable
    private static List<ItemStack> chargeFor(ServerLevel sl, Workshop workshop, String metal, int mb) {
        int per = Math.max(1, MetalworkingData.mbPerUnit("copper"));
        int base = Math.max(1, (mb + per - 1) / per);
        if (metal.equals("copper") || metal.equals("tin")) {
            Item ore = metal.equals("copper") ? Items.RAW_COPPER : BannerboundAntiquity.RAW_TIN.get();
            int n = Math.min(8, base);
            if (n * per < mb) return null;   // > 8 units would be needed — no single melt covers it
            return WorkshopStorage.count(sl, workshop, ore) >= n ? List.of(new ItemStack(ore, n)) : null;
        }
        if (!metal.equals("bronze")) return null;
        int haveCu = WorkshopStorage.count(sl, workshop, Items.RAW_COPPER);
        int haveSn = WorkshopStorage.count(sl, workshop, BannerboundAntiquity.RAW_TIN.get());
        for (int n = base; n <= 8; n++) {
            int bestCu = -1;
            double bestDist = Double.MAX_VALUE;
            for (int cu = (int) Math.ceil(0.6 * n); cu <= (int) Math.floor(0.9 * n); cu++) {
                int sn = n - cu;
                if (sn < 1 || cu > haveCu || sn > haveSn) continue;
                if (!resolvesToBronze(cu, sn)) continue;
                double dist = Math.abs(cu - 0.7 * n);
                if (dist < bestDist) {
                    bestDist = dist;
                    bestCu = cu;
                }
            }
            if (bestCu >= 0) {
                return List.of(new ItemStack(Items.RAW_COPPER, bestCu),
                    new ItemStack(BannerboundAntiquity.RAW_TIN.get(), n - bestCu));
            }
        }
        return null;
    }

    /** Validate a copper/tin split against the same data-driven alloy resolution the bloomery uses. */
    private static boolean resolvesToBronze(int cu, int sn) {
        List<ItemStack> charge = new ArrayList<>();
        for (int i = 0; i < cu; i++) charge.add(new ItemStack(Items.RAW_COPPER));
        for (int i = 0; i < sn; i++) charge.add(new ItemStack(BannerboundAntiquity.RAW_TIN.get()));
        MetalworkingItems.MeltValue v = MetalworkingItems.resolveCharge(charge);
        return v != null && v.metalId().equals("bronze");
    }

    // ── Bloomery state helpers ──────────────────────────────────────────────────────────────────

    /** The bloomery crucible's still-solid charge, or {@code null} (no crucible / empty / molten). */
    @Nullable
    private static CrucibleContents chargedContents(BloomeryBlockEntity bloomery) {
        CrucibleContents c = crucibleContents(bloomery);
        return c != null && c.hasCharge() && !c.molten() ? c : null;
    }

    /** The bloomery crucible's molten metal, or {@code null}. */
    @Nullable
    private static CrucibleContents moltenContents(BloomeryBlockEntity bloomery) {
        CrucibleContents c = crucibleContents(bloomery);
        return c != null && c.molten() && c.totalMb() > 0 ? c : null;
    }

    @Nullable
    private static CrucibleContents crucibleContents(BloomeryBlockEntity bloomery) {
        ItemStack held = bloomery.getHeldItem();
        if (held.isEmpty() || !held.is(BannerboundAntiquity.CRUCIBLE.get())) return null;
        return held.get(BannerboundAntiquity.CRUCIBLE_CONTENTS.get());
    }

    @Nullable
    private static BloomeryBlockEntity bloomeryFor(ServerLevel sl, CitizenEntity citizen) {
        Settlement s = citizen.getSettlement();
        Workshop w = s == null ? null : s.getWorkshop(citizen.getAssignedWorkshopId());
        return w == null ? null : SmithyWorkshopRules.findBloomery(sl, w);
    }

    // ── Craft discrimination ────────────────────────────────────────────────────────────────────

    private static boolean isExtract(Craft craft) {
        return !craft.result().isEmpty() && craft.inputs().isEmpty();
    }

    private static boolean isHammer(Craft craft) {
        return !craft.result().isEmpty() && !craft.inputs().isEmpty();
    }

    private static boolean isPour(Craft craft) {
        return craft.result().isEmpty() && craft.inputs().isEmpty();
    }

    private static boolean isTend(Craft craft) {
        return craft.result().isEmpty() && craft.inputs().size() == 1
            && craft.inputs().get(0).is(BannerboundAntiquity.FIRE_STICKS.get());
    }

    private static boolean isPlaceMold(Craft craft) {
        if (!craft.result().isEmpty()) return false;
        for (ItemStack in : craft.inputs()) {
            if (moldShapeOf(in) != null) return true;
        }
        return false;
    }

    private static boolean isSmelt(Craft craft) {
        if (!craft.result().isEmpty()) return false;
        for (ItemStack in : craft.inputs()) {
            if (MetalworkingItems.isSmeltable(in)) return true;
        }
        return false;
    }

    @Nullable
    private static String moldShapeOf(ItemStack stack) {
        String path = net.minecraft.core.registries.BuiltInRegistries.ITEM
            .getKey(stack.getItem()).getPath();
        return path.startsWith("fired_clay_mold_") ? path.substring("fired_clay_mold_".length()) : null;
    }

    // ── Work placement & lifecycle ──────────────────────────────────────────────────────────────

    /** SMELT/TEND happen at the bellows (falling back to the bloomery); the rest at the anvil. */
    @Override
    public BlockPos workTarget(ServerLevel sl, Settlement settlement, Workshop workshop,
                               BlockPos workBlock, Craft craft) {
        if (isSmelt(craft) || isTend(craft)) {
            BloomeryBlockEntity bloomery = SmithyWorkshopRules.findBloomery(sl, workshop);
            if (bloomery == null) return workBlock;
            BlockPos bellows = SmithyWorkshopRules.findBellows(sl, bloomery.getBlockPos());
            return bellows != null ? bellows : bloomery.getBlockPos();
        }
        return workBlock;
    }

    @Override
    public void onStart(ServerLevel sl, CitizenEntity citizen, BlockPos workBlock, Craft craft) {
        if (isSmelt(craft)) {
            BloomeryBlockEntity bloomery = bloomeryFor(sl, citizen);
            if (bloomery == null) return;
            // The crucible: withdrawn from storage (in the inputs), else the one already inside —
            // whose leftover contents (a useless molten dreg) are slagged by the fresh charge.
            ItemStack crucible = ItemStack.EMPTY;
            List<ItemStack> charge = new ArrayList<>();
            for (ItemStack in : craft.inputs()) {
                if (in.is(BannerboundAntiquity.CRUCIBLE.get())) {
                    crucible = new ItemStack(BannerboundAntiquity.CRUCIBLE.get());
                } else if (MetalworkingItems.isSmeltable(in)) {
                    for (int i = 0; i < in.getCount(); i++) charge.add(in.copyWithCount(1));
                }
            }
            if (crucible.isEmpty()) crucible = bloomery.extract();
            if (crucible.isEmpty() || charge.isEmpty()) return;
            crucible.set(BannerboundAntiquity.CRUCIBLE_CONTENTS.get(), CrucibleContents.ofCharge(charge));
            bloomery.insert(crucible);
            if (bloomery.isOpen()) bloomery.toggle();   // heat only builds behind a shut door
            if (!bloomery.isLit()) {
                igniteTicksLeft.put(bloomery.getBlockPos().immutable(), IGNITE_TICKS);
            }
        } else if (isHammer(craft)) {
            if (sl.getBlockEntity(workBlock) instanceof StoneAnvilBlockEntity anvil) {
                String metal = MetalworkingItems.metalOf(craft.result().getItem());
                anvil.beginForging(craft.result(), craft.beats(),
                    metal.isEmpty() ? 0xFFFFFF : MetalworkingItems.colorOf(metal));
            }
        }
    }

    @Override
    public void onWorkTick(ServerLevel sl, CitizenEntity citizen, BlockPos workBlock, Craft craft,
                           int ticksLeft) {
        if (!isSmelt(craft) && !isTend(craft)) return;
        BloomeryBlockEntity bloomery = bloomeryFor(sl, citizen);
        if (bloomery == null) return;
        BlockPos key = bloomery.getBlockPos().immutable();
        if (!bloomery.isLit()) {
            // Fire-stick ignite wind-up (the Potter's kiln idiom) — also covers a fire that died.
            int left = igniteTicksLeft.getOrDefault(key, IGNITE_TICKS);
            if (left % IGNITE_SWING_INTERVAL == 0) {
                citizen.swing(InteractionHand.MAIN_HAND);
                sl.playSound(null, key, SoundEvents.FLINTANDSTEEL_USE, SoundSource.BLOCKS, 0.5F, 1.4F);
            }
            if (left <= 1) {
                igniteTicksLeft.remove(key);
                bloomery.ignite();
            } else {
                igniteTicksLeft.put(key, left - 1);
            }
            return;
        }
        // A player-opened door mid-melt bleeds all the heat off — re-shut it.
        if (bloomery.isOpen()) bloomery.toggle();
        // Band-aware pump regulation, at most one pump per second: pump while the temperature sits
        // below the middle of the metal's green band, coast (boost decays) once above it — copper
        // pumps continuously, tin/bronze oscillate around green-mid without overshooting the band.
        if (sl.getGameTime() % 20 == 0) {
            CrucibleContents contents = crucibleContents(bloomery);
            if (contents == null || contents.molten()) return;
            MetalworkingItems.MeltValue resolved = MetalworkingItems.resolveCharge(contents.charge());
            if (resolved == null) return;
            int[] band = BloomeryHeat.meltBand(resolved.metalId());
            float greenMid = band[0] + MetalworkingData.bloomery().greenWidth() / 2.0F;
            if (bloomery.temperatureC() < greenMid) {
                bloomery.pumpBellows();
                citizen.swing(InteractionHand.MAIN_HAND);
                sl.playSound(null, key, SoundEvents.CROSSBOW_LOADING_MIDDLE.value(),
                    SoundSource.BLOCKS, 0.6F, 0.6F);
            }
        }
    }

    /** SMELT/TEND end the moment the melt lands (the Potter's kiln idiom). */
    @Override
    public boolean externallyComplete(ServerLevel sl, CitizenEntity citizen, BlockPos workBlock,
                                      Craft craft, int ticksLeft) {
        if (!isSmelt(craft) && !isTend(craft)) return false;
        BloomeryBlockEntity bloomery = bloomeryFor(sl, citizen);
        return bloomery != null && moltenContents(bloomery) != null;
    }

    @Override
    public ItemStack finish(ServerLevel sl, CitizenEntity citizen, BlockPos workBlock, Craft craft) {
        if (isSmelt(craft) || isTend(craft)) {
            // The molten crucible STAYS in the bloomery (its occupancy throttles the chain, like the
            // potter's kiln slot); an unfinished melt simply re-enters as TEND next scan.
            BloomeryBlockEntity bloomery = bloomeryFor(sl, citizen);
            if (bloomery != null) igniteTicksLeft.remove(bloomery.getBlockPos());
            return ItemStack.EMPTY;
        }
        if (isPour(craft)) {
            BloomeryBlockEntity bloomery = bloomeryFor(sl, citizen);
            StoneAnvilBlockEntity anvil =
                sl.getBlockEntity(workBlock) instanceof StoneAnvilBlockEntity a ? a : null;
            CrucibleContents molten = bloomery == null ? null : moltenContents(bloomery);
            if (anvil != null && molten != null) {
                int missing = anvil.requiredMb() - anvil.fillMb();
                int moved = missing <= 0 ? 0 : anvil.pourInto(molten.dominantMetal(),
                    molten.tintColor(), Math.min(missing, molten.totalMb()));
                if (moved > 0) {
                    CrucibleContents drained = molten.drain(moved);
                    ItemStack held = bloomery.getHeldItem();
                    if (drained.isEmpty()) {
                        held.remove(BannerboundAntiquity.CRUCIBLE_CONTENTS.get());
                    } else {
                        held.set(BannerboundAntiquity.CRUCIBLE_CONTENTS.get(), drained);
                    }
                    bloomery.setChanged();
                }
            }
            return ItemStack.EMPTY;
        }
        if (isPlaceMold(craft)) {
            StoneAnvilBlockEntity anvil =
                sl.getBlockEntity(workBlock) instanceof StoneAnvilBlockEntity a ? a : null;
            for (ItemStack in : craft.inputs()) {
                String shape = moldShapeOf(in);
                if (shape != null && anvil != null && !anvil.hasMold() && anvil.pileEmpty()) {
                    anvil.placeMold(shape);
                    return ItemStack.EMPTY;
                }
            }
            // The anvil raced busy — hand the mold back to storage instead of losing it.
            return craft.inputs().get(0).copy();
        }
        if (isExtract(craft)) {
            if (sl.getBlockEntity(workBlock) instanceof StoneAnvilBlockEntity anvil) {
                ItemStack out = anvil.extractCasting();
                if (!out.isEmpty()) return out;
            }
            return ItemStack.EMPTY;
        }
        if (isHammer(craft)) {
            StoneAnvilBlockEntity anvil =
                sl.getBlockEntity(workBlock) instanceof StoneAnvilBlockEntity a ? a : null;
            if (anvil != null) anvil.endForging();
            // Per-strike scores through the shared NPC XP curve, then the player minigame's exact
            // hammer-rank gate: without a hammer within one rank of the workpiece, quality caps at
            // Standard (Hammer.complete's rule).
            QualityTier tier = QualityMath.simulateNpcTier(
                sl.random, citizen.getJobXp("smithy"), Math.max(1, craft.beats()));
            String metal = MetalworkingItems.metalOf(craft.result().getItem());
            Settlement s = citizen.getSettlement();
            Workshop w = s == null ? null : s.getWorkshop(citizen.getAssignedWorkshopId());
            int hammerRank = w == null ? -1 : SmithyWorkshopRules.bestHammerRank(sl, w);
            boolean canSuperior = !metal.isEmpty() && hammerRank >= MetalworkingData.rank(metal) - 1;
            if (!canSuperior && tier.ordinal() > QualityTier.STANDARD.ordinal()) {
                tier = QualityTier.STANDARD;
            }
            return Fletching.applyQuality(craft.result().copy(), tier);
        }
        return craft.result().copy();
    }

    @Override
    public void onAbort(ServerLevel sl, CitizenEntity citizen, BlockPos workBlock, Craft craft) {
        if (isSmelt(craft)) {
            // The goal returns the withdrawn ores/crucible/fire sticks — discard the bloomery-side
            // charge so nothing dupes. A crucible that was ALREADY inside stays inside, emptied.
            BloomeryBlockEntity bloomery = bloomeryFor(sl, citizen);
            if (bloomery == null) return;
            igniteTicksLeft.remove(bloomery.getBlockPos());
            if (chargedContents(bloomery) == null) return;   // melt landed / player intervened
            boolean crucibleWasInput = false;
            for (ItemStack in : craft.inputs()) {
                if (in.is(BannerboundAntiquity.CRUCIBLE.get())) crucibleWasInput = true;
            }
            ItemStack held = bloomery.extract();
            if (!crucibleWasInput && !held.isEmpty()) {
                held.remove(BannerboundAntiquity.CRUCIBLE_CONTENTS.get());
                bloomery.insert(held);
            }
        } else if (isHammer(craft)) {
            if (sl.getBlockEntity(workBlock) instanceof StoneAnvilBlockEntity anvil) {
                anvil.endForging();
            }
        }
    }

    @Override
    public void onBeat(ServerLevel sl, CitizenEntity citizen, BlockPos workBlock, Craft craft, int beatIndex) {
        if (isSmelt(craft) || isTend(craft)) {
            BloomeryBlockEntity bloomery = bloomeryFor(sl, citizen);
            BlockPos pos = bloomery != null ? bloomery.getBlockPos() : workBlock;
            sl.playSound(null, pos, SoundEvents.FURNACE_FIRE_CRACKLE, SoundSource.BLOCKS, 0.8F, 1.0F);
            return;
        }
        if (isHammer(craft)) {
            if (sl.getBlockEntity(workBlock) instanceof StoneAnvilBlockEntity anvil) {
                anvil.forgeStrike();
            }
            sl.playSound(null, workBlock, SoundEvents.ANVIL_LAND, SoundSource.BLOCKS, 0.5F, 1.0F);
            sl.sendParticles(net.minecraft.core.particles.ParticleTypes.CRIT,
                workBlock.getX() + 0.5, workBlock.getY() + 1.1, workBlock.getZ() + 0.5,
                8, 0.2, 0.15, 0.2, 0.1);
            return;
        }
        if (isPour(craft)) {
            sl.playSound(null, workBlock, SoundEvents.BUCKET_EMPTY_LAVA, SoundSource.BLOCKS, 0.6F, 1.0F);
            sl.sendParticles(net.minecraft.core.particles.ParticleTypes.LAVA,
                workBlock.getX() + 0.5, workBlock.getY() + 1.0, workBlock.getZ() + 0.5,
                3, 0.15, 0.1, 0.15, 0.02);
            return;
        }
        sl.playSound(null, workBlock, SoundEvents.STONE_PLACE, SoundSource.BLOCKS, 0.6F, 1.1F);
    }

    @Override
    public boolean consumesInput(Craft craft, ItemStack input) {
        return !input.is(BannerboundAntiquity.FIRE_STICKS.get());
    }

    // ── Stocker surfaces ────────────────────────────────────────────────────────────────────────

    /** Min-stock rows: every hammered tool + every direct-cast item (researched). */
    @Override
    public List<ItemStack> possibleOutputs(ServerLevel sl, BlockPos workBlock) {
        List<ItemStack> out = new ArrayList<>();
        for (AnvilRecipe recipe : AnvilRecipeManager.all()) {
            if (CraftGating.canProduceAt(sl, workBlock, recipe.result().getItem())) {
                out.add(recipe.result().copy());
            }
        }
        for (String metal : MetalworkingItems.METALS) {
            for (String shape : DIRECT_SHAPES) {
                ItemStack cast = MetalworkingItems.castingFor(shape, metal);
                if (!cast.isEmpty() && CraftGating.canProduceAt(sl, workBlock, cast.getItem())) {
                    out.add(cast);
                }
            }
        }
        return out;
    }

    /** Stocker SUPPLY: for every wanted final — its non-casting ingredients (sticks), the ores +
     *  fired mold behind each missing casting, plus the chain's conserved tools (fire sticks, a
     *  crucible when none is anywhere, a stone hammer when the smithy has no hammer at all). */
    @Override
    public List<ItemStack> missingInputs(ServerLevel sl, Settlement settlement, Workshop workshop,
                                         BlockPos workBlock) {
        Map<Item, Integer> desired = new LinkedHashMap<>();
        boolean anyMetalDemand = false;
        for (AnvilRecipe recipe : AnvilRecipeManager.all()) {
            ItemStack result = recipe.result();
            if (!CraftGating.canProduceAt(sl, workBlock, result.getItem())) continue;
            if (Workshops.orderedCraftCount(workshop, result.getItem()) <= 0
                    && !Workshops.wantedByMinStock(sl, settlement, workshop, result)) continue;
            for (AnvilRecipe.Ing ing : recipe.ingredients()) {
                Need need = castingNeed(ing.item());
                if (need == null) {
                    desired.merge(ing.item(), ing.count() * 2, Integer::sum);
                } else if (WorkshopStorage.count(sl, workshop, ing.item()) < ing.count()) {
                    anyMetalDemand = true;
                    addCastingDemand(desired, need);
                }
            }
        }
        for (String metal : MetalworkingItems.METALS) {
            for (String shape : DIRECT_SHAPES) {
                ItemStack cast = MetalworkingItems.castingFor(shape, metal);
                if (cast.isEmpty() || !CraftGating.canProduceAt(sl, workBlock, cast.getItem())) continue;
                if (Workshops.orderedCraftCount(workshop, cast.getItem()) <= 0
                        && !Workshops.wantedByMinStock(sl, settlement, workshop, cast)) continue;
                if (WorkshopStorage.count(sl, workshop, cast.getItem()) <= 0) {
                    anyMetalDemand = true;
                    addCastingDemand(desired, new Need(shape, metal, cast.getItem()));
                }
            }
        }
        List<ItemStack> out = new ArrayList<>();
        for (Map.Entry<Item, Integer> e : desired.entrySet()) {
            int have = WorkshopStorage.count(sl, workshop, e.getKey());
            if (have < e.getValue()) out.add(new ItemStack(e.getKey(), e.getValue() - have));
        }
        if (anyMetalDemand) {
            if (WorkshopStorage.count(sl, workshop, BannerboundAntiquity.FIRE_STICKS.get()) < 1) {
                out.add(new ItemStack(BannerboundAntiquity.FIRE_STICKS.get()));
            }
            BloomeryBlockEntity bloomery = SmithyWorkshopRules.findBloomery(sl, workshop);
            boolean crucibleInside = bloomery != null
                && bloomery.getHeldItem().is(BannerboundAntiquity.CRUCIBLE.get());
            if (!crucibleInside
                    && WorkshopStorage.count(sl, workshop, BannerboundAntiquity.CRUCIBLE.get()) < 1) {
                out.add(new ItemStack(BannerboundAntiquity.CRUCIBLE.get()));
            }
        }
        if (SmithyWorkshopRules.bestHammerRank(sl, workshop) < 0) {
            var stoneHammer = MetalworkingItems.HAMMERS.get("stone_hammer");
            if (stoneHammer != null) out.add(new ItemStack(stoneHammer.get()));
        }
        return out;
    }

    /** The ore units (+ the fired mold) one casting of {@code need} consumes. */
    private static void addCastingDemand(Map<Item, Integer> desired, Need need) {
        int mb = MetalworkingItems.requiredMb(need.shape());
        int per = Math.max(1, MetalworkingData.mbPerUnit("copper"));
        int units = Math.max(1, (mb + per - 1) / per);
        if (need.metal().equals("bronze")) {
            int cu = Math.max(1, (int) Math.round(units * 0.7));
            int sn = Math.max(1, units - cu);
            desired.merge(Items.RAW_COPPER, cu, Integer::sum);
            desired.merge(BannerboundAntiquity.RAW_TIN.get(), sn, Integer::sum);
        } else {
            Item ore = need.metal().equals("copper") ? Items.RAW_COPPER : BannerboundAntiquity.RAW_TIN.get();
            desired.merge(ore, units, Integer::sum);
        }
        var mold = MetalworkingItems.MOLDS.get("fired_clay_mold_" + need.shape());
        if (mold != null) desired.merge(mold.get(), 1, Integer::sum);
    }

    /** Stocker KEEP: ores, sticks, fired molds, castings, the conserved chain tools and every
     *  hammer — only finished tools are surplus. */
    @Override
    public Set<Item> retainedItems(ServerLevel sl, Settlement settlement, Workshop workshop, BlockPos workBlock) {
        Set<Item> keep = new LinkedHashSet<>();
        keep.add(Items.RAW_COPPER);
        keep.add(BannerboundAntiquity.RAW_TIN.get());
        keep.add(Items.STICK);
        keep.add(BannerboundAntiquity.FIRE_STICKS.get());
        keep.add(BannerboundAntiquity.CRUCIBLE.get());
        for (var mold : MetalworkingItems.MOLDS.values()) keep.add(mold.get());
        for (var casting : MetalworkingItems.CASTINGS.values()) keep.add(casting.get());
        for (var hammer : MetalworkingItems.HAMMERS.values()) keep.add(hammer.get());
        return keep;
    }
}
