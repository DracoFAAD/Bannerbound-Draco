package com.bannerbound.core.codex;

import com.bannerbound.core.api.settlement.Settlement;

import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

/** Public Chronicle API for expansion mods, commands, and scripted systems. */
public final class CodexUnlocks {
    private CodexUnlocks() {
    }

    public static boolean unlock(ServerPlayer player, ResourceLocation entryId) {
        return entryId != null && CodexManager.unlock(player, entryId.toString(), true);
    }

    public static boolean unlock(ServerPlayer player, String entryId) {
        return CodexManager.unlock(player, entryId, true);
    }

    public static boolean lock(ServerPlayer player, ResourceLocation entryId) {
        return entryId != null && CodexManager.lock(player, entryId.toString());
    }

    public static boolean lock(ServerPlayer player, String entryId) {
        return CodexManager.lock(player, entryId);
    }

    public static void open(ServerPlayer player, ResourceLocation entryId) {
        CodexManager.open(player, entryId == null ? "" : entryId.toString());
    }

    public static void open(ServerPlayer player, String entryId) {
        CodexManager.open(player, entryId);
    }

    public static void fire(ServerPlayer player, String type, String id) {
        CodexManager.onCustom(player, type, id);
    }

    public static void fireForSettlement(MinecraftServer server, Settlement settlement, String type, String id) {
        CodexManager.onCustom(server, settlement, type, id);
    }
}
