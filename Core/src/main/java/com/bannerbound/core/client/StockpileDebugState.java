package com.bannerbound.core.client;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * Client-side TTL holder for the Stockpile enclosure debug wireframe — mirrors {@link DetectPreviewState}.
 * Holds the scan's detected interior tiles, the connected container blocks, and the failure position
 * so {@link SelectionRenderer} can draw them (green / blue / red) while the flash is active. A debug
 * aid until the storage terminal exists.
 */
@OnlyIn(Dist.CLIENT)
@ApiStatus.Internal
public final class StockpileDebugState {
    private static final Set<BlockPos> interior = new HashSet<>();
    private static final Set<BlockPos> containers = new HashSet<>();
    @Nullable
    private static BlockPos failPos;
    private static long expiryGameTime;

    private StockpileDebugState() {
    }

    /** Arm the flash for {@code durationTicks} from the current client game-time. */
    public static void show(List<BlockPos> newInterior, List<BlockPos> newContainers,
                            Optional<BlockPos> newFailPos, int durationTicks) {
        Minecraft mc = Minecraft.getInstance();
        long now = mc.level != null ? mc.level.getGameTime() : 0L;
        interior.clear();
        for (BlockPos p : newInterior) interior.add(p.immutable());
        containers.clear();
        for (BlockPos p : newContainers) containers.add(p.immutable());
        failPos = newFailPos.map(BlockPos::immutable).orElse(null);
        expiryGameTime = now + Math.max(0, durationTicks);
    }

    public static boolean isActive(long now) {
        return now < expiryGameTime && !interior.isEmpty();
    }

    public static Set<BlockPos> interior() { return interior; }
    public static Set<BlockPos> containers() { return containers; }
    @Nullable
    public static BlockPos failPos() { return failPos; }
}
