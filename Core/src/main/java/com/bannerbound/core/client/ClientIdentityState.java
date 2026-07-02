package com.bannerbound.core.client;

import org.jetbrains.annotations.ApiStatus;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.bannerbound.core.api.settlement.SettlementColor;
import com.bannerbound.core.network.IdentitySyncPayload;

import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * Client mirror of the settlement identity color table (see {@code IdentitySyncPayload}):
 * founding-color ordinal → banner-derived 0xRRGGBB list, most-present dye first, as many
 * colors as the banner has. EVERY client renderer that shows a settlement color resolves
 * through here — falling back to the founding {@code SettlementColor} rgb for ordinals the
 * table doesn't know (pre-design settlements, stale syncs), so nothing ever renders black.
 */
@OnlyIn(Dist.CLIENT)
@ApiStatus.Internal
public final class ClientIdentityState {

    private static final Map<Integer, List<Integer>> BY_COLOR_ORDINAL = new HashMap<>();

    private ClientIdentityState() {}

    public static void replace(IdentitySyncPayload payload) {
        BY_COLOR_ORDINAL.clear();
        for (int i = 0; i < payload.colorOrdinals().size(); i++) {
            List<Integer> rgbs = payload.rgbLists().get(i);
            if (rgbs.isEmpty()) continue; // malformed entry — keep the founding fallback
            BY_COLOR_ORDINAL.put(payload.colorOrdinals().get(i), List.copyOf(rgbs));
        }
    }

    /** ALL identity colors of the settlement flying this founding-color slot — never empty
     *  (founding rgb fallback). Feed straight into the identity-gradient helpers. */
    public static List<Integer> rgbs(int colorOrdinal) {
        List<Integer> rgbs = BY_COLOR_ORDINAL.get(colorOrdinal);
        return rgbs != null ? rgbs : List.of(SettlementColor.byIndex(colorOrdinal).rgb());
    }

    /** THE color of the settlement flying this founding-color slot (0xRRGGBB). */
    public static int primaryRgb(int colorOrdinal) {
        return rgbs(colorOrdinal).get(0);
    }

    /** The settlement's accent (0xRRGGBB) — the second identity color, or the primary when the
     *  banner is single-color. For two-tone trim (overlay outlines, ribbons). */
    public static int secondaryRgb(int colorOrdinal) {
        List<Integer> rgbs = rgbs(colorOrdinal);
        return rgbs.size() > 1 ? rgbs.get(1) : rgbs.get(0);
    }
}
