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
 * in the order they appear in the Job-tab picker. Each entry knows its lang suffix (picker label),
 * the research flag that unlocks it, which world blocks it {@link #matches matches}, and whether it
 * is {@link #sustainable()} (picked without destroying the plant, like a berry bush) or simply
 * broken. Unlock gating: berries / flowers / mushrooms / sticks_fibers / wild_crops come with the
 * base Foraging research; the shear-requiring vines / grass / leaves stay LOCKED until Shearing is
 * researched ({@link #usesShears()} keys off that flag so those drop the block itself rather than
 * the bare-hand byproduct). STICKS_FIBERS scavenges grass + leaves + dead bushes BARE-HANDED for
 * crafting raws (sticks from leaves; an expansion adds fibers via {@code ForagerHooks}), which is what
 * makes the fletching/crafting chain self-sustaining; where it overlaps the shear categories those
 * win because they are declared earlier (the work goal walks the enum in order), so the player
 * chooses raws-vs-blocks by toggling. WILD_CROPS is a mature {@link CropBlock} on a crop chunk's
 * dry farmland (sustainable - reset to seedling, not destroyed); the work goal further restricts it
 * to genuine unworked crop chunks so it can't strip a stray planting.
 * <p>
 * The per-citizen enabled set is a bitmask keyed by {@link #ordinal()} (see {@link #bit()} and
 * {@link #ALL_BITS}). This enum is the single source of truth shared by the work goal, the
 * {@code CitizenJobStatePayload} sync, and the client picker, so order and identity never drift -
 * never reorder or remove a constant without bumping the mask consumers.
 */
public enum ForageCategory {
    // Unlock-flag strings are inlined (not FORAGER_FLAG/SHEARING_FLAG): enum constants initialise
    // before static fields, so those constants aren't assigned yet at this point.
    BERRIES     ("berries",        "bannerbound.unlock.forager",  true,  ForageCategory::isRipeBerry),
    SMALL_FLOWERS("small_flowers", "bannerbound.unlock.forager",  false, s -> s.is(BlockTags.SMALL_FLOWERS)),
    TALL_FLOWERS ("tall_flowers",  "bannerbound.unlock.forager",  false, s -> s.is(BlockTags.TALL_FLOWERS)),
    MUSHROOMS   ("mushrooms",      "bannerbound.unlock.forager",  false, s -> s.is(Blocks.RED_MUSHROOM) || s.is(Blocks.BROWN_MUSHROOM)),
    VINES       ("vines",          "bannerbound.allow_shearing",  false, s -> s.is(Blocks.VINE)),
    GRASS       ("grass",          "bannerbound.allow_shearing",  false, s -> s.is(Blocks.SHORT_GRASS) || s.is(Blocks.FERN)
                                                                           || s.is(Blocks.TALL_GRASS) || s.is(Blocks.LARGE_FERN)),
    LEAVES      ("leaves",         "bannerbound.allow_shearing",  false, s -> s.is(BlockTags.LEAVES)),
    STICKS_FIBERS("sticks_fibers", "bannerbound.unlock.forager",  false, s -> s.is(BlockTags.LEAVES)
                                                                           || s.is(Blocks.SHORT_GRASS) || s.is(Blocks.FERN)
                                                                           || s.is(Blocks.TALL_GRASS) || s.is(Blocks.LARGE_FERN)
                                                                           || s.is(Blocks.DEAD_BUSH)),
    WILD_CROPS  ("wild_crops",     "bannerbound.unlock.forager",  true,  s -> s.getBlock() instanceof CropBlock cb
                                                                           && cb.isMaxAge(s));

    public static final String FORAGER_FLAG = "bannerbound.unlock.forager";
    public static final String SHEARING_FLAG = "bannerbound.allow_shearing";

    private static final ForageCategory[] VALUES = values();
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

    public String langKey() {
        return "bannerbound.forager.target." + langSuffix;
    }

    public int bit() {
        return 1 << ordinal();
    }

    public boolean sustainable() {
        return sustainable;
    }

    public boolean usesShears() {
        return SHEARING_FLAG.equals(unlockFlag);
    }

    public boolean matches(BlockState state) {
        return matcher.test(state);
    }

    public boolean isUnlocked(Settlement settlement) {
        return settlement != null && ResearchManager.hasFlag(settlement, unlockFlag);
    }

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
