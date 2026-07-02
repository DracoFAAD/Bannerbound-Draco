package com.bannerbound.core.client;

import org.jetbrains.annotations.ApiStatus;

import java.util.HashSet;
import java.util.Set;

import net.minecraft.world.entity.Entity;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * Holds the transient state needed to render the territory birdseye overlay in-world while the
 * {@link ExpandTerritoryScreen} is open: which chunks are own vs foreign vs purchasable, the
 * settlement color, and references to the original / synthetic camera entities so the renderer
 * can no-op when birdseye isn't active.
 * <p>
 * Set on screen open, cleared on close. The screen is responsible for setting + clearing.
 */
@OnlyIn(Dist.CLIENT)
@ApiStatus.Internal
public final class ClientBirdseyeState {
    private static boolean active = false;
    private static Entity ghostCamera;
    private static Entity originalCamera;
    private static final Set<Long> OWN = new HashSet<>();
    private static final Set<Long> FOREIGN = new HashSet<>();
    private static final Set<Long> PURCHASABLE = new HashSet<>();
    private static int colorOrdinal;
    private static long townHallChunk;
    private static boolean canAfford;
    /** The chunk currently under the mouse cursor (set per-frame by the screen). Null = none. */
    private static Long hoveredChunk;
    /** World Y of the slab plane the renderer paints chunks on, computed per-session from the
     *  player's position so the overlay sits above local terrain without exceeding render
     *  distance from above. */
    private static int slabY;
    private static int cameraY;

    private ClientBirdseyeState() {}

    public static void enter(Entity ghost, Entity original,
                             Set<Long> own, Set<Long> foreign, Set<Long> purchasable,
                             int colorOrdinalIn, long thChunk, boolean canAffordIn,
                             int slabYIn, int cameraYIn) {
        ghostCamera = ghost;
        originalCamera = original;
        OWN.clear(); OWN.addAll(own);
        FOREIGN.clear(); FOREIGN.addAll(foreign);
        PURCHASABLE.clear(); PURCHASABLE.addAll(purchasable);
        colorOrdinal = colorOrdinalIn;
        townHallChunk = thChunk;
        canAfford = canAffordIn;
        slabY = slabYIn;
        cameraY = cameraYIn;
        hoveredChunk = null;
        active = true;
    }

    public static void exit() {
        active = false;
        ghostCamera = null;
        originalCamera = null;
        OWN.clear(); FOREIGN.clear(); PURCHASABLE.clear();
        hoveredChunk = null;
    }

    public static boolean isActive() { return active; }
    public static Entity originalCamera() { return originalCamera; }
    public static Entity ghostCamera() { return ghostCamera; }
    public static Set<Long> own() { return OWN; }
    public static Set<Long> foreign() { return FOREIGN; }
    public static Set<Long> purchasable() { return PURCHASABLE; }
    public static int colorOrdinal() { return colorOrdinal; }
    public static long townHallChunk() { return townHallChunk; }
    public static boolean canAfford() { return canAfford; }
    public static Long hoveredChunk() { return hoveredChunk; }
    public static void setHoveredChunk(Long chunk) { hoveredChunk = chunk; }
    public static int slabY() { return slabY; }
    public static int cameraY() { return cameraY; }
}
