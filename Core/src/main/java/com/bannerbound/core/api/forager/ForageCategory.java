package com.bannerbound.core.api.forager;

import java.util.function.Predicate;

import com.bannerbound.core.api.research.ResearchManager;
import com.bannerbound.core.api.settlement.Settlement;

import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.CaveVines;
import net.minecraft.world.level.block.CropBlock;
import net.minecraft.world.level.block.SweetBerryBushBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * The kinds of wild growth a {@link com.bannerbound.core.entity.ForagerWorkGoal forager} can gather,
 * in the order they appear in the Job-tab picker. Each entry knows three things:
 * <ul>
 *   <li>its lang suffix (label shown in the picker),</li>
 *   <li>the research flag that <b>unlocks</b> it — the first four come with the Foraging research,
 *       the shear-requiring three ({@code VINES}, {@code GRASS}, {@code LEAVES}) stay LOCKED until
 *       Shearing is researched,</li>
 *   <li>which world blocks it {@link #matches matches}, and whether it's {@link #sustainable()}
 *       (picked without destroying the plant, like a berry bush) or simply broken.</li>
 * </ul>
 * The per-citizen enabled set is a 7-bit mask keyed by {@link #ordinal()} — see {@link #bit()} and
 * {@link #ALL_BITS}. This enum is the single source of truth shared by the work goal, the
 * {@code CitizenJobStatePayload} sync, and the client picker, so order and identity never drift.
 */
public enum ForageCategory {
    // NOTE: the unlock-flag strings are inlined here (not the FORAGER_FLAG/SHEARING_FLAG constants),
    // because Java initialises enum constants before static fields, so the constants aren't visible yet.
    BERRIES     ("berries",        "bannerbound.unlock.forager",  true,  ForageCategory::isRipeBerry),
    SMALL_FLOWERS("small_flowers", "bannerbound.unlock.forager",  false, s -> s.is(BlockTags.SMALL_FLOWERS)),
    TALL_FLOWERS ("tall_flowers",  "bannerbound.unlock.forager",  false, s -> s.is(BlockTags.TALL_FLOWERS)),
    MUSHROOMS   ("mushrooms",      "bannerbound.unlock.forager",  false, s -> s.is(Blocks.RED_MUSHROOM) || s.is(Blocks.BROWN_MUSHROOM)),
    VINES       ("vines",          "bannerbound.allow_shearing",  false, s -> s.is(Blocks.VINE)),
    GRASS       ("grass",          "bannerbound.allow_shearing",  false, s -> s.is(Blocks.SHORT_GRASS) || s.is(Blocks.FERN)
                                                                           || s.is(Blocks.TALL_GRASS) || s.is(Blocks.LARGE_FERN)),
    LEAVES      ("leaves",         "bannerbound.allow_shearing",  false, s -> s.is(BlockTags.LEAVES)),
    // Scavenging: break grass + leaves BARE-HANDED for crafting raws (sticks from leaves; an
    // expansion adds fibers from grass via ForagerHooks) — what makes the fletching/crafting
    // chain self-sustaining. Available with the base Foraging research, NOT shear-gated. When
    // the shear categories above are also enabled they win the overlap (declared earlier), so
    // the player chooses raws-vs-blocks by toggling.
    STICKS_FIBERS("sticks_fibers", "bannerbound.unlock.forager",  false, s -> s.is(BlockTags.LEAVES)
                                                                           || s.is(Blocks.SHORT_GRASS) || s.is(Blocks.FERN)
                                                                           || s.is(Blocks.TALL_GRASS) || s.is(Blocks.LARGE_FERN)),
    // Wild crops: a mature crop standing on the dry-farmland patches of a crop chunk — "pick wild
    // carrots/potatoes". Sustainable (the plant is reset to seedling, not destroyed). The work goal
    // additionally gates this to genuine, unworked crop chunks so it can't strip a stray planting.
    WILD_CROPS  ("wild_crops",     "bannerbound.unlock.forager",  true,  s -> s.getBlock() instanceof CropBlock cb
                                                                           && cb.isMaxAge(s));

    /** Research flag granted by the Foraging research — gates the first four categories + the job. */
    public static final String FORAGER_FLAG = "bannerbound.unlock.forager";
    /** Research flag granted by the Shearing research — gates vines / grass / leaves. */
    public static final String SHEARING_FLAG = "bannerbound.allow_shearing";

    private static final ForageCategory[] VALUES = values();
    /** Bitmask with every category enabled — the default for a freshly-assigned forager. */
    public static final int ALL_BITS = (1 << VALUES.length) - 1;

    private final String langSuffix;
    private final String unlockFlag;
    private final boolean sustainable;
    private final Predicate<BlockState> matcher;

    ForageCategory(String langSuffix, String unlockFlag, boolean sustainable, Predicate<BlockState> matcher) {
        this.langSuffix = langSuffix;
        this.unlockFlag = unlockFlag;
        this.sustainable = sustainable;
        this.matcher = matcher;
    }

    /** Translation key for this category's picker label, e.g. {@code bannerbound.forager.target.berries}. */
    public String langKey() {
        return "bannerbound.forager.target." + langSuffix;
    }

    /** This category's bit in the per-citizen enabled mask. */
    public int bit() {
        return 1 << ordinal();
    }

    /** True if the plant is harvested non-destructively (berries picked, bush left to regrow). */
    public boolean sustainable() {
        return sustainable;
    }

    /** True for the shear-gated categories (vines/grass/leaves) — harvested as if with shears, so
     *  they drop the block itself rather than the bare-hand byproduct. */
    public boolean usesShears() {
        return SHEARING_FLAG.equals(unlockFlag);
    }

    /** True if {@code state} is a block this category gathers (and, for berries, is ripe). */
    public boolean matches(BlockState state) {
        return matcher.test(state);
    }

    /** True if {@code settlement} has researched what this category needs (so it isn't LOCKED). */
    public boolean isUnlocked(Settlement settlement) {
        return settlement != null && ResearchManager.hasFlag(settlement, unlockFlag);
    }

    /** Bitmask of every category {@code settlement} has unlocked — sent to the client picker so it
     *  can grey out the LOCKED rows without the client needing the settlement's research flags. */
    public static int unlockedBits(Settlement settlement) {
        int bits = 0;
        for (ForageCategory c : VALUES) {
            if (c.isUnlocked(settlement)) bits |= c.bit();
        }
        return bits;
    }

    public static ForageCategory byOrdinal(int ordinal) {
        return ordinal >= 0 && ordinal < VALUES.length ? VALUES[ordinal] : null;
    }

    public static int count() {
        return VALUES.length;
    }

    /** Ripe sweet-berry bush or a glow-berry-bearing cave vine — the two pick-don't-destroy sources. */
    private static boolean isRipeBerry(BlockState s) {
        if (s.is(Blocks.SWEET_BERRY_BUSH)) {
            return s.getValue(SweetBerryBushBlock.AGE) >= 2;
        }
        if (s.is(Blocks.CAVE_VINES) || s.is(Blocks.CAVE_VINES_PLANT)) {
            return s.getValue(CaveVines.BERRIES);
        }
        return false;
    }
}
