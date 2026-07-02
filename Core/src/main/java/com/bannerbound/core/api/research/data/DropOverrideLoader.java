package com.bannerbound.core.api.research.data;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.jetbrains.annotations.Nullable;

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
 * Loads the global drop-override list from {@code data/<namespace>/drop_overrides/*.json}. These
 * are the explicit exceptions to the automatic "don't drop unknown items" filter — the hybrid
 * half of the gating system. Two modes:
 * <ul>
 *   <li>{@code always_drop}: this item drops even when the civ doesn't know it yet (force-allow).
 *       Used for bootstrap drops like bones, which must reach a fresh settlement before any
 *       research exists.</li>
 *   <li>{@code never_drop}: this item never drops, even when the civ does know it (force-block).
 *       Optionally scoped to a {@code sources} list of block- or entity-type ids; absent =
 *       applies to every source.</li>
 * </ul>
 * Example file:
 * <pre>
 * { "overrides": [
 *     { "item": "minecraft:bone", "mode": "always_drop" },
 *     { "item": "minecraft:wheat_seeds", "mode": "never_drop", "sources": ["minecraft:short_grass"] }
 * ] }
 * </pre>
 * Datapacks may drop additional files into the folder; all entries are merged. An {@code item}
 * with conflicting entries resolves {@code never_drop} (a global block) over {@code always_drop},
 * since an explicit block is the more restrictive intent.
 */
public class DropOverrideLoader extends SimpleJsonResourceReloadListener {
    public static final String FOLDER = "drop_overrides";
    private static final Gson GSON = new Gson();

    public enum Decision {
        /** No override; fall back to the automatic known-set check. */
        DEFAULT,
        /** Drop regardless of whether the item is known. */
        ALWAYS_DROP,
        /** Never drop, regardless of whether the item is known. */
        NEVER_DROP
    }

    /** item id -> always-drop. */
    private static volatile Set<String> ALWAYS_DROP = Set.of();
    /** item id -> set of source ids it's blocked from; an empty set means "blocked everywhere". */
    private static volatile Map<String, Set<String>> NEVER_DROP = Map.of();

    public DropOverrideLoader() {
        super(GSON, FOLDER);
    }

    @Override
    protected void apply(Map<ResourceLocation, JsonElement> resources, ResourceManager manager, ProfilerFiller profiler) {
        Set<String> always = new HashSet<>();
        Map<String, Set<String>> never = new HashMap<>();
        for (Map.Entry<ResourceLocation, JsonElement> entry : resources.entrySet()) {
            try {
                JsonObject obj = entry.getValue().getAsJsonObject();
                JsonArray arr = GsonHelper.getAsJsonArray(obj, "overrides");
                for (JsonElement el : arr) {
                    JsonObject o = el.getAsJsonObject();
                    String item = GsonHelper.getAsString(o, "item");
                    String mode = GsonHelper.getAsString(o, "mode");
                    if ("always_drop".equals(mode)) {
                        always.add(item);
                    } else if ("never_drop".equals(mode)) {
                        Set<String> sources = never.computeIfAbsent(item, k -> new HashSet<>());
                        if (o.has("sources")) {
                            for (JsonElement s : GsonHelper.getAsJsonArray(o, "sources")) {
                                sources.add(s.getAsString());
                            }
                        }
                        // An entry with no "sources" means "block everywhere"; we record that as a
                        // sentinel so a later scoped entry can't accidentally narrow it.
                        else {
                            sources.add(BLOCK_EVERYWHERE);
                        }
                    } else {
                        BannerboundCore.LOGGER.warn("Unknown drop-override mode '{}' in {}", mode, entry.getKey());
                    }
                }
            } catch (Exception ex) {
                BannerboundCore.LOGGER.error("Failed to parse drop_overrides {}", entry.getKey(), ex);
            }
        }
        ALWAYS_DROP = always;
        NEVER_DROP = never;
        BannerboundCore.LOGGER.info("Loaded drop overrides: {} always-drop, {} never-drop",
            always.size(), never.size());
    }

    private static final String BLOCK_EVERYWHERE = "*";

    /**
     * Resolves the override decision for {@code itemId} dropping from {@code sourceId} (the broken
     * block's or killed entity's id; may be null when the source is unknown, e.g. a felling-tree
     * drop). {@code never_drop} wins over {@code always_drop} when both name the same item.
     */
    public static Decision decide(String itemId, @Nullable String sourceId) {
        Set<String> blockedSources = NEVER_DROP.get(itemId);
        if (blockedSources != null) {
            if (blockedSources.contains(BLOCK_EVERYWHERE)) {
                return Decision.NEVER_DROP;
            }
            if (sourceId != null && blockedSources.contains(sourceId)) {
                return Decision.NEVER_DROP;
            }
        }
        if (ALWAYS_DROP.contains(itemId)) {
            return Decision.ALWAYS_DROP;
        }
        return Decision.DEFAULT;
    }
}
