package com.bannerbound.core.client;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.jetbrains.annotations.ApiStatus;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.level.block.Block;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * Client-side mirror of the resolved block appeal for the local player's settlement, synced
 * from the server. Read by the appeal item tooltip and the beauty-debug overlay.
 */
@OnlyIn(Dist.CLIENT)
@ApiStatus.Internal
public final class ClientBlockAppealState {
    private static volatile Map<Block, Float> APPEAL = Map.of();

    private ClientBlockAppealState() {
    }

    public static void replace(List<String> ids, List<Float> appeals) {
        Map<Block, Float> map = new HashMap<>();
        for (int i = 0; i < ids.size() && i < appeals.size(); i++) {
            ResourceLocation rl = ResourceLocation.tryParse(ids.get(i));
            if (rl != null && BuiltInRegistries.BLOCK.containsKey(rl)) {
                map.put(BuiltInRegistries.BLOCK.get(rl), appeals.get(i));
            }
        }
        APPEAL = Map.copyOf(map);
    }

    /** Resolved appeal of {@code block} for the local settlement; {@code 0} when undefined. */
    public static float appealOf(Block block) {
        return APPEAL.getOrDefault(block, 0f);
    }
}
