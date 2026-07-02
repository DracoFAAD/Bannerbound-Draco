package com.bannerbound.core.client;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * Client-side TTL for the House Block "Detect" wireframe flash. The detected boxes are already in
 * {@link ClientSelectionState} (broadcast as HOME selections), so this only remembers <i>which</i>
 * home to highlight and <i>until when</i>. {@link SelectionRenderer} draws that home's silhouette
 * in green while the flash is active, regardless of whether the player holds a rod.
 */
@OnlyIn(Dist.CLIENT)
@ApiStatus.Internal
public final class DetectPreviewState {
    @Nullable
    private static BlockPos housePos;
    private static long expiryGameTime;

    private DetectPreviewState() {
    }

    /** Arm the flash for {@code durationTicks} from the current client game-time. */
    public static void show(BlockPos pos, int durationTicks) {
        Minecraft mc = Minecraft.getInstance();
        long now = mc.level != null ? mc.level.getGameTime() : 0L;
        housePos = pos == null ? null : pos.immutable();
        expiryGameTime = now + Math.max(0, durationTicks);
    }

    /** The home pos to highlight if the flash is still active at {@code now}, else null. */
    @Nullable
    public static BlockPos activeHousePos(long now) {
        return (housePos != null && now < expiryGameTime) ? housePos : null;
    }
}
