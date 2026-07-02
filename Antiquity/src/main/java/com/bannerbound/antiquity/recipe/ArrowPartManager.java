package com.bannerbound.antiquity.recipe;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.antiquity.BannerboundAntiquity;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.mojang.serialization.JsonOps;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.profiling.ProfilerFiller;

/**
 * Datapack loader for modular {@link ArrowPart}s — reads every JSON under
 * {@code data/<namespace>/arrow_parts/}. Registered as a reload listener in {@code AntiquityEvents};
 * the parsed set lands in {@link ArrowPartRegistry} and is then synced to clients
 * ({@link com.bannerbound.antiquity.network.ArrowPartsSyncPayload}) for rendering. {@code applyEntries}
 * is public so the client jar fallback ({@code ClientDatapackRecipes}) can reuse it.
 */
@ApiStatus.Internal
public class ArrowPartManager extends SimpleJsonResourceReloadListener {
    private static final Gson GSON = new Gson();

    public ArrowPartManager() {
        super(GSON, "arrow_parts");
    }

    @Override
    protected void apply(Map<ResourceLocation, JsonElement> entries, ResourceManager resourceManager,
                         ProfilerFiller profiler) {
        applyEntries(entries);
    }

    public static void applyEntries(Map<ResourceLocation, JsonElement> entries) {
        List<ArrowPart> loaded = new ArrayList<>();
        for (Map.Entry<ResourceLocation, JsonElement> entry : entries.entrySet()) {
            ArrowPart.CODEC.parse(JsonOps.INSTANCE, entry.getValue())
                .resultOrPartial(error -> BannerboundAntiquity.LOGGER.error(
                    "Skipping invalid arrow part {}: {}", entry.getKey(), error))
                .ifPresent(loaded::add);
        }
        ArrowPartRegistry.replace(loaded);
        BannerboundAntiquity.LOGGER.info("Loaded {} arrow part(s).", loaded.size());
    }
}
