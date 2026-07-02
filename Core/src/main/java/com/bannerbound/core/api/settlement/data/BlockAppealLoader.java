package com.bannerbound.core.api.settlement.data;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import com.bannerbound.core.BannerboundCore;
import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.server.packs.resources.SimpleJsonResourceReloadListener;
import net.minecraft.util.GsonHelper;
import net.minecraft.util.profiling.ProfilerFiller;
import net.minecraft.world.level.block.Block;

/**
 * Loads the <b>base</b> block-appeal table from {@code data/<ns>/block_appeal/*.json}. Every
 * file's {@code "blocks"} object is unioned into one map (later files override earlier ones for
 * the same block id), so expansion datapacks can extend the table.
 *
 * <pre>{@code
 * { "blocks": { "minecraft:oak_planks": 0.15, "minecraft:mud": -0.30, ... } }
 * }</pre>
 *
 * Values are clamped to {@code [-1, 1]}. Block ids are resolved to {@link Block} instances at
 * load time so the per-tick chunk scan never touches the registry. A block with no entry has a
 * base appeal of {@code 0} (bland). A culture style <i>overrides</i> these per-block — see
 * {@link com.bannerbound.core.api.settlement.AppealResolver}.
 */
public class BlockAppealLoader extends SimpleJsonResourceReloadListener {
    public static final String FOLDER = "block_appeal";
    private static final Gson GSON = new Gson();
    private static volatile Map<Block, Float> BASE = Collections.emptyMap();

    public BlockAppealLoader() {
        super(GSON, FOLDER);
    }

    @Override
    protected void apply(Map<ResourceLocation, JsonElement> resources, ResourceManager manager,
                         ProfilerFiller profiler) {
        Map<Block, Float> map = new HashMap<>();
        for (Map.Entry<ResourceLocation, JsonElement> entry : resources.entrySet()) {
            ResourceLocation key = entry.getKey();
            try {
                JsonObject obj = entry.getValue().getAsJsonObject();
                JsonObject blocks = GsonHelper.getAsJsonObject(obj, "blocks");
                for (Map.Entry<String, JsonElement> b : blocks.entrySet()) {
                    ResourceLocation blockRl = ResourceLocation.tryParse(b.getKey());
                    if (blockRl == null || !BuiltInRegistries.BLOCK.containsKey(blockRl)) {
                        BannerboundCore.LOGGER.warn("Unknown block '{}' in block_appeal {}",
                            b.getKey(), key);
                        continue;
                    }
                    float appeal = Math.max(-1f, Math.min(1f, b.getValue().getAsFloat()));
                    map.put(BuiltInRegistries.BLOCK.get(blockRl), appeal);
                }
            } catch (Exception ex) {
                BannerboundCore.LOGGER.error("Failed to parse block_appeal {}", key, ex);
            }
        }
        BASE = map;
        BannerboundCore.LOGGER.info("Loaded {} block appeal definitions", map.size());
    }

    /** Base appeal of {@code block}; {@code 0} when undefined. */
    public static float base(Block block) {
        return BASE.getOrDefault(block, 0f);
    }

    public static Map<Block, Float> all() {
        return BASE;
    }
}
