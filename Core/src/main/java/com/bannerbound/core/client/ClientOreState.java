package com.bannerbound.core.client;

import org.jetbrains.annotations.ApiStatus;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.bannerbound.core.api.research.OreDisguise;

import net.minecraft.client.Minecraft;
import net.minecraft.client.resources.model.BakedModel;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * Client-side mirror of the server's ore disguise table. Tracks which ore IDs are currently
 * "disguised" for the local player (i.e. their settlement lacks the reveal flag) and caches
 * the disguise BakedModel so the chunk mesher's per-block lookup stays fast.
 */
@OnlyIn(Dist.CLIENT)
@ApiStatus.Internal
public final class ClientOreState {
    private static volatile Map<String, OreDisguise> DISGUISES = Collections.emptyMap();
    private static volatile Set<String> ACTIVE_DISGUISED_ORES = Collections.emptySet();
    private static final Map<String, BakedModel> MODEL_CACHE = new HashMap<>();

    private ClientOreState() {
    }

    public static void replaceDisguises(List<OreDisguise> list) {
        Map<String, OreDisguise> map = new HashMap<>();
        for (OreDisguise d : list) {
            map.put(d.oreId(), d);
        }
        DISGUISES = map;
        MODEL_CACHE.clear();
        recomputeActiveDisguises();
        invalidateNearbySections();
    }

    /**
     * Recompute the set of ores that are currently disguised for the local player, based on
     * the player's settlement's active research flags. Called whenever the disguise list or
     * the player's research state changes.
     */
    public static void recomputeActiveDisguises() {
        Set<String> active = new HashSet<>();
        for (Map.Entry<String, OreDisguise> e : DISGUISES.entrySet()) {
            if (!ClientResearchState.hasFlag(e.getValue().flag())) {
                active.add(e.getKey());
            }
        }
        ACTIVE_DISGUISED_ORES = active;
    }

    public static boolean isCurrentlyDisguised(Block block) {
        if (ACTIVE_DISGUISED_ORES.isEmpty()) return false;
        ResourceLocation id = BuiltInRegistries.BLOCK.getKey(block);
        return id != null && ACTIVE_DISGUISED_ORES.contains(id.toString());
    }

    public static OreDisguise getDisguiseFor(Block block) {
        ResourceLocation id = BuiltInRegistries.BLOCK.getKey(block);
        return id == null ? null : DISGUISES.get(id.toString());
    }

    public static BakedModel getCachedDisguiseModel(OreDisguise disguise) {
        BakedModel cached = MODEL_CACHE.get(disguise.oreId());
        if (cached != null) return cached;
        Minecraft mc = Minecraft.getInstance();
        if (mc == null || mc.getModelManager() == null) return null;
        ResourceLocation disguiseLoc;
        try {
            disguiseLoc = ResourceLocation.parse(disguise.disguiseId());
        } catch (Exception ex) {
            return null;
        }
        Block disguiseBlock = BuiltInRegistries.BLOCK.get(disguiseLoc);
        if (disguiseBlock == null || disguiseBlock == Blocks.AIR) return null;
        BakedModel model = mc.getModelManager().getBlockModelShaper().getBlockModel(disguiseBlock.defaultBlockState());
        MODEL_CACHE.put(disguise.oreId(), model);
        return model;
    }

    public static void invalidateModelCache() {
        MODEL_CACHE.clear();
    }

    /**
     * Marks every section within render distance dirty so the chunk mesher re-bakes them next
     * frame — used after disguise list or research state changes to refresh ore visuals
     * without the heavyweight {@code LevelRenderer.allChanged()} (which drops GPU resources).
     */
    public static void invalidateNearbySections() {
        try {
            Minecraft mc = Minecraft.getInstance();
            if (mc == null || mc.level == null || mc.player == null || mc.levelRenderer == null) {
                return;
            }
            int renderDist = mc.options.renderDistance().get();
            int centerCX = mc.player.blockPosition().getX() >> 4;
            int centerCZ = mc.player.blockPosition().getZ() >> 4;
            int minSecY = mc.level.getMinSection();
            int maxSecY = mc.level.getMaxSection();
            for (int dx = -renderDist; dx <= renderDist; dx++) {
                for (int dz = -renderDist; dz <= renderDist; dz++) {
                    int cx = centerCX + dx;
                    int cz = centerCZ + dz;
                    for (int sy = minSecY; sy < maxSecY; sy++) {
                        mc.levelRenderer.setSectionDirty(cx, sy, cz);
                    }
                }
            }
        } catch (Exception ignored) {
        }
    }
}
