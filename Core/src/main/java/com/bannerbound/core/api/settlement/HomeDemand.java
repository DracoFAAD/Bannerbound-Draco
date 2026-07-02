package com.bannerbound.core.api.settlement;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import com.bannerbound.core.api.research.ResearchManager;
import com.bannerbound.core.api.workshop.Workshops;
import com.bannerbound.core.social.ThoughtKind;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.TagKey;
import net.minecraft.world.Container;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Home demands — comforts a home wants <em>beyond beds</em> to be liveable. Beds make a home
 * functional; demands make it happy. Each demand is research-gated (a {@code bannerbound.home_demand:
 * <suffix>} flag), so the set of demands escalates as the civ advances — a fresh Antiquity hut is
 * asked for nothing but beds.
 *
 * <p>Two kinds:
 * <ul>
 *   <li><b>Fixtures</b> — a block in the home's marked region: LIGHTING (a light source), STORAGE
 *       (a {@code #bannerbound:workshop_storage} container), CAMPFIRE (a lit hearth).</li>
 *   <li><b>Luxuries</b> — an item the home keeps in its OWN storage container: COOKED_FOOD, CHARCOAL
 *       (matched by {@code #bannerbound:home_luxury/<suffix>} item tags). A luxury inherently needs a
 *       container, so it also implies the STORAGE fixture.</li>
 * </ul>
 *
 * <p>Demands are <b>soft</b>: an unmet demand never makes a home INVALID — it only lowers the home's
 * {@linkplain #computeHappiness happiness}, which in turn drives the resident mood thought
 * ({@link #moodThoughtFor}) and the nightly reproduction chance ({@link #reproductionBonus}).
 */
public enum HomeDemand {
    LIGHTING("lighting", Kind.FIXTURE, null),
    STORAGE("storage", Kind.FIXTURE, null),
    CAMPFIRE("campfire", Kind.FIXTURE, null),
    COOKED_FOOD("cooked_food", Kind.LUXURY, luxuryTag("cooked_food")),
    CHARCOAL("charcoal", Kind.LUXURY, luxuryTag("charcoal"));

    public enum Kind { FIXTURE, LUXURY }

    private final String suffix;
    private final Kind kind;
    /** Item tag a home container must hold to satisfy a luxury demand; null for fixtures. */
    private final TagKey<Item> luxuryTag;

    HomeDemand(String suffix, Kind kind, TagKey<Item> luxuryTag) {
        this.suffix = suffix;
        this.kind = kind;
        this.luxuryTag = luxuryTag;
    }

    public String suffix() { return suffix; }
    public Kind kind() { return kind; }
    public boolean isLuxury() { return kind == Kind.LUXURY; }
    /** The item tag a home container must hold to satisfy this luxury demand; null for fixtures.
     *  Used by the stocker's home-supply pass to find a deliverable luxury. */
    public TagKey<Item> luxuryTag() { return luxuryTag; }
    /** Research flag that activates this demand. */
    public String flag() { return FLAG_PREFIX + suffix; }

    // ─── Tags + constants ─────────────────────────────────────────────────────────────────────

    private static final String FLAG_PREFIX = "bannerbound.home_demand:";
    /** A block emitting at least this much light counts as the LIGHTING fixture (torch=14,
     *  lantern/campfire/glowstone=15, soul torch=10; weak candles/redstone fall short). */
    public static final int LIGHT_MIN = 8;
    /** Blocks that count as a hearth for the CAMPFIRE demand (lit campfires by default). */
    public static final TagKey<net.minecraft.world.level.block.Block> HEARTH_TAG = TagKey.create(
        Registries.BLOCK, ResourceLocation.fromNamespaceAndPath("bannerbound", "home_hearth"));

    private static TagKey<Item> luxuryTag(String suffix) {
        return TagKey.create(Registries.ITEM,
            ResourceLocation.fromNamespaceAndPath("bannerbound", "home_luxury/" + suffix));
    }

    // ─── Active-demand resolution ─────────────────────────────────────────────────────────────

    /** The demands switched on for {@code settlement} (their research flag is completed). */
    public static List<HomeDemand> activeDemands(Settlement settlement) {
        List<HomeDemand> out = new ArrayList<>();
        for (HomeDemand d : values()) {
            if (ResearchManager.hasFlag(settlement, d.flag())) out.add(d);
        }
        return out;
    }

    // ─── Evaluation ───────────────────────────────────────────────────────────────────────────

    /** One demand's met/unmet result, for the status panel. */
    public record DemandState(HomeDemand demand, boolean met) {}

    /**
     * Evaluates every active demand against the home's marked region in a single pass: scan for a
     * light source / storage container / lit hearth, gather the home's storage containers, then test
     * each luxury against those containers' contents. Returns one {@link DemandState} per active
     * demand (in enum order). Empty list when no demands are active.
     */
    public static List<DemandState> evaluate(ServerLevel sl, Settlement settlement, Set<BlockPos> marked) {
        List<HomeDemand> active = activeDemands(settlement);
        if (active.isEmpty()) return List.of();

        boolean hasLight = false, hasStorage = false, hasHearth = false;
        List<Container> containers = new ArrayList<>();
        for (BlockPos p : marked) {
            BlockState s = sl.getBlockState(p);
            if (!hasLight && s.getLightEmission(sl, p) >= LIGHT_MIN) hasLight = true;
            if (s.is(Workshops.STORAGE_TAG)) {
                hasStorage = true;
                BlockEntity be = sl.getBlockEntity(p);
                if (be instanceof Container c) containers.add(c);
            }
            if (!hasHearth && s.is(HEARTH_TAG)
                    && s.hasProperty(CampfireBlock.LIT) && s.getValue(CampfireBlock.LIT)) {
                hasHearth = true;
            }
        }

        List<DemandState> out = new ArrayList<>(active.size());
        for (HomeDemand d : active) {
            boolean met = switch (d) {
                case LIGHTING -> hasLight;
                case STORAGE -> hasStorage;
                case CAMPFIRE -> hasHearth;
                default -> d.isLuxury() && containersHold(containers, d.luxuryTag);
            };
            out.add(new DemandState(d, met));
        }
        return out;
    }

    private static boolean containersHold(List<Container> containers, TagKey<Item> tag) {
        for (Container c : containers) {
            for (int i = 0; i < c.getContainerSize(); i++) {
                ItemStack stack = c.getItem(i);
                if (!stack.isEmpty() && stack.is(tag)) return true;
            }
        }
        return false;
    }

    // ─── Daily luxury consumption ─────────────────────────────────────────────────────────────

    /** How much of each active luxury a home eats per resident per day. Luxuries are an ongoing
     *  sink, not a one-time stock: a stocked pantry drains over days and the demand lapses (then
     *  the stocker — or, later, a market — refills it). */
    private static final int PER_RESIDENT_DAILY = 1;

    /**
     * Consume one day's worth of each active luxury from {@code home}'s own pantry containers —
     * {@code residents × PER_RESIDENT_DAILY} of each. Takes whatever is available (down to none);
     * once a luxury runs out the demand reads unmet on the next validation and gets restocked.
     * Called once per in-game day from {@code HomeUpkeep}; no-op for an empty/pantry-less home.
     */
    public static void consumeDaily(ServerLevel sl, Settlement settlement, Home home) {
        int residents = home.residents().size();
        if (residents <= 0) return;
        List<HomeDemand> active = activeDemands(settlement);
        boolean anyLuxury = false;
        for (HomeDemand d : active) if (d.isLuxury()) { anyLuxury = true; break; }
        if (!anyLuxury) return;
        List<BlockPos> containers = Homes.deliverableContainers(sl, home);
        if (containers.isEmpty()) return;
        for (HomeDemand d : active) {
            if (!d.isLuxury()) continue;
            consumeFromContainers(sl, containers, d.luxuryTag, residents * PER_RESIDENT_DAILY);
        }
    }

    /** Removes up to {@code amount} items matching {@code tag} from the home's containers. */
    private static void consumeFromContainers(ServerLevel sl, List<BlockPos> containers,
                                              TagKey<Item> tag, int amount) {
        int remaining = amount;
        for (BlockPos pos : containers) {
            if (remaining <= 0) break;
            Container c = com.bannerbound.core.entity.DropOffContainers.resolveDropOff(sl, pos);
            if (c == null) continue;
            for (int i = 0; i < c.getContainerSize() && remaining > 0; i++) {
                ItemStack st = c.getItem(i);
                if (st.isEmpty() || !st.is(tag)) continue;
                ItemStack removed = c.removeItem(i, remaining); // Container API: shrinks the slot
                remaining -= removed.getCount();
            }
        }
    }

    // ─── Home happiness math ──────────────────────────────────────────────────────────────────

    private static final double MET_BONUS = 6.0;
    private static final double UNMET_PENALTY = 8.0; // an unmet expectation stings more than a met one pleases

    /** How much each beauty tier moves home happiness from the neutral midpoint. */
    private static final double APPEAL_WEIGHT = 6.0;

    /** Sentinel {@code spacePerBed} for a home with no measurable crowding (broken, unmarked, or
     *  bedless) — no crowding ceiling is applied. */
    public static final int NO_CROWDING = Integer.MAX_VALUE;

    /**
     * Combines <b>appeal</b> (beauty tier), demand satisfaction, and <b>living space</b> into a
     * 0–100 home-happiness value. A plain BLAND home with nothing demanded sits at the neutral
     * midpoint (50 → 0 reproduction bonus, matching the old appeal-only behaviour); appeal pushes it
     * up/down (±24 across the tiers), met demands push toward 100, unmet ones drag it down (harder
     * than met helps), and a roomy home earns a small spaciousness bonus.
     *
     * <p>Crowding is applied as a <b>hard ceiling</b>, not a small subtraction: a home packed to a
     * few blocks per bed cannot read happy no matter how many comforts are stuffed in, so a giant
     * shared dormitory tanks into the HATE band (near-zero reproduction) even with every demand met.
     * Overcrowding thus <em>overrides</em> comforts while staying SOFT — it never invalidates a home.
     *
     * @param spacePerBed enclosed interior air cells per bed, or {@link #NO_CROWDING} for none
     */
    public static double computeHappiness(ChunkBeauty beauty, int met, int unmet, int spacePerBed) {
        int tier = beauty != null ? beauty.tierIndex() : 0;  // −4..+4
        double base = 50 + tier * APPEAL_WEIGHT;             // 26..74; BLAND = 50 (neutral)
        double demandTerm = met * MET_BONUS - unmet * UNMET_PENALTY;
        double happiness = base + demandTerm + spaciousnessBonus(spacePerBed);
        return Math.max(0.0, Math.min(crowdingCeiling(spacePerBed), Math.min(100.0, happiness)));
    }

    /** Small additive reward for a generously sized home. Only bites above the crowding-ceiling
     *  range (≥10 blocks/bed), so it rewards real houses without ever rescuing a dorm. */
    private static double spaciousnessBonus(int spacePerBed) {
        if (spacePerBed >= 20) return 4.0;
        if (spacePerBed >= 14) return 2.0;
        return 0.0;
    }

    /**
     * Happiness ceiling imposed by crowding (enclosed interior air cells per bed). A properly sized
     * home (≥10 blocks/bed) is uncapped; below that the ceiling falls steeply so a packed dormitory
     * is forced into the UNCOMFORTABLE/HATE bands regardless of comforts. {@link #NO_CROWDING} ⇒
     * uncapped (no beds / broken home).
     */
    private static double crowdingCeiling(int spacePerBed) {
        if (spacePerBed >= 10) return 100.0;                                          // a real room: free
        if (spacePerBed <= 4) return Math.max(8.0, 22.0 - (4 - spacePerBed) * 7.0);   // 4→22, 3→15, ≤2→8
        return 22.0 + (spacePerBed - 4) * 13.0;                                       // 5→35 … 9→87
    }

    /** Conception-chance modifier from home happiness — replaces the old raw-appeal bonus. +50% at
     *  a perfect home, 0 at the neutral midpoint (50), down to the old −0.35 floor for a miserable one. */
    public static double reproductionBonus(double homeHappiness) {
        double b = (homeHappiness - 50.0) / 50.0 * 0.5;
        return Math.max(-0.35, Math.min(0.5, b));
    }

    /** The daily {@code *_HOME} mood thought a resident should hold, by home happiness, or null for
     *  the neutral band (no thought). */
    public static ThoughtKind moodThoughtFor(double homeHappiness) {
        if (homeHappiness >= 85.0) return ThoughtKind.LOVE_HOME;
        if (homeHappiness >= 60.0) return ThoughtKind.LIKE_HOME;
        if (homeHappiness >= 45.0) return null;
        if (homeHappiness >= 25.0) return ThoughtKind.UNCOMFORTABLE_HOME;
        return ThoughtKind.HATE_HOME;
    }
}
