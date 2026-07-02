package com.bannerbound.core.client;

import org.jetbrains.annotations.ApiStatus;

import it.unimi.dsi.fastutil.longs.Long2ObjectMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Client mirror of this player's settlement wall blueprint (pos → expected state), fed by
 * {@code WallBlueprintSyncPayload} and read every frame by {@link WallGhostRenderer}. Replaced
 * wholesale on each sync; an empty sync clears it (plan cancelled / left settlement).
 */
@ApiStatus.Internal
public final class ClientWallBlueprint {

    private static volatile Long2ObjectMap<BlockState> blueprint = new Long2ObjectOpenHashMap<>();

    private ClientWallBlueprint() {
    }

    public static void set(long[] positions, int[] stateIds) {
        Long2ObjectMap<BlockState> map = new Long2ObjectOpenHashMap<>(positions.length);
        for (int i = 0; i < positions.length; i++) {
            BlockState state = Block.stateById(stateIds[i]);
            if (!state.isAir()) {
                map.put(positions[i], state);
            }
        }
        blueprint = map;
    }

    public static void clear() {
        blueprint = new Long2ObjectOpenHashMap<>();
    }

    public static Long2ObjectMap<BlockState> view() {
        return blueprint;
    }

    public static boolean isEmpty() {
        return blueprint.isEmpty();
    }
}
