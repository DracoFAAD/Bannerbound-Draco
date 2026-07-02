package com.bannerbound.core.client;

import com.bannerbound.core.api.world.BlockSelectionRegistry;

import org.jetbrains.annotations.ApiStatus;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.bannerbound.core.api.world.BlockSelection;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * Client-side mirror of the server's {@code BlockSelectionRegistry}. Repopulated wholesale on
 * each {@code SelectionSyncPayload}. The {@link SelectionRenderer} reads from here to know what
 * to draw while the local player is holding a Foreman's Rod.
 * <p>
 * {@link #version()} bumps on every replacement so the renderer's per-selection vertex-buffer
 * cache can detect changes and invalidate without diffing the maps itself.
 */
@OnlyIn(Dist.CLIENT)
@ApiStatus.Internal
public final class ClientSelectionState {
    private static final Map<UUID, BlockSelection> SELECTIONS = new HashMap<>();
    private static long version = 0L;

    private ClientSelectionState() {
    }

    public static void replace(List<BlockSelection> incoming) {
        SELECTIONS.clear();
        for (BlockSelection s : incoming) {
            SELECTIONS.put(s.rodId(), s);
        }
        version++;
    }

    public static Collection<BlockSelection> getAll() {
        return Collections.unmodifiableCollection(SELECTIONS.values());
    }

    public static long version() {
        return version;
    }
}
