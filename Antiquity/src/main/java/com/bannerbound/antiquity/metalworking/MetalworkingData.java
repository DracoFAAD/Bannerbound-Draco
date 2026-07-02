package com.bannerbound.antiquity.metalworking;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.bannerbound.antiquity.BannerboundAntiquity;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.mojang.serialization.Codec;
import com.mojang.serialization.JsonOps;
import com.mojang.serialization.codecs.RecordCodecBuilder;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;

/**
 * Data-driven tuning for metalworking — the mB amounts, melt temperatures, and per-metal/per-mold
 * numbers, loaded from {@code data/<namespace>/metalworking/*.json} (one file, e.g. {@code definitions.json}).
 * Code keeps a {@link #DEFAULTS} table so everything works before/without a datapack; a datapack file
 * overrides it. Server-side reload listener (registered in {@code AntiquityEvents}); jar-loaded on
 * remote clients via {@code ClientDatapackRecipes} so the HUD/renderer see the same numbers.
 */
public final class MetalworkingData extends SimpleJsonResourceReloadListener {
    private static final Gson GSON = new Gson();

    /** Per-metal numbers: molten display colour, hammer rank, melting point (°C), mB per raw unit. */
    public record MetalDef(int color, int rank, int meltPoint, int mbPerUnit) {
        static final Codec<Integer> HEX = Codec.STRING.xmap(
            s -> (int) Long.parseLong(s, 16), i -> String.format("%06X", i & 0xFFFFFF));
        public static final Codec<MetalDef> CODEC = RecordCodecBuilder.create(i -> i.group(
            HEX.fieldOf("color").forGetter(MetalDef::color),
            Codec.INT.fieldOf("rank").forGetter(MetalDef::rank),
            Codec.INT.fieldOf("melt_point").forGetter(MetalDef::meltPoint),
            Codec.INT.optionalFieldOf("mb_per_unit", 50).forGetter(MetalDef::mbPerUnit)
        ).apply(i, MetalDef::new));
    }

    /** Bloomery temperature tuning (METALWORKING_PLAN.md Part 1). */
    public record Bloomery(float baseCeiling, float bellowsPerPump, float bellowsMax, float bellowsDecay,
                           float climb, float fall, int meltBandWidth, int greenWidth) {
        public static final Codec<Bloomery> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.FLOAT.optionalFieldOf("base_ceiling", 100F).forGetter(Bloomery::baseCeiling),
            Codec.FLOAT.optionalFieldOf("bellows_per_pump", 220F).forGetter(Bloomery::bellowsPerPump),
            Codec.FLOAT.optionalFieldOf("bellows_max", 1200F).forGetter(Bloomery::bellowsMax),
            Codec.FLOAT.optionalFieldOf("bellows_decay", 9F).forGetter(Bloomery::bellowsDecay),
            Codec.FLOAT.optionalFieldOf("climb", 0.06F).forGetter(Bloomery::climb),
            Codec.FLOAT.optionalFieldOf("fall", 0.03F).forGetter(Bloomery::fall),
            Codec.INT.optionalFieldOf("melt_band_width", 220).forGetter(Bloomery::meltBandWidth),
            Codec.INT.optionalFieldOf("green_width", 120).forGetter(Bloomery::greenWidth)
        ).apply(i, Bloomery::new));
    }

    /** A <b>ratio-driven</b> alloy rule: each component carries the fraction band (of total molten mB)
     *  it must occupy. A charge becomes {@code result} only if it contains <i>exactly</i> these metals
     *  AND each one's mB share falls inside its band (e.g. bronze = copper 60–90 % + tin 10–40 %). An
     *  off-ratio mix (too little tin, or a stray third metal) fails the rule and falls back to the
     *  dominant metal — so you have to get the proportions right to make a clean alloy. */
    public record AlloyDef(Map<String, Range> components, String result) {
        public record Range(double min, double max) {
            public static final Codec<Range> CODEC = RecordCodecBuilder.create(i -> i.group(
                Codec.DOUBLE.fieldOf("min").forGetter(Range::min),
                Codec.DOUBLE.fieldOf("max").forGetter(Range::max)
            ).apply(i, Range::new));
        }

        public static final Codec<AlloyDef> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.unboundedMap(Codec.STRING, Range.CODEC).fieldOf("components").forGetter(AlloyDef::components),
            Codec.STRING.fieldOf("result").forGetter(AlloyDef::result)
        ).apply(i, AlloyDef::new));

        /** True if the charge's per-metal mB map (and its {@code total}) matches every component band
         *  and carries no metal outside this alloy's components. */
        public boolean matches(Map<String, Integer> byMetal, int total) {
            if (total <= 0) return false;
            for (String present : byMetal.keySet()) {
                if (!components.containsKey(present)) return false; // a stray metal contaminates the melt
            }
            for (Map.Entry<String, Range> c : components.entrySet()) {
                double frac = byMetal.getOrDefault(c.getKey(), 0) / (double) total;
                if (frac < c.getValue().min() || frac > c.getValue().max()) return false;
            }
            return true;
        }
    }

    public record Config(Map<String, MetalDef> metals, Map<String, Integer> molds,
                         List<AlloyDef> alloys, Bloomery bloomery) {
        public static final Codec<Config> CODEC = RecordCodecBuilder.create(i -> i.group(
            Codec.unboundedMap(Codec.STRING, MetalDef.CODEC).fieldOf("metals").forGetter(Config::metals),
            Codec.unboundedMap(Codec.STRING, Codec.INT).fieldOf("molds").forGetter(Config::molds),
            AlloyDef.CODEC.listOf().optionalFieldOf("alloys", DEFAULT_ALLOYS).forGetter(Config::alloys),
            Bloomery.CODEC.optionalFieldOf("bloomery", DEFAULT_BLOOMERY).forGetter(Config::bloomery)
        ).apply(i, Config::new));
    }

    private static final Bloomery DEFAULT_BLOOMERY =
        new Bloomery(100F, 220F, 1200F, 9F, 0.06F, 0.03F, 220, 120);
    private static final List<AlloyDef> DEFAULT_ALLOYS = List.of(
        new AlloyDef(Map.of(
            "copper", new AlloyDef.Range(0.60, 0.90),
            "tin", new AlloyDef.Range(0.10, 0.40)), "bronze"));

    /** Fallback used until a datapack loads (matches the original hardcoded values). */
    public static final Config DEFAULTS = new Config(
        Map.of(
            "copper", new MetalDef(0xED8E56, 1, 1085, 50),
            "tin", new MetalDef(0xD9DEE3, 1, 232, 50),
            "bronze", new MetalDef(0xE29622, 2, 950, 50)),
        Map.ofEntries(
            Map.entry("axe", 150), Map.entry("pickaxe", 150), Map.entry("hoe", 100),
            Map.entry("shovel", 100), Map.entry("sword", 200), Map.entry("knife", 80),
            Map.entry("hammer", 180), Map.entry("chisel", 60), Map.entry("ingot", 100),
            Map.entry("spear", 120), Map.entry("arrow", 60)),
        DEFAULT_ALLOYS, DEFAULT_BLOOMERY);

    private static volatile Config config = DEFAULTS;

    public MetalworkingData() {
        super(GSON, "metalworking");
    }

    @Override
    protected void apply(Map<ResourceLocation, JsonElement> entries, ResourceManager rm, ProfilerFiller p) {
        applyEntries(entries);
    }

    public static void applyEntries(Map<ResourceLocation, JsonElement> entries) {
        for (Map.Entry<ResourceLocation, JsonElement> e : entries.entrySet()) {
            Config parsed = Config.CODEC.parse(JsonOps.INSTANCE, e.getValue())
                .resultOrPartial(err -> BannerboundAntiquity.LOGGER.error(
                    "Skipping invalid metalworking config {}: {}", e.getKey(), err))
                .orElse(null);
            if (parsed != null) {
                // Merge over the defaults so a partial file (e.g. only molds) keeps the rest.
                Map<String, MetalDef> metals = new HashMap<>(DEFAULTS.metals());
                metals.putAll(parsed.metals());
                Map<String, Integer> molds = new HashMap<>(DEFAULTS.molds());
                molds.putAll(parsed.molds());
                config = new Config(metals, molds, parsed.alloys(), parsed.bloomery());
            }
        }
    }

    public static Config get() {
        return config;
    }

    // ── Convenience accessors (with sensible fallbacks for unknown ids) ──────────────────────────
    private static MetalDef metal(String id) {
        return config.metals().getOrDefault(id, DEFAULTS.metals().getOrDefault(id,
            new MetalDef(0xFFFFFF, 1, 600, 50)));
    }

    public static int color(String metalId) { return metal(metalId).color(); }
    public static int rank(String metalId) { return metalId.equals("stone") ? 0 : metal(metalId).rank(); }
    public static int meltPoint(String metalId) { return metal(metalId).meltPoint(); }
    public static int mbPerUnit(String metalId) { return metal(metalId).mbPerUnit(); }

    public static int requiredMb(String shape) {
        return config.molds().getOrDefault(shape, 100);
    }

    public static Bloomery bloomery() {
        return config.bloomery();
    }

    public static List<AlloyDef> alloys() {
        return config.alloys();
    }
}
