package com.bannerbound.core.client;

import java.util.List;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.network.DiplomacyObjectivePayload;
import com.bannerbound.core.network.DiplomacyStatePayload;

import net.minecraft.core.BlockPos;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

@OnlyIn(Dist.CLIENT)
@ApiStatus.Internal
public final class ClientDiplomacyState {
    private static boolean rallying;
    private static int winnerCooldownSeconds;
    private static List<DiplomacyStatePayload.Row> rows = List.of();
    private static List<DiplomacyStatePayload.BarbarianRow> barbarianRows = List.of();
    private static DiplomacyObjectivePayload objective =
        new DiplomacyObjectivePayload(false, "", "", BlockPos.ZERO, 0xFFFFFF);

    private ClientDiplomacyState() {}

    public static void replace(DiplomacyStatePayload payload) {
        rallying = payload.rallying();
        winnerCooldownSeconds = payload.winnerCooldownSeconds();
        rows = List.copyOf(payload.rows());
        barbarianRows = List.copyOf(payload.barbarianRows());
    }

    public static boolean rallying() { return rallying; }
    public static int winnerCooldownSeconds() { return winnerCooldownSeconds; }
    public static List<DiplomacyStatePayload.Row> rows() { return rows; }
    public static List<DiplomacyStatePayload.BarbarianRow> barbarianRows() { return barbarianRows; }

    public static void objective(DiplomacyObjectivePayload payload) {
        objective = payload;
    }

    public static DiplomacyObjectivePayload objective() {
        return objective;
    }
}
