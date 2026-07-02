package com.bannerbound.core.social;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.api.research.ResearchManager;
import com.bannerbound.core.api.settlement.Settlement;

import net.minecraft.network.chat.Component;

/**
 * Resolves the <b>display name</b> of a Foreman's-Rod worker type, which can change with research.
 * The "digger" unit is shown as <i>Digger</i> until the Quarry research, then as <i>Quarryworker</i>.
 * <p>
 * Server-safe (no client-only references). The client equivalent lives next to the rod UI and reads
 * {@code ClientResearchState} instead of a {@link Settlement}.
 */
@ApiStatus.Internal
public final class WorkstationNames {
    public static final String DIGGER = "digger";
    public static final String FLAG_QUARRY = "bannerbound.unlock_quarry";

    private WorkstationNames() {
    }

    /** The worker-type label for {@code wsType} given this settlement's research. */
    public static Component dynamic(Settlement settlement, String wsType) {
        if (DIGGER.equals(wsType) && settlement != null
                && ResearchManager.hasFlag(settlement, FLAG_QUARRY)) {
            return Component.translatable("bannerbound.workstation_type.quarryworker");
        }
        return Component.translatable("bannerbound.workstation_type." + wsType);
    }
}
