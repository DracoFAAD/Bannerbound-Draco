package com.bannerbound.core.codex;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.bannerbound.core.BannerboundCore;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.GsonHelper;
import net.minecraft.util.profiling.ProfilerFiller;

/**
 * Datapack reload listener that parses data/<namespace>/codex_entries JSON into immutable
 * CodexEntry records. Every read goes through GsonHelper defaults so a partial file still
 * loads; entries default secret=true (hidden until unlocked). Tutorial objectives accept
 * both new and legacy JSON keys (progress/progress_text, complete/complete_text,
 * substeps/sub_steps/steps) so older authored content keeps working. The parsed map is held
 * in a static volatile field replaced wholesale on each reload and exposed read-only via
 * getAll/get/sorted (sorted by category, then order, then title).
 */
public final class CodexEntryLoader extends SimpleJsonResourceReloadListener {
    public static final String FOLDER = "codex_entries";
    private static final Gson GSON = new Gson();
    private static volatile Map<String, CodexEntry> ENTRIES = Map.of();

    public CodexEntryLoader() {
        super(GSON, FOLDER);
    }

    @Override
    protected void apply(Map<ResourceLocation, JsonElement> resources, ResourceManager manager, ProfilerFiller profiler) {
        Map<String, CodexEntry> out = new HashMap<>();
        for (Map.Entry<ResourceLocation, JsonElement> resource : resources.entrySet()) {
            String id = resource.getKey().toString();
            try {
                JsonObject obj = resource.getValue().getAsJsonObject();
                out.put(id, new CodexEntry(
                    id,
                    GsonHelper.getAsString(obj, "category", "bannerbound:getting_started"),
                    GsonHelper.getAsString(obj, "title", id),
                    GsonHelper.getAsString(obj, "subtitle", ""),
                    GsonHelper.getAsString(obj, "icon", ""),
                    GsonHelper.getAsInt(obj, "order", 0),
                    GsonHelper.getAsBoolean(obj, "secret", true),
                    readUnlock(obj),
                    GsonHelper.getAsString(obj, "ponder", ""),
                    readTutorial(obj),
                    readPages(obj)
                ));
            } catch (Exception ex) {
                BannerboundCore.LOGGER.error("Failed to parse Chronicle entry {}", resource.getKey(), ex);
            }
        }
        ENTRIES = Collections.unmodifiableMap(out);
        BannerboundCore.LOGGER.info("Loaded {} Chronicle entries", out.size());
    }

    private static CodexUnlockRule readUnlock(JsonObject obj) {
        return readRule(obj, "unlock");
    }

    static CodexUnlockRule readRule(JsonObject obj, String key) {
        if (!obj.has(key) || obj.get(key).isJsonNull()) {
            return CodexUnlockRule.unlockedByDefault();
        }
        JsonObject unlock = GsonHelper.getAsJsonObject(obj, key);
        if (unlock.has("any")) {
            return new CodexUnlockRule(CodexUnlockRule.Mode.ANY,
                readConditions(GsonHelper.getAsJsonArray(unlock, "any")));
        }
        if (unlock.has("all")) {
            return new CodexUnlockRule(CodexUnlockRule.Mode.ALL,
                readConditions(GsonHelper.getAsJsonArray(unlock, "all")));
        }
        return new CodexUnlockRule(CodexUnlockRule.Mode.ALL, List.of(readCondition(unlock)));
    }

    private static List<CodexCondition> readConditions(JsonArray array) {
        List<CodexCondition> out = new ArrayList<>();
        for (JsonElement element : array) {
            out.add(readCondition(element.getAsJsonObject()));
        }
        return out;
    }

    private static CodexCondition readCondition(JsonObject obj) {
        return new CodexCondition(
            GsonHelper.getAsString(obj, "type", ""),
            GsonHelper.getAsString(obj, "id", ""),
            GsonHelper.getAsString(obj, "item", ""),
            GsonHelper.getAsString(obj, "block", ""),
            GsonHelper.getAsString(obj, "era", ""),
            GsonHelper.getAsString(obj, "flag", ""),
            GsonHelper.getAsString(obj, "advancement", ""),
            GsonHelper.getAsString(obj, "job", "")
        );
    }

    private static CodexTutorial readTutorial(JsonObject obj) {
        if (!obj.has("tutorial") || obj.get("tutorial").isJsonNull()) {
            return new CodexTutorial("", "", 10, List.of());
        }
        JsonObject tutorial = GsonHelper.getAsJsonObject(obj, "tutorial");
        List<CodexTutorial.Objective> objectives = new ArrayList<>();
        if (tutorial.has("objectives")) {
            JsonArray arr = GsonHelper.getAsJsonArray(tutorial, "objectives");
            for (int i = 0; i < arr.size(); i++) {
                JsonObject objective = arr.get(i).getAsJsonObject();
                JsonObject trigger = objective.has("trigger")
                    ? GsonHelper.getAsJsonObject(objective, "trigger")
                    : objective;
                String id = GsonHelper.getAsString(objective, "id", "step_" + i);
                objectives.add(new CodexTutorial.Objective(
                    id,
                    GsonHelper.getAsString(objective, "label", id),
                    GsonHelper.getAsString(objective, "progress",
                        GsonHelper.getAsString(objective, "progress_text", "")),
                    GsonHelper.getAsString(objective, "complete",
                        GsonHelper.getAsString(objective, "complete_text", "Completed")),
                    readCondition(trigger),
                    readStringArray(objective, "substeps", "sub_steps", "steps")
                ));
            }
        }
        return new CodexTutorial(
            GsonHelper.getAsString(tutorial, "title", ""),
            GsonHelper.getAsString(tutorial, "subtitle", ""),
            GsonHelper.getAsInt(tutorial, "priority", 10),
            objectives
        );
    }

    private static List<CodexPageElement> readPages(JsonObject obj) {
        if (!obj.has("pages")) return List.of();
        JsonArray array = GsonHelper.getAsJsonArray(obj, "pages");
        List<CodexPageElement> out = new ArrayList<>();
        for (JsonElement element : array) {
            JsonObject page = element.getAsJsonObject();
            out.add(new CodexPageElement(
                GsonHelper.getAsString(page, "type", "text"),
                GsonHelper.getAsString(page, "text", ""),
                GsonHelper.getAsString(page, "caption", ""),
                GsonHelper.getAsString(page, "entry", ""),
                GsonHelper.getAsString(page, "clip", ""),
                GsonHelper.getAsString(page, "image", ""),
                GsonHelper.getAsString(page, "recipe", ""),
                readStringArray(page, "items")
            ));
        }
        return out;
    }

    private static List<String> readStringArray(JsonObject obj, String... keys) {
        for (String key : keys) {
            if (!obj.has(key)) continue;
            JsonElement element = obj.get(key);
            if (element.isJsonPrimitive()) return List.of(element.getAsString());
            JsonArray array = GsonHelper.getAsJsonArray(obj, key);
            List<String> out = new ArrayList<>();
            for (JsonElement value : array) out.add(value.getAsString());
            return out;
        }
        return List.of();
    }

    public static Map<String, CodexEntry> getAll() {
        return ENTRIES;
    }

    public static CodexEntry get(String id) {
        return ENTRIES.get(id);
    }

    public static List<CodexEntry> sorted() {
        return ENTRIES.values().stream()
            .sorted(Comparator.comparing(CodexEntry::category)
                .thenComparingInt(CodexEntry::order)
                .thenComparing(CodexEntry::title))
            .toList();
    }
}
