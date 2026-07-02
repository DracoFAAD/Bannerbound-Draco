package com.bannerbound.core.api.research.data;

import com.bannerbound.core.api.research.ToolAge;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.OptionalInt;

import com.bannerbound.core.BannerboundCore;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.GsonHelper;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.Items;

/**
 * Loads tool-age definitions from {@code data/<namespace>/tool_ages/<id>.json}. One file per age:
 * <pre>{@code
 * { "name": "Stone Age",
 *   "order": 1,
 *   "chop_ticks": 15,
 *   "tools": { "axe": "minecraft:stone_axe", "shovel": "minecraft:stone_shovel", ... } }
 * }</pre>
 * Modded ages drop in their own files — no merge logic required. Map is keyed by the file id
 * (path minus the {@code .json} suffix, namespace-prefixed via {@link ResourceLocation#getPath()}).
 */
public class ToolAgeLoader extends SimpleJsonResourceReloadListener {
    public static final String FOLDER = "tool_ages";
    private static final Gson GSON = new Gson();
    private static volatile Map<String, ToolAge> AGES = Collections.emptyMap();

    public ToolAgeLoader() {
        super(GSON, FOLDER);
    }

    @Override
    protected void apply(Map<ResourceLocation, JsonElement> resources, ResourceManager manager, ProfilerFiller profiler) {
        Map<String, ToolAge> map = new HashMap<>();
        for (Map.Entry<ResourceLocation, JsonElement> entry : resources.entrySet()) {
            ResourceLocation key = entry.getKey();
            String id = key.getPath(); // e.g. "stone" for data/<ns>/tool_ages/stone.json
            try {
                JsonObject obj = entry.getValue().getAsJsonObject();
                String rawName = GsonHelper.getAsString(obj, "name", id);
                int order = GsonHelper.getAsInt(obj, "order", 0);
                OptionalInt chopTicks = obj.has("chop_ticks")
                    ? OptionalInt.of(GsonHelper.getAsInt(obj, "chop_ticks"))
                    : OptionalInt.empty();
                // mine_speed: ticks per digger-mined block. Lower = faster. Parallel to
                // chop_ticks; default lives on the work goal (80 ticks for bare-handed).
                OptionalInt mineTicks = obj.has("mine_speed")
                    ? OptionalInt.of(GsonHelper.getAsInt(obj, "mine_speed"))
                    : OptionalInt.empty();
                // harvest_speed: ticks per till/plant/harvest action for the farmer worker.
                // Same semantics as mine_speed — lower = faster. Default 70 ticks bare-handed.
                OptionalInt harvestTicks = obj.has("harvest_speed")
                    ? OptionalInt.of(GsonHelper.getAsInt(obj, "harvest_speed"))
                    : OptionalInt.empty();
                // weapon_damage: half-hearts per swing when a citizen uses the age's sword in
                // self-defence. weapon_attack_speed: swings per second; combat cooldown =
                // 20 / value ticks. Defaults match the vanilla wooden-sword baseline.
                double weaponDamage = obj.has("weapon_damage")
                    ? GsonHelper.getAsDouble(obj, "weapon_damage") : 4.0;
                double weaponAttackSpeed = obj.has("weapon_attack_speed")
                    ? GsonHelper.getAsDouble(obj, "weapon_attack_speed") : 1.6;

                Map<String, Item> tools = new HashMap<>();
                if (obj.has("tools")) {
                    JsonObject toolsObj = GsonHelper.getAsJsonObject(obj, "tools");
                    for (Map.Entry<String, JsonElement> t : toolsObj.entrySet()) {
                        String itemId = t.getValue().getAsString();
                        ResourceLocation itemRl = ResourceLocation.tryParse(itemId);
                        Item item = itemRl == null ? Items.AIR : BuiltInRegistries.ITEM.get(itemRl);
                        if (item != Items.AIR) {
                            tools.put(t.getKey(), item);
                        } else {
                            BannerboundCore.LOGGER.warn("Tool age {} references unknown item '{}'", id, itemId);
                        }
                    }
                }

                // Try the lang key bannerbound.tool_age.<id>; fall back to the raw "name" string.
                Component displayName = Component.translatableWithFallback(
                    "bannerbound.tool_age." + id, rawName);

                map.put(id, new ToolAge(id, displayName, order, chopTicks, mineTicks, harvestTicks,
                    weaponDamage, weaponAttackSpeed, tools));
            } catch (Exception ex) {
                BannerboundCore.LOGGER.error("Failed to parse tool_age {}", key, ex);
            }
        }
        AGES = map;
        BannerboundCore.LOGGER.info("Loaded {} tool ages", map.size());
    }

    public static Map<String, ToolAge> getAll() {
        return AGES;
    }

    public static ToolAge get(String id) {
        return AGES.get(id);
    }

    /**
     * Finds the tool age whose {@code role} tool (e.g. {@code "axe"}, {@code "shovel"}) is exactly
     * {@code item}, or {@code null} if no age defines that item for that role. Used so a worker's
     * work cadence reflects the tool they were actually handed, not the settlement's best unlocked
     * age — give them a bone axe and they chop at bone speed even after Wood Refining.
     */
    public static ToolAge getByTool(String role, Item item) {
        if (item == null) return null;
        for (ToolAge age : AGES.values()) {
            if (age.tools().get(role) == item) return age;
        }
        return null;
    }
}
