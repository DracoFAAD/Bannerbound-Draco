package com.bannerbound.core.barbarian;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

import com.bannerbound.core.BannerboundCore;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.GsonHelper;
import net.minecraft.util.profiling.ProfilerFiller;

/**
 * Loads barbarian capability tiers from {@code data/<namespace>/barbarian_loadout/*.json} (shipped
 * by Antiquity, but loaded here in Core so raid/squad code can {@link #resolve} without a
 * Core→Antiquity dependency — same arrangement as {@link
 * com.bannerbound.core.api.research.data.ResearchTreeLoader} loading Antiquity research).
 *
 * <p>The camp's MELEE weapon tier now comes from its TOOL AGE (see {@code BarbarianTech.capability} —
 * the research tree already orders bone→wood→stone→iron via {@code set_tool_age}). These loadout
 * files only supply the barbarian-specific RANGED weapons the tool-age data lacks — the archery bow:
 * <pre>
 * { "research": "bannerboundantiquity:archery",
 *   "grants": { "ranged": true, "weapon_item": "bannerboundantiquity:primitive_bow",
 *               "projectile": "bannerboundantiquity:arrow", "behavior": "skirmisher" } }
 * </pre>
 * {@link #rangedOverride(Set)} returns the highest-tier such entry whose research is known.
 */
public class BarbarianLoadoutLoader extends SimpleJsonResourceReloadListener {
    public static final String FOLDER = "barbarian_loadout";
    private static final Gson GSON = new Gson();
    private static volatile Map<String, Entry> ENTRIES = Map.of();

    public BarbarianLoadoutLoader() {
        super(GSON, FOLDER);
    }

    /** One loadout row: knowing {@code research} grants this weapon/behaviour. */
    public record Entry(String research, String weapon, String weaponItem, String meleeWeaponItem,
                        int tier, double damage, double attackSpeed, boolean ranged, String projectile,
                        String behavior, int squadWeight) {
    }

    @Override
    protected void apply(Map<ResourceLocation, JsonElement> resources, ResourceManager manager,
                         ProfilerFiller profiler) {
        Map<String, Entry> map = new HashMap<>();
        for (Map.Entry<ResourceLocation, JsonElement> e : resources.entrySet()) {
            try {
                JsonObject obj = e.getValue().getAsJsonObject();
                String research = GsonHelper.getAsString(obj, "research", "");
                JsonObject grants = GsonHelper.getAsJsonObject(obj, "grants", new JsonObject());
                String weapon = GsonHelper.getAsString(grants, "weapon", "fists");
                String weaponItem = GsonHelper.getAsString(grants, "weapon_item", "");
                String meleeWeaponItem = GsonHelper.getAsString(grants, "melee_weapon", "");
                int tier = GsonHelper.getAsInt(grants, "tier", 0);
                double damage = GsonHelper.getAsDouble(grants, "damage", 1.0);
                double attackSpeed = GsonHelper.getAsDouble(grants, "attack_speed", 1.0);
                boolean ranged = GsonHelper.getAsBoolean(grants, "ranged", false);
                String projectile = GsonHelper.getAsString(grants, "projectile", "");
                String behavior = GsonHelper.getAsString(grants, "behavior", "");
                int squadWeight = GsonHelper.getAsInt(grants, "squad_weight", 1);
                map.put(research, new Entry(research, weapon, weaponItem, meleeWeaponItem, tier,
                    damage, attackSpeed, ranged, projectile, behavior, squadWeight));
            } catch (Exception ex) {
                BannerboundCore.LOGGER.error("Failed to parse barbarian loadout {}", e.getKey(), ex);
            }
        }
        ENTRIES = Collections.unmodifiableMap(map);
        BannerboundCore.LOGGER.info("Loaded {} barbarian ranged-loadout entries", map.size());
    }

    /** The highest-tier RANGED loadout entry whose gating research is known (the archery bow), or
     *  null. The melee weapon tier itself comes from the tool age (see {@code BarbarianTech.capability});
     *  these entries only add the barbarian-specific ranged weapons (bows) the tool-age data lacks. */
    public static Entry rangedOverride(Set<String> known) {
        if (known == null) return null;
        Entry best = null;
        for (String id : known) {
            Entry e = ENTRIES.get(id);
            if (e != null && e.ranged() && (best == null || e.tier() > best.tier())) best = e;
        }
        return best;
    }

    public static Map<String, Entry> getAll() {
        return ENTRIES;
    }
}
