package com.bannerbound.core.codex;

import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.bannerbound.core.BannerboundCore;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.GsonHelper;
import net.minecraft.util.profiling.ProfilerFiller;

/** Loads Chronicle categories from data/<namespace>/codex_categories. */
public final class CodexCategoryLoader extends SimpleJsonResourceReloadListener {
    public static final String FOLDER = "codex_categories";
    private static final Gson GSON = new Gson();
    private static volatile Map<String, CodexCategory> CATEGORIES = Map.of();

    public CodexCategoryLoader() {
        super(GSON, FOLDER);
    }

    @Override
    protected void apply(Map<ResourceLocation, JsonElement> resources, ResourceManager manager, ProfilerFiller profiler) {
        Map<String, CodexCategory> out = new HashMap<>();
        for (Map.Entry<ResourceLocation, JsonElement> resource : resources.entrySet()) {
            String id = resource.getKey().toString();
            try {
                JsonObject obj = resource.getValue().getAsJsonObject();
                out.put(id, new CodexCategory(
                    id,
                    GsonHelper.getAsString(obj, "title", id),
                    GsonHelper.getAsString(obj, "icon", ""),
                    GsonHelper.getAsInt(obj, "order", 0)
                ));
            } catch (Exception ex) {
                BannerboundCore.LOGGER.error("Failed to parse Chronicle category {}", resource.getKey(), ex);
            }
        }
        CATEGORIES = Collections.unmodifiableMap(out);
        BannerboundCore.LOGGER.info("Loaded {} Chronicle categories", out.size());
    }

    public static Map<String, CodexCategory> getAll() {
        return CATEGORIES;
    }

    public static CodexCategory get(String id) {
        return CATEGORIES.get(id);
    }

    public static List<CodexCategory> sorted() {
        return CATEGORIES.values().stream()
            .sorted(Comparator.comparingInt(CodexCategory::order).thenComparing(CodexCategory::title))
            .toList();
    }
}
