package com.bannerbound.core.network;

import java.util.ArrayList;
import java.util.List;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.api.settlement.Settlement;
import com.bannerbound.core.api.settlement.SettlementData;
import com.bannerbound.core.api.territory.TerritoryService;
import com.bannerbound.core.api.walls.WallData;
import com.bannerbound.core.api.walls.WallLayoutEngine;
import com.bannerbound.core.api.walls.WallPiece;
import com.bannerbound.core.api.walls.WallPlan;
import com.bannerbound.core.api.walls.WallProgress;
import com.bannerbound.core.api.walls.WallService;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * Server handlers for the wall-preview screen's payload family — thin glue over
 * {@link WallService} (the same verbs the {@code /bannerbound walls} commands use). Every
 * action replies with a refreshed {@code OpenWallPreview} so the open screen re-renders.
 */
@ApiStatus.Internal
public final class WallNetworkHandlers {

    private WallNetworkHandlers() {
    }

    /** All wall feedback goes through the in-screen status banner — never chat ("a lot of
     *  chat bloat", playtest 2026-06-12). Falls back to the action bar client-side. */
    private static void status(ServerPlayer player, String message, boolean error) {
        PacketDistributor.sendToPlayer(player, new WallScreenPayloads.WallStatus(message, error));
    }

    public static void handleRequestWallPreview(WallScreenPayloads.RequestWallPreview payload,
                                                IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) return;
            Settlement settlement = settlementOf(player);
            if (settlement == null || !settlement.hasTownHall()) {
                status(player, "You need a settlement with a town hall to plan walls.", true);
                return;
            }
            sendPreview(player, settlement);
        });
    }

    public static void handleToggleWallGate(WallScreenPayloads.ToggleWallGate payload,
                                            IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) return;
            Settlement settlement = settlementOf(player);
            if (settlement == null) return;
            String error = WallService.toggleGate(player.serverLevel(), settlement, payload.anchor());
            if (error != null) {
                status(player, error, true);
            }
            sendPreview(player, settlement);
        });
    }

    public static void handlePreviewWallGhosts(WallScreenPayloads.PreviewWallGhosts payload,
                                               IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) return;
            Settlement settlement = settlementOf(player);
            if (settlement == null) return;
            if (payload.show()) {
                // Sender-only ghosts of the layout AS PREVIEWED (gates included, nothing
                // committed). Placement tracking stays keyed to the committed plan.
                com.bannerbound.core.api.walls.WallSync.sendPlanPreview(player, settlement,
                    WallService.computeLayout(player.serverLevel(), settlement).plan());
            } else {
                com.bannerbound.core.api.walls.WallSync.syncPlayer(player, settlement);
            }
        });
    }

    public static void handleRefineWallTop(WallScreenPayloads.RefineWallTop payload,
                                           IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) return;
            Settlement settlement = settlementOf(player);
            if (settlement == null) return;
            String error = WallService.refineTop(player.serverLevel(), settlement,
                payload.anchor(), payload.delta());
            if (error != null) {
                status(player, error, true);
            }
            sendPreview(player, settlement);
        });
    }

    public static void handleCycleWallVariant(WallScreenPayloads.CycleWallVariant payload,
                                              IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) return;
            Settlement settlement = settlementOf(player);
            if (settlement == null) return;
            String result = WallService.cycleVariant(player.serverLevel(), settlement, payload.anchor());
            if (result.startsWith("ok:")) {
                status(player, "Variant: " + result.substring(3), false);
            } else {
                status(player, result, true);
            }
            sendPreview(player, settlement);
        });
    }

    public static void handleToggleWallFoundation(WallScreenPayloads.ToggleWallFoundation payload,
                                                  IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) return;
            Settlement settlement = settlementOf(player);
            if (settlement == null) return;
            String result = WallService.toggleFoundation(player.serverLevel(), settlement, payload.anchor());
            if (result.startsWith("ok:")) {
                status(player, "Foundation continuation: " + result.substring(3).toUpperCase(java.util.Locale.ROOT), false);
            } else {
                status(player, result, true);
            }
            sendPreview(player, settlement);
        });
    }

    public static void handleConstructWalls(WallScreenPayloads.ConstructWalls payload,
                                            IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) return;
            Settlement settlement = settlementOf(player);
            if (settlement == null) return;
            WallService.ConstructResult outcome = WallService.construct(player.serverLevel(), settlement);
            if (!outcome.ok()) {
                status(player, outcome.error(), true);
            } else {
                status(player, "Plan committed — ghosts mark the line; builders and you can "
                    + "start building.", false);
            }
            sendPreview(player, settlement);
        });
    }

    public static void handleCancelWallPlan(WallScreenPayloads.CancelWallPlan payload,
                                            IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) return;
            Settlement settlement = settlementOf(player);
            if (settlement == null) return;
            int leftovers = WallService.cancel(player.serverLevel(), settlement);
            if (leftovers < 0) {
                status(player, "No wall plan to cancel.", true);
            } else if (leftovers > 0) {
                status(player, "Plan cancelled — " + leftovers
                    + " standing wall blocks remembered for demolition.", false);
            } else {
                status(player, "Plan cancelled.", false);
            }
            sendPreview(player, settlement);
        });
    }

    public static void handleRequestWallDesigner(WallScreenPayloads.RequestWallDesigner payload,
                                                 IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) return;
            Settlement settlement = settlementOf(player);
            if (settlement == null) return;
            sendDesigner(player, settlement, player.serverLevel());
        });
    }

    /** Builds and ships the designer payload — also used to refresh an OPEN designer's
     *  library list in place after save/delete. */
    private static void sendDesigner(ServerPlayer player, Settlement settlement, ServerLevel level) {
        var designs = WallService.designs(level, settlement);
        // Picker candidates: BlockItems the settlement's knowledge system recognizes —
        // the same gate every worker yield passes (unknown items never reach players).
        it.unimi.dsi.fastutil.ints.IntArrayList known = new it.unimi.dsi.fastutil.ints.IntArrayList();
        it.unimi.dsi.fastutil.ints.IntArrayList owned = new it.unimi.dsi.fastutil.ints.IntArrayList();
        for (net.minecraft.world.item.Item item : net.minecraft.core.registries.BuiltInRegistries.ITEM) {
            if (!(item instanceof net.minecraft.world.item.BlockItem blockItem)) continue;
            if (blockItem.getBlock().defaultBlockState().isAir()) continue;
            if (!com.bannerbound.core.api.research.SettlementDropFilter.shouldDrop(
                    settlement, null, new net.minecraft.world.item.ItemStack(item))) {
                continue;
            }
            known.add(net.minecraft.core.registries.BuiltInRegistries.ITEM.getId(item));
            // "Owned" = actually on hand: settlement stockpiles + the requester's inventory.
            owned.add(com.bannerbound.core.stockpile.StockpileService.count(level, settlement, item)
                + player.getInventory().countItem(item));
        }
        // Unsaved drafts (designer autosave on close) ride along and override the active
        // set in the editor — Escape never loses work.
        WallData walls = WallData.get(level);
        List<com.bannerbound.core.api.walls.WallDesign> drafts = new ArrayList<>();
        for (com.bannerbound.core.api.walls.WallDesign.Kind kind
                : com.bannerbound.core.api.walls.WallDesign.Kind.values()) {
            com.bannerbound.core.api.walls.WallDesign draft = walls.draft(settlement.id(), kind);
            if (draft != null) drafts.add(draft);
        }
        PacketDistributor.sendToPlayer(player, new WallScreenPayloads.OpenWallDesigner(
            List.of(designs.wall(), designs.corner(), designs.gate()),
            known.toIntArray(), owned.toIntArray(), drafts,
            new ArrayList<>(walls.library(settlement.id()))));
    }

    public static void handleSaveWallDesign(WallScreenPayloads.SaveWallDesign payload,
                                            IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) return;
            Settlement settlement = settlementOf(player);
            if (settlement == null) return;
            ServerLevel level = player.serverLevel();
            com.bannerbound.core.api.walls.WallDesign incoming = payload.design();

            // Never trust the client: rebuild with a server-owned id and validate. Saved
            // designs key on a SLUG of the player-given name + kind ("variants are designs
            // the player made and saved", playtest 2026-06-12) — same name overwrites,
            // new name = new library entry.
            String kindName = incoming.kind().name().toLowerCase(java.util.Locale.ROOT);
            String serverId = payload.draft() ? "draft_" + kindName
                : "u_" + slug(incoming.name()) + "_" + kindName;
            com.bannerbound.core.api.walls.WallDesign design;
            try {
                design = new com.bannerbound.core.api.walls.WallDesign(serverId, incoming.name(),
                    incoming.kind(), incoming.length(), incoming.depth(), incoming.height(),
                    incoming.palette(), incoming.voxelsCopy(), incoming.foundation());
            } catch (IllegalArgumentException e) {
                if (!payload.draft()) status(player, "Invalid design: " + e.getMessage(), true);
                return;
            }
            WallData walls = WallData.get(level);
            if (payload.draft()) {
                // Silent autosave of the working copy (designer close) — no validation
                // beyond geometry, never activated, never enters the layout resolver.
                walls.setDraft(settlement.id(), design);
                return;
            }
            if (design.blockCount() == 0) {
                status(player, "The " + kindName + " design is empty — place some blocks.", true);
                return;
            }
            for (net.minecraft.world.level.block.state.BlockState state : design.palette()) {
                if (!com.bannerbound.core.api.research.SettlementDropFilter.shouldDrop(settlement,
                        null, new net.minecraft.world.item.ItemStack(state.getBlock().asItem()))) {
                    status(player, "Your settlement doesn't know "
                        + state.getBlock().getName().getString() + " yet.", true);
                    return;
                }
            }
            if (design.kind() == com.bannerbound.core.api.walls.WallDesign.Kind.GATE
                && !hasPathableOpening(design)) {
                status(player,
                    "A gate design needs at least one fence gate or door so citizens can pass.",
                    true);
                return;
            }
            boolean exists = walls.libraryDesign(settlement.id(), design.id()) != null;
            if (!exists && walls.library(settlement.id()).size() >= 48) {
                status(player, "Design library is full (48) — Shift+click entries to delete some.", true);
                return;
            }
            walls.upsertDesign(settlement.id(), design);
            walls.setActiveId(settlement.id(), design.kind(), design.id());
            // Draft mirrors the saved state so reopening the designer shows what was saved.
            walls.setDraft(settlement.id(), design);
            // Generic text: the client saves all three kinds in one click; identical banners
            // overwrite invisibly while a per-kind error (banner gives errors priority) sticks.
            status(player, "Designs saved & set active — re-run Construct to apply.", false);
            // Refresh the open designer's library list in place.
            sendDesigner(player, settlement, level);
        });
    }

    /** Lowercase-alnum slug of a player-given design name (id-safe, never empty). */
    private static String slug(String name) {
        String s = name.toLowerCase(java.util.Locale.ROOT)
            .replaceAll("[^a-z0-9]+", "_")
            .replaceAll("^_+|_+$", "");
        if (s.isEmpty()) s = "design";
        return s.length() > 48 ? s.substring(0, 48) : s;
    }

    public static void handleDeleteWallDesign(WallScreenPayloads.DeleteWallDesign payload,
                                              IPayloadContext context) {
        context.enqueueWork(() -> {
            if (!(context.player() instanceof ServerPlayer player)) return;
            Settlement settlement = settlementOf(player);
            if (settlement == null) return;
            ServerLevel level = player.serverLevel();
            WallData walls = WallData.get(level);
            if (walls.libraryDesign(settlement.id(), payload.designId()) == null) {
                status(player, "That design no longer exists.", true);
            } else {
                walls.removeDesign(settlement.id(), payload.designId());
                status(player, "Design deleted.", false);
            }
            sendDesigner(player, settlement, level);
        });
    }

    /** GATE rule: at least one openable block (fence gate / door tag) somewhere in the grid. */
    private static boolean hasPathableOpening(com.bannerbound.core.api.walls.WallDesign design) {
        for (int l = 0; l < design.length(); l++) {
            for (int d = 0; d < design.depth(); d++) {
                for (int h = 0; h < design.height(); h++) {
                    net.minecraft.world.level.block.state.BlockState state = design.stateAt(l, d, h);
                    if (state != null && (state.is(net.minecraft.tags.BlockTags.FENCE_GATES)
                        || state.is(net.minecraft.tags.BlockTags.DOORS))) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /** Builds and sends a fresh preview: territory base payload + the previewed wall pieces. */
    public static void sendPreview(ServerPlayer player, Settlement settlement) {
        sendPreview(player, settlement, false);
    }

    /** {@code openRefine} = the client should open the 3D refinement view directly (the
     *  {@code /bannerbound walls refine} shortcut — no menu chain just to test heights). */
    public static void sendPreview(ServerPlayer player, Settlement settlement, boolean openRefine) {
        ServerLevel overworld = player.getServer().overworld();
        ServerLevel level = player.serverLevel();
        OpenExpandTerritoryScreenPayload base =
            TerritoryService.buildScreenPayload(overworld, settlement, player);
        WallLayoutEngine.LayoutResult result = WallService.computeLayout(level, settlement);
        java.util.function.Function<String, com.bannerbound.core.api.walls.WallDesign> resolver =
            WallService.resolver(level, settlement);
        // Every design the pieces reference (dedup, insertion order) rides the payload; each
        // piece carries its INDEX into that list — per-piece variants render their real
        // blocks client-side without any kind-based guessing.
        java.util.Map<String, Integer> designIndexById = new java.util.LinkedHashMap<>();
        List<com.bannerbound.core.api.walls.WallDesign> referencedDesigns = new ArrayList<>();
        List<WallScreenPayloads.PieceLite> pieces = new ArrayList<>(result.plan().pieces().size());
        for (WallPiece piece : result.plan().pieces()) {
            com.bannerbound.core.api.walls.WallDesign design = resolver.apply(piece.designId());
            int designIndex = -1;
            if (design != null) {
                Integer existing = designIndexById.get(piece.designId());
                if (existing == null) {
                    existing = referencedDesigns.size();
                    designIndexById.put(piece.designId(), existing);
                    referencedDesigns.add(design);
                }
                designIndex = existing;
            }
            pieces.add(new WallScreenPayloads.PieceLite(piece.startX(), piece.startZ(),
                piece.length(), piece.depth(), piece.outward().get2DDataValue(),
                piece.kind().ordinal(), piece.waterGap(),
                piece.baseY(), design == null ? 3 : design.height(),
                piece.minGround(), piece.maxGround(),
                designIndex, piece.noFoundation()));
        }
        WallPlan committed = WallData.get(level).plan(settlement.id());
        int completeness = 0;
        if (committed != null) {
            completeness = WallProgress.scan(level, committed,
                WallService.resolver(level, settlement)).percent();
        }
        // planCurrent: does the COMMITTED plan still match what's being previewed? False
        // after design/gate/territory changes — the built wall is an OLDER design and the
        // "% built" headline must say so (playtest 2026-06-12).
        boolean planCurrent = committed != null
            && samePieces(committed.pieces(), result.plan().pieces());
        var activeSet = WallService.designs(level, settlement);
        PacketDistributor.sendToPlayer(player,
            new WallScreenPayloads.OpenWallPreview(base, pieces, committed != null, completeness,
                activeSet.gate().length(), openRefine, planCurrent, referencedDesigns));
    }

    /** Piece-list identity for planCurrent: same count and per-slot geometry/design/height. */
    private static boolean samePieces(List<WallPiece> a, List<WallPiece> b) {
        if (a.size() != b.size()) return false;
        for (int i = 0; i < a.size(); i++) {
            WallPiece p = a.get(i);
            WallPiece q = b.get(i);
            if (p.startX() != q.startX() || p.startZ() != q.startZ()
                || p.kind() != q.kind() || !p.designId().equals(q.designId())
                || p.length() != q.length() || p.depth() != q.depth()
                || p.baseY() != q.baseY() || p.outward() != q.outward()
                || p.waterGap() != q.waterGap()) {
                return false;
            }
        }
        return true;
    }

    private static Settlement settlementOf(ServerPlayer player) {
        return SettlementData.get(player.getServer().overworld()).getByPlayer(player.getUUID());
    }
}
