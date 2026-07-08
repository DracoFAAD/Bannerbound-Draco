package com.bannerbound.core;

import org.jetbrains.annotations.ApiStatus;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.fml.event.config.ModConfigEvent;
import net.neoforged.neoforge.common.ModConfigSpec;

/**
 * Common (server-authoritative) config spec, registered as ModConfig.Type.COMMON in the
 * BannerboundCore constructor. Per-option documentation deliberately lives in the .comment()
 * string literals, because those ship into the generated TOML for pack authors - do not move it
 * here. The researchSpeedMultiplier / immigrationMinSecondsBetween / birthRateMultiplier /
 * foodPerCitizenPerDay block is the pacing group: the original tuning played far too fast in
 * playtests, so the defaults are the deliberately slowed values. Gotcha: changing a default in
 * this class does NOT update an already-generated TOML on disk - delete or hand-edit the saved
 * file (or tune live) for a new default to take effect. Read month lengths via
 * calendarMonthDays(), never CALENDAR_MONTH_DAYS raw: the accessor guarantees exactly 12 entries
 * clamped to 1-31 (missing entries default to 7).
 */
@ApiStatus.Internal
public class Config {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    public static final ModConfigSpec.BooleanValue LOG_DIRT_BLOCK = BUILDER
            .comment("Whether to log the dirt block on common setup")
            .define("logDirtBlock", true);

    public static final ModConfigSpec.BooleanValue UI_ANIMATIONS = BUILDER
            .comment("Polish animations on Bannerbound GUIs (open zoom/slide, tab-switch slide, "
                + "tab hover eases). Set false for instant, static screens.")
            .define("uiAnimations", true);

    public static final ModConfigSpec.BooleanValue TUTORIAL_POPUPS = BUILDER
            .comment("When true (default) tutorial popups open as interrupt modals at their "
                + "trigger moments. Set false to downgrade every popup to a quiet Chronicle "
                + "toast - the content still unlocks for re-reading in the Chronicle.")
            .define("tutorialPopups", true);

    public static final ModConfigSpec.BooleanValue JEI_SHOW_UNKNOWN = BUILDER
            .comment("When false, JEI hides recipes whose output item is still unknown to the "
                + "local player's settlement. Set true for packs that prefer full recipe visibility "
                + "or as an escape hatch if a JEI runtime-hiding regression appears.")
            .define("jeiShowUnknown", false);

    public static final ModConfigSpec.BooleanValue VANILLA_CONTENT = BUILDER
            .comment("When true (default) vanilla Minecraft external content is left untouched. "
                + "Set false to strip it for a from-scratch progression: all hostile-mob spawning "
                + "(natural / structure / spawner), nether & end portal creation in survival, and "
                + "free access to vanilla chests/barrels are disabled. Installing Bannerbound: Antiquity "
                + "forces this false at load (and ships the matching worldgen/loot data). "
                + "Read everywhere via VanillaContentState.isEnabled(), never this field directly.")
            .define("vanillaContent", true);

    public static final ModConfigSpec.BooleanValue ENABLE_CITY_STATES = BUILDER
            .comment("Master switch for AI city-states (discovered vanilla villages as diplomatic "
                + "actors). Default FALSE: city-states are a Classical-era feature — in the Ancient "
                + "era, barbarian camps fill the AI-neighbour role. Disabled = no detection, no "
                + "economy/war ticking, no territory protection, no Diplomacy-tab rows; existing "
                + "records stay saved but inert, and re-enabling wakes them. (Antiquity also strips "
                + "vanilla VILLAGE worldgen from newly generated chunks via datapack, independent of "
                + "this runtime switch.)")
            .define("enableCityStates", false);

    public static final ModConfigSpec.ConfigValue<String> CITY_STATE_DIFFICULTY = BUILDER
            .comment("Difficulty of AI city-states (discovered vanilla villages): VERY_EASY, EASY, "
                + "MEDIUM (default), HARD, VERY_HARD. Higher = a more populous/industrious neighbour "
                + "that accrues tradeable materials and advances its own research faster. City-states "
                + "never advance an era on their own — they always wait for a player settlement. "
                + "NOTE: only NEW city-states pick up a changed default; existing ones keep their saved "
                + "difficulty.")
            .define("cityStateDifficulty", "MEDIUM",
                o -> o instanceof String s && com.bannerbound.core.citystate.CityStateDifficulty
                    .fromName(s).name().equalsIgnoreCase(s.trim()));

    public static final ModConfigSpec.BooleanValue OPS_BYPASS_CLAIM_PROTECTION = BUILDER
            .comment("When true, op-level-2+ players can break/place/destroy anything inside any "
                + "settlement's claimed territory (banners and town halls included) for moderation. "
                + "Default false so claim protection applies to EVERYONE — important because a "
                + "single-player world opened to LAN with cheats makes every player an op, which "
                + "otherwise silently disables all grief protection during playtests.")
            .define("opsBypassClaimProtection", false);

    public static final ModConfigSpec.DoubleValue RESEARCH_SPEED_MULTIPLIER = BUILDER
            .comment("Global multiplier on how fast BOTH the science and culture research trees "
                + "progress. 1.0 = original speed; 0.4 (default) = 2.5x slower. Lower = slower. "
                + "Scales only research accumulation, not the food/culture survival economy.")
            .defineInRange("researchSpeedMultiplier", 0.4, 0.01, 10.0);

    public static final ModConfigSpec.DoubleValue IMMIGRATION_MIN_SECONDS_BETWEEN = BUILDER
            .comment("Minimum real-time seconds between immigrants arriving in a settlement. The "
                + "food/culture bars still have to be full, but a new citizen can't arrive until "
                + "this long after the previous one — the actual pacing lever for population growth "
                + "(food/culture fill near-instantly, so cost alone never paced it). 0 = no cooldown "
                + "(original behaviour). Default 12.")
            .defineInRange("immigrationMinSecondsBetween", 12.0, 0.0, 600.0);

    public static final ModConfigSpec.DoubleValue BIRTH_RATE_MULTIPLIER = BUILDER
            .comment("Multiplier on the per-night chance a citizen couple conceives. 1.0 = original; "
                + "0.5 (default) = half as many births. Lower = slower population growth from births. "
                + "(Does not affect livestock breeding — see herderBaseBreedChance.)")
            .defineInRange("birthRateMultiplier", 0.5, 0.0, 5.0);

    public static final ModConfigSpec.DoubleValue FOOD_PER_CITIZEN_PER_DAY = BUILDER
            .comment("How much abstract settlement food one citizen consumes per in-game day "
                + "(24000 ticks). Higher = harsher food pressure. Only applies once a government is "
                + "enacted (anarchy eats nothing). At 150 a 7-citizen tribe drains ~0.9 food/s, so "
                + "food production is a real, ongoing constraint rather than a non-issue.")
            .defineInRange("foodPerCitizenPerDay", 150.0, 0.0, 1000.0);

    public static final ModConfigSpec.DoubleValue STORED_FOOD_RATE_PER_VALUE = BUILDER
            .comment("Passive settlement food/sec contributed by each valid stored food VALUE in "
                + "claimed settlement storage after Storage Logistics. Example: 16 cod with food "
                + "value 1.0 at the default 0.01 = +0.16 food/s. Stored food is NOT consumed by "
                + "this passive contribution; spoiled or rejected food contributes 0.")
            .defineInRange("storedFoodRatePerValue", 0.01, 0.0, 1000.0);

    public static final ModConfigSpec.IntValue MAGIC_NUMBER = BUILDER
            .comment("A magic number")
            .defineInRange("magicNumber", 42, 0, Integer.MAX_VALUE);

    public static final ModConfigSpec.ConfigValue<String> MAGIC_NUMBER_INTRODUCTION = BUILDER
            .comment("What you want the introduction message to be for the magic number")
            .define("magicNumberIntroduction", "The magic number is... ");

    public static final ModConfigSpec.DoubleValue HERDER_BASE_BREED_CHANCE = BUILDER
            .comment("Base probability (0.0-1.0) that ANY ready pair (player-bred or herder-bred) produces a "
                + "baby. The ground they stand on and nearby water adjust it (see the breed* options below). "
                + "Default 0.5 = 50%.")
            .defineInRange("herderBaseBreedChance", 0.5, 0.0, 1.0);

    public static final ModConfigSpec.DoubleValue BREED_GRASS_BONUS = BUILDER
            .comment("Added to breed chance when the pair stands on fertile ground "
                + "(#bannerbound:fertile_breeding_ground, e.g. grass). Default 0.10 = +10%.")
            .defineInRange("breedGrassBonus", 0.10, 0.0, 1.0);

    public static final ModConfigSpec.DoubleValue BREED_WATER_BONUS = BUILDER
            .comment("Added to breed chance when a water block is within breedWaterRadius. Default 0.15 = +15%.")
            .defineInRange("breedWaterBonus", 0.15, 0.0, 1.0);

    public static final ModConfigSpec.DoubleValue BREED_INFERTILE_PENALTY = BUILDER
            .comment("Subtracted from breed chance when the pair stands on infertile ground "
                + "(#bannerbound:infertile_breeding_ground, e.g. sand/gravel/coarse dirt). Default 0.25 = -25%.")
            .defineInRange("breedInfertilePenalty", 0.25, 0.0, 1.0);

    public static final ModConfigSpec.IntValue BREED_WATER_RADIUS = BUILDER
            .comment("Block radius searched around a breeding pair for water (the breedWaterBonus). Default 10.")
            .defineInRange("breedWaterRadius", 10, 0, 32);

    public static final ModConfigSpec.DoubleValue BREED_MANURE_PENALTY = BUILDER
            .comment("Subtracted from breed chance PER manure block (#bannerbound:manure) within "
                + "breedManureRadius of the pair. A filthy, unmucked pen breeds poorly until a herder (or "
                + "the player) clears the manure. Default 0.06 → ~4 pats halves a base pen's odds.")
            .defineInRange("breedManurePenalty", 0.06, 0.0, 1.0);

    public static final ModConfigSpec.IntValue BREED_MANURE_RADIUS = BUILDER
            .comment("Block radius searched around a breeding pair for manure (the breedManurePenalty). Default 6.")
            .defineInRange("breedManureRadius", 6, 0, 32);

    public static final ModConfigSpec.DoubleValue HERDER_PEN_WATER_MULTIPLIER = BUILDER
            .comment("Pen capacity is multiplied by this if the pen has any water source. Default 1.5.")
            .defineInRange("herderPenWaterMultiplier", 1.5, 1.0, 4.0);

    public static final ModConfigSpec.IntValue HERDER_PEN_LARGE_FOOTPRINT = BUILDER
            .comment("How many pen size-units a LARGE animal (cow, horse) occupies; small animals "
                + "(chicken, pig, sheep) always take 1. Higher = fewer big animals per pen. Default 4.")
            .defineInRange("herderPenLargeFootprint", 4, 1, 16);

    public static final ModConfigSpec.DoubleValue HERDER_FOOD_PER_SIZE_PER_SECOND = BUILDER
            .comment("Passive food/sec each live penned animal adds to its settlement, PER food-size "
                + "(chicken=1, cow/sheep/pig=2, horse=3). Default 0.05 → 6 horses = 6×3×0.05 = +0.9 food/s. "
                + "Culling an animal removes its share (the trade: settlement income vs. a meat harvest).")
            .defineInRange("herderFoodPerSizePerSecond", 0.05, 0.0, 1.0);

    public static final ModConfigSpec.ConfigValue<List<? extends String>> ITEM_STRINGS = BUILDER
            .comment("A list of items to log on common setup.")
            .defineListAllowEmpty("items", List.of("minecraft:iron_ingot"), () -> "", Config::validateItemName);

    public static final ModConfigSpec.ConfigValue<List<? extends Integer>> CALENDAR_MONTH_DAYS = BUILDER
            .comment("Days in each of the 12 calendar months. The year is their sum — and the year IS one "
                + "orbit of the world around the sun, so every planet's orbital period rescales with it "
                + "(Kepler). 12 entries, each 1-31. Default: 7 each (84-day year).")
            .defineListAllowEmpty("calendarMonthDays",
                List.of(7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7, 7), () -> 7, Config::validateMonthDays);

    static final ModConfigSpec SPEC = BUILDER.build();

    private static boolean validateItemName(final Object obj) {
        return obj instanceof String itemName && BuiltInRegistries.ITEM.containsKey(ResourceLocation.parse(itemName));
    }

    private static boolean validateMonthDays(final Object obj) {
        return obj instanceof Integer days && days >= 1 && days <= 31;
    }

    public static int[] calendarMonthDays() {
        List<? extends Integer> raw = CALENDAR_MONTH_DAYS.get();
        int[] out = new int[12];
        for (int i = 0; i < 12; i++) {
            int v = i < raw.size() ? raw.get(i) : 7;
            out[i] = Math.max(1, Math.min(31, v));
        }
        return out;
    }
}
