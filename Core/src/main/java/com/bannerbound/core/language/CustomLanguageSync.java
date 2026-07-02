package com.bannerbound.core.language;

import com.bannerbound.core.api.settlement.Settlement;
import com.bannerbound.core.api.settlement.SettlementData;
import com.bannerbound.core.chat.BannerboundGameRules;
import com.bannerbound.core.entity.CitizenEntity;
import com.bannerbound.core.network.LanguageStatePayload;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;

/** Server-side sync and refresh hooks for the custom language gamerule. */
public final class CustomLanguageSync {
    private CustomLanguageSync() {
    }

    public static boolean enabled(ServerLevel level) {
        return level != null
            && BannerboundGameRules.USE_CUSTOM_LANGUAGE != null
            && level.getGameRules().getBoolean(BannerboundGameRules.USE_CUSTOM_LANGUAGE);
    }

    public static void sendTo(ServerPlayer player) {
        if (player == null) return;
        MinecraftServer server = player.getServer();
        if (server == null) return;
        Settlement settlement = SettlementData.get(server.overworld()).getByPlayer(player.getUUID());
        boolean active = settlement != null && enabled(server.overworld());
        long seed = settlement == null ? 0L : settlement.languageSeed();
        PacketDistributor.sendToPlayer(player, new LanguageStatePayload(
            active, seed, LanguageConceptOverrideLoader.encodeForSync()));
    }

    public static void broadcast(MinecraftServer server) {
        if (server == null) return;
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            sendTo(player);
        }
    }

    public static void onRuleChanged(MinecraftServer server) {
        refreshLoadedCitizenNames(server);
        broadcast(server);
    }

    public static void refreshLoadedCitizenNames(MinecraftServer server) {
        if (server == null) return;
        SettlementData data = SettlementData.get(server.overworld());
        for (ServerLevel level : server.getAllLevels()) {
            for (Settlement settlement : data.all()) {
                refreshSettlementCitizenNames(level, settlement);
            }
        }
    }

    public static void refreshSettlementCitizenNames(ServerLevel level, Settlement settlement) {
        if (level == null || settlement == null) return;
        for (CitizenEntity citizen
                : com.bannerbound.core.api.settlement.SettlementManager.allCitizensOf(level, settlement)) {
            citizen.refreshDisplayName();
        }
    }
}
