package com.bannerbound.antiquity;

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

// An example config class. This is not required, but it's a good idea to have one to keep your config organized.
// Demonstrates how to use Neo's config APIs
public class Config {
    private static final ModConfigSpec.Builder BUILDER = new ModConfigSpec.Builder();

    public static final ModConfigSpec.BooleanValue LOG_DIRT_BLOCK = BUILDER
            .comment("Whether to log the dirt block on common setup")
            .define("logDirtBlock", true);

    public static final ModConfigSpec.IntValue MAGIC_NUMBER = BUILDER
            .comment("A magic number")
            .defineInRange("magicNumber", 42, 0, Integer.MAX_VALUE);

    public static final ModConfigSpec.ConfigValue<String> MAGIC_NUMBER_INTRODUCTION = BUILDER
            .comment("What you want the introduction message to be for the magic number")
            .define("magicNumberIntroduction", "The magic number is... ");

    // a list of strings that are treated as resource locations for items
    public static final ModConfigSpec.ConfigValue<List<? extends String>> ITEM_STRINGS = BUILDER
            .comment("A list of items to log on common setup.")
            .defineListAllowEmpty("items", List.of("minecraft:iron_ingot"), () -> "", Config::validateItemName);

    // ── Immersive hunting ───────────────────────────────────────────────────────────────────
    // Smarter, warier vanilla-animal AI for the stone-age hunting loop. All server-side.
    public static final ModConfigSpec.BooleanValue HUNTING_ENABLED;
    public static final ModConfigSpec.IntValue FLEE_RANGE;
    public static final ModConfigSpec.IntValue FLEE_RANGE_SNEAK;
    public static final ModConfigSpec.DoubleValue RANGE_SPRINT_MULT;
    public static final ModConfigSpec.BooleanValue REQUIRE_LINE_OF_SIGHT;
    public static final ModConfigSpec.IntValue SCARED_DURATION_TICKS;
    public static final ModConfigSpec.IntValue HERD_ALARM_RADIUS;
    public static final ModConfigSpec.IntValue MINING_NOISE_RADIUS;
    public static final ModConfigSpec.BooleanValue LURE_FOOD_CANCELS;
    // Flee speed multipliers (nav speed = base attribute × multiplier). Cow < player < others.
    public static final ModConfigSpec.DoubleValue COW_WALK_SPEED;
    public static final ModConfigSpec.DoubleValue COW_SPRINT_SPEED;
    public static final ModConfigSpec.DoubleValue PREY_WALK_SPEED;
    public static final ModConfigSpec.DoubleValue PREY_SPRINT_SPEED;
    public static final ModConfigSpec.DoubleValue HORSE_WALK_SPEED;
    public static final ModConfigSpec.DoubleValue HORSE_SPRINT_SPEED;
    public static final ModConfigSpec.DoubleValue FAST_WALK_SPEED;
    public static final ModConfigSpec.DoubleValue FAST_SPRINT_SPEED;
    // Pig boar charge.
    public static final ModConfigSpec.DoubleValue BOAR_CHARGE_CHANCE;
    public static final ModConfigSpec.IntValue BOAR_WINDUP_TICKS;
    public static final ModConfigSpec.IntValue BOAR_CHARGE_TICKS;
    public static final ModConfigSpec.DoubleValue BOAR_CHARGE_SPEED;
    public static final ModConfigSpec.DoubleValue BOAR_CHARGE_REDIRECT;
    public static final ModConfigSpec.DoubleValue BOAR_CHARGE_DAMAGE;
    public static final ModConfigSpec.DoubleValue BOAR_IMPACT_REACH;
    public static final ModConfigSpec.IntValue BOAR_CHARGE_CLAIM_TICKS;
    // Predators + babies.
    public static final ModConfigSpec.BooleanValue WILD_WOLVES_HOSTILE;
    public static final ModConfigSpec.BooleanValue OCELOTS_HOSTILE;
    public static final ModConfigSpec.BooleanValue PREDATORS_PACIFIED_BY_FOOD;
    public static final ModConfigSpec.BooleanValue BABIES_FLEE;
    public static final ModConfigSpec.BooleanValue BABIES_CHARGE;
    // Persistence hunting (stamina).
    public static final ModConfigSpec.DoubleValue STAMINA_MAX;
    public static final ModConfigSpec.DoubleValue STAMINA_DRAIN_PER_TICK;
    public static final ModConfigSpec.DoubleValue STAMINA_REGEN_PER_TICK;
    public static final ModConfigSpec.DoubleValue STAMINA_TIRED_THRESHOLD;
    public static final ModConfigSpec.DoubleValue TIRED_SPEED_MULT;
    // Hostile predator buffs (wild wolves / ocelots).
    public static final ModConfigSpec.DoubleValue HOSTILE_SPEED_MULT;
    public static final ModConfigSpec.DoubleValue HOSTILE_ATTACK_BONUS;
    // Bleeding.
    public static final ModConfigSpec.BooleanValue BLEED_ENABLED;
    public static final ModConfigSpec.IntValue BLEED_DURATION_TICKS;
    public static final ModConfigSpec.IntValue BLEED_INTERVAL_TICKS;
    public static final ModConfigSpec.DoubleValue BLEED_DAMAGE_PER_TICK;
    public static final ModConfigSpec.DoubleValue BLEED_SPEED_MULT;
    // Ground blood-splat decals.
    public static final ModConfigSpec.BooleanValue BLOOD_SPLAT_ENABLED;
    public static final ModConfigSpec.IntValue BLOOD_SPLAT_LIFETIME_TICKS;
    public static final ModConfigSpec.DoubleValue BLOOD_SPLAT_CHANCE;
    // Footprint tracks (only species that have a <species>_footprint.png).
    public static final ModConfigSpec.BooleanValue FOOTPRINTS_ENABLED;
    public static final ModConfigSpec.IntValue FOOTPRINT_LIFETIME_TICKS;
    public static final ModConfigSpec.DoubleValue FOOTPRINT_SPACING;
    public static final ModConfigSpec.DoubleValue FOOTPRINT_SPEED_STRETCH;
    public static final ModConfigSpec.DoubleValue FOOTPRINT_CHANCE;
    public static final ModConfigSpec.ConfigValue<List<? extends String>> FOOTPRINT_SPECIES;
    // Spear fishing — a thrown spear that kills a fish leaves a floating spear-with-fish catch.
    public static final ModConfigSpec.BooleanValue SPEAR_FISHING_ENABLED;
    public static final ModConfigSpec.IntValue SPEAR_CATCH_LIFETIME_TICKS;
    // Fletching minigame.
    public static final ModConfigSpec.BooleanValue FLETCHING_FOV_EFFECT;
    // ── Poison (blowdart / herb poisons) ────────────────────────────────────────────────────
    // Generic, per-poison behaviour is coded in PoisonType; these are the shared knobs.
    public static final ModConfigSpec.BooleanValue POISON_ENABLED;
    public static final ModConfigSpec.IntValue POISON_STAGE_ADVANCE_TICKS;
    public static final ModConfigSpec.IntValue POISON_DOT_INTERVAL_TICKS;
    public static final ModConfigSpec.DoubleValue POISON_DOT_PER_STAGE;
    public static final ModConfigSpec.DoubleValue POISON_NONLETHAL_FLOOR;
    public static final ModConfigSpec.DoubleValue POISON_SLOW_PER_STAGE;
    public static final ModConfigSpec.DoubleValue POISON_PLAYER_DOT_MULT;
    public static final ModConfigSpec.IntValue POISON_OLEANDER_CLOCK_TICKS;
    public static final ModConfigSpec.IntValue POISON_CURARE_STUN_TICKS;
    public static final ModConfigSpec.IntValue POISON_CURARE_PLAYER_OUT_TICKS;
    public static final ModConfigSpec.IntValue POISON_CURARE_ANIMAL_OUT_TICKS;
    public static final ModConfigSpec.IntValue POISON_CURARE_IMMUNE_TICKS;
    public static final ModConfigSpec.IntValue POISON_FOOD_DELAY_TICKS;

    static {
        BUILDER.push("hunting");
        HUNTING_ENABLED = BUILDER.comment("Master toggle for immersive hunting AI on vanilla animals.")
            .define("enabled", true);
        FLEE_RANGE = BUILDER.comment("Distance (blocks) a non-hostile animal flees a visible player.")
            .defineInRange("fleeRange", 24, 1, 128);
        FLEE_RANGE_SNEAK = BUILDER.comment("Flee distance when the player is sneaking.")
            .defineInRange("fleeRangeSneak", 12, 1, 128);
        RANGE_SPRINT_MULT = BUILDER.comment("Flee range multiplier when the player is sprinting (noise).")
            .defineInRange("rangeSprintMult", 1.5, 1.0, 4.0);
        REQUIRE_LINE_OF_SIGHT = BUILDER.comment("Animals only flee a player they can actually see.")
            .define("requireLineOfSight", true);
        SCARED_DURATION_TICKS = BUILDER.comment("How long an animal stays spooked after being scared.")
            .defineInRange("scaredDurationTicks", 160, 20, 2400);
        HERD_ALARM_RADIUS = BUILDER.comment("Radius (blocks) a scared animal alarms same-species herd-mates.")
            .defineInRange("herdAlarmRadius", 12, 0, 64);
        MINING_NOISE_RADIUS = BUILDER.comment("Radius (blocks) breaking a block spooks nearby animals (0 = off).")
            .defineInRange("miningNoiseRadius", 16, 0, 64);
        LURE_FOOD_CANCELS = BUILDER.comment("Holding an animal's favourite food stops it fleeing/charging you.")
            .define("lureFoodCancels", true);

        // ~1.45× faster across the board (animals felt too slow). Cow stays the slowest tier.
        COW_WALK_SPEED = BUILDER.defineInRange("cowWalkSpeed", 1.89, 0.1, 8.0);
        COW_SPRINT_SPEED = BUILDER.defineInRange("cowSprintSpeed", 2.18, 0.1, 8.0);
        PREY_WALK_SPEED = BUILDER.defineInRange("preyWalkSpeed", 2.32, 0.1, 8.0);
        PREY_SPRINT_SPEED = BUILDER.defineInRange("preySprintSpeed", 3.19, 0.1, 8.0);
        HORSE_WALK_SPEED = BUILDER.defineInRange("horseWalkSpeed", 2.03, 0.1, 8.0);
        HORSE_SPRINT_SPEED = BUILDER.defineInRange("horseSprintSpeed", 2.61, 0.1, 8.0);
        FAST_WALK_SPEED = BUILDER.defineInRange("fastWalkSpeed", 2.32, 0.1, 8.0);
        FAST_SPRINT_SPEED = BUILDER.defineInRange("fastSprintSpeed", 3.48, 0.1, 8.0);

        BOAR_CHARGE_CHANCE = BUILDER.comment("Chance a scared pig charges instead of fleeing.")
            .defineInRange("boarChargeChance", 0.50, 0.0, 1.0);
        BOAR_WINDUP_TICKS = BUILDER.defineInRange("boarWindupTicks", 2, 0, 40);
        BOAR_CHARGE_TICKS = BUILDER.defineInRange("boarChargeTicks", 14, 1, 100);
        BOAR_CHARGE_SPEED = BUILDER.defineInRange("boarChargeSpeed", 0.55, 0.1, 2.0);
        BOAR_CHARGE_REDIRECT = BUILDER.comment("How strongly a charge tracks the player (0=straight, 1=homing).")
            .defineInRange("boarChargeRedirect", 0.10, 0.0, 1.0);
        BOAR_CHARGE_DAMAGE = BUILDER.defineInRange("boarChargeDamage", 6.0, 0.0, 100.0);
        BOAR_IMPACT_REACH = BUILDER.defineInRange("boarImpactReach", 2.0, 0.5, 6.0);
        BOAR_CHARGE_CLAIM_TICKS = BUILDER.defineInRange("boarChargeClaimTicks", 20, 1, 200);

        WILD_WOLVES_HOSTILE = BUILDER.define("wildWolvesHostile", true);
        OCELOTS_HOSTILE = BUILDER.define("ocelotsHostile", true);
        PREDATORS_PACIFIED_BY_FOOD = BUILDER.comment("Holding a predator's food stops it attacking.")
            .define("predatorsPacifiedByFood", true);
        BABIES_FLEE = BUILDER.define("babiesFlee", true);
        BABIES_CHARGE = BUILDER.define("babiesCharge", false);

        STAMINA_MAX = BUILDER.defineInRange("staminaMax", 100.0, 1.0, 10000.0);
        STAMINA_DRAIN_PER_TICK = BUILDER.defineInRange("staminaDrainPerTick", 0.5, 0.0, 100.0);
        STAMINA_REGEN_PER_TICK = BUILDER.defineInRange("staminaRegenPerTick", 0.4, 0.0, 100.0);
        STAMINA_TIRED_THRESHOLD = BUILDER.comment("Below this stamina, fleeing animals slow down (run-down).")
            .defineInRange("staminaTiredThreshold", 20.0, 0.0, 10000.0);
        TIRED_SPEED_MULT = BUILDER.comment("Speed multiplier applied to a tired fleeing animal.")
            .defineInRange("tiredSpeedMult", 0.6, 0.1, 1.0);

        HOSTILE_SPEED_MULT = BUILDER.comment("Movement-speed multiplier for hostile wild wolves/ocelots.")
            .defineInRange("hostileSpeedMult", 1.30, 1.0, 4.0);
        HOSTILE_ATTACK_BONUS = BUILDER.comment("Bonus attack damage added to hostile wild wolves/ocelots.")
            .defineInRange("hostileAttackBonus", 3.0, 0.0, 100.0);

        BLEED_ENABLED = BUILDER.define("bleedEnabled", true);
        BLEED_DURATION_TICKS = BUILDER.defineInRange("bleedDurationTicks", 200, 1, 6000);
        BLEED_INTERVAL_TICKS = BUILDER.comment("Ticks between bleed damage/particle pulses (higher = slower bleed).")
            .defineInRange("bleedIntervalTicks", 60, 1, 600);
        BLEED_DAMAGE_PER_TICK = BUILDER.comment("Damage per bleed pulse.")
            .defineInRange("bleedDamagePerPulse", 1.0, 0.0, 100.0);
        BLEED_SPEED_MULT = BUILDER.comment("Flee speed multiplier while bleeding (wounded slow).")
            .defineInRange("bleedSpeedMult", 0.85, 0.1, 1.0);

        BLOOD_SPLAT_ENABLED = BUILDER.define("bloodSplatEnabled", true);
        BLOOD_SPLAT_LIFETIME_TICKS = BUILDER.comment("How long a ground blood splat lasts before fading away.")
            .defineInRange("bloodSplatLifetimeTicks", 1200, 20, 24000);
        BLOOD_SPLAT_CHANCE = BUILDER.comment("Chance per bleed pulse to leave a ground blood splat.")
            .defineInRange("bloodSplatChance", 1.0, 0.0, 1.0);

        FOOTPRINTS_ENABLED = BUILDER.comment("Walking animals leave footprint tracks you can examine.")
            .define("footprintsEnabled", true);
        FOOTPRINT_LIFETIME_TICKS = BUILDER.comment("How long a footprint track lasts before fading away.")
            .defineInRange("footprintLifetimeTicks", 1800, 20, 24000);
        FOOTPRINT_SPACING = BUILDER.comment("Blocks an animal walks between dropping footprint tracks.")
            .defineInRange("footprintSpacing", 3.5, 0.25, 16.0);
        FOOTPRINT_SPEED_STRETCH = BUILDER.comment(
                "Extra track spacing per block/tick of speed — longer strides at speed mean far fewer",
                "tracks while sprinting; walking is barely affected. 0 = fixed spacing regardless of speed.")
            .defineInRange("footprintSpeedStretch", 3.0, 0.0, 50.0);
        FOOTPRINT_CHANCE = BUILDER.comment("Chance to actually leave a track at each spacing interval.")
            .defineInRange("footprintChance", 0.25, 0.0, 1.0);
        FOOTPRINT_SPECIES = BUILDER.comment(
                "Animals that leave footprints — each needs a textures/item/<name>_footprint.png.")
            .defineListAllowEmpty("footprintSpecies",
                List.of("cow", "sheep", "pig", "chicken", "wolf", "horse"),
                () -> "cow", obj -> obj instanceof String);

        SPEAR_FISHING_ENABLED = BUILDER.comment(
                "Spearing a fish leaves a floating spear-with-fish catch you pick up (spear + fish +",
                "drops together), instead of dropping the fish and spear as loose items.")
            .define("spearFishingEnabled", true);
        SPEAR_CATCH_LIFETIME_TICKS = BUILDER.comment(
                "How long a floating speared-fish catch lasts before despawning (20 ticks = 1s).")
            .defineInRange("spearCatchLifetimeTicks", 6000, 200, 24000);
        BUILDER.pop();

        BUILDER.push("fletching");
        FLETCHING_FOV_EFFECT = BUILDER.comment(
                "Widen the FOV slightly while drawing the fletching stretch bar (visual only; disable",
                "if FOV shifts bother you).")
            .define("fovEffect", true);
        BUILDER.pop();

        BUILDER.push("poison");
        POISON_ENABLED = BUILDER.comment("Master toggle for blowdart/herb poisons (root-pulses, DoT, etc).")
            .define("enabled", true);
        POISON_STAGE_ADVANCE_TICKS = BUILDER.comment(
                "Ticks an untreated poison spends at each stage before escalating to the next",
                "(20 ticks = 1s; 3600 = 3 min, so wolfsbane reaches its lethal final stage in ~9 min).")
            .defineInRange("stageAdvanceTicks", 3600, 20, 24000);
        POISON_DOT_INTERVAL_TICKS = BUILDER.comment(
                "Ticks between poison damage pulses (higher = slower). 600 = one hit per 30s — the DoT",
                "is a slow background drain; death comes from reaching the final stage, not chip damage.")
            .defineInRange("dotIntervalTicks", 600, 1, 6000);
        POISON_DOT_PER_STAGE = BUILDER.comment("Poison damage per pulse, multiplied by the current stage (1-based).")
            .defineInRange("dotPerStage", 0.5, 0.0, 100.0);
        POISON_NONLETHAL_FLOOR = BUILDER.comment(
                "Below max stage, poison DoT can't take a victim under this health floor (so a curable",
                "poison never kills before its final stage). Lethality at max stage is per-poison.")
            .defineInRange("nonlethalFloor", 2.0, 0.0, 100.0);
        POISON_SLOW_PER_STAGE = BUILDER.comment(
                "Movement-speed reduction fraction per stage for paralytic poisons (0.15 = -15%/stage).")
            .defineInRange("slowPerStage", 0.15, 0.0, 1.0);
        POISON_PLAYER_DOT_MULT = BUILDER.comment(
                "Multiplier on poison damage-over-time for PLAYERS (animals take full). Low so wolfsbane",
                "barely scratches a player — players die from reaching the lethal final stage, not the DoT.")
            .defineInRange("playerDotMult", 0.15, 0.0, 1.0);
        POISON_OLEANDER_CLOCK_TICKS = BUILDER.comment(
                "Oleander only: ticks from infection until cardiac arrest (the 'heart attack') fires — a",
                "FIXED countdown that kills if not cured in time, regardless of stage. 3600 = 3 min.",
                "Oleander also blocks ALL healing while active, so chip damage can't be undone as it runs.")
            .defineInRange("oleanderClockTicks", 3600, 100, 24000);
        POISON_CURARE_STUN_TICKS = BUILDER.comment(
                "Curare only: ticks of heavy-slow STUN (eyelids going heavy) before the victim passes out.",
                "20 ticks = 1s; 60 = 3s.")
            .defineInRange("curareStunTicks", 60, 0, 1200);
        POISON_CURARE_PLAYER_OUT_TICKS = BUILDER.comment(
                "Curare only: ticks a PLAYER stays unconscious (fully immobilised, prone, draggable) after",
                "passing out. 300 = 15s.")
            .defineInRange("curarePlayerOutTicks", 300, 20, 12000);
        POISON_CURARE_ANIMAL_OUT_TICKS = BUILDER.comment(
                "Curare only: ticks an ANIMAL or citizen stays unconscious after passing out. 600 = 30s.")
            .defineInRange("curareAnimalOutTicks", 600, 20, 12000);
        POISON_CURARE_IMMUNE_TICKS = BUILDER.comment(
                "Curare only: ticks of immunity to NEW curare doses granted by drinking/applying the arnica",
                "antidote (so a kidnapper can't instantly re-dart a freed victim). 600 = 30s; 0 = none.")
            .defineInRange("curareImmuneTicks", 600, 0, 24000);
        POISON_FOOD_DELAY_TICKS = BUILDER.comment(
                "Ticks after eating LACED food before the poison sets in (so the meal isn't an obvious",
                "culprit). 200 = 10s.")
            .defineInRange("foodDelayTicks", 200, 0, 12000);
        BUILDER.pop();
    }

    static final ModConfigSpec SPEC = BUILDER.build();

    private static boolean validateItemName(final Object obj) {
        return obj instanceof String itemName && BuiltInRegistries.ITEM.containsKey(ResourceLocation.parse(itemName));
    }
}
