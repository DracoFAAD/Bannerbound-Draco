package com.bannerbound.core.crisis;

import com.bannerbound.core.BannerboundCore;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.GsonHelper;
import net.minecraft.util.profiling.ProfilerFiller;

/** Loads data/<namespace>/crises/*.json. */
public final class CrisisDefinitionLoader extends SimpleJsonResourceReloadListener {
    public static final String FOLDER = "crises";
    private static final Gson GSON = new Gson();
    private static volatile Map<String, CrisisDefinition> DEFINITIONS = Map.of();

    public CrisisDefinitionLoader() {
        super(GSON, FOLDER);
    }

    @Override
    protected void apply(Map<ResourceLocation, JsonElement> resources, ResourceManager manager, ProfilerFiller profiler) {
        Map<String, CrisisDefinition> map = new HashMap<>();
        for (Map.Entry<ResourceLocation, JsonElement> entry : resources.entrySet()) {
            String id = entry.getKey().toString();
            try {
                JsonObject obj = entry.getValue().getAsJsonObject();
                CrisisDefinition def = new CrisisDefinition(
                    id,
                    GsonHelper.getAsString(obj, "category", "Crisis"),
                    GsonHelper.getAsString(obj, "title", id),
                    GsonHelper.getAsString(obj, "headline", GsonHelper.getAsString(obj, "title", id)),
                    GsonHelper.getAsString(obj, "body", ""),
                    GsonHelper.getAsString(obj, "prompt", ""),
                    GsonHelper.getAsString(obj, "background", ""),
                    readArtLayers(obj),
                    GsonHelper.getAsString(obj, "chronicle_entry", ""),
                    GsonHelper.getAsString(obj, "start_sound",
                        GsonHelper.getAsString(obj, "startSound", "")),
                    GsonHelper.getAsInt(obj, "priority", 100),
                    readTrigger(obj),
                    readCompletionEffects(obj),
                    readChoices(obj, id)
                );
                if (def.choices().isEmpty()) {
                    BannerboundCore.LOGGER.warn("Crisis {} has no choices; skipping", id);
                    continue;
                }
                map.put(id, def);
            } catch (Exception ex) {
                BannerboundCore.LOGGER.error("Failed to parse crisis {}", entry.getKey(), ex);
            }
        }
        DEFINITIONS = Collections.unmodifiableMap(map);
        BannerboundCore.LOGGER.info("Loaded {} crisis definitions", map.size());
    }

    private static CrisisDefinition.Trigger readTrigger(JsonObject obj) {
        if (!obj.has("trigger")) return CrisisDefinition.Trigger.manual();
        JsonObject trigger = GsonHelper.getAsJsonObject(obj, "trigger");
        int durationTicks = GsonHelper.getAsInt(trigger, "duration_ticks",
            GsonHelper.getAsInt(trigger, "ticks",
                GsonHelper.getAsInt(trigger, "duration", 0)));
        if (durationTicks == 0 && trigger.has("seconds")) {
            durationTicks = Math.max(0, GsonHelper.getAsInt(trigger, "seconds", 0) * 20);
        }
        int delayTicks = GsonHelper.getAsInt(trigger, "delay_ticks",
            GsonHelper.getAsInt(trigger, "delayTicks", 0));
        if (delayTicks == 0) {
            delayTicks = Math.max(0, GsonHelper.getAsInt(trigger, "delay_seconds",
                GsonHelper.getAsInt(trigger, "delaySeconds", 0)) * 20);
        }
        boolean requiresGovernment = GsonHelper.getAsBoolean(trigger, "requires_government",
            GsonHelper.getAsBoolean(trigger, "requiresGovernment",
                GsonHelper.getAsBoolean(trigger, "requires_tribe", false)));
        return new CrisisDefinition.Trigger(
            GsonHelper.getAsString(trigger, "type", "manual"),
            GsonHelper.getAsString(trigger, "target", ""),
            GsonHelper.getAsString(trigger, "government", ""),
            GsonHelper.getAsString(trigger, "source", ""),
            GsonHelper.getAsString(trigger, "biome", ""),
            GsonHelper.getAsString(trigger, "comparison",
                GsonHelper.getAsString(trigger, "operator", "")),
            GsonHelper.getAsInt(trigger, "count", 1),
            GsonHelper.getAsDouble(trigger, "rate",
                GsonHelper.getAsDouble(trigger, "target_rate",
                    GsonHelper.getAsDouble(trigger, "threshold", 0.0))),
            durationTicks,
            delayTicks,
            requiresGovernment
        );
    }

    private static List<CrisisDefinition.ArtLayer> readArtLayers(JsonObject obj) {
        JsonArray arr = null;
        if (obj.has("background_layers")) arr = GsonHelper.getAsJsonArray(obj, "background_layers");
        else if (obj.has("art_layers")) arr = GsonHelper.getAsJsonArray(obj, "art_layers");
        if (arr == null) return List.of();
        List<CrisisDefinition.ArtLayer> out = new ArrayList<>();
        for (int i = 0; i < arr.size(); i++) {
            JsonElement element = arr.get(i);
            if (element == null) continue;
            if (element.isJsonPrimitive()) {
                String texture = element.getAsString();
                if (!texture.isBlank()) {
                    out.add(new CrisisDefinition.ArtLayer(texture, i == 0 ? 0.08f : 0.22f + i * 0.12f,
                        0.0f, 0.0f, 1.04f, 1.0f, i * 720, 1500));
                }
                continue;
            }
            JsonObject layer = element.getAsJsonObject();
            String texture = GsonHelper.getAsString(layer, "texture",
                GsonHelper.getAsString(layer, "image", ""));
            if (texture.isBlank()) continue;
            out.add(new CrisisDefinition.ArtLayer(
                texture,
                getFloat(layer, "parallax", i == 0 ? 0.08f : 0.22f + i * 0.12f),
                getFloat(layer, "drift_x", getFloat(layer, "driftX", 0.0f)),
                getFloat(layer, "drift_y", getFloat(layer, "driftY", 0.0f)),
                getFloat(layer, "scale", 1.04f + i * 0.015f),
                getFloat(layer, "opacity", 1.0f),
                GsonHelper.getAsInt(layer, "reveal_delay_ms",
                    GsonHelper.getAsInt(layer, "delay_ms", i * 720)),
                GsonHelper.getAsInt(layer, "reveal_duration_ms",
                    GsonHelper.getAsInt(layer, "duration_ms", 1500))
            ));
        }
        return out;
    }

    private static float getFloat(JsonObject obj, String key, float fallback) {
        return obj.has(key) ? obj.get(key).getAsFloat() : fallback;
    }

    private static CrisisDefinition.CompletionEffects readCompletionEffects(JsonObject obj) {
        JsonObject effects = null;
        if (obj.has("completion_effects")) {
            effects = GsonHelper.getAsJsonObject(obj, "completion_effects");
        } else if (obj.has("on_complete")) {
            effects = GsonHelper.getAsJsonObject(obj, "on_complete");
        }
        if (effects == null) return CrisisDefinition.CompletionEffects.none();
        int compliance = GsonHelper.getAsInt(effects, "compliance_delta",
            GsonHelper.getAsInt(effects, "compliance", 0));
        int resentment = GsonHelper.getAsInt(effects, "resentment_delta",
            GsonHelper.getAsInt(effects, "resentment", 0));
        return new CrisisDefinition.CompletionEffects(compliance, resentment);
    }

    private static List<CrisisChoice> readChoices(JsonObject obj, String crisisId) {
        List<CrisisChoice> out = new ArrayList<>();
        if (!obj.has("choices")) return out;
        JsonArray arr = GsonHelper.getAsJsonArray(obj, "choices");
        for (int i = 0; i < arr.size(); i++) {
            JsonObject choice = arr.get(i).getAsJsonObject();
            String id = GsonHelper.getAsString(choice, "id", "choice_" + i);
            out.add(new CrisisChoice(
                id,
                GsonHelper.getAsString(choice, "label", id),
                GsonHelper.getAsString(choice, "description", ""),
                GsonHelper.getAsString(choice, "outcome", ""),
                readViability(choice),
                readObjectives(choice, crisisId, id)
            ));
        }
        return out;
    }

    private static List<CrisisViabilityRequirement> readViability(JsonObject choice) {
        List<CrisisViabilityRequirement> out = new ArrayList<>();
        JsonArray arr = null;
        if (choice.has("viability")) arr = GsonHelper.getAsJsonArray(choice, "viability");
        else if (choice.has("requirements")) arr = GsonHelper.getAsJsonArray(choice, "requirements");
        if (arr == null) return out;
        for (JsonElement element : arr) {
            if (element == null) continue;
            if (element.isJsonPrimitive()) {
                out.add(new CrisisViabilityRequirement(element.getAsString(), ""));
            } else {
                JsonObject obj = element.getAsJsonObject();
                out.add(new CrisisViabilityRequirement(
                    GsonHelper.getAsString(obj, "type", ""),
                    GsonHelper.getAsString(obj, "warning",
                        GsonHelper.getAsString(obj, "message", ""))
                ));
            }
        }
        return out;
    }

    private static List<CrisisObjectiveDefinition> readObjectives(JsonObject choice, String crisisId, String choiceId) {
        List<CrisisObjectiveDefinition> out = new ArrayList<>();
        if (!choice.has("objectives")) return out;
        JsonArray arr = GsonHelper.getAsJsonArray(choice, "objectives");
        for (int i = 0; i < arr.size(); i++) {
            JsonObject obj = arr.get(i).getAsJsonObject();
            String id = GsonHelper.getAsString(obj, "id", crisisId + "." + choiceId + "." + i);
            out.add(new CrisisObjectiveDefinition(
                id,
                GsonHelper.getAsString(obj, "type", ""),
                GsonHelper.getAsString(obj, "label", id),
                GsonHelper.getAsString(obj, "source", ""),
                GsonHelper.getAsString(obj, "research", GsonHelper.getAsString(obj, "research_id", "")),
                GsonHelper.getAsString(obj, "job", GsonHelper.getAsString(obj, "job_type", "")),
                GsonHelper.getAsString(obj, "target", ""),
                GsonHelper.getAsDouble(obj, "target_rate", 0.0),
                GsonHelper.getAsInt(obj, "target_count", GsonHelper.getAsInt(obj, "count", 1)),
                readStringList(obj, "substeps", "sub_steps", "steps")
            ));
        }
        return out;
    }

    private static List<String> readStringList(JsonObject obj, String... keys) {
        for (String key : keys) {
            if (!obj.has(key)) continue;
            JsonArray arr = GsonHelper.getAsJsonArray(obj, key);
            List<String> out = new ArrayList<>(arr.size());
            for (JsonElement element : arr) {
                String value = element == null ? "" : element.getAsString();
                if (!value.isBlank()) out.add(value);
            }
            return out;
        }
        return List.of();
    }

    public static Map<String, CrisisDefinition> getAll() {
        return DEFINITIONS;
    }

    public static CrisisDefinition get(String id) {
        return DEFINITIONS.get(id);
    }
}
