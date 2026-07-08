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
 * Datapack reload listener parsing data/<namespace>/tutorials JSON into immutable TutorialPopup
 * records. The trigger block reuses CodexEntryLoader.readRule, so tutorial triggers accept the
 * exact same condition schema as Chronicle entry unlocks; an absent trigger yields a rule that
 * starts unlocked (fires on the login reconcile pass). The parsed map is replaced wholesale on
 * each reload and exposed read-only via get/sorted (sorted by order, then id).
 */
public final class TutorialPopupLoader extends SimpleJsonResourceReloadListener {
    public static final String FOLDER = "tutorials";
    private static final Gson GSON = new Gson();
    private static volatile Map<String, TutorialPopup> POPUPS = Map.of();

    public TutorialPopupLoader() {
        super(GSON, FOLDER);
    }

    @Override
    protected void apply(Map<ResourceLocation, JsonElement> resources, ResourceManager manager, ProfilerFiller profiler) {
        Map<String, TutorialPopup> out = new HashMap<>();
        for (Map.Entry<ResourceLocation, JsonElement> resource : resources.entrySet()) {
            String id = resource.getKey().toString();
            try {
                JsonObject obj = resource.getValue().getAsJsonObject();
                out.put(id, new TutorialPopup(
                    id,
                    GsonHelper.getAsString(obj, "priority", "interrupt"),
                    GsonHelper.getAsBoolean(obj, "once", true),
                    GsonHelper.getAsString(obj, "entry", ""),
                    GsonHelper.getAsInt(obj, "order", 0),
                    CodexEntryLoader.readRule(obj, "trigger"),
                    readPages(obj)
                ));
            } catch (Exception ex) {
                BannerboundCore.LOGGER.error("Failed to parse tutorial popup {}", resource.getKey(), ex);
            }
        }
        POPUPS = Collections.unmodifiableMap(out);
        BannerboundCore.LOGGER.info("Loaded {} tutorial popups", out.size());
    }

    private static List<TutorialPopup.Page> readPages(JsonObject obj) {
        if (!obj.has("pages")) return List.of();
        JsonArray array = GsonHelper.getAsJsonArray(obj, "pages");
        List<TutorialPopup.Page> out = new ArrayList<>();
        for (JsonElement element : array) {
            JsonObject page = element.getAsJsonObject();
            out.add(new TutorialPopup.Page(
                GsonHelper.getAsString(page, "title", ""),
                GsonHelper.getAsString(page, "text", ""),
                GsonHelper.getAsString(page, "clip", ""),
                GsonHelper.getAsString(page, "image", "")
            ));
        }
        return out;
    }

    public static TutorialPopup get(String id) {
        return POPUPS.get(id);
    }

    public static Map<String, TutorialPopup> getAll() {
        return POPUPS;
    }

    public static List<TutorialPopup> sorted() {
        return POPUPS.values().stream()
            .sorted(Comparator.comparingInt(TutorialPopup::order).thenComparing(TutorialPopup::id))
            .toList();
    }
}
