package com.bannerbound.antiquity.client;

import java.io.BufferedReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Stream;

import com.bannerbound.antiquity.BannerboundAntiquity;
import com.bannerbound.antiquity.carpentry.CarpentryAssemblyManager;
import com.bannerbound.antiquity.carpentry.CarpentryOutputManager;
import com.bannerbound.antiquity.masonry.MasonryOutputManager;
import com.bannerbound.antiquity.recipe.BloomeryRecipeManager;
import com.bannerbound.antiquity.recipe.CraftingStoneRecipeManager;
import com.bannerbound.antiquity.recipe.FletchingRecipeManager;
import com.bannerbound.antiquity.recipe.KilnRecipeManager;
import com.bannerbound.antiquity.recipe.KnappingShapeManager;
import com.bannerbound.antiquity.recipe.MortarRecipeManager;
import com.bannerbound.antiquity.recipe.PotteryRecipeManager;
import com.google.gson.Gson;
import com.google.gson.JsonElement;

import net.minecraft.client.Minecraft;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.ResourceManagerReloadListener;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.fml.ModList;

/**
 * Loads the mod's custom datapack recipes ({@code data/<modid>/crafting_stone_recipes/}, …) on the
 * CLIENT, reading the JSON straight from this mod's own file. Those recipe managers are server-data
 * reload listeners, so their static lists are populated only where a server runs: in singleplayer /
 * on the host the integrated server shares the JVM, but on a <b>remote client</b> nothing ever fills
 * them — which is why JEI showed no custom recipes and the workstation ghost previews stayed blank
 * for everyone but the host. The recipes ship in the jar, identical to the server's, so reading them
 * here client-side gives JEI and the renderers the same data.
 *
 * <p>Registered as a client reload listener so it runs at startup (before JEI registers its recipes)
 * and on F3+T. It NO-OPs whenever an integrated server is present — there the server's own reload
 * listener is authoritative (and may carry datapack overrides this jar-only read wouldn't), and the
 * two share the static lists, so we must not clobber it.
 */
@OnlyIn(Dist.CLIENT)
public final class ClientDatapackRecipes implements ResourceManagerReloadListener {
    private static final Gson GSON = new Gson();

    @Override
    public void onResourceManagerReload(ResourceManager resourceManager) {
        // An integrated server (singleplayer / LAN host) populates these same statics from the real
        // datapack stack in this JVM — leave its data alone. Only a true remote client needs us.
        if (Minecraft.getInstance().getSingleplayerServer() != null) {
            return;
        }
        loadAll();
    }

    private static void loadAll() {
        load("crafting_stone_recipes", CraftingStoneRecipeManager::applyEntries);
        load("drying_recipes", com.bannerbound.antiquity.recipe.DryingRackRecipeManager::applyEntries);
        load("grog_recipes", com.bannerbound.antiquity.recipe.GrogRecipeManager::applyEntries);
        load("stew_recipes", com.bannerbound.antiquity.recipe.StewRecipeManager::applyEntries);
        load("fletching_recipes", FletchingRecipeManager::applyEntries);
        // NOTE: arrow_parts is intentionally NOT loaded here — it's pushed to clients by
        // ArrowPartsSyncPayload on join/reload (covers singleplayer + remote + modpack-added parts),
        // and a client F3+T must not clobber that synced set with only the jar's bundled parts.
        load("anvil_recipes", com.bannerbound.antiquity.recipe.AnvilRecipeManager::applyEntries);
        load("metalworking", com.bannerbound.antiquity.metalworking.MetalworkingData::applyEntries);
        load("pottery_recipes", PotteryRecipeManager::applyEntries);
        load("bloomery_recipes", BloomeryRecipeManager::applyEntries);
        load("kiln_recipes", KilnRecipeManager::applyEntries);
        load("mortar_recipes", MortarRecipeManager::applyEntries);
        load("knapping_shapes", KnappingShapeManager::applyEntries);
        load("carpentry_outputs", CarpentryOutputManager::applyEntries);
        load("carpentry_assembly", CarpentryAssemblyManager::applyEntries);
        load("masonry_outputs", MasonryOutputManager::applyEntries);
    }

    /** Read every {@code .json} under {@code data/<modid>/<folder>} in this mod's file and hand the
     *  parsed entries to {@code sink} (the manager's {@code applyEntries}). Fully defensive — any
     *  failure logs and leaves that manager empty rather than throwing through the reload. */
    private static void load(String folder, Consumer<Map<ResourceLocation, JsonElement>> sink) {
        Map<ResourceLocation, JsonElement> entries = new HashMap<>();
        try {
            var info = ModList.get().getModFileById(BannerboundAntiquity.MODID);
            if (info == null) {
                sink.accept(entries);
                return;
            }
            Path root = info.getFile().findResource("data", BannerboundAntiquity.MODID, folder);
            if (root == null || !Files.isDirectory(root)) {
                sink.accept(entries);
                return;
            }
            try (Stream<Path> walk = Files.walk(root)) {
                walk.filter(p -> p.toString().endsWith(".json")).forEach(p -> {
                    try (BufferedReader reader = Files.newBufferedReader(p)) {
                        JsonElement json = GSON.fromJson(reader, JsonElement.class);
                        if (json == null) return;
                        String name = root.relativize(p).toString().replace('\\', '/');
                        name = name.substring(0, name.length() - ".json".length());
                        entries.put(
                            ResourceLocation.fromNamespaceAndPath(BannerboundAntiquity.MODID, name), json);
                    } catch (Exception ex) {
                        BannerboundAntiquity.LOGGER.error(
                            "Client recipe read failed for {}: {}", p, ex.toString());
                    }
                });
            }
        } catch (Exception ex) {
            BannerboundAntiquity.LOGGER.error(
                "Client datapack load failed for folder {}: {}", folder, ex.toString());
        }
        sink.accept(entries);
    }

    public ClientDatapackRecipes() {}
}
