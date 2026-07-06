package com.bannerbound.core.client;

import com.bannerbound.core.api.settlement.Settlement;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.api.settlement.Era;
import com.bannerbound.core.api.settlement.PolicyRegistry;
import com.bannerbound.core.api.settlement.PolicyType;
import com.bannerbound.core.api.settlement.SettlementColor;
import com.bannerbound.core.client.ui.OutlinedText;
import com.bannerbound.core.network.CastPaletteVotePayload;
import com.bannerbound.core.network.CastPolicyVotePayload;
import com.bannerbound.core.network.DisbandSettlementPayload;
import com.bannerbound.core.network.GetRegistrationTabletPayload;
import com.bannerbound.core.network.ProposePaletteChangePayload;
import com.bannerbound.core.network.ProposePolicyChangePayload;
import com.bannerbound.core.network.RequestExpandTerritoryPayload;
import com.bannerbound.core.network.RequestSettlementCitizensPayload;
import com.bannerbound.core.network.SuggestPalettePayload;
import com.bannerbound.core.network.SuggestPolicyPayload;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.network.PacketDistributor;

@OnlyIn(Dist.CLIENT)
@ApiStatus.Internal
public class TownHallScreen extends Screen {
    private static final int PANEL_WIDTH = 260;
    /** Tall enough to fit the SIX-button Chief main tab (Research / Citizens / Tablet / Expand /
     *  Disband / Step Down) plus Cancel without overlap — the title block also gained a couple of
     *  px of breathing room under the era line, which pushed the button block down. Stats panel is
     *  96px tall (grew from 84 for the larger 16px era icons); the buttons block follows it. */
    private static final int PANEL_HEIGHT = 352;

    /** Tab strip lives between the title block and the body. Each tab swaps the body content
     *  without closing/reopening the screen. {@link Tab#MAIN} hosts the stats panel + action
     *  buttons (the original screen); {@link Tab#STATUSES} hosts a scrollable list of active
     *  {@link com.bannerbound.core.api.settlement.StatusEffect}s. */
    private enum Tab { MAIN, STATUSES, LABOR, DICTIONARY, STATISTICS, POLICIES, PALETTES, VOTES, SUGGESTIONS, DIPLOMACY, WALLS }

    /** How far the bookmark tabs protrude above the panel's top edge. */
    private static final int TAB_HEIGHT = 16;
    private static final int STATUS_ROW_HEIGHT = 26;
    private static final int DICTIONARY_ROW_HEIGHT = 34;
    private static final int STATISTICS_ROW_HEIGHT = 12;
    private static final int POLICY_ROW_HEIGHT = 22;
    private static final int POLICY_SLOT_HEIGHT = 26;
    private static final int SCROLLBAR_WIDTH = 4;
    // Palette rows/slots are taller than policy ones: they show a label line plus a row of block
    // icons (rendered like research-tree node icons), so each box fits a 16px icon strip.
    private static final int PALETTE_ROW_HEIGHT = 30;
    private static final int PALETTE_SLOT_HEIGHT = 32;
    private static final int PALETTE_ICON = 16;
    private static final int PALETTE_ICON_GAP = 2;

    private Tab activeTab = Tab.MAIN;
    // ─── Polish animations (gated by Config.UI_ANIMATIONS) ────────────────────────────────
    /** When this screen instance was opened — drives the open zoom/slide-in (~160ms ease-out). */
    private final long openedAtMs = net.minecraft.Util.getMillis();
    /** When the active tab last changed — drives a subtler slide on tab switches (~110ms). */
    private long tabSwitchedAtMs = 0L;
    /** Vertical scroll offset in pixels for the Statuses tab. Clamped to keep the list within
     *  bounds in render(). Reset to 0 when switching tabs. */
    private int statusesScrollY = 0;
    private int dictionaryScrollY = 0;
    private int statisticsScrollY = 0;
    private int policiesScrollY = 0;

    // ─── Policies-tab interaction state ───────────────────────────────────────────────────
    /** Policy id being dragged from the Available list; null when not dragging. While dragging
     *  the row is lifted OUT of the list (the list compacts to close the gap) and rendered as a
     *  swaying card under the cursor. */
    private String draggingPolicyId = null;
    /** Locally-staged change before Confirm: the policy to add into {@link #stagedSlot} and/or
     *  the policy to remove. Cleared after Confirm/Suggest or Cancel. */
    private String stagedAddId = null;
    private String stagedRemoveId = null;
    private int stagedSlot = -1;
    /** Drag-card sway: eased lean angle (deg) following horizontal cursor velocity, plus the
     *  previous cursor X used to derive that velocity each frame. Gives the lifted card a little
     *  physical lag/sway as the player moves it. */
    private double dragSwayAngle = 0.0;
    private double dragPrevMouseX = Double.NaN;
    /** Geometry shared between buildPoliciesTab (layout), render (draw), and the mouse handlers
     *  (hit-test). Set each rebuild. */
    private int polLeftX, polRightX, polColW, polSlotsTop, polListTop, polListH;
    private int polSlotCount;
    private final java.util.List<String> polVisibleList = new java.util.ArrayList<>();
    private final TransientClickFeedback policyFeedback = new TransientClickFeedback();

    // ─── Palettes-tab interaction state (twin of the policy state above) ─────────────────────
    private int palettesScrollY = 0;
    private String draggingPaletteId = null;
    private String pStagedAddId = null;
    private String pStagedRemoveId = null;
    private int pStagedSlot = -1;
    // The drag sway (dragSwayAngle / dragPrevMouseX) is shared — only one item is ever dragged.
    private int palLeftX, palRightX, palColW, palSlotsTop, palListTop, palListH;
    private int palSlotCount;
    private final java.util.List<String> palVisibleList = new java.util.ArrayList<>();
    private final TransientClickFeedback paletteFeedback = new TransientClickFeedback();
    /** Full known-word list for the active Dictionary tab build; {@link #dictionaryRows} is the
     *  search-filtered view actually drawn. */
    private java.util.List<ClientLanguageState.DictionaryEntry> dictionaryAll = java.util.List.of();
    private java.util.List<ClientLanguageState.DictionaryEntry> dictionaryRows = java.util.List.of();
    /** Live lexicon search text. Persisted on the screen instance so it survives rebuildWidgets
     *  (it's re-applied to a freshly-built EditBox); cleared on tab switch. */
    private String dictionaryQuery = "";
    private net.minecraft.client.gui.components.EditBox dictionarySearch;

    private final String settlementName;
    /** Banner-identity accents (ARGB, most-present first, as many as the banner has): the
     *  first tints the name + selected tab ribbons, the full list drives every gradient
     *  divider and the panel border. See FactionBanner.identityDyes. */
    private final java.util.List<Integer> identityAccents;
    private final int primaryAccent;
    /** Second identity color (accent), or the primary when the banner is single-color.
     *  Tab ribbons sweep primary→this. */
    private final int secondaryAccent;
    private final SettlementColor settlementColor;
    private final Era era;
    private final int tabletsIssued;
    private final int tabletCapacity;
    private final int disbandVoteCount;
    private final int disbandTotalMembers;
    private final boolean playerHasVotedToDisband;
    private final boolean disbandVoteActive;
    /** {@link Settlement.Government} ordinal — drives the Chiefdom gate. NONE / COUNCIL never
     *  gate weighty buttons; only CHIEFDOM + !playerIsChief greys them out. */
    private final int governmentOrdinal;
    /** True iff this player is the seated Chief. Used together with {@link #governmentOrdinal}
     *  to decide whether to grey out Disband / Expand. */
    private final boolean playerIsChief;
    /** Absolute game tick the seated chief may Step Down (seat tick + term cooldown), or {@code -1}
     *  when not applicable. The Step-Down button greys out with a live mm:ss countdown until the
     *  client's {@code level.getGameTime()} reaches this. */
    private final long chiefStepDownReadyTick;
    /** Absolute game tick at which this player may Leave (anti-cheese join/found cooldown), or
     *  {@code 0} when already free. The Leave button greys out with a live mm:ss countdown until
     *  the client's {@code level.getGameTime()} reaches this. */
    private final long leaveReadyTick;

    public TownHallScreen(String settlementName, SettlementColor color, Era era,
                          int tabletsIssued, int tabletCapacity,
                          int disbandVoteCount, int disbandTotalMembers,
                          boolean playerHasVotedToDisband, boolean disbandVoteActive,
                          int governmentOrdinal, boolean playerIsChief,
                          long chiefStepDownReadyTick,
                          long leaveReadyTick,
                          java.util.List<Integer> identityRgbs) {
        super(Component.literal(settlementName));
        this.chiefStepDownReadyTick = chiefStepDownReadyTick;
        this.leaveReadyTick = leaveReadyTick;
        this.settlementName = settlementName;
        this.settlementColor = color;
        // Banner-driven identity (Heraldry): the screen's accent colors come from the faction
        // banner's dyes — as many as the banner has — not the founding color slot.
        java.util.List<Integer> accents = new java.util.ArrayList<>(identityRgbs.size());
        for (int rgb : identityRgbs) accents.add(0xFF000000 | rgb);
        if (accents.isEmpty()) accents.add(0xFF000000 | (color.rgb() & 0x00FFFFFF));
        this.identityAccents = accents;
        this.primaryAccent = accents.get(0);
        this.secondaryAccent = accents.size() > 1 ? accents.get(1) : accents.get(0);
        this.era = era;
        this.tabletsIssued = tabletsIssued;
        this.tabletCapacity = tabletCapacity;
        this.disbandVoteCount = disbandVoteCount;
        this.disbandTotalMembers = disbandTotalMembers;
        this.playerHasVotedToDisband = playerHasVotedToDisband;
        this.disbandVoteActive = disbandVoteActive;
        this.governmentOrdinal = governmentOrdinal;
        this.playerIsChief = playerIsChief;
    }

    /** True if this player isn't allowed to perform weighty actions (Disband / Expand).
     *  In Chiefdom only the seated Chief may; everyone else in the settlement is read-only
     *  for those actions. Council + NONE never block. */
    private boolean weightyBlocked() {
        return governmentOrdinal == Settlement.Government.CHIEFDOM.ordinal() && !playerIsChief;
    }

    /** Ticks until this chief may Step Down — 0 once the term cooldown has elapsed (or it never
     *  applied: pre-feature chief / not the chief). Live against the client's synced game time. */
    private long stepDownRemainingTicks() {
        if (chiefStepDownReadyTick < 0) return 0L;
        net.minecraft.client.Minecraft mc = this.minecraft;
        if (mc == null || mc.level == null) return 0L;
        return Math.max(0L, chiefStepDownReadyTick - mc.level.getGameTime());
    }

    /** Formats a tick duration as {@code m:ss}, rounding up to the next whole second. */
    private static String formatMmSs(long ticks) {
        long sec = (ticks + 19L) / 20L;
        return String.format("%d:%02d", sec / 60, sec % 60);
    }

    /** The Step-Down button: greys out with a live mm:ss countdown until the chief's term
     *  cooldown elapses (anti-cheese), then enables and resigns the chair on click. The label +
     *  enabled state recompute every frame from {@link #stepDownRemainingTicks()}. */
    private final class StepDownButton extends Button {
        StepDownButton(int x, int y, int w, int h) {
            super(x, y, w, h, Component.translatable("bannerbound.townhall.menu.quit_chief"),
                b -> {
                    PacketDistributor.sendToServer(
                        com.bannerbound.core.network.QuitChiefPayload.INSTANCE);
                    TownHallScreen.this.onClose();
                }, DEFAULT_NARRATION);
        }

        @Override
        protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
            long remaining = stepDownRemainingTicks();
            if (remaining > 0) {
                this.active = false;
                this.setMessage(Component.translatable(
                    "bannerbound.townhall.menu.quit_chief.cooldown", formatMmSs(remaining)));
            } else {
                this.active = true;
                this.setMessage(Component.translatable("bannerbound.townhall.menu.quit_chief"));
            }
            super.renderWidget(g, mouseX, mouseY, partialTick);
        }
    }

    /** Ticks until this player may Leave — 0 once the join/found cooldown has elapsed (or it never
     *  applied). Live against the client's synced game time. */
    private long leaveRemainingTicks() {
        if (leaveReadyTick <= 0) return 0L;
        net.minecraft.client.Minecraft mc = this.minecraft;
        if (mc == null || mc.level == null) return 0L;
        return Math.max(0L, leaveReadyTick - mc.level.getGameTime());
    }

    /** The Leave-Settlement button: greys out with a live mm:ss countdown until the join/found
     *  cooldown elapses (anti-cheese), then enables and walks the member out on click. The label +
     *  enabled state recompute every frame from {@link #leaveRemainingTicks()}. */
    private final class LeaveButton extends Button {
        LeaveButton(int x, int y, int w, int h) {
            super(x, y, w, h, Component.translatable("bannerbound.townhall.menu.leave"),
                b -> {
                    PacketDistributor.sendToServer(
                        com.bannerbound.core.network.LeaveSettlementPayload.INSTANCE);
                    TownHallScreen.this.onClose();
                }, DEFAULT_NARRATION);
        }

        @Override
        protected void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
            long remaining = leaveRemainingTicks();
            if (remaining > 0) {
                this.active = false;
                this.setMessage(Component.translatable(
                    "bannerbound.townhall.menu.leave.cooldown", formatMmSs(remaining)));
            } else {
                this.active = true;
                this.setMessage(Component.translatable("bannerbound.townhall.menu.leave"));
            }
            super.renderWidget(g, mouseX, mouseY, partialTick);
        }
    }

    @Override
    protected void init() {
        final int panelX = (this.width - PANEL_WIDTH) / 2;
        // Shift the panel down by the bookmark-tab height so the tabs that protrude above its
        // top edge stay on-screen; the panel + tab strip centre together as one block.
        final int panelY = (this.height - PANEL_HEIGHT + TAB_HEIGHT) / 2;
        final int btnWidth = PANEL_WIDTH - 24;
        // Tabs now live ABOVE the panel (bookmark style), so the body starts right under the
        // title divider instead of below an internal tab strip.
        final int bodyTop = panelY + 56;
        final int cancelButtonY = panelY + PANEL_HEIGHT - 28;

        // Panel background + title divider lines. The outline carries a hint of the faction's
        // primary identity color; the title divider runs a primary→secondary gradient — the
        // banner's colors, threaded through the seat of government.
        this.addRenderableOnly((graphics, mouseX, mouseY, partialTick) -> {
            graphics.fill(panelX, panelY, panelX + PANEL_WIDTH, panelY + PANEL_HEIGHT, 0xFF101010);
            graphics.renderOutline(panelX, panelY, PANEL_WIDTH, PANEL_HEIGHT,
                PolishedScreen.blendArgb(0xFF606060, primaryAccent, 0.45f));
            // The border itself wears the identity gradient (top/bottom = full color run,
            // sides = first→last), painted over the plain outline.
            PolishedScreen.drawIdentityBorder(graphics, panelX, panelY,
                PANEL_WIDTH, PANEL_HEIGHT, identityAccents);
            PolishedScreen.drawIdentityGradient(graphics, panelX + 8, panelY + 52,
                PANEL_WIDTH - 16, 1, identityAccents);
        });

        // Title + era + settlement-rank title (Hearth / Tribe / …). Population-based; the
        // ClientPopulationState getter returns the same lookup the server side computes.
        this.addRenderableOnly((graphics, mouseX, mouseY, partialTick) -> {
            // Identity primary, not the founding slot — a mostly-magenta banner makes this a
            // magenta settlement, and the name says so.
            graphics.drawCenteredString(this.font,
                Component.literal(settlementName),
                panelX + PANEL_WIDTH / 2, panelY + 12, primaryAccent);
            graphics.drawCenteredString(this.font,
                era.displayName(),
                panelX + PANEL_WIDTH / 2, panelY + 28, 0xFFCCCCCC);
            graphics.drawCenteredString(this.font,
                Component.translatable(ClientPopulationState.getTitleKey()),
                panelX + PANEL_WIDTH / 2, panelY + 40, 0xFF999999);
        });

        // ─── Bookmark tabs (protrude above the panel's top edge) ───
        // The Policies tab only appears once the settlement is a tribe (any government other
        // than anarchy) — policies are a post-Code-of-Laws system. Two tabs pre-tribe, three after.
        boolean showPolicies = governmentOrdinal != Settlement.Government.NONE.ordinal();
        if ((activeTab == Tab.POLICIES || activeTab == Tab.PALETTES) && !showPolicies) activeTab = Tab.MAIN;
        // Votes is a council mechanic; Suggestions is the chiefdom inbox. Snap back to Main if the
        // government changed under an open screen.
        if (activeTab == Tab.VOTES && !isCouncil()) activeTab = Tab.MAIN;
        if (activeTab == Tab.SUGGESTIONS && (!isChiefdom() || !playerIsChief)) activeTab = Tab.MAIN;
        if (activeTab == Tab.STATISTICS && !clientHasFlagEitherTree("bannerbound.unlock.statistics")) activeTab = Tab.MAIN;
        addBookmarkTabs(panelX, panelY, showPolicies);

        // Cancel button is shared across tabs.
        this.addRenderableWidget(PolishButton.polished(
            Component.translatable("gui.cancel"),
            btn -> this.onClose())
            .bounds(panelX + 12, cancelButtonY, btnWidth, 20)
            .build());

        // ─── Body ───
        if (activeTab == Tab.MAIN) {
            buildMainTab(panelX, bodyTop, btnWidth);
        } else if (activeTab == Tab.LABOR) {
            buildLaborTab(panelX, bodyTop, btnWidth);
        } else if (activeTab == Tab.DICTIONARY) {
            buildDictionaryTab(panelX, bodyTop, cancelButtonY - 8);
        } else if (activeTab == Tab.STATISTICS) {
            buildStatisticsTab(panelX, bodyTop, cancelButtonY - 8);
        } else if (activeTab == Tab.POLICIES) {
            buildPoliciesTab(panelX, bodyTop, cancelButtonY - 8, btnWidth);
        } else if (activeTab == Tab.PALETTES) {
            buildPalettesTab(panelX, bodyTop, cancelButtonY - 8);
        } else if (activeTab == Tab.VOTES) {
            buildVotesTab(panelX, bodyTop, btnWidth);
        } else if (activeTab == Tab.SUGGESTIONS) {
            buildSuggestionsTab(panelX, bodyTop, cancelButtonY - 8, btnWidth);
        } else if (activeTab == Tab.DIPLOMACY) {
            buildDiplomacyTab(panelX, bodyTop, cancelButtonY - 8, btnWidth);
        } else if (activeTab == Tab.WALLS) {
            buildWallsTab(panelX, bodyTop, btnWidth);
        } else {
            buildStatusesTab(panelX, bodyTop, cancelButtonY - 8);
        }
    }

    /** The walls hub (WALLS_PLAN.md): preview/construct the territory wall, open the 3D
     *  designer. The design list itself lives in the designer (one custom design per kind in
     *  v1); this tab is the doorway plus a primer for players who haven't touched walls yet. */
    private void buildWallsTab(int panelX, int bodyTop, int btnWidth) {
        int y = bodyTop + 4;
        this.addRenderableWidget(PolishButton.polished(
            Component.translatable("bannerbound.townhall.menu.wall_preview"),
            btn -> net.neoforged.neoforge.network.PacketDistributor.sendToServer(
                new com.bannerbound.core.network.WallScreenPayloads.RequestWallPreview()))
            .bounds(panelX + 12, y, btnWidth, 20)
            .build());
        y += 24;
        this.addRenderableWidget(PolishButton.polished(
            Component.translatable("bannerbound.townhall.menu.wall_designer"),
            btn -> net.neoforged.neoforge.network.PacketDistributor.sendToServer(
                new com.bannerbound.core.network.WallScreenPayloads.RequestWallDesigner()))
            .bounds(panelX + 12, y, btnWidth, 20)
            .build());
        final int textY = y + 30;
        this.addRenderableOnly((g, mx, my, t) -> {
            int line = 0;
            for (String key : new String[]{
                "bannerbound.townhall.walls.hint1",
                "bannerbound.townhall.walls.hint2",
                "bannerbound.townhall.walls.hint3"}) {
                g.drawWordWrap(this.font, Component.translatable(key),
                    panelX + 14, textY + line, PANEL_WIDTH - 28, 0xFF9A9A9A);
                line += 30;
            }
        });
    }

    private void switchTab(Tab tab) {
        this.activeTab = tab;
        this.statusesScrollY = 0;
        this.dictionaryScrollY = 0;
        this.statisticsScrollY = 0;
        this.statsMenuEntityId = -1; // close any open Statistics action popup
        this.dictionaryQuery = "";
        this.palettesScrollY = 0;
        this.suggestionsScroll = 0;
        this.tabSwitchedAtMs = net.minecraft.Util.getMillis();
        this.rebuildWidgets();
    }

    /** Lays out the navigation: the always-on core tabs (MAIN / STATUSES / LABOR) across the top
     *  edge, and the GOVERNANCE tabs (POLICIES / PALETTES / VOTES / SUGGESTIONS) as side bookmarks
     *  protruding from the panel's right edge — seven tabs no longer fit across the top, and
     *  grouping governance together reads better anyway. */
    private void addBookmarkTabs(int panelX, int panelY, boolean showPolicies) {
        // Tab bookmark ribbons wear the banner-derived identity — a primary→secondary sweep.
        int accent = primaryAccent;
        int accent2 = secondaryAccent;
        java.util.List<Tab> tabs = new java.util.ArrayList<>();
        tabs.add(Tab.MAIN);
        tabs.add(Tab.STATUSES);
        tabs.add(Tab.LABOR);   // labor allocation matters from anarchy onward — always shown
        tabs.add(Tab.DICTIONARY);

        final int edgePad = 6;
        final int gap = 3;
        int count = tabs.size();
        int tabW = (PANEL_WIDTH - 2 * edgePad - (count - 1) * gap) / count;
        int y = panelY - TAB_HEIGHT;
        for (int i = 0; i < count; i++) {
            final Tab tab = tabs.get(i);
            int x = panelX + edgePad + i * (tabW + gap);
            // Last tab stretches to the right edge so integer-division rounding doesn't leave a gap.
            int w = (i == count - 1) ? (panelX + PANEL_WIDTH - edgePad) - x : tabW;
            this.addRenderableWidget(new BookmarkTab(
                x, y, w, TAB_HEIGHT, panelY, tabLabel(tab), activeTab == tab, accent, accent2,
                () -> switchTab(tab)));
        }

        // Right-edge side tabs (post-Code-of-Laws governance), stacked below the title block.
        java.util.List<Tab> side = new java.util.ArrayList<>();
        if (showPolicies) {
            side.add(Tab.POLICIES);
            side.add(Tab.PALETTES);
        }
        if (isCouncil()) side.add(Tab.VOTES);             // council chat-votes mirror
        if (isChiefdom() && playerIsChief) side.add(Tab.SUGGESTIONS); // chief-only suggestion inbox
        // Statistics is a Mathematics unlock — the settlement can't read its own numbers before it.
        if (clientHasFlagEitherTree("bannerbound.unlock.statistics")) side.add(Tab.STATISTICS);
        side.add(Tab.DIPLOMACY);
        // Walls only become a doorway once Wall Building is researched — the planner, builder
        // trade, and wall pieces all ride that flag, so the tab would be a dead-end before it.
        if (clientHasFlagEitherTree("bannerbound.unlock.walls")) {
            side.add(Tab.WALLS);                          // wall planning hub (WALLS_PLAN.md)
        }
        final int sideW = 68;
        final int sideH = 18;
        final int sideGap = 3;
        final int panelRight = panelX + PANEL_WIDTH;
        int sy = panelY + 10;
        for (Tab tab : side) {
            // WALLS jumps straight into the Wall Preview — it IS the walls menu (playtest
            // 2026-06-12); Escape returns here via the preview's parent link.
            Runnable onClick = tab == Tab.WALLS
                ? () -> net.neoforged.neoforge.network.PacketDistributor.sendToServer(
                    new com.bannerbound.core.network.WallScreenPayloads.RequestWallPreview())
                : () -> switchTab(tab);
            this.addRenderableWidget(new SideTab(
                panelRight, sy, sideW, sideH, tabLabel(tab), activeTab == tab, accent, accent2,
                onClick));
            if (tab == Tab.SUGGESTIONS) {
                // Unread marker: a green "+" pinned past the tab's outer end whenever suggestions
                // changed since this player last opened the tab. Drawn per-frame (reads live
                // state) so a new suggestion lights it up without rebuilding the screen.
                final int badgeX = panelRight + sideW + 2;
                final int badgeY = sy + 5;
                this.addRenderableOnly((g, mx, my, t) -> {
                    if (activeTab != Tab.SUGGESTIONS && suggestionsUnread()) {
                        g.drawString(this.font, "+", badgeX, badgeY, 0xFF55FF55, true);
                    }
                });
            }
            sy += sideH + sideGap;
        }
    }

    private static Component tabLabel(Tab tab) {
        return switch (tab) {
            case MAIN -> Component.translatable("bannerbound.townhall.tab.main");
            case STATUSES -> Component.translatable("bannerbound.townhall.tab.statuses");
            case LABOR -> Component.translatable("bannerbound.townhall.tab.labor");
            case DICTIONARY -> Component.translatable("bannerbound.townhall.tab.dictionary");
            case STATISTICS -> Component.translatable("bannerbound.townhall.tab.statistics");
            case POLICIES -> Component.translatable("bannerbound.townhall.tab.policies");
            case PALETTES -> Component.translatable("bannerbound.townhall.tab.palettes");
            case VOTES -> Component.translatable("bannerbound.townhall.tab.votes");
            case SUGGESTIONS -> Component.translatable("bannerbound.townhall.tab.suggestions");
            case DIPLOMACY -> Component.translatable("bannerbound.townhall.tab.diplomacy");
            case WALLS -> Component.translatable("bannerbound.townhall.tab.walls");
        };
    }

    /** Signature of the Labor tab's STRUCTURE (job order + enabled flags + auto) at last build —
     *  used so a 1/s labor sync only rebuilds widgets when the structure actually changed, not on
     *  every count tick (the counts redraw live without a rebuild). */
    private String laborSignature = "";

    private static String laborSignatureNow() {
        return String.join(",", ClientLaborState.getJobIds())
            + "|" + ClientLaborState.getEnabled()
            + "|" + ClientLaborState.isAutoAssign();
    }

    /** Refresh the Labor tab when fresh state arrives from the server. Only rebuilds the widgets when
     *  the job order / enabled flags / auto changed (an edit or a new research unlock); plain count
     *  updates from the 1/s broadcast are picked up live by the row text, so they don't rebuild. */
    public void onLaborStateSynced() {
        if (activeTab != Tab.LABOR) return;
        String sig = laborSignatureNow();
        if (!sig.equals(laborSignature)) {
            laborSignature = sig;
            this.rebuildWidgets();
        }
    }

    /**
     * The Labor tab: the settlement's unlocked gatherer jobs ranked by priority (higher = more
     * workers), each with up/down reorder arrows, an On/Off enable toggle, and a current/target
     * worker count. A bottom toggle controls whether the settlement auto-distributes labor (locked
     * on in anarchy). Editing is allowed for any member in anarchy / a council, or the chief in a
     * chiefdom; others see the controls greyed out.
     */
    private void buildLaborTab(int panelX, int bodyTop, int btnWidth) {
        java.util.List<String> jobs = ClientLaborState.getJobIds();
        java.util.List<Boolean> enabled = ClientLaborState.getEnabled();
        java.util.List<Integer> caps = ClientLaborState.getCaps();
        boolean auto = ClientLaborState.isAutoAssign();
        boolean anarchy = governmentOrdinal == Settlement.Government.NONE.ordinal();
        boolean canEdit = anarchy
            || governmentOrdinal == Settlement.Government.COUNCIL.ordinal()
            || (governmentOrdinal == Settlement.Government.CHIEFDOM.ordinal()
                && (playerIsChief || ClientLaborState.isWorkloadShareActive()));
        // With auto-assign OFF the distributor doesn't run, so the priority list / caps / on-off do
        // nothing — grey them all out; only the Auto-assign toggle stays live to turn it back on.
        // (Anarchy forces auto on, so this never greys the list there.)
        boolean listActive = canEdit && auto;

        final int left = panelX + 12;
        this.addRenderableOnly((g, mx, my, t) -> g.drawString(this.font,
            Component.translatable("bannerbound.townhall.labor.header").withStyle(ChatFormatting.GRAY),
            left, bodyTop, 0xFFCCCCCC, false));

        if (jobs.isEmpty()) {
            this.addRenderableOnly((g, mx, my, t) -> g.drawString(this.font,
                Component.translatable("bannerbound.townhall.labor.none").withStyle(ChatFormatting.DARK_GRAY),
                left, bodyTop + 18, 0xFF888888, false));
            return;
        }

        final int rowH = 24;
        final int rowsTop = bodyTop + 14;
        final int toggleW = 40;
        final int toggleX = panelX + PANEL_WIDTH - 12 - toggleW;
        net.minecraft.client.gui.components.Tooltip reorderTip =
            net.minecraft.client.gui.components.Tooltip.create(
                Component.translatable("bannerbound.townhall.labor.reorder_tooltip"));
        net.minecraft.client.gui.components.Tooltip toggleTip =
            net.minecraft.client.gui.components.Tooltip.create(
                Component.translatable("bannerbound.townhall.labor.toggle_tooltip"));
        for (int i = 0; i < jobs.size(); i++) {
            final int idx = i;
            final String job = jobs.get(i);
            final boolean en = i < enabled.size() && enabled.get(i);
            final int ry = rowsTop + i * rowH;

            Button up = PolishButton.polished(Component.literal("▲"), b -> sendReorder(idx, idx - 1))
                .bounds(left, ry, 18, 20).tooltip(reorderTip).build();
            up.active = listActive && idx > 0;
            this.addRenderableWidget(up);
            Button down = PolishButton.polished(Component.literal("▼"), b -> sendReorder(idx, idx + 1))
                .bounds(left + 20, ry, 18, 20).tooltip(reorderTip).build();
            down.active = listActive && idx < jobs.size() - 1;
            this.addRenderableWidget(down);

            Button toggle = PolishButton.polished(
                Component.translatable(en ? "bannerbound.townhall.labor.on" : "bannerbound.townhall.labor.off")
                    .withStyle(en ? ChatFormatting.GREEN : ChatFormatting.RED),
                b -> sendToggleEnable(idx))
                .bounds(toggleX, ry, toggleW, 20).tooltip(toggleTip).build();
            toggle.active = listActive && !anarchy;   // locked in anarchy, and whenever auto is off
            this.addRenderableWidget(toggle);

            // Per-job worker cap textbox. Under a government it's editable: -1 = no limit, ≥0 = a hard
            // cap (surplus cascades to other jobs). In anarchy it's greyed and mirrors the live
            // head-count — you can only reorder priority, not cap.
            final int capW = 34;
            final int capX = toggleX - 6 - capW;
            final boolean capEditable = listActive && !anarchy;
            final net.minecraft.client.gui.components.EditBox capBox =
                new net.minecraft.client.gui.components.EditBox(this.font, capX, ry + 2, capW, 16,
                    Component.translatable("bannerbound.townhall.labor.cap"));
            capBox.setMaxLength(5);
            capBox.setFilter(str -> str.matches("-?\\d*"));
            int syncedCap = idx < caps.size() ? caps.get(idx) : -1;
            int curAtBuild = idx < ClientLaborState.getCurrent().size() ? ClientLaborState.getCurrent().get(idx) : 0;
            capBox.setValue(String.valueOf(anarchy ? curAtBuild : syncedCap));
            capBox.setEditable(capEditable);
            if (capEditable) {
                // Set the responder AFTER the initial value so the seed value doesn't fire a send.
                capBox.setResponder(str -> {
                    if (str.isEmpty() || str.equals("-")) return;
                    try {
                        int v = Integer.parseInt(str);
                        sendCapEdit(job, Math.max(-1, v));
                    } catch (NumberFormatException ignored) { /* mid-edit, ignore */ }
                });
                capBox.setTooltip(net.minecraft.client.gui.components.Tooltip.create(
                    Component.translatable("bannerbound.townhall.labor.cap_tooltip")));
            }
            this.addRenderableWidget(capBox);

            final int textX = left + 42;
            final int slashRightX = capX - 4;
            final int cy = ry + 6;
            // The label + live "current /" are drawn each frame (the server rebroadcasts ~1/s) so the
            // numbers tick as the distributor re-skills — without rebuilding the tab's widgets. In
            // anarchy the greyed cap box mirrors that live count.
            this.addRenderableOnly((g, mx, my, t) -> {
                g.drawString(this.font, jobLabel(job),
                    textX, cy, en ? 0xFFFFFFFF : 0xFF777777, false);
                java.util.List<Integer> cur = ClientLaborState.getCurrent();
                int c = idx < cur.size() ? cur.get(idx) : 0;
                if (anarchy && !capBox.isFocused()) capBox.setValue(String.valueOf(c));
                String prefix = c + " /";
                g.drawString(this.font, prefix, slashRightX - this.font.width(prefix), cy, 0xFFCCCCCC, false);
            });
        }
        this.laborSignature = laborSignatureNow();   // baseline for change-gated refresh

        // Auto-assign toggle. Locked ON in anarchy (no manual alternative without a government).
        int autoY = rowsTop + jobs.size() * rowH + 8;
        Button autoBtn = PolishButton.polished(
            Component.translatable(auto ? "bannerbound.townhall.labor.auto_on" : "bannerbound.townhall.labor.auto_off")
                .withStyle(auto ? ChatFormatting.GREEN : ChatFormatting.GRAY),
            b -> sendToggleAuto())
            .bounds(left, autoY, btnWidth, 20)
            .tooltip(net.minecraft.client.gui.components.Tooltip.create(
                Component.translatable("bannerbound.townhall.labor.auto_tooltip")))
            .build();
        autoBtn.active = canEdit && !anarchy;
        this.addRenderableWidget(autoBtn);

    }

    private static Component jobLabel(String jobId) {
        return Component.translatable("bannerbound.job." + jobId);
    }

    public void onDiplomacySynced() {
        if (activeTab == Tab.DIPLOMACY) this.rebuildWidgets();
    }

    private void buildDiplomacyTab(int panelX, int bodyTop, int bottomY, int btnWidth) {
        final int left = panelX + 12;
        final int right = panelX + PANEL_WIDTH - 12;
        this.addRenderableOnly((g, mx, my, t) -> g.drawString(this.font,
            Component.translatable("bannerbound.townhall.diplomacy.header").withStyle(ChatFormatting.GRAY),
            left, bodyTop, 0xFFCCCCCC, false));

        int y = bodyTop + 14;
        Button rally = PolishButton.polished(
            Component.translatable(com.bannerbound.core.client.ClientDiplomacyState.rallying()
                ? "bannerbound.townhall.diplomacy.rally_on"
                : "bannerbound.townhall.diplomacy.rally_off")
                .withStyle(com.bannerbound.core.client.ClientDiplomacyState.rallying()
                    ? ChatFormatting.GOLD : ChatFormatting.GRAY),
            b -> net.neoforged.neoforge.network.PacketDistributor.sendToServer(
                new com.bannerbound.core.network.DiplomacyActionPayload(
                    com.bannerbound.core.network.DiplomacyActionPayload.TOGGLE_RALLY, "")))
            .bounds(left, y, btnWidth, 18)
            .build();
        this.addRenderableWidget(rally);
        y += 23;

        int winnerCooldown = com.bannerbound.core.client.ClientDiplomacyState.winnerCooldownSeconds();
        if (winnerCooldown > 0) {
            final int lineY = y;
            this.addRenderableOnly((g, mx, my, t) -> g.drawString(this.font,
                Component.translatable("bannerbound.townhall.diplomacy.winner_cooldown",
                    formatSeconds(winnerCooldown)).withStyle(ChatFormatting.YELLOW),
                left, lineY, 0xFFFFD56C, false));
            y += 12;
        }

        java.util.List<com.bannerbound.core.network.DiplomacyStatePayload.Row> rows =
            com.bannerbound.core.client.ClientDiplomacyState.rows();
        java.util.List<com.bannerbound.core.network.DiplomacyStatePayload.BarbarianRow> camps =
            com.bannerbound.core.client.ClientDiplomacyState.barbarianRows();
        if (rows.isEmpty() && camps.isEmpty()) {
            final int lineY = y + 4;
            this.addRenderableOnly((g, mx, my, t) -> g.drawString(this.font,
                Component.translatable("bannerbound.townhall.diplomacy.none").withStyle(ChatFormatting.DARK_GRAY),
                left, lineY, 0xFF888888, false));
            return;
        }

        final int rowH = 42;
        final int actionW = 58;
        // Reserve room at the bottom for the read-only barbarian section so a long settlement list
        // can't crowd it out entirely (header + up to ~3 compact rows).
        final int barbReserve = camps.isEmpty() ? 0 : 14 + Math.min(camps.size(), 3) * 28;
        int maxRows = rows.isEmpty() ? 0
            : Math.min(rows.size(), Math.max(1, (bottomY - y - 12 - barbReserve) / rowH));
        for (int i = 0; i < maxRows; i++) {
            var row = rows.get(i);
            final int ry = y + i * rowH;
            final Component stance = diplomacyStance(row);
            final Component detail = diplomacyDetail(row);
            this.addRenderableOnly((g, mx, my, t) -> {
                g.fill(left, ry, right, ry + rowH - 4, 0x35000000);
                g.drawString(this.font, clip(Component.literal(row.name()), right - left - actionW - 14),
                    left + 5, ry + 5, 0xFFFFFFFF, false);
                g.drawString(this.font, stance, left + 5, ry + 16, 0xFFBBBBBB, false);
                g.drawString(this.font, clip(detail, right - left - actionW - 14),
                    left + 5, ry + 27, 0xFF999999, false);
            });
            if (row.cityState() && row.capturedTarget() && row.capturedByUs()) {
                // Captured city-state: choose its fate — three full-width buttons stacked so the
                // labels fit (Raze / Vassal / Annex(locked)).
                int bx = right - actionW - 5;
                this.addRenderableWidget(citystateResolveButton(row,
                    "bannerbound.townhall.diplomacy.raze", ChatFormatting.RED,
                    com.bannerbound.core.network.DiplomacyActionPayload.RAZE, bx, ry + 2, actionW, 12, true));
                this.addRenderableWidget(citystateResolveButton(row,
                    "bannerbound.townhall.diplomacy.vassal", ChatFormatting.GOLD,
                    com.bannerbound.core.network.DiplomacyActionPayload.VASSAL, bx, ry + 14, actionW, 12, true));
                this.addRenderableWidget(citystateResolveButton(row,
                    "bannerbound.townhall.diplomacy.annex", ChatFormatting.GRAY,
                    com.bannerbound.core.network.DiplomacyActionPayload.ANNEX, bx, ry + 26, actionW, 12, false));
                continue;
            }
            Button action = diplomacyActionButton(row, right - actionW - 5, ry + 6, actionW);
            if (action != null) this.addRenderableWidget(action);
            // Settlement-to-settlement trade entry: gated server-side (Bartering research, peace,
            // a show-for-trading stockpile). Gold + "!" while their latest terms await your answer.
            if (!row.cityState() && row.canTrade()
                    && row.stance() == com.bannerbound.core.network.DiplomacyStatePayload.STANCE_PEACE
                    && !(row.capturedTarget() && row.capturedByUs())) {
                boolean unread = row.tradeBadge() > 0;
                Button trade = PolishButton.polished(
                    Component.translatable(unread ? "bannerbound.townhall.diplomacy.trade_new"
                        : "bannerbound.townhall.diplomacy.trade")
                        .withStyle(unread ? ChatFormatting.GOLD : ChatFormatting.WHITE),
                    b -> net.neoforged.neoforge.network.PacketDistributor.sendToServer(
                        new com.bannerbound.core.network.RequestOpenTradePayload(row.settlementId())))
                    .bounds(right - actionW - 5, ry + 25, actionW, 14).build();
                this.addRenderableWidget(trade);
            }
            if (row.capturedTarget() && row.capturedByUs()) {
                Button keep = PolishButton.polished(
                    Component.translatable("bannerbound.townhall.diplomacy.keep"),
                    b -> {})
                    .bounds(right - actionW - 5, ry + 25, actionW, 14)
                    .build();
                keep.active = false;
                keep.setTooltip(net.minecraft.client.gui.components.Tooltip.create(
                    Component.translatable("bannerbound.townhall.diplomacy.keep_disabled")));
                this.addRenderableWidget(keep);
            }
        }
        int settlementsBottom = y + maxRows * rowH;
        if (rows.size() > maxRows) {
            final int more = rows.size() - maxRows;
            final int moreY = settlementsBottom;
            this.addRenderableOnly((g, mx, my, t) -> g.drawString(this.font,
                Component.translatable("bannerbound.townhall.diplomacy.more", more)
                    .withStyle(ChatFormatting.DARK_GRAY),
                left, moreY, 0xFF777777, false));
            settlementsBottom += 12;
        }
        buildBarbarianDiplomacyRows(left, right, camps, settlementsBottom + 4, bottomY);
    }

    /** Read-only barbarian-camp standing rows, drawn below the settlement/city-state rows. Barbarian
     *  diplomacy runs through envoys/parley, so these carry no action buttons — just visibility. */
    private void buildBarbarianDiplomacyRows(int left, int right,
            java.util.List<com.bannerbound.core.network.DiplomacyStatePayload.BarbarianRow> camps,
            int startY, int bottomY) {
        if (camps.isEmpty()) return;
        this.addRenderableOnly((g, mx, my, t) -> g.drawString(this.font,
            Component.translatable("bannerbound.townhall.diplomacy.barbarian_header")
                .withStyle(ChatFormatting.GRAY),
            left, startY, 0xFFCCCCCC, false));
        final int top = startY + 12;
        final int rowH = 28;
        int maxRows = Math.max(0, Math.min(camps.size(), (bottomY - top) / rowH));
        for (int i = 0; i < maxRows; i++) {
            final var c = camps.get(i);
            final int ry = top + i * rowH;
            final int color = 0xFF000000 | (c.nameColor() & 0xFFFFFF);
            final Component line = barbarianStanceLine(c);
            this.addRenderableOnly((g, mx, my, t) -> {
                g.fill(left, ry, right, ry + rowH - 4, 0x35000000);
                g.drawString(this.font, clip(Component.literal(c.name()), right - left - 10),
                    left + 5, ry + 4, color, false);
                g.drawString(this.font, line, left + 5, ry + 15, 0xFFBBBBBB, false);
            });
        }
        if (camps.size() > maxRows) {
            final int more = camps.size() - maxRows;
            final int moreY = top + maxRows * rowH;
            this.addRenderableOnly((g, mx, my, t) -> g.drawString(this.font,
                Component.translatable("bannerbound.townhall.diplomacy.more", more)
                    .withStyle(ChatFormatting.DARK_GRAY),
                left, moreY, 0xFF777777, false));
        }
    }

    /** "hostile  (-30) - 240m N" — coloured stance word, dark-gray score, gray distance/direction. */
    private Component barbarianStanceLine(
            com.bannerbound.core.network.DiplomacyStatePayload.BarbarianRow c) {
        com.bannerbound.core.barbarian.CampRelationState[] vals =
            com.bannerbound.core.barbarian.CampRelationState.values();
        int ord = Math.max(0, Math.min(vals.length - 1, c.relationOrdinal()));
        com.bannerbound.core.barbarian.CampRelationState st = vals[ord];
        ChatFormatting col = switch (st) {
            case HOSTILE -> ChatFormatting.RED;
            case FRIENDLY -> ChatFormatting.GREEN;
            default -> ChatFormatting.YELLOW;
        };
        Component stance = Component.translatable(
            "bannerbound.barbarian.relation." + st.name().toLowerCase(java.util.Locale.ROOT))
            .withStyle(col);
        String dist = c.distanceBlocks() < 0 ? "?" : c.distanceBlocks() + "m " + c.direction();
        return Component.empty().append(stance)
            .append(Component.literal("  (" + c.score() + ")").withStyle(ChatFormatting.DARK_GRAY))
            .append(Component.literal(" - " + dist).withStyle(ChatFormatting.GRAY));
    }

    /** One capture-resolution button for a captured city-state (Raze / Vassal / Annex). Annex is
     *  disabled until a free settlement slot exists (far-future Feudalism). */
    private Button citystateResolveButton(com.bannerbound.core.network.DiplomacyStatePayload.Row row,
                                          String key, ChatFormatting color, int action,
                                          int x, int y, int w, int h, boolean enabled) {
        Button b = PolishButton.polished(Component.translatable(key).withStyle(color),
            btn -> net.neoforged.neoforge.network.PacketDistributor.sendToServer(
                new com.bannerbound.core.network.DiplomacyActionPayload(action, row.settlementId())))
            .bounds(x, y, w, h)
            .build();
        b.active = enabled;
        if (action == com.bannerbound.core.network.DiplomacyActionPayload.ANNEX) {
            b.setTooltip(net.minecraft.client.gui.components.Tooltip.create(
                Component.translatable("bannerbound.townhall.diplomacy.annex_locked")));
        }
        return b;
    }

    private Button diplomacyActionButton(com.bannerbound.core.network.DiplomacyStatePayload.Row row,
                                         int x, int y, int w) {
        int action;
        Component label;
        boolean active = true;
        if (row.capturedTarget() && row.capturedByUs()) {
            action = com.bannerbound.core.network.DiplomacyActionPayload.RAZE;
            label = Component.translatable("bannerbound.townhall.diplomacy.raze")
                .withStyle(ChatFormatting.RED);
        } else if (row.stance() == com.bannerbound.core.network.DiplomacyStatePayload.STANCE_PEACE) {
            action = com.bannerbound.core.network.DiplomacyActionPayload.DECLARE_WAR;
            label = Component.translatable("bannerbound.townhall.diplomacy.declare")
                .withStyle(ChatFormatting.RED);
            active = row.cooldownSeconds() <= 0;
        } else if (row.stance() == com.bannerbound.core.network.DiplomacyStatePayload.STANCE_PENDING) {
            action = com.bannerbound.core.network.DiplomacyActionPayload.OFFER_PEACE;
            label = Component.translatable("bannerbound.townhall.diplomacy.pending");
            active = false;
        } else if (row.stance() == com.bannerbound.core.network.DiplomacyStatePayload.STANCE_WAR) {
            action = com.bannerbound.core.network.DiplomacyActionPayload.OFFER_PEACE;
            label = Component.translatable(row.peaceOfferedByThem()
                ? "bannerbound.townhall.diplomacy.accept_peace"
                : "bannerbound.townhall.diplomacy.offer_peace").withStyle(ChatFormatting.GREEN);
        } else {
            action = com.bannerbound.core.network.DiplomacyActionPayload.OFFER_PEACE;
            label = Component.translatable("bannerbound.townhall.diplomacy.captured");
            active = false;
        }
        Button button = PolishButton.polished(label,
            b -> net.neoforged.neoforge.network.PacketDistributor.sendToServer(
                new com.bannerbound.core.network.DiplomacyActionPayload(action, row.settlementId())))
            .bounds(x, y, w, 18)
            .build();
        button.active = active;
        return button;
    }

    private Component diplomacyStance(com.bannerbound.core.network.DiplomacyStatePayload.Row row) {
        if (row.cityState()) {
            Component label = switch (row.stance()) {
                case com.bannerbound.core.network.DiplomacyStatePayload.STANCE_PENDING ->
                    Component.translatable("bannerbound.townhall.diplomacy.stance.pending",
                        formatSeconds(row.pendingSeconds()));
                case com.bannerbound.core.network.DiplomacyStatePayload.STANCE_WAR ->
                    Component.translatable("bannerbound.townhall.diplomacy.stance.war");
                case com.bannerbound.core.network.DiplomacyStatePayload.STANCE_CAPTURED ->
                    Component.translatable("bannerbound.townhall.diplomacy.stance.captured");
                default -> Component.translatable("bannerbound.townhall.diplomacy.stance.citystate");
            };
            String dist = row.distanceBlocks() < 0 ? "?" : row.distanceBlocks() + "m " + row.direction();
            return Component.empty().append(label).append(" - ").append(dist);
        }
        Component stance = switch (row.stance()) {
            case com.bannerbound.core.network.DiplomacyStatePayload.STANCE_PENDING ->
                Component.translatable("bannerbound.townhall.diplomacy.stance.pending",
                    formatSeconds(row.pendingSeconds()));
            case com.bannerbound.core.network.DiplomacyStatePayload.STANCE_WAR ->
                Component.translatable("bannerbound.townhall.diplomacy.stance.war");
            case com.bannerbound.core.network.DiplomacyStatePayload.STANCE_CAPTURED ->
                Component.translatable("bannerbound.townhall.diplomacy.stance.captured");
            default -> Component.translatable("bannerbound.townhall.diplomacy.stance.peace");
        };
        String dist = row.distanceBlocks() < 0 ? "?" : row.distanceBlocks() + "m " + row.direction();
        return Component.empty().append(stance).append(" - ").append(dist);
    }

    private Component diplomacyDetail(com.bannerbound.core.network.DiplomacyStatePayload.Row row) {
        if (row.cityState()) {
            if (row.capturedTarget() && row.capturedByUs()) {
                return Component.translatable("bannerbound.townhall.diplomacy.citystate_captured_hint")
                    .withStyle(ChatFormatting.GOLD);
            }
            if (row.stance() == com.bannerbound.core.network.DiplomacyStatePayload.STANCE_PEACE) {
                Component market = citystateMarketLine(row);
                if (market != null) return market;
                return Component.translatable("bannerbound.townhall.diplomacy.citystate_hint")
                    .withStyle(ChatFormatting.DARK_GRAY);
            }
            return Component.empty();
        }
        if (row.cooldownSeconds() > 0) {
            return Component.translatable("bannerbound.townhall.diplomacy.cooldown",
                formatSeconds(row.cooldownSeconds()));
        }
        if (!row.objective().isBlank()) {
            return Component.literal(row.objective());
        }
        if (row.peaceOfferedByUs()) {
            return Component.translatable("bannerbound.townhall.diplomacy.peace_sent");
        }
        if (row.peaceOfferedByThem()) {
            return Component.translatable("bannerbound.townhall.diplomacy.peace_received");
        }
        return Component.translatable("bannerbound.townhall.diplomacy.no_objective");
    }

    /** "Seeks leather · Sells wheat, hides" — the city-state's live demands (gold, the player's
     *  market hook) and best-stocked wares (gray). Null when the economy hasn't produced yet, so the
     *  static Bartering hint shows instead. Unknown items render masked like every other surface. */
    private Component citystateMarketLine(com.bannerbound.core.network.DiplomacyStatePayload.Row row) {
        String seeks = itemNameList(row.seeks());
        String goods = itemNameList(row.goods());
        if (seeks.isEmpty() && goods.isEmpty()) return null;
        net.minecraft.network.chat.MutableComponent out = Component.empty();
        if (!seeks.isEmpty()) {
            out.append(Component.translatable("bannerbound.townhall.diplomacy.citystate_seeks", seeks)
                .withStyle(ChatFormatting.GOLD));
        }
        if (!goods.isEmpty()) {
            if (!seeks.isEmpty()) out.append(Component.literal(" · ").withStyle(ChatFormatting.DARK_GRAY));
            out.append(Component.translatable("bannerbound.townhall.diplomacy.citystate_goods", goods)
                .withStyle(ChatFormatting.GRAY));
        }
        return out;
    }

    /** Comma-joined display names for a comma-joined item-id list; unknown items masked. */
    private static String itemNameList(String csv) {
        if (csv == null || csv.isBlank()) return "";
        StringBuilder sb = new StringBuilder();
        for (String id : csv.split(",")) {
            net.minecraft.resources.ResourceLocation rl =
                net.minecraft.resources.ResourceLocation.tryParse(id.trim());
            if (rl == null) continue;
            net.minecraft.world.item.Item item =
                net.minecraft.core.registries.BuiltInRegistries.ITEM.get(rl);
            if (item == net.minecraft.world.item.Items.AIR) continue;
            String name = UnknownItemHelper.isKnown(item)
                ? new net.minecraft.world.item.ItemStack(item).getHoverName().getString()
                : UnknownItemHelper.unknownName().getString();
            if (sb.length() > 0) sb.append(", ");
            sb.append(name);
        }
        return sb.toString();
    }

    private static String formatSeconds(int seconds) {
        int m = Math.max(0, seconds) / 60;
        int s = Math.max(0, seconds) % 60;
        return String.format("%d:%02d", m, s);
    }

    /** The currently-disabled job ids, derived from the synced enabled flags. */
    private java.util.List<String> currentDisabledLabor() {
        java.util.List<String> jobs = ClientLaborState.getJobIds();
        java.util.List<Boolean> en = ClientLaborState.getEnabled();
        java.util.List<String> out = new java.util.ArrayList<>();
        for (int i = 0; i < jobs.size(); i++) {
            if (i >= en.size() || !en.get(i)) out.add(jobs.get(i));
        }
        return out;
    }

    private void sendLaborEdit(java.util.List<String> order, java.util.List<String> disabled,
            java.util.List<Integer> caps, boolean auto) {
        PacketDistributor.sendToServer(
            new com.bannerbound.core.network.ProposeLaborPriorityChangePayload(order, disabled, caps, auto));
    }

    /** Worker caps parallel to {@code order}, pulled from the synced state (default -1 = no limit). */
    private java.util.List<Integer> capsFor(java.util.List<String> order) {
        java.util.List<String> jobs = ClientLaborState.getJobIds();
        java.util.List<Integer> caps = ClientLaborState.getCaps();
        java.util.List<Integer> out = new java.util.ArrayList<>(order.size());
        for (String j : order) {
            int idx = jobs.indexOf(j);
            out.add(idx >= 0 && idx < caps.size() ? caps.get(idx) : -1);
        }
        return out;
    }

    private void sendReorder(int from, int to) {
        java.util.List<String> order = new java.util.ArrayList<>(ClientLaborState.getJobIds());
        if (from < 0 || to < 0 || from >= order.size() || to >= order.size()) return;
        java.util.Collections.swap(order, from, to);
        sendLaborEdit(order, currentDisabledLabor(), capsFor(order), ClientLaborState.isAutoAssign());
    }

    private void sendToggleEnable(int i) {
        java.util.List<String> jobs = ClientLaborState.getJobIds();
        if (i < 0 || i >= jobs.size()) return;
        String job = jobs.get(i);
        java.util.List<String> disabled = currentDisabledLabor();
        if (!disabled.remove(job)) disabled.add(job);   // toggle
        java.util.List<String> order = new java.util.ArrayList<>(jobs);
        sendLaborEdit(order, disabled, capsFor(order), ClientLaborState.isAutoAssign());
    }

    private void sendToggleAuto() {
        java.util.List<String> order = new java.util.ArrayList<>(ClientLaborState.getJobIds());
        sendLaborEdit(order, currentDisabledLabor(), capsFor(order), !ClientLaborState.isAutoAssign());
    }

    /** Set one job's worker cap (-1 = no limit) and push the full labor config to the server. */
    private void sendCapEdit(String jobId, int newCap) {
        java.util.List<String> order = new java.util.ArrayList<>(ClientLaborState.getJobIds());
        int idx = order.indexOf(jobId);
        if (idx < 0) return;
        java.util.List<Integer> caps = capsFor(order);
        caps.set(idx, newCap);
        sendLaborEdit(order, currentDisabledLabor(), caps, ClientLaborState.isAutoAssign());
    }

    // ─── Votes tab (council chat-votes mirror) ─────────────────────────────────────────────

    /** Live-refresh hook: vote state arrived (a cast / start / resolve). Counts + button states
     *  need a rebuild; the countdown ticks per-frame without one. */
    public void onChatVotesSynced() {
        if (activeTab == Tab.VOTES) this.rebuildWidgets();
    }

    private void buildVotesTab(int panelX, int bodyTop, int btnWidth) {
        final int left = panelX + 12;
        this.addRenderableOnly((g, mx, my, t) -> g.drawString(this.font,
            Component.translatable("bannerbound.townhall.votes.header").withStyle(ChatFormatting.GRAY),
            left, bodyTop, 0xFFCCCCCC, false));
        java.util.List<com.bannerbound.core.network.ChatVotesStatePayload.Entry> entries =
            ClientChatVotesState.getEntries();
        if (entries.isEmpty()) {
            this.addRenderableOnly((g, mx, my, t) -> g.drawString(this.font,
                Component.translatable("bannerbound.townhall.votes.none").withStyle(ChatFormatting.DARK_GRAY),
                left, bodyTop + 18, 0xFF888888, false));
            return;
        }
        final int rowH = 36;
        final int rowsTop = bodyTop + 14;
        final int voteBtnW = 36;
        final int noX = panelX + PANEL_WIDTH - 12 - voteBtnW;
        final int yesX = noX - voteBtnW - 4;
        for (int i = 0; i < entries.size(); i++) {
            final com.bannerbound.core.network.ChatVotesStatePayload.Entry e = entries.get(i);
            final int ry = rowsTop + i * rowH;
            final Component label = voteLabel(e);
            Button yesBtn = PolishButton.polished(
                Component.translatable("bannerbound.vote.yes").withStyle(ChatFormatting.GREEN),
                b -> PacketDistributor.sendToServer(
                    new com.bannerbound.core.network.CastChatVotePayload(e.voteId(), true)))
                .bounds(yesX, ry, voteBtnW, 18).build();
            yesBtn.active = e.myVote() != 1;
            this.addRenderableWidget(yesBtn);
            Button noBtn = PolishButton.polished(
                Component.translatable("bannerbound.vote.no").withStyle(ChatFormatting.RED),
                b -> PacketDistributor.sendToServer(
                    new com.bannerbound.core.network.CastChatVotePayload(e.voteId(), false)))
                .bounds(noX, ry, voteBtnW, 18).build();
            noBtn.active = e.myVote() != -1;
            this.addRenderableWidget(noBtn);
            this.addRenderableOnly((g, mx, my, t) -> {
                g.drawString(this.font, clip(label, yesX - left - 6), left, ry + 1, 0xFFFFFFFF, false);
                g.drawString(this.font, Component.translatable("bannerbound.townhall.votes.counts",
                        e.yes(), e.no(), ClientChatVotesState.secondsLeftNow(e)),
                    left, ry + 12, 0xFF999999, false);
            });
        }
    }

    private Component voteLabel(com.bannerbound.core.network.ChatVotesStatePayload.Entry e) {
        return switch (e.kind()) {
            case 0 -> Component.translatable("bannerbound.townhall.votes.row.exile",
                e.initiatorName(), e.targetName());
            case 2 -> Component.translatable("bannerbound.townhall.votes.row.diplomacy_war",
                e.initiatorName(), e.targetName());
            case 3 -> Component.translatable("bannerbound.townhall.votes.row.diplomacy_peace",
                e.initiatorName(), e.targetName());
            case 4 -> Component.translatable("bannerbound.townhall.votes.row.diplomacy_rally",
                e.initiatorName());
            case 5 -> Component.translatable("bannerbound.townhall.votes.row.diplomacy_raze",
                e.initiatorName(), e.targetName());
            default -> {
                boolean paper = era.ordinal() >= Era.MEDIEVAL.ordinal();
                yield Component.translatable(paper
                    ? "bannerbound.townhall.votes.row.paper" : "bannerbound.townhall.votes.row.tablet",
                    e.initiatorName());
            }
        };
    }

    // ─── Suggestions tab (the chiefdom's suggestion inbox) ─────────────────────────────────

    /** One aggregated suggestion row; {@code kind}/{@code id} match {@code IgnoreSuggestionPayload}. */
    private record SugRow(int kind, String id, Component label,
                          java.util.List<java.util.UUID> suggesters) {}

    /** First visible row index (mouse-wheel scrolled; the rows are widgets so scrolling rebuilds). */
    private int suggestionsScroll = 0;
    /** Signature of the suggestion set this CLIENT last saw with the tab open. Static so it
     *  survives screen close/reopen within the session; the tab's green "+" compares against it. */
    private static String lastSeenSuggestionsSig = "";

    /** Live-refresh hook for the exile/tablet suggestion sync. (The other four kinds piggyback on
     *  the policy/palette/research syncs, whose hooks rebuild their own tabs — this tab re-reads
     *  every cache on rebuild, so refreshing here when active keeps all six current.) */
    public void onSuggestionsSynced() {
        if (activeTab == Tab.SUGGESTIONS) this.rebuildWidgets();
    }

    private boolean suggestionsUnread() {
        return !suggestionsSignatureNow().equals(lastSeenSuggestionsSig);
    }

    /** Order-insensitive signature of all six suggestion sources (sorted parts → map iteration
     *  order can't fake an unread state). Empty sources contribute NOTHING, so "no suggestions at
     *  all" signs as "" and matches the fresh-launch {@link #lastSeenSuggestionsSig} — otherwise
     *  the unread "+" would light up on every world join with an empty inbox. */
    private static String suggestionsSignatureNow() {
        java.util.List<String> parts = new java.util.ArrayList<>();
        ClientSuggestionState.getAllScience().forEach((id, s) -> {
            if (!s.isEmpty()) parts.add("s:" + id + "=" + s.size()); });
        ClientSuggestionState.getAllCulture().forEach((id, s) -> {
            if (!s.isEmpty()) parts.add("c:" + id + "=" + s.size()); });
        ClientPolicyState.getAllSuggestions().forEach((id, s) -> {
            if (!s.isEmpty()) parts.add("p:" + id + "=" + s.size()); });
        ClientPaletteState.getAllSuggestions().forEach((id, s) -> {
            if (!s.isEmpty()) parts.add("a:" + id + "=" + s.size()); });
        for (com.bannerbound.core.network.ExtraSuggestionsPayload.ExileEntry e
                : ClientExtraSuggestionsState.getExiles()) {
            if (!e.suggesters().isEmpty()) parts.add("e:" + e.citizenUuid() + "=" + e.suggesters().size());
        }
        int tablet = ClientExtraSuggestionsState.getTabletSuggesters().size();
        if (tablet > 0) parts.add("t=" + tablet);
        java.util.Collections.sort(parts);
        return String.join(";", parts);
    }

    /** Aggregates every active suggestion (all six kinds) into display rows. */
    private java.util.List<SugRow> buildSuggestionRows() {
        java.util.List<SugRow> rows = new java.util.ArrayList<>();
        ClientSuggestionState.getAllScience().forEach((id, sug) -> {
            com.bannerbound.core.api.research.ResearchDefinition def =
                ClientResearchState.getTree().get(id);
            rows.add(new SugRow(com.bannerbound.core.network.IgnoreSuggestionPayload.KIND_SCIENCE, id,
                rowLabel(sug, "bannerbound.townhall.suggestions.row.research",
                    def == null ? id : def.name()), sug));
        });
        ClientSuggestionState.getAllCulture().forEach((id, sug) -> {
            com.bannerbound.core.api.research.ResearchDefinition def =
                ClientCultureState.getTree().get(id);
            rows.add(new SugRow(com.bannerbound.core.network.IgnoreSuggestionPayload.KIND_CULTURE, id,
                rowLabel(sug, "bannerbound.townhall.suggestions.row.research",
                    def == null ? id : def.name()), sug));
        });
        ClientPolicyState.getAllSuggestions().forEach((id, sug) ->
            rows.add(new SugRow(com.bannerbound.core.network.IgnoreSuggestionPayload.KIND_POLICY, id,
                rowLabel(sug, "bannerbound.townhall.suggestions.row.policy", policyName(id)), sug)));
        ClientPaletteState.getAllSuggestions().forEach((id, sug) ->
            rows.add(new SugRow(com.bannerbound.core.network.IgnoreSuggestionPayload.KIND_PALETTE, id,
                rowLabel(sug, "bannerbound.townhall.suggestions.row.palette",
                    ClientPaletteState.nameOf(id)), sug)));
        for (com.bannerbound.core.network.ExtraSuggestionsPayload.ExileEntry e
                : ClientExtraSuggestionsState.getExiles()) {
            rows.add(new SugRow(com.bannerbound.core.network.IgnoreSuggestionPayload.KIND_EXILE,
                e.citizenUuid().toString(),
                rowLabel(e.suggesters(), "bannerbound.townhall.suggestions.row.exile",
                    e.citizenName()), e.suggesters()));
        }
        java.util.List<java.util.UUID> tablet = ClientExtraSuggestionsState.getTabletSuggesters();
        if (!tablet.isEmpty()) {
            boolean paper = era.ordinal() >= Era.MEDIEVAL.ordinal();
            rows.add(new SugRow(com.bannerbound.core.network.IgnoreSuggestionPayload.KIND_TABLET, "",
                rowLabel(tablet, paper
                    ? "bannerbound.townhall.suggestions.row.paper"
                    : "bannerbound.townhall.suggestions.row.tablet", null), tablet));
        }
        return rows;
    }

    /** "<first suggester> suggests <arg>", with a dark " (+N)" when more members co-suggested. */
    private Component rowLabel(java.util.List<java.util.UUID> suggesters, String key, Object arg) {
        String who = suggesterName(suggesters.isEmpty() ? null : suggesters.get(0));
        net.minecraft.network.chat.MutableComponent label = arg == null
            ? Component.translatable(key, who) : Component.translatable(key, who, arg);
        if (suggesters.size() > 1) {
            label.append(Component.literal(" (+" + (suggesters.size() - 1) + ")")
                .withStyle(ChatFormatting.DARK_GRAY));
        }
        return label;
    }

    private String suggesterName(java.util.UUID id) {
        if (id != null && this.minecraft != null && this.minecraft.getConnection() != null) {
            net.minecraft.client.multiplayer.PlayerInfo info =
                this.minecraft.getConnection().getPlayerInfo(id);
            if (info != null) return info.getProfile().getName();
        }
        return Component.translatable("bannerbound.townhall.suggestions.someone").getString();
    }

    private void buildSuggestionsTab(int panelX, int bodyTop, int bodyBottom, int btnWidth) {
        final int left = panelX + 12;
        this.addRenderableOnly((g, mx, my, t) -> g.drawString(this.font,
            Component.translatable("bannerbound.townhall.suggestions.header").withStyle(ChatFormatting.GRAY),
            left, bodyTop, 0xFFCCCCCC, false));
        java.util.List<SugRow> rows = buildSuggestionRows();
        // Opening (or rebuilding) the tab marks everything as seen — clears the green "+".
        lastSeenSuggestionsSig = suggestionsSignatureNow();
        if (rows.isEmpty()) {
            this.addRenderableOnly((g, mx, my, t) -> g.drawString(this.font,
                Component.translatable("bannerbound.townhall.suggestions.none").withStyle(ChatFormatting.DARK_GRAY),
                left, bodyTop + 18, 0xFF888888, false));
            return;
        }
        final int rowH = 24;
        final int rowsTop = bodyTop + 14;
        final int visible = Math.max(1, (bodyBottom - rowsTop) / rowH);
        suggestionsScroll = Math.max(0, Math.min(suggestionsScroll, rows.size() - visible));
        final int ignoreW = 44;
        final int resolveW = 52;
        final int ignoreX = panelX + PANEL_WIDTH - 12 - ignoreW;
        final int resolveX = ignoreX - resolveW - 4;
        for (int v = 0; v < visible && suggestionsScroll + v < rows.size(); v++) {
            final SugRow row = rows.get(suggestionsScroll + v);
            final int ry = rowsTop + v * rowH;
            Button resolve = PolishButton.polished(
                Component.translatable("bannerbound.townhall.suggestions.resolve"),
                b -> resolveSuggestion(row))
                .bounds(resolveX, ry, resolveW, 18).build();
            resolve.active = playerIsChief;
            this.addRenderableWidget(resolve);
            Button ignore = PolishButton.polished(
                Component.translatable("bannerbound.townhall.suggestions.ignore")
                    .withStyle(ChatFormatting.GRAY),
                b -> PacketDistributor.sendToServer(
                    new com.bannerbound.core.network.IgnoreSuggestionPayload(row.kind(), row.id())))
                .bounds(ignoreX, ry, ignoreW, 18).build();
            ignore.active = playerIsChief;
            this.addRenderableWidget(ignore);
            final java.util.UUID face = row.suggesters().isEmpty() ? null : row.suggesters().get(0);
            this.addRenderableOnly((g, mx, my, t) -> {
                int textX = left;
                if (face != null) {
                    net.minecraft.resources.ResourceLocation skin = resolveSkin(face);
                    if (skin != null) {
                        net.minecraft.client.gui.components.PlayerFaceRenderer.draw(
                            g, new net.minecraft.client.resources.PlayerSkin(
                                skin, null, null, null,
                                net.minecraft.client.resources.PlayerSkin.Model.WIDE, true),
                            left, ry + 5, 8);
                    } else {
                        g.fill(left, ry + 5, left + 8, ry + 13, 0xFF338833);
                    }
                    textX += 12;
                }
                g.drawString(this.font, clip(row.label(), resolveX - textX - 6),
                    textX, ry + 5, 0xFFFFFFFF, false);
            });
        }
        if (rows.size() > visible) {
            final int more = rows.size();
            this.addRenderableOnly((g, mx, my, t) -> g.drawString(this.font,
                Component.translatable("bannerbound.townhall.suggestions.scroll_hint",
                    suggestionsScroll + 1, Math.min(suggestionsScroll + visible, more), more),
                left, bodyBottom - 10, 0xFF777777, false));
        }
    }

    /** [Resolve]: route the chief to where the suggestion can be acted on — the research tree
     *  focused on the node, the Policies/Palettes tab, the Citizens list, or (tablet) just issue
     *  it directly since there's no deeper menu for it. */
    private void resolveSuggestion(SugRow row) {
        // Clearing on resolve: the chief has taken this suggestion in hand, so it leaves the
        // inbox immediately (server clears it for everyone + re-syncs). Players may re-suggest.
        PacketDistributor.sendToServer(
            new com.bannerbound.core.network.IgnoreSuggestionPayload(row.kind(), row.id()));
        switch (row.kind()) {
            case com.bannerbound.core.network.IgnoreSuggestionPayload.KIND_SCIENCE -> {
                ResearchScreen.requestFocus(row.id(), false);
                if (this.minecraft != null) this.minecraft.setScreen(new ResearchScreen(this));
            }
            case com.bannerbound.core.network.IgnoreSuggestionPayload.KIND_CULTURE -> {
                ResearchScreen.requestFocus(row.id(), true);
                if (this.minecraft != null) this.minecraft.setScreen(new ResearchScreen(this));
            }
            case com.bannerbound.core.network.IgnoreSuggestionPayload.KIND_POLICY ->
                switchTab(Tab.POLICIES);
            case com.bannerbound.core.network.IgnoreSuggestionPayload.KIND_PALETTE ->
                switchTab(Tab.PALETTES);
            case com.bannerbound.core.network.IgnoreSuggestionPayload.KIND_EXILE ->
                PacketDistributor.sendToServer(new RequestSettlementCitizensPayload());
            case com.bannerbound.core.network.IgnoreSuggestionPayload.KIND_TABLET ->
                PacketDistributor.sendToServer(new GetRegistrationTabletPayload());
            default -> { }
        }
    }

    // BookmarkTab + SideTab were born here as inner classes; they now live as shared widgets in
    // this package (every tabbed screen uses them), constructed in addBookmarkTabs above.

    /** True when this player can only SUGGEST policies (Chiefdom non-chief): suggestion is a
     *  single click on a row in the Available list, not a drag-stage-Confirm. */
    private boolean isSuggestMode() {
        return isChiefdom() && weightyBlocked();
    }

    /** Live-refresh hook: the server pushed new policy state (a vote was cast/resolved, or a
     *  suggestion toggled) while this screen is open. Rebuild so the Policies-tab action buttons
     *  (Agree/Disagree, Confirm) reflect the new state immediately instead of lingering stale.
     *  Skipped mid-drag so an incoming sync doesn't drop a policy the player is holding. */
    public void onPolicyStateSynced() {
        if (activeTab == Tab.POLICIES && draggingPolicyId == null) {
            this.rebuildWidgets();
        }
    }

    /** Live-refresh hook for palette state — twin of {@link #onPolicyStateSynced}. */
    public void onPaletteStateSynced() {
        if (activeTab == Tab.PALETTES && draggingPaletteId == null) {
            this.rebuildWidgets();
        }
    }

    /** Client-side mirror of ResearchManager.hasFlagEitherTree — both trees' definitions
     *  (including flags) are synced, so capability gates can drive UI without a round trip. */
    private static boolean clientHasFlagEitherTree(String flag) {
        if (ClientResearchState.hasFlag(flag)) return true;
        for (String id : ClientCultureState.getCompleted()) {
            com.bannerbound.core.api.research.ResearchDefinition def =
                ClientCultureState.getTree().get(id);
            if (def != null && def.unlocksFlags().contains(flag)) return true;
        }
        return false;
    }

    private void buildMainTab(int panelX, int bodyTop, int btnWidth) {
        final int statsTop = bodyTop + 4;
        // A faithful settlement gets a third stat block (Devotion) below Culture — the
        // divider + button stack shift down with it so nothing overlaps.
        final boolean faithful = ClientFaithState.hasFaith();
        final int faithExtra = faithful ? 34 : 0;
        final int statsBottom = statsTop + 96 + faithExtra;
        // Six button rows (the sixth is Step Down for a Chief, Leave Settlement otherwise) sit above
        // the shared Cancel button at the panel bottom. When the faith Devotion block is present the
        // stack starts ~34px lower, which made the sixth row overlap Cancel — so with faith we
        // tighten both the gap above the stack and the inter-row gap just enough to clear it.
        final int firstBtnY = statsBottom + (faithful ? 8 : 12);
        final int gap = faithful ? 20 : 22;

        // Stats panel divider — identity gradient, dimmed so it trims rather than shouts.
        final java.util.List<Integer> dimmedAccents = new java.util.ArrayList<>(identityAccents.size());
        for (int accentColor : identityAccents) {
            dimmedAccents.add(PolishedScreen.blendArgb(0xFF2A2A2A, accentColor, 0.55f));
        }
        this.addRenderableOnly((graphics, mouseX, mouseY, partialTick) ->
            PolishedScreen.drawIdentityGradient(graphics, panelX + 8, statsBottom + 4,
                PANEL_WIDTH - 16, 1, dimmedAccents));

        // Stats panel: population + food / culture lines + progress bars toward next citizen.
        this.addRenderableOnly((graphics, mouseX, mouseY, partialTick) ->
            drawStatsPanel(graphics, panelX + 14, statsTop, PANEL_WIDTH - 28, mouseX, mouseY));

        // Heraldry (culture) splits the Research row: the Banner editor button only exists
        // once the flag is unlocked, so pre-Heraldry settlements see the familiar full-width
        // Research button and nothing new. Flag check is client-side (full tree definitions
        // are synced); the server re-validates on request anyway.
        boolean heraldry = clientHasFlagEitherTree(
            com.bannerbound.core.network.ServerPayloadHandler.HERALDRY_FLAG);
        int researchW = heraldry ? btnWidth - 64 : btnWidth;
        this.addRenderableWidget(PolishButton.polished(
            Component.translatable("bannerbound.townhall.menu.research"),
            btn -> this.minecraft.setScreen(new ResearchScreen(this)))
            .bounds(panelX + 12, firstBtnY, researchW, 20)
            .build());
        if (heraldry) {
            this.addRenderableWidget(PolishButton.polished(
                Component.translatable("bannerbound.townhall.menu.banner"),
                btn -> PacketDistributor.sendToServer(
                    com.bannerbound.core.network.RequestBannerEditorPayload.INSTANCE))
                .bounds(panelX + 12 + researchW + 4, firstBtnY, btnWidth - researchW - 4, 20)
                .build());
        }

        // Faith shares the Citizens row once relevant (founding window open OR a faith is
        // followed) — same split-row trick as Research/Banner above, so the button stack
        // never re-flows. Window open → ask the server for the Choose-Faith snapshot;
        // already faithful → local info screen from ClientFaithState.
        boolean faithRelevant = ClientFaithState.choiceWindowOpen() || ClientFaithState.hasFaith();
        int citizensW = faithRelevant ? btnWidth - 64 : btnWidth;
        this.addRenderableWidget(PolishButton.polished(
            Component.translatable("bannerbound.townhall.menu.citizens"),
            btn -> PacketDistributor.sendToServer(new RequestSettlementCitizensPayload()))
            .bounds(panelX + 12, firstBtnY + gap, citizensW, 20)
            .build());
        if (faithRelevant) {
            this.addRenderableWidget(PolishButton.polished(
                Component.translatable("bannerbound.townhall.menu.faith"),
                btn -> {
                    if (ClientFaithState.choiceWindowOpen()) {
                        PacketDistributor.sendToServer(
                            new com.bannerbound.core.network.RequestFaithScreenPayload());
                    } else {
                        this.minecraft.setScreen(new FaithInfoScreen(this));
                    }
                })
                .bounds(panelX + 12 + citizensW + 4, firstBtnY + gap, btnWidth - citizensW - 4, 20)
                .build());
        }

        Button tabletButton = PolishButton.polished(
            Component.translatable("bannerbound.townhall.menu.get_tablet", tabletsIssued, tabletCapacity),
            btn -> {
                PacketDistributor.sendToServer(new GetRegistrationTabletPayload());
                this.onClose();
            })
            .bounds(panelX + 12, firstBtnY + gap * 2, btnWidth, 20)
            .build();
        tabletButton.active = tabletsIssued < tabletCapacity;
        this.addRenderableWidget(tabletButton);

        // Expand Territory stays open to EVERY member regardless of government — non-chiefs
        // in Chiefdom click chunks to suggest, Council members click to vote, Chief or
        // NONE-government members claim directly. The dispatch happens inside
        // TerritoryService.tryClaim per-click, so the button just opens the screen.
        Button expandButton = PolishButton.polished(
            Component.translatable("bannerbound.townhall.menu.expand_territory"),
            btn -> PacketDistributor.sendToServer(new RequestExpandTerritoryPayload()))
            .bounds(panelX + 12, firstBtnY + gap * 3, btnWidth, 20)
            .build();
        this.addRenderableWidget(expandButton);

        // Disband is a governed action — there's no one with the authority to dissolve a lawless
        // band, so the button only appears once a government is enacted. In anarchy the only exit is
        // Leave Settlement (a settlement collapses naturally once its last member leaves).
        boolean anarchy = governmentOrdinal == Settlement.Government.NONE.ordinal();
        if (!anarchy) {
            Component disbandLabel;
            if (disbandVoteActive && disbandTotalMembers > 1) {
                disbandLabel = Component.translatable(
                    "bannerbound.townhall.menu.disband_vote",
                    disbandVoteCount, disbandTotalMembers);
            } else {
                disbandLabel = Component.translatable("bannerbound.townhall.menu.disband");
            }
            Button disbandButton = PolishButton.polished(disbandLabel, btn -> {
                    PacketDistributor.sendToServer(new DisbandSettlementPayload());
                    this.onClose();
                })
                .bounds(panelX + 12, firstBtnY + gap * 4, btnWidth, 20)
                .build();
            // Chiefdom: only the seated Chief may disband; otherwise greyed out alongside the
            // existing "already voted" lock. Council keeps the original behaviour.
            disbandButton.active = !weightyBlocked() && !(disbandVoteActive && playerHasVotedToDisband);
            this.addRenderableWidget(disbandButton);
        }

        // A seated Chief gets Step Down (Step 15) — sends QuitChiefPayload; the server clears
        // chiefPlayerId, re-opens the election window, recomputes the regent. EVERYONE ELSE gets
        // Leave Settlement: an individual member walks out (loses research access, collapses a
        // now-empty settlement). A chief can't leave directly — they must Step Down first. Sits in
        // the disband row (gap*4) in anarchy where there's no Disband button, else the row below it.
        int leaveRow = anarchy ? 4 : 5;
        if (playerIsChief) {
            this.addRenderableWidget(new StepDownButton(panelX + 12, firstBtnY + gap * leaveRow, btnWidth, 20));
        } else {
            this.addRenderableWidget(new LeaveButton(panelX + 12, firstBtnY + gap * leaveRow, btnWidth, 20));
        }

        // Settlement unrest warnings (homelessness / strikes / coup) — a small panel hung off the
        // panel's LEFT edge so it never collides with the packed stats + button stack. Data is the
        // server's single source of truth (SettlementManager.settlementWarnings), held client-side
        // in ClientSettlementWarningsState. Drawn each frame so it reflects the latest sync.
        final int warnTop = statsTop;
        this.addRenderableOnly((g, mx, my, t) ->
            drawWarningsPanel(g, panelX, warnTop));

        // Hint when this player is a non-Chief in a Chiefdom, so the greyed-out Disband button
        // isn't mysterious. The sixth row (Leave) sits at gap*5, so place the hint just below it.
        if (weightyBlocked()) {
            int hintY = firstBtnY + gap * 5 + 26;
            this.addRenderableOnly((g, mx, my, t) ->
                g.drawCenteredString(this.font,
                    Component.translatable("bannerbound.townhall.chief_only_hint")
                        .withStyle(ChatFormatting.GRAY),
                    panelX + PANEL_WIDTH / 2, hintY, 0xFFAAAAAA));
        }
    }

    /**
     * Draws the settlement-unrest warnings as a small parchment-dark card hung off the LEFT edge
     * of the main panel (so it never collides with the packed stats + button stack). Each line is
     * the server's already-styled warning Component, word-wrapped to the card width and rendered in
     * a warm amber. An empty warning list shows a muted "all is well" line instead. The card width
     * + position are fixed; the height grows with the wrapped line count.
     */
    private void drawWarningsPanel(GuiGraphics graphics, int panelX, int top) {
        final int cardW = 130;
        final int pad = 6;
        final int textW = cardW - pad * 2;
        final int cardX = panelX - cardW - 6;   // hangs to the left of the main panel
        final int lineH = 10;

        java.util.List<Component> warnings = ClientSettlementWarningsState.get();

        // Heading + body lines (pre-wrapped) so we can size the card before drawing.
        java.util.List<net.minecraft.util.FormattedCharSequence> body = new java.util.ArrayList<>();
        if (warnings.isEmpty()) {
            body.addAll(this.font.split(
                Component.translatable("bannerbound.townhall.warnings.none")
                    .withStyle(ChatFormatting.GRAY), textW));
        } else {
            for (Component w : warnings) {
                body.addAll(this.font.split(w, textW));
            }
        }

        final int headingH = 12;
        final int cardH = pad + headingH + body.size() * lineH + pad;

        // Card background + identity-tinted outline, matching the main panel's chrome.
        graphics.fill(cardX, top, cardX + cardW, top + cardH, 0xE0101010);
        graphics.renderOutline(cardX, top, cardW, cardH,
            PolishedScreen.blendArgb(0xFF606060,
                warnings.isEmpty() ? 0xFF6ABE6A : 0xFFD8A23A, 0.45f));

        int y = top + pad;
        graphics.drawString(this.font,
            Component.translatable("bannerbound.townhall.warnings.heading")
                .withStyle(ChatFormatting.GOLD),
            cardX + pad, y, 0xFFFFD56C, false);
        y += headingH;

        int lineColor = warnings.isEmpty() ? 0xFFAAAAAA : 0xFFE0A94A;
        for (net.minecraft.util.FormattedCharSequence line : body) {
            graphics.drawString(this.font, line, cardX + pad, y, lineColor, false);
            y += lineH;
        }
    }

    private void buildStatusesTab(int panelX, int bodyTop, int bodyBottom) {
        // The list itself is drawn in a render callback so it can scissor-clip + handle scroll.
        // We don't use a separate widget here — drawing inline keeps the per-row layout simple
        // and lets us reuse the existing OutlinedText / icon helpers.
        this.addRenderableOnly((graphics, mouseX, mouseY, partialTick) ->
            drawStatusesList(graphics, panelX + 12, bodyTop + 4, PANEL_WIDTH - 24, bodyBottom - bodyTop - 8));
    }

    // ─── Statistics tab (Mathematics unlock) — read-only analytics ──────────────────────────────
    private static final int STAT_GOOD_COLOR = 0xFF6ABE6A;
    private static final int STAT_NEUTRAL_COLOR = 0xFFB8B8B8;
    private static final int STAT_BLOCKED_COLOR = 0xFFD0705E;
    private static final int STAT_HEADER_COLOR = 0xFFE2C065;

    /** One row of the Statistics detail list. {@code actionEntityId} >= 0 means the row is a clickable
     *  citizen (network entity id) the player can reassign/release; -1 = a header or non-actionable row. */
    private record StatRow(Component text, int actionEntityId) {}

    // ── Statistics tab interaction (Phase 3): click a worker row → action popup ──
    private java.util.List<StatRow> statsRows = java.util.List.of(); // cached from last draw, for hit-testing
    private int statsListX, statsListY, statsListW, statsListH;      // cached list rect (panel space)
    private int statsMenuEntityId = -1;                              // citizen the popup is acting on (-1 = closed)
    private int statsMenuX, statsMenuY;                              // popup anchor (panel space)
    private static final int STATS_MENU_W = 122;
    private static final int STATS_MENU_ROW_H = 12;

    private void buildStatisticsTab(int panelX, int bodyTop, int bodyBottom) {
        // Drawn in a render callback (scissor-clipped + scrollable). Mouse coords are mapped into
        // panel space (virtualX/Y) so hover/click hit-testing matches the auto-fit pose.
        this.addRenderableOnly((graphics, mouseX, mouseY, partialTick) ->
            drawStatisticsPanel(graphics, panelX + 12, bodyTop + 4, PANEL_WIDTH - 24, bodyBottom - bodyTop - 8,
                (int) virtualX(mouseX), (int) virtualY(mouseY)));
    }

    private void drawStatisticsPanel(GuiGraphics graphics, int x, int y, int width, int height,
                                     int mouseX, int mouseY) {
        // ── Top: food-economy summary (fixed block) ──
        double net = ClientPopulationState.getFoodPerSecond();
        double consumption = ClientPopulationState.getFoodConsumptionPerSecond();
        double storedValue = ClientPopulationState.getStoredFoodValue();
        double foodStored = ClientPopulationState.getFoodStored();
        java.util.Map<String, Double> sourceRates = ClientPopulationState.getFoodSourceRates();

        int ly = y + 2;
        graphics.drawString(this.font, Component.translatable("bannerbound.townhall.statistics.economy_header")
            .withStyle(ChatFormatting.AQUA), x, ly, STAT_HEADER_COLOR, false);
        ly += 13;
        int netColor = net >= 0 ? STAT_GOOD_COLOR : STAT_BLOCKED_COLOR;
        graphics.drawString(this.font, Component.translatable("bannerbound.townhall.statistics.net",
            String.format("%+.2f", net)), x + 4, ly, netColor, false);
        ly += 11;
        if (consumption > 0.0001) {
            graphics.drawString(this.font, Component.translatable("bannerbound.townhall.statistics.consumption",
                String.format("%.2f", consumption)), x + 4, ly, STAT_BLOCKED_COLOR, false);
            ly += 11;
            // Runway: with a net deficit, how long the food bar lasts (in-game days, 1200s/day).
            if (net < -0.0001) {
                double days = foodStored / (-net) / 1200.0;
                graphics.drawString(this.font, Component.translatable("bannerbound.townhall.statistics.runway",
                    String.format("%.1f", days)), x + 4, ly, STAT_BLOCKED_COLOR, false);
            } else {
                graphics.drawString(this.font, Component.translatable("bannerbound.townhall.statistics.sustainable"),
                    x + 4, ly, STAT_GOOD_COLOR, false);
            }
            ly += 11;
        }
        graphics.drawString(this.font, Component.translatable("bannerbound.townhall.statistics.stored",
            String.format("%.1f", storedValue)), x + 4, ly, STAT_NEUTRAL_COLOR, false);
        ly += 13;
        // Per-source income (food-stuff/min).
        java.util.List<java.util.Map.Entry<String, Double>> sources = sourceRates.entrySet().stream()
            .filter(e -> e.getValue() != null && e.getValue() > 0.0001)
            .sorted((a, b) -> Double.compare(b.getValue(), a.getValue())).toList();
        if (!sources.isEmpty()) {
            graphics.drawString(this.font, Component.translatable("bannerbound.townhall.statistics.income_header")
                .withStyle(ChatFormatting.AQUA), x, ly, STAT_HEADER_COLOR, false);
            ly += 12;
            for (java.util.Map.Entry<String, Double> e : sources) {
                graphics.drawString(this.font, Component.translatable("bannerbound.townhall.statistics.income_line",
                    foodSourceName(e.getKey()), String.format("%.1f", e.getValue() * 60.0)),
                    x + 4, ly, STAT_NEUTRAL_COLOR, false);
                ly += 11;
            }
        }

        // ── Scrollable detail list: workshops + workforce (section headers are in-list rows) ──
        ly += 4;
        int listY = ly;
        int listH = (y + height) - listY;
        if (listH < STATISTICS_ROW_HEIGHT) return;

        java.util.List<StatRow> rows = buildStatsListRows();
        // Cache the list rect + rows so mouseClicked can hit-test clickable worker rows.
        statsRows = rows;
        statsListX = x; statsListY = listY; statsListW = width; statsListH = listH;
        graphics.fill(x, listY, x + width, listY + listH, 0xFF181818);
        graphics.renderOutline(x, listY, width, listH, 0xFF2A2A2A);
        if (rows.isEmpty()) {
            graphics.drawCenteredString(this.font,
                Component.translatable("bannerbound.townhall.statistics.no_workers"),
                x + width / 2, listY + listH / 2 - 4, 0xFF808080);
            return;
        }

        int contentHeight = rows.size() * STATISTICS_ROW_HEIGHT;
        int maxScroll = Math.max(0, contentHeight - listH + 4);
        if (statisticsScrollY < 0) statisticsScrollY = 0;
        if (statisticsScrollY > maxScroll) statisticsScrollY = maxScroll;

        graphics.enableScissor(scissorX(x + 1), scissorY(listY + 1),
            scissorX(x + width - 1), scissorY(listY + listH - 1));
        for (int i = 0; i < rows.size(); i++) {
            int rowY = listY + 3 + i * STATISTICS_ROW_HEIGHT - statisticsScrollY;
            if (rowY + STATISTICS_ROW_HEIGHT < listY || rowY > listY + listH) continue;
            StatRow row = rows.get(i);
            // Clickable worker rows get a faint hover highlight + a hint that they're actionable.
            if (row.actionEntityId() >= 0 && mouseInList(mouseX, mouseY)
                    && mouseY >= rowY - 1 && mouseY < rowY + STATISTICS_ROW_HEIGHT - 1) {
                graphics.fill(x + 1, rowY - 1, x + width - SCROLLBAR_WIDTH - 1,
                    rowY + STATISTICS_ROW_HEIGHT - 2, 0x33FFFFFF);
            }
            graphics.drawString(this.font, row.text(), x + 5, rowY, 0xFFFFFFFF, false);
        }
        graphics.disableScissor();

        if (maxScroll > 0) {
            int trackX = x + width - SCROLLBAR_WIDTH - 1;
            int trackY = listY + 1;
            int trackH = listH - 2;
            graphics.fill(trackX, trackY, trackX + SCROLLBAR_WIDTH, trackY + trackH, 0xFF0A0A0A);
            int thumbH = Math.max(8, trackH * listH / contentHeight);
            int thumbY = trackY + (trackH - thumbH) * statisticsScrollY / maxScroll;
            graphics.fill(trackX, thumbY, trackX + SCROLLBAR_WIDTH, thumbY + thumbH, 0xFF606060);
        }

        if (statsMenuEntityId >= 0) drawStatsActionMenu(graphics, mouseX, mouseY);
    }

    /** Is the (panel-space) cursor inside the cached Statistics list rect? */
    private boolean mouseInList(int mx, int my) {
        return mx >= statsListX && mx <= statsListX + statsListW
            && my >= statsListY && my <= statsListY + statsListH;
    }

    /** The action popup's option labels: release-to-auto first, then each unlocked job. */
    private java.util.List<String> statsMenuOptions() {
        java.util.List<String> opts = new java.util.ArrayList<>();
        opts.add(""); // sentinel = "Release to auto-distribution"
        opts.addAll(ClientLaborState.getJobIds());
        return opts;
    }

    /** Popup rect {x, y, w, h} in panel space, clamped inside the list so it never spills past edges.
     *  Shared by the renderer and the click handler so they always agree. */
    private int[] statsMenuRect() {
        int h = statsMenuOptions().size() * STATS_MENU_ROW_H + 2;
        int mx = Math.max(statsListX + 1, Math.min(statsMenuX, statsListX + statsListW - STATS_MENU_W - 1));
        int my = Math.max(statsListY + 1, Math.min(statsMenuY, statsListY + statsListH - h - 1));
        return new int[]{mx, my, STATS_MENU_W, h};
    }

    private void drawStatsActionMenu(GuiGraphics graphics, int mouseX, int mouseY) {
        java.util.List<String> opts = statsMenuOptions();
        int[] r = statsMenuRect();
        int mx = r[0], my = r[1], h = r[3];
        graphics.fill(mx, my, mx + STATS_MENU_W, my + h, 0xF0101418);
        graphics.renderOutline(mx, my, STATS_MENU_W, h, 0xFFD8C070);
        for (int i = 0; i < opts.size(); i++) {
            int oy = my + 1 + i * STATS_MENU_ROW_H;
            boolean hover = mouseX >= mx && mouseX <= mx + STATS_MENU_W
                && mouseY >= oy && mouseY < oy + STATS_MENU_ROW_H;
            if (hover) graphics.fill(mx + 1, oy, mx + STATS_MENU_W - 1, oy + STATS_MENU_ROW_H, 0x44FFFFFF);
            Component label = opts.get(i).isEmpty()
                ? Component.translatable("bannerbound.townhall.statistics.action_release")
                : foodJobName(opts.get(i));
            int col = opts.get(i).isEmpty() ? STAT_BLOCKED_COLOR : STAT_NEUTRAL_COLOR;
            graphics.drawString(this.font, label, mx + 4, oy + 2, col, false);
        }
    }

    /** Handle a click on the Statistics tab (panel-space coords). Returns true if it consumed the click.
     *  Opens the action popup on a clickable worker row; on an open popup, picks an option or dismisses. */
    private boolean handleStatsClick(double mxd, double myd) {
        int mx = (int) mxd, my = (int) myd;
        if (statsMenuEntityId >= 0) {
            int[] r = statsMenuRect();
            if (mx >= r[0] && mx <= r[0] + r[2] && my >= r[1] && my < r[1] + r[3]) {
                java.util.List<String> opts = statsMenuOptions();
                int idx = (my - (r[1] + 1)) / STATS_MENU_ROW_H;
                if (idx >= 0 && idx < opts.size()) {
                    String job = opts.get(idx);
                    if (job.isEmpty()) {
                        PacketDistributor.sendToServer(new com.bannerbound.core.network.SetJobAutoPayload(statsMenuEntityId));
                    } else {
                        PacketDistributor.sendToServer(
                            new com.bannerbound.core.network.AssignCitizenJobPayload(statsMenuEntityId, job));
                    }
                }
                statsMenuEntityId = -1;
                return true; // consumed the option click
            }
            statsMenuEntityId = -1; // click outside the popup just dismisses; let it fall through
            return false;
        }
        if (!mouseInList(mx, my)) return false;
        int rel = my - (statsListY + 3) + statisticsScrollY;
        if (rel < 0) return false;
        int idx = rel / STATISTICS_ROW_HEIGHT;
        if (idx < 0 || idx >= statsRows.size()) return false;
        StatRow row = statsRows.get(idx);
        if (row.actionEntityId() < 0) return false;
        statsMenuEntityId = row.actionEntityId();
        statsMenuX = mx;
        statsMenuY = my;
        return true;
    }

    /** The full Statistics detail list: a Workshops section (output rate + backlog + ETA per workshop)
     *  followed by the Workforce roster. Section headers are in-list rows so everything scrolls together. */
    private java.util.List<StatRow> buildStatsListRows() {
        java.util.List<StatRow> rows = new java.util.ArrayList<>();
        java.util.List<com.bannerbound.core.network.WorkshopStatsPayload.Entry> shops =
            ClientWorkshopState.getEntries();
        if (!shops.isEmpty()) {
            rows.add(new StatRow(Component.translatable("bannerbound.townhall.statistics.workshops_header")
                .withStyle(net.minecraft.network.chat.Style.EMPTY.withColor(STAT_HEADER_COLOR)), -1));
            for (com.bannerbound.core.network.WorkshopStatsPayload.Entry w : shops) {
                com.bannerbound.core.api.settlement.Workshop.Status st =
                    com.bannerbound.core.api.settlement.Workshop.Status.fromOrdinalOrDefault(w.statusOrdinal());
                int color = st == com.bannerbound.core.api.settlement.Workshop.Status.VALID
                    ? STAT_NEUTRAL_COLOR : STAT_BLOCKED_COLOR;
                Component name = w.name() == null || w.name().isBlank()
                    ? workshopTypeName(w.typeId()) : Component.literal(w.name());
                rows.add(new StatRow(Component.translatable("bannerbound.townhall.statistics.workshop_row",
                    name, w.workers(), w.capacity())
                    .withStyle(net.minecraft.network.chat.Style.EMPTY.withColor(color)), -1));
                rows.add(new StatRow(workshopDetailRow(w), -1));
            }
        }
        rows.add(new StatRow(Component.translatable("bannerbound.townhall.statistics.workforce_header")
            .withStyle(net.minecraft.network.chat.Style.EMPTY.withColor(STAT_HEADER_COLOR)), -1));
        rows.addAll(buildWorkforceRows());
        return rows;
    }

    /** Second line of a workshop row: output rate, pending backlog, and a backlog-clear ETA. */
    private static Component workshopDetailRow(com.bannerbound.core.network.WorkshopStatsPayload.Entry w) {
        String rate = String.format(java.util.Locale.ROOT, "%.1f", w.outputRate() * 60.0);
        String eta = "";
        if (w.pendingOrders() > 0 && w.outputRate() > 1.0e-4) {
            double mins = (w.pendingOrders() / w.outputRate()) / 60.0;
            eta = " · ~" + String.format(java.util.Locale.ROOT, "%.0f", Math.ceil(mins)) + "m";
        }
        return Component.translatable("bannerbound.townhall.statistics.workshop_detail",
            rate, String.valueOf(w.pendingOrders()), eta)
            .withStyle(net.minecraft.network.chat.Style.EMPTY.withColor(0xFF8A8A8A));
    }

    /** Localised workshop type name ("crafting_stone" → "Crafting Stone"); falls back to a cleaned id. */
    private static Component workshopTypeName(String typeId) {
        return Component.translatableWithFallback(
            "bannerbound.townhall.statistics.workshopname." + typeId, prettifyId(typeId));
    }

    /** Build the workforce roster rows: a header per job ("Fisher (3)") followed by each citizen and
     *  their work-status, plus an "Away" group for unloaded citizens. Each row is pre-coloured. */
    private java.util.List<StatRow> buildWorkforceRows() {
        java.util.List<StatRow> rows = new java.util.ArrayList<>();
        java.util.Map<String, java.util.List<com.bannerbound.core.network.WorkforceStatsPayload.Entry>> byJob =
            new java.util.LinkedHashMap<>();
        java.util.List<com.bannerbound.core.network.WorkforceStatsPayload.Entry> away = new java.util.ArrayList<>();
        for (com.bannerbound.core.network.WorkforceStatsPayload.Entry e : ClientWorkforceState.getEntries()) {
            if (e.statusOrdinal() < 0) { away.add(e); continue; }
            byJob.computeIfAbsent(e.jobType(), k -> new java.util.ArrayList<>()).add(e);
        }
        for (var group : byJob.entrySet()) {
            String jobId = group.getKey();
            java.util.List<com.bannerbound.core.network.WorkforceStatsPayload.Entry> list = group.getValue();
            Component jobName = jobId.isEmpty()
                ? Component.translatable("bannerbound.townhall.statistics.unemployed")
                : foodJobName(jobId);
            rows.add(new StatRow(Component.translatable("bannerbound.townhall.statistics.job_group", jobName, list.size())
                .withStyle(net.minecraft.network.chat.Style.EMPTY.withColor(STAT_HEADER_COLOR)), -1));
            for (com.bannerbound.core.network.WorkforceStatsPayload.Entry e : list) {
                com.bannerbound.core.entity.CitizenWorkStatus st =
                    com.bannerbound.core.entity.CitizenWorkStatus.values()[e.statusOrdinal()];
                int color = switch (st.category()) {
                    case GOOD -> STAT_GOOD_COLOR;
                    case BLOCKED -> STAT_BLOCKED_COLOR;
                    default -> STAT_NEUTRAL_COLOR;
                };
                rows.add(new StatRow(Component.translatable("bannerbound.townhall.statistics.worker_row",
                    e.name(), workStatusName(st))
                    .withStyle(net.minecraft.network.chat.Style.EMPTY.withColor(color)), e.entityId()));
            }
        }
        if (!away.isEmpty()) {
            rows.add(new StatRow(Component.translatable("bannerbound.townhall.statistics.away_group", away.size())
                .withStyle(net.minecraft.network.chat.Style.EMPTY.withColor(STAT_NEUTRAL_COLOR)), -1));
            for (com.bannerbound.core.network.WorkforceStatsPayload.Entry e : away) {
                rows.add(new StatRow(Component.literal("  " + e.name())
                    .withStyle(net.minecraft.network.chat.Style.EMPTY.withColor(0xFF707070)), -1));
            }
        }
        return rows;
    }

    /** Localised job name for the roster ("fishers_creel" → "Fisher"); falls back to a cleaned id. */
    private static Component foodJobName(String jobId) {
        return Component.translatableWithFallback("bannerbound.townhall.statistics.jobname." + jobId,
            prettifyId(jobId));
    }

    /** Localised work-status label; falls back to the enum name. */
    private static Component workStatusName(com.bannerbound.core.entity.CitizenWorkStatus status) {
        return Component.translatableWithFallback(
            "bannerbound.workstatus." + status.name().toLowerCase(java.util.Locale.ROOT),
            prettifyId(status.name()));
    }

    /** "fishers_creel" / "NEED_MATERIALS" → "Fishers Creel" / "Need Materials" as a readable fallback. */
    private static String prettifyId(String id) {
        if (id == null || id.isEmpty()) return "";
        String[] parts = id.toLowerCase(java.util.Locale.ROOT).split("[_:]");
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (p.isEmpty()) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(Character.toUpperCase(p.charAt(0))).append(p.substring(1));
        }
        return sb.toString();
    }

    private void buildDictionaryTab(int panelX, int bodyTop, int bodyBottom) {
        dictionaryAll = ClientLanguageState.dictionaryEntries();
        final int x = panelX + 12;
        final int width = PANEL_WIDTH - 24;
        final int searchY = bodyTop + 4;

        dictionarySearch = new net.minecraft.client.gui.components.EditBox(
            this.font, x + 1, searchY, width - 2, 16,
            Component.translatable("bannerbound.townhall.dictionary.search"));
        dictionarySearch.setMaxLength(48);
        dictionarySearch.setHint(Component.translatable("bannerbound.townhall.dictionary.search"));
        dictionarySearch.setValue(dictionaryQuery);
        dictionarySearch.setResponder(s -> {
            dictionaryQuery = s;
            dictionaryScrollY = 0;
            applyDictionaryFilter();
        });
        this.addRenderableWidget(dictionarySearch);
        applyDictionaryFilter();

        // List panel sits below the search box; bottom stays where the old panel ended (bodyBottom-4).
        final int listTop = searchY + 20;
        this.addRenderableOnly((graphics, mouseX, mouseY, partialTick) ->
            drawDictionaryList(graphics, x, listTop, width, bodyBottom - 4 - listTop));
    }

    /** Narrow {@link #dictionaryAll} to the rows matching the search box; case-insensitive
     *  substring over the word, its gloss/meaning, the root note, and the category. */
    private void applyDictionaryFilter() {
        String q = dictionaryQuery == null ? "" : dictionaryQuery.trim().toLowerCase(java.util.Locale.ROOT);
        if (q.isEmpty()) {
            dictionaryRows = dictionaryAll;
            return;
        }
        java.util.List<ClientLanguageState.DictionaryEntry> out = new java.util.ArrayList<>();
        for (ClientLanguageState.DictionaryEntry e : dictionaryAll) {
            if (matchesDictionaryQuery(e, q)) out.add(e);
        }
        dictionaryRows = out;
    }

    private static boolean matchesDictionaryQuery(ClientLanguageState.DictionaryEntry e, String q) {
        return contains(e.word(), q) || contains(e.gloss(), q)
            || contains(e.note(), q) || contains(e.category(), q);
    }

    private static boolean contains(String haystack, String needleLower) {
        return haystack != null && haystack.toLowerCase(java.util.Locale.ROOT).contains(needleLower);
    }

    private void drawDictionaryList(GuiGraphics graphics, int x, int y, int width, int height) {
        graphics.fill(x, y, x + width, y + height, 0xFF181818);
        graphics.renderOutline(x, y, width, height, 0xFF2A2A2A);
        graphics.drawString(this.font, Component.translatable("bannerbound.townhall.dictionary.title"),
            x + 7, y + 7, 0xFFE2C065, false);
        Component count = Component.translatable("bannerbound.townhall.dictionary.count", dictionaryRows.size());
        graphics.drawString(this.font, count, x + width - 7 - this.font.width(count),
            y + 7, 0xFF8E8E8E, false);

        int listY = y + 25;
        int listHeight = height - 28;

        if (dictionaryRows.isEmpty()) {
            boolean searching = dictionaryQuery != null && !dictionaryQuery.isBlank();
            graphics.drawCenteredString(this.font,
                Component.translatable(searching
                    ? "bannerbound.townhall.dictionary.no_matches"
                    : "bannerbound.townhall.dictionary.empty"),
                x + width / 2, listY + listHeight / 2 - 4, 0xFF808080);
            return;
        }

        int contentHeight = dictionaryRows.size() * DICTIONARY_ROW_HEIGHT;
        int maxScroll = Math.max(0, contentHeight - listHeight + 4);
        if (dictionaryScrollY < 0) dictionaryScrollY = 0;
        if (dictionaryScrollY > maxScroll) dictionaryScrollY = maxScroll;

        graphics.enableScissor(scissorX(x + 1), scissorY(listY),
            scissorX(x + width - 1), scissorY(listY + listHeight));
        for (int i = 0; i < dictionaryRows.size(); i++) {
            int rowY = listY + 3 + i * DICTIONARY_ROW_HEIGHT - dictionaryScrollY;
            if (rowY + DICTIONARY_ROW_HEIGHT < listY || rowY > listY + listHeight) continue;
            drawDictionaryRow(graphics, dictionaryRows.get(i), x + 5, rowY, width - 10 - SCROLLBAR_WIDTH);
        }
        graphics.disableScissor();

        if (maxScroll > 0) {
            int trackX = x + width - SCROLLBAR_WIDTH - 1;
            int trackY = listY;
            int trackH = listHeight;
            graphics.fill(trackX, trackY, trackX + SCROLLBAR_WIDTH, trackY + trackH, 0xFF0A0A0A);
            int thumbH = Math.max(8, trackH * listHeight / contentHeight);
            int thumbY = trackY + (trackH - thumbH) * dictionaryScrollY / maxScroll;
            graphics.fill(trackX, thumbY, trackX + SCROLLBAR_WIDTH, thumbY + thumbH, 0xFF606060);
        }
    }

    private void drawDictionaryRow(GuiGraphics graphics, ClientLanguageState.DictionaryEntry entry,
                                   int x, int y, int width) {
        int color = dictionaryCategoryColor(entry.category());
        int rowBottom = y + DICTIONARY_ROW_HEIGHT - 5;
        graphics.fill(x - 2, y - 1, x + width + 2, rowBottom, 0xFF141414);
        graphics.fill(x - 2, y - 1, x + 1, rowBottom, color);
        graphics.fill(x + 2, rowBottom - 1, x + width, rowBottom, 0xFF242424);

        Component category = Component.literal(entry.category()).withStyle(ChatFormatting.GRAY);
        int catW = this.font.width(category);
        graphics.drawString(this.font, clip(Component.literal(entry.word()), width - catW - 8),
            x, y, 0xFFE2C065, false);
        graphics.drawString(this.font, category, x + width - catW, y, color, false);

        String detail = entry.note() == null || entry.note().isBlank()
            ? entry.gloss()
            : entry.gloss() + " - " + entry.note();
        graphics.drawString(this.font, clip(Component.literal(detail), width),
            x, y + 12, 0xFFB8B8B8, false);
    }

    private static int dictionaryCategoryColor(String category) {
        return switch (category) {
            case "Goods" -> 0xFF8FC7E8;
            case "Work" -> 0xFFF0B260;
            case "Knowledge" -> 0xFF9ED27A;
            case "Tradition" -> 0xFFC074DB;
            default -> 0xFF909090;
        };
    }

    private void drawStatusesList(GuiGraphics graphics, int x, int y, int width, int height) {
        java.util.List<ClientStatusState.Entry> effects = ClientStatusState.getAll();
        // Panel-edge groove + dark background so the list reads as a distinct region.
        graphics.fill(x, y, x + width, y + height, 0xFF181818);
        graphics.renderOutline(x, y, width, height, 0xFF2A2A2A);

        if (effects.isEmpty()) {
            graphics.drawCenteredString(this.font,
                Component.translatable("bannerbound.townhall.statuses.empty"),
                x + width / 2, y + height / 2 - 4, 0xFF808080);
            return;
        }

        // Clamp scroll so the list can't be dragged past either edge. Done here (not in
        // mouseScrolled) so window resizes / new effects don't strand us out of bounds.
        int contentHeight = effects.size() * STATUS_ROW_HEIGHT;
        int maxScroll = Math.max(0, contentHeight - height + 4);
        if (statusesScrollY < 0) statusesScrollY = 0;
        if (statusesScrollY > maxScroll) statusesScrollY = maxScroll;

        // Scissor-clip rendering to the list region so off-screen rows don't bleed over the tab
        // strip or cancel button. enableScissor takes raw screen-space coords and ignores the
        // auto-fit pose, so map the bounds through the fit scale first (see scissorX/scissorY).
        graphics.enableScissor(scissorX(x + 1), scissorY(y + 1),
            scissorX(x + width - 1), scissorY(y + height - 1));
        for (int i = 0; i < effects.size(); i++) {
            int rowY = y + 4 + i * STATUS_ROW_HEIGHT - statusesScrollY;
            // Skip rows entirely outside the viewport.
            if (rowY + STATUS_ROW_HEIGHT < y || rowY > y + height) continue;
            drawStatusRow(graphics, effects.get(i), x + 4, rowY, width - 8 - SCROLLBAR_WIDTH);
        }
        graphics.disableScissor();

        // Scrollbar — only shown when content overflows.
        if (maxScroll > 0) {
            int trackX = x + width - SCROLLBAR_WIDTH - 1;
            int trackY = y + 1;
            int trackH = height - 2;
            graphics.fill(trackX, trackY, trackX + SCROLLBAR_WIDTH, trackY + trackH, 0xFF0A0A0A);
            int thumbH = Math.max(8, trackH * height / contentHeight);
            int thumbY = trackY + (trackH - thumbH) * statusesScrollY / maxScroll;
            graphics.fill(trackX, thumbY, trackX + SCROLLBAR_WIDTH, thumbY + thumbH, 0xFF606060);
        }
    }

    /** The 16×16 warning sprite drawn on ALERT status rows (outpost lost etc.). */
    private static final net.minecraft.resources.ResourceLocation ALERT_ICON =
        net.minecraft.resources.ResourceLocation.fromNamespaceAndPath(
            "bannerbound", "textures/gui/alert.png");

    private void drawStatusRow(GuiGraphics graphics, ClientStatusState.Entry entry, int x, int y, int width) {
        // Each status is a self-contained card:
        //   ▌ +0.25🍖  Fisher caught Raw Salmon
        //   ▌ ▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓▓░░░  (progress bar pinned to the card's lower edge)
        // The card framing (dark fill + gold left accent) is what visually ties the bar to its
        // own row. Drawn loose against the list background, a 100%-full bar reads as a stray
        // full-width divider floating between entries — which is what looked broken before.
        int cardBottom = y + STATUS_ROW_HEIGHT - 5;
        int cardRight = x + width;
        graphics.fill(x - 2, y - 2, cardRight, cardBottom, 0xFF161616);
        graphics.fill(x - 2, y - 2, x, cardBottom, 0xFFE2C065); // gold left accent stripe

        // Rate + themed resource icon (e.g. "+0.25🍖"). ALERT rows carry no rate — a "+0.00"
        // prefix would read as nonsense — so they show just the warning sprite.
        int textX = x + 4;
        int prefixWidth;
        if (entry.icon() == com.bannerbound.core.api.settlement.StatusEffectIcon.ALERT) {
            graphics.blit(ALERT_ICON, textX, y, 12, 12, 0f, 0f, 16, 16, 16, 16);
            prefixWidth = 14;
        } else {
            net.minecraft.network.chat.MutableComponent rate =
                net.minecraft.network.chat.Component.literal(String.format("+%.2f", entry.iconValue()))
                    .append(iconFor(entry.icon()));
            graphics.drawString(this.font, rate, textX, y + 1, 0xFFE2C065, false);
            prefixWidth = this.font.width(rate);
        }

        java.util.List<net.minecraft.network.chat.Component> textArgs = new java.util.ArrayList<>(entry.args().size());
        for (String a : entry.args()) {
            textArgs.add(net.minecraft.network.chat.Component.literal(a));
        }
        net.minecraft.network.chat.Component label =
            net.minecraft.network.chat.Component.translatable(entry.translationKey(),
                textArgs.toArray(new Object[0]));
        int labelX = textX + prefixWidth + 6;
        // Clip the label so a long catch name can't spill past the card / into the scrollbar.
        graphics.drawString(this.font, clip(label, cardRight - 3 - labelX), labelX, y + 1, 0xFFE0E0E0, false);

        // Progress bar fills 100% → 0% as time runs out, pinned to the bottom of the card.
        int barY = cardBottom - 4;
        int barX = textX;
        int barWidth = cardRight - 3 - barX;
        graphics.fill(barX, barY, barX + barWidth, barY + 2, 0xFF000000);
        if (entry.totalDurationTicks() > 0) {
            int fill = barWidth * entry.remainingTicks() / entry.totalDurationTicks();
            graphics.fill(barX, barY, barX + fill, barY + 2, 0xFFE2C065);
        }
    }

    private net.minecraft.network.chat.Component iconFor(com.bannerbound.core.api.settlement.StatusEffectIcon icon) {
        return switch (icon) {
            case FOOD -> Icons.food(era);
            case CULTURE -> Icons.culture(era);
            case SCIENCE -> Icons.science();
            case ALERT -> net.minecraft.network.chat.Component.literal("⚠")
                .withStyle(net.minecraft.ChatFormatting.RED);
        };
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        mouseX = virtualX(mouseX); mouseY = virtualY(mouseY); // map into auto-fit panel space
        if (activeTab == Tab.STATUSES) {
            statusesScrollY -= (int) (scrollY * STATUS_ROW_HEIGHT);
            return true;
        }
        if (activeTab == Tab.DICTIONARY) {
            dictionaryScrollY -= (int) (scrollY * DICTIONARY_ROW_HEIGHT);
            return true;
        }
        if (activeTab == Tab.STATISTICS) {
            statisticsScrollY -= (int) (scrollY * STATISTICS_ROW_HEIGHT);
            return true;
        }
        if (activeTab == Tab.POLICIES) {
            policiesScrollY -= (int) (scrollY * POLICY_ROW_HEIGHT);
            return true;
        }
        if (activeTab == Tab.PALETTES) {
            palettesScrollY -= (int) (scrollY * PALETTE_ROW_HEIGHT);
            return true;
        }
        if (activeTab == Tab.SUGGESTIONS) {
            // Rows are widgets (Resolve/Ignore buttons), so scrolling rebuilds; build clamps.
            suggestionsScroll -= (int) Math.signum(scrollY);
            this.rebuildWidgets();
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    // ─── Policies tab ─────────────────────────────────────────────────────────────────────

    private boolean isCouncil() {
        return governmentOrdinal == Settlement.Government.COUNCIL.ordinal();
    }
    private boolean isChiefdom() {
        return governmentOrdinal == Settlement.Government.CHIEFDOM.ordinal();
    }

    private void buildPoliciesTab(int panelX, int bodyTop, int bodyBottom, int btnWidth) {
        // Two columns. Left = active slots + action buttons; right = scrollable Available list.
        polColW = (PANEL_WIDTH - 28) / 2;
        polLeftX = panelX + 10;
        polRightX = panelX + PANEL_WIDTH / 2 + 4;
        int headerY = bodyTop + 2;
        polSlotsTop = headerY + 14;
        polSlotCount = ClientPolicyState.getSlotCount();
        polListTop = headerY + 14;
        polListH = bodyBottom - polListTop - 4;

        // Snapshot the right-hand list (available, not already active). A policy that's been
        // staged into a slot is omitted — it has visually "moved" from the list to the slot, so
        // it shouldn't also sit in the list. (The dragging item is excluded at render time via
        // renderedPolicyList(), since the drag starts/ends without a widget rebuild.)
        polVisibleList.clear();
        for (String id : ClientPolicyState.getAvailableNotActive()) {
            if (id.equals(stagedAddId)) continue;
            polVisibleList.add(id);
        }

        // Body draw callback (columns + slots + list + headers).
        this.addRenderableOnly((g, mx, my, pt) -> drawPoliciesTab(g, mx, my));

        // Action buttons sit under the left column.
        int actionY = polSlotsTop + polSlotCount * (POLICY_SLOT_HEIGHT + 4) + 6;
        int halfW = (polColW - 4) / 2;
        boolean pending = ClientPolicyState.hasPending();
        if (isCouncil() && pending) {
            // Confirm-vote mode: Agree / Disagree. Once this player has cast their vote both
            // buttons disable (greyed) so the screen reflects, in real time, that they've voted
            // and are now waiting on the rest of the council — instead of the click doing nothing
            // visible. The live PolicyStateSync rebuild (onPolicyStateSynced) keeps this current.
            Boolean ownVote = (this.minecraft != null && this.minecraft.player != null)
                ? ClientPolicyState.getOwnConfirmVote(this.minecraft.player.getUUID()) : null;
            Button agree = PolishButton.polished(
                Component.translatable("bannerbound.policy.agree"),
                b -> { PacketDistributor.sendToServer(new CastPolicyVotePayload(true)); policyFeedback.spawnAtCursor(); })
                .bounds(polLeftX, actionY, halfW, 18).build();
            Button disagree = PolishButton.polished(
                Component.translatable("bannerbound.policy.disagree"),
                b -> { PacketDistributor.sendToServer(new CastPolicyVotePayload(false)); policyFeedback.spawnAtCursor(); })
                .bounds(polLeftX + halfW + 4, actionY, halfW, 18).build();
            if (ownVote != null) { agree.active = false; disagree.active = false; }
            this.addRenderableWidget(agree);
            this.addRenderableWidget(disagree);
        } else if (!pending && !isSuggestMode() && (stagedAddId != null || stagedRemoveId != null)) {
            // Staging mode (Council member proposes → vote; Chiefdom chief enacts): Confirm +
            // Cancel. Chiefdom non-chiefs never stage — they suggest with a single click on a
            // row in the Available list, so they don't reach this branch.
            this.addRenderableWidget(PolishButton.polished(
                Component.translatable("bannerbound.policy.confirm"),
                b -> sendStagedChange())
                .bounds(polLeftX, actionY, halfW, 18).build());
            this.addRenderableWidget(PolishButton.polished(
                Component.translatable("gui.cancel"),
                b -> { clearStaging(); this.rebuildWidgets(); })
                .bounds(polLeftX + halfW + 4, actionY, halfW, 18).build());
        }
    }

    /** Send the staged change as a ProposePolicyChange — the server opens a council confirm vote
     *  on it, or (Chiefdom chief) enacts it immediately. Chiefdom non-chiefs don't stage; they
     *  suggest with a single click on an Available-list row (see {@link #mouseClicked}). */
    private void sendStagedChange() {
        PacketDistributor.sendToServer(new ProposePolicyChangePayload(
            stagedSlot < 0 ? 0 : stagedSlot,
            stagedAddId == null ? "" : stagedAddId,
            stagedRemoveId == null ? "" : stagedRemoveId));
        policyFeedback.spawnAtCursor();
        clearStaging();
        this.rebuildWidgets();
    }

    private void clearStaging() {
        stagedAddId = null;
        stagedRemoveId = null;
        stagedSlot = -1;
        draggingPolicyId = null;
    }

    /** Client-side slot→policy assignment: walk the synced slot-type list and drop each active
     *  policy into the first matching free slot (signature policies → the SIGNATURE slot). Entries
     *  are null for empty slots. Recomputed on demand — cheap (≤ a handful of policies). */
    private String[] slotAssignment() {
        java.util.List<String> slotTypes = ClientPolicyState.getSlotTypes();
        java.util.List<String> remaining = new java.util.ArrayList<>(ClientPolicyState.getActive());
        String[] assign = new String[slotTypes.size()];
        for (int i = 0; i < slotTypes.size(); i++) {
            String st = slotTypes.get(i);
            for (java.util.Iterator<String> it = remaining.iterator(); it.hasNext();) {
                String pid = it.next();
                if (slotAcceptsPolicy(st, pid)) { assign[i] = pid; it.remove(); break; }
            }
        }
        return assign;
    }

    /** Whether a slot of {@code slotType} ("ECONOMIC"/…/"SIGNATURE") accepts {@code policyId}. */
    private static boolean slotAcceptsPolicy(String slotType, String policyId) {
        PolicyRegistry.Policy p = PolicyRegistry.get(policyId);
        if (p == null) return false;
        if ("SIGNATURE".equals(slotType)) return PolicyRegistry.isSignature(policyId);
        return !PolicyRegistry.isSignature(policyId) && p.type().name().equals(slotType);
    }

    /** Display name for a slot's type, used as the empty-slot placeholder label. */
    private static Component slotTypeLabel(String slotType) {
        if ("SIGNATURE".equals(slotType)) {
            return Component.translatable("bannerbound.policy.type.signature");
        }
        PolicyType t = PolicyType.byName(slotType);
        return t != null ? Component.translatable(t.langKey()) : Component.literal(slotType);
    }

    /** Accent colour for a slot's type (the signature slot uses the gold UI accent). */
    private static int slotTypeColor(String slotType) {
        if ("SIGNATURE".equals(slotType)) return 0xFFD8C070;
        PolicyType t = PolicyType.byName(slotType);
        return t != null ? t.color() : 0xFF808080;
    }

    /** Halves the RGB channels (keeps alpha) — a muted variant for slot borders / empty text. */
    private static int dimColor(int argb) {
        return scaleColor(argb, 0.5f);
    }

    /** Scales the RGB channels by {@code f} (keeps alpha), clamped to [0,255]. */
    private static int scaleColor(int argb, float f) {
        int a = (argb >>> 24) & 0xFF;
        int r = Math.min(255, Math.round(((argb >> 16) & 0xFF) * f));
        int gg = Math.min(255, Math.round(((argb >> 8) & 0xFF) * f));
        int b = Math.min(255, Math.round((argb & 0xFF) * f));
        return (a << 24) | (r << 16) | (gg << 8) | b;
    }

    /** RGB of {@code rgb} with an explicit alpha byte. */
    private static int withAlpha(int rgb, int alpha) {
        return ((alpha & 0xFF) << 24) | (rgb & 0xFFFFFF);
    }

    /** Linear blend a→b by t∈[0,1] over the RGB channels (alpha taken from a). */
    private static int lerpColor(int a, int b, float t) {
        int aa = (a >>> 24) & 0xFF;
        int ar = (a >> 16) & 0xFF, ag = (a >> 8) & 0xFF, ab = a & 0xFF;
        int br = (b >> 16) & 0xFF, bg = (b >> 8) & 0xFF, bb = b & 0xFF;
        int r = Math.round(ar + (br - ar) * t);
        int gg = Math.round(ag + (bg - ag) * t);
        int bl = Math.round(ab + (bb - ab) * t);
        return (aa << 24) | (r << 16) | (gg << 8) | bl;
    }

    /** The accent colour of a policy: its {@link PolicyType} colour, or the signature gold. */
    private static int policyAccentColor(String policyId) {
        PolicyRegistry.Policy p = PolicyRegistry.get(policyId);
        if (p == null) return 0xFF808080;
        if (PolicyRegistry.isSignature(policyId)) return 0xFFD8C070;
        return p.type().color();
    }

    /** Draws a small diamond "gem" (the per-type emblem) centred at (cx, cy), point-to-point span
     *  {@code 2*radius+1}, in {@code color}. Pure fills — stays in the sprite aesthetic. */
    private static void drawGem(GuiGraphics g, int cx, int cy, int radius, int color) {
        for (int dy = -radius; dy <= radius; dy++) {
            int half = radius - Math.abs(dy);
            g.fill(cx - half, cy + dy, cx + half + 1, cy + dy + 1, color);
        }
    }

    /** A gentle 0→1→0 pulse for drag-target highlighting (≈1.25 s period). */
    private static float uiPulse() {
        return 0.5f + 0.5f * (float) Math.sin(net.minecraft.Util.getMillis() / 200.0);
    }

    /** Draws a 1px dashed rectangle outline — the "drop here" convention for an empty slot. */
    private static void drawDashedOutline(GuiGraphics g, int x, int y, int w, int h, int color) {
        final int dash = 3, step = 5;
        for (int dx = 0; dx < w; dx += step) {           // top + bottom edges
            int seg = Math.min(dash, w - dx);
            g.fill(x + dx, y, x + dx + seg, y + 1, color);
            g.fill(x + dx, y + h - 1, x + dx + seg, y + h, color);
        }
        for (int dy = 0; dy < h; dy += step) {           // left + right edges
            int seg = Math.min(dash, h - dy);
            g.fill(x, y + dy, x + 1, y + dy + seg, color);
            g.fill(x + w - 1, y + dy, x + w, y + dy + seg, color);
        }
    }

    /** Draws a small 1px-thick plus sign centred at (cx, cy) — the empty-slot "add here" hint. */
    private static void drawPlus(GuiGraphics g, int cx, int cy, int radius, int color) {
        g.fill(cx - radius, cy, cx + radius + 1, cy + 1, color);
        g.fill(cx, cy - radius, cx + 1, cy + radius + 1, color);
    }

    /** What policy id is shown in slot {@code i}, accounting for local staging + server pending.
     *  Returns null for an empty slot. */
    private String slotDisplayId(int i) {
        String[] assign = slotAssignment();
        String base = i >= 0 && i < assign.length ? assign[i] : null;
        // Server pending (council vote in flight) takes precedence for display.
        if (ClientPolicyState.hasPending()) {
            if (ClientPolicyState.getPendingSlot() == i
                    && !ClientPolicyState.getPendingAddId().isEmpty()) {
                return ClientPolicyState.getPendingAddId();
            }
            return base;
        }
        // Local staging preview.
        if (stagedSlot == i && stagedAddId != null) return stagedAddId;
        if (base != null && base.equals(stagedRemoveId)) return base; // shown but marked
        return base;
    }

    private void drawPoliciesTab(GuiGraphics g, int mouseX, int mouseY) {
        // Column headers.
        g.drawString(this.font, Component.translatable("bannerbound.policy.active_header"),
            polLeftX, polSlotsTop - 12, 0xFFD8C070, false);
        g.drawString(this.font, Component.translatable("bannerbound.policy.available_header"),
            polRightX, polListTop - 12, 0xFFD8C070, false);

        boolean pending = ClientPolicyState.hasPending();

        // Left: active slots — each rendered as a type-tinted "card": a faint colour wash, a solid
        // accent spine + gem on the left in the type colour, the policy (or the type name when
        // empty) to its right. While a policy is being dragged, the slots that can accept it light
        // up (brighter wash + pulsing border) and the rest dim — so the type system reads at a
        // glance and the drag feels responsive.
        java.util.List<String> slotTypes = ClientPolicyState.getSlotTypes();
        boolean dragging = draggingPolicyId != null;
        float pulse = uiPulse();
        for (int i = 0; i < polSlotCount; i++) {
            int sy = polSlotsTop + i * (POLICY_SLOT_HEIGHT + 4);
            int sBottom = sy + POLICY_SLOT_HEIGHT;
            String id = slotDisplayId(i);
            String slotType = i < slotTypes.size() ? slotTypes.get(i) : "";
            int typeColor = slotTypeColor(slotType);
            boolean filled = id != null;
            boolean isStagedAdd = !pending && stagedSlot == i && stagedAddId != null;
            boolean isPendingSlot = pending && ClientPolicyState.getPendingSlot() == i
                && !ClientPolicyState.getPendingAddId().isEmpty();
            boolean isRemoving = id != null && id.equals(stagedRemoveId)
                || (pending && id != null && id.equals(ClientPolicyState.getPendingRemoveId()));
            boolean matchesDrag = dragging && slotAcceptsPolicy(slotType, draggingPolicyId);
            boolean dimmed = dragging && !matchesDrag;

            // Card body: black base + a type-coloured wash (stronger when filled / a drag target).
            int washAlpha = matchesDrag ? 0x66 : filled ? 0x38 : 0x18;
            g.fill(polLeftX, sy, polLeftX + polColW, sBottom, 0xFF0A0A0A);
            g.fill(polLeftX + 1, sy + 1, polLeftX + polColW - 1, sBottom - 1,
                withAlpha(dimmed ? scaleColor(typeColor, 0.4f) : typeColor, washAlpha));
            // Thin top inner highlight for a little depth.
            g.fill(polLeftX + 1, sy + 1, polLeftX + polColW - 1, sy + 2,
                withAlpha(typeColor, dimmed ? 0x20 : 0x40));

            // Accent spine + gem on the left edge.
            int accent = dimmed ? scaleColor(typeColor, 0.4f) : typeColor;
            g.fill(polLeftX, sy, polLeftX + 3, sBottom, accent);
            drawGem(g, polLeftX + 12, (sy + sBottom) / 2, 4,
                filled || matchesDrag ? accent : scaleColor(typeColor, 0.55f));

            // Border: status colours win; otherwise the type colour, pulsing for a drag target.
            // A *plain empty* slot gets a DASHED border + a "+" — the wordless "drag a policy here"
            // cue. Filled slots and active drag-targets get a solid outline.
            boolean statusBorder = isStagedAdd || isPendingSlot || isRemoving;
            int border = isPendingSlot ? 0xFFE0D055
                : isStagedAdd ? 0xFF55E055
                : isRemoving ? 0xFFE05555
                : matchesDrag ? lerpColor(typeColor, 0xFFFFFFFF, 0.35f * pulse)
                : dimmed ? scaleColor(typeColor, 0.30f)
                : dimColor(typeColor);
            boolean dropHint = !filled && !statusBorder && !matchesDrag;
            if (dropHint) {
                drawDashedOutline(g, polLeftX, sy, polColW, POLICY_SLOT_HEIGHT,
                    scaleColor(typeColor, 0.75f));
                drawPlus(g, polLeftX + polColW - 9, (sy + sBottom) / 2, 3,
                    scaleColor(typeColor, 0.8f));
            } else {
                g.renderOutline(polLeftX, sy, polColW, POLICY_SLOT_HEIGHT, border);
            }

            // Label to the right of the gem: white policy name, or the dim type name when empty.
            Component label = filled ? policyName(id) : slotTypeLabel(slotType);
            int textColor = filled ? (dimmed ? 0xFFAAAAAA : 0xFFFFFFFF) : dimColor(typeColor);
            int textX = polLeftX + 21;
            int rightReserve = dropHint ? 16 : 4;   // keep clear of the "+" hint
            g.drawString(this.font, clip(label, polColW - textX + polLeftX - rightReserve),
                textX, sy + (POLICY_SLOT_HEIGHT - this.font.lineHeight) / 2, textColor, false);
        }

        // Council vote tally line under the slots (when a vote's running).
        if (pending && isCouncil()) {
            int tallyY = polSlotsTop + polSlotCount * (POLICY_SLOT_HEIGHT + 4) - 2;
            g.drawString(this.font, Component.translatable("bannerbound.policy.vote_progress",
                    ClientPolicyState.countVotesCast(), ClientPolicyState.getOnlineMemberCount(),
                    ClientPolicyState.countAgrees())
                    .withStyle(ChatFormatting.GRAY),
                polLeftX, tallyY, 0xFFAAAAAA, false);
        }

        // Right: available list (scrollable).
        int x = polRightX, y = polListTop, w = polColW, h = polListH;
        g.fill(x, y, x + w, y + h, 0xFF181818);
        g.renderOutline(x, y, w, h, 0xFF2A2A2A);
        java.util.List<String> list = renderedPolicyList();
        int contentH = list.size() * POLICY_ROW_HEIGHT;
        int maxScroll = Math.max(0, contentH - h + 4);
        if (policiesScrollY < 0) policiesScrollY = 0;
        if (policiesScrollY > maxScroll) policiesScrollY = maxScroll;
        if (list.isEmpty()) {
            // Word-wrap to the column width so the placeholder doesn't bleed past the panel.
            java.util.List<net.minecraft.util.FormattedCharSequence> wrapped =
                this.font.split(Component.translatable("bannerbound.policy.none_available"), w - 12);
            int ty = y + h / 2 - (wrapped.size() * this.font.lineHeight) / 2;
            for (net.minecraft.util.FormattedCharSequence line : wrapped) {
                g.drawCenteredString(this.font, line, x + w / 2, ty, 0xFF808080);
                ty += this.font.lineHeight;
            }
        } else {
            g.enableScissor(scissorX(x + 1), scissorY(y + 1),
                scissorX(x + w - 1), scissorY(y + h - 1));
            for (int i = 0; i < list.size(); i++) {
                int rowY = y + 2 + i * POLICY_ROW_HEIGHT - policiesScrollY;
                if (rowY + POLICY_ROW_HEIGHT < y || rowY > y + h) continue;
                String id = list.get(i);
                int rowBottom = rowY + POLICY_ROW_HEIGHT - 2;
                int accent = policyAccentColor(id);
                boolean hovered = draggingPolicyId == null
                    && mouseX >= x + 2 && mouseX <= x + w - 2 && mouseY >= rowY && mouseY < rowBottom;
                // Row card: black base + faint type wash (brighter on hover) + accent spine + gem.
                g.fill(x + 2, rowY, x + w - 2, rowBottom, 0xFF0A0A0A);
                g.fill(x + 3, rowY + 1, x + w - 3, rowBottom - 1,
                    withAlpha(accent, hovered ? 0x44 : 0x22));
                g.fill(x + 2, rowY, x + 4, rowBottom, accent);
                drawGem(g, x + 12, (rowY + rowBottom) / 2, 4, accent);
                if (hovered) g.renderOutline(x + 2, rowY, w - 4, POLICY_ROW_HEIGHT - 2,
                    dimColor(accent));
                // Chiefdom suggestion marker: the shared "+N + player heads" badge (same one the
                // research screen uses), right-aligned. The name is clipped to leave it room.
                java.util.List<java.util.UUID> suggesters = ClientPolicyState.getSuggesters(id);
                int badgeW = suggesters.isEmpty() ? 0 : policySuggestBadgeWidth(suggesters.size());
                int textX = x + 20;
                g.drawString(this.font, clip(policyName(id), x + w - textX - 6 - badgeW),
                    textX, rowY + (POLICY_ROW_HEIGHT - 2 - this.font.lineHeight) / 2, 0xFFF0F0F0, false);
                if (!suggesters.isEmpty()) {
                    drawPolicySuggestionBadge(g, x + w - 4,
                        rowY + (POLICY_ROW_HEIGHT - 2) / 2, suggesters);
                }
            }
            g.disableScissor();
            if (maxScroll > 0) {
                int trackX = x + w - SCROLLBAR_WIDTH - 1;
                g.fill(trackX, y + 1, trackX + SCROLLBAR_WIDTH, y + h - 1, 0xFF0A0A0A);
                int thumbH = Math.max(8, (h - 2) * h / contentH);
                int thumbY = y + 1 + (h - 2 - thumbH) * policiesScrollY / maxScroll;
                g.fill(trackX, thumbY, trackX + SCROLLBAR_WIDTH, thumbY + thumbH, 0xFF606060);
            }
        }

        // Hover tooltip for any policy row / filled slot under the cursor.
        String hoverId = policyAtCursor(mouseX, mouseY);
        if (hoverId != null) drawPolicyTooltip(g, hoverId, mouseX, mouseY);
    }

    /** Draws the lifted policy as a card hanging from the cursor. The card pivots at the cursor
     *  (top-centre) and leans toward the horizontal cursor velocity, easing back upright when
     *  the cursor stops — a light "held by a string" sway. */
    private void drawDragCard(GuiGraphics graphics, int mouseX, int mouseY) {
        // Update sway from this frame's horizontal cursor velocity. Target lean is proportional
        // to velocity (clamped); dragSwayAngle eases toward it so it leans while moving and
        // settles when still. Trailing sign (-vx) so the card lags behind the cursor.
        double vx = Double.isNaN(dragPrevMouseX) ? 0.0 : (mouseX - dragPrevMouseX);
        dragPrevMouseX = mouseX;
        // +vx → positive angle swings the card's bottom LEFT (it trails behind a rightward
        // move), which reads as the card lagging the cursor like a held-on-a-string sign.
        double target = Math.max(-18.0, Math.min(18.0, vx * 1.2));
        dragSwayAngle += (target - dragSwayAngle) * 0.35;

        Component name = policyName(draggingPolicyId);
        int accent = policyAccentColor(draggingPolicyId);
        int cardW = this.font.width(name) + 24;   // room for the type gem on the left
        int cardH = this.font.lineHeight + 8;
        int halfW = cardW / 2;

        var pose = graphics.pose();
        pose.pushPose();
        // Pivot at the cursor, a couple px below it; rotate the whole card about that pivot.
        pose.translate(mouseX, mouseY + 2.0, 350.0);
        pose.mulPose(com.mojang.math.Axis.ZP.rotationDegrees((float) dragSwayAngle));
        graphics.fill(-halfW, 0, halfW, cardH, 0xF0151515);
        graphics.fill(-halfW + 1, 1, halfW - 1, cardH - 1, withAlpha(accent, 0x40));
        graphics.fill(-halfW, 0, -halfW + 3, cardH, accent);   // type spine
        drawGem(graphics, -halfW + 12, cardH / 2, 4, accent);
        graphics.renderOutline(-halfW, 0, cardW, cardH, accent);
        graphics.drawString(this.font, name, -halfW + 21, 4, 0xFFFFFFFF, false);
        pose.popPose();
    }

    /** The Available-list rows actually drawn + hit-tested: the snapshot minus the item being
     *  dragged, so a picked-up row lifts out and the list compacts to close the gap. */
    private java.util.List<String> renderedPolicyList() {
        if (draggingPolicyId == null) return polVisibleList;
        java.util.List<String> out = new java.util.ArrayList<>(polVisibleList.size());
        for (String id : polVisibleList) {
            if (!id.equals(draggingPolicyId)) out.add(id);
        }
        return out;
    }

    private Component policyName(String id) {
        PolicyRegistry.Policy p = PolicyRegistry.get(id);
        return p == null ? Component.literal(id) : Component.translatable(p.nameKey());
    }

    private Component clip(Component c, int maxWidth) {
        String s = c.getString();
        if (this.font.width(s) <= maxWidth) return c;
        return Component.literal(this.font.plainSubstrByWidth(s, maxWidth - this.font.width("..")) + "..")
            .withStyle(c.getStyle());
    }

    /** Policy id under the cursor, from either the Available list or a filled slot; null if none.
     *  Uses {@link #renderedPolicyList()} so the index math matches the compacted list while an
     *  item is lifted out by a drag. */
    private String policyAtCursor(double mouseX, double mouseY) {
        // Available list rows.
        java.util.List<String> list = renderedPolicyList();
        if (mouseX >= polRightX && mouseX <= polRightX + polColW
                && mouseY >= polListTop && mouseY <= polListTop + polListH) {
            int idx = (int) ((mouseY - (polListTop + 2) + policiesScrollY) / POLICY_ROW_HEIGHT);
            if (idx >= 0 && idx < list.size()) return list.get(idx);
        }
        // Filled slots.
        int slot = slotAtCursor(mouseX, mouseY);
        if (slot >= 0) return slotDisplayId(slot);
        return null;
    }

    /** True when the cursor is inside the Available-list rectangle (a valid "drop back" target). */
    private boolean isOverPolicyList(double mouseX, double mouseY) {
        return mouseX >= polRightX && mouseX <= polRightX + polColW
            && mouseY >= polListTop && mouseY <= polListTop + polListH;
    }

    /** Play the note-block kick used for drag pick-up / drop. Pitch lets pick-up + drop differ
     *  slightly while staying the same percussive "kick". */
    private void playDragSound(float pitch) {
        net.minecraft.client.Minecraft mc = this.minecraft;
        if (mc != null) {
            mc.getSoundManager().play(net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(
                net.minecraft.sounds.SoundEvents.NOTE_BLOCK_BASEDRUM.value(), pitch));
        }
    }

    /** Index of the active-slot rectangle under the cursor, or -1. */
    private int slotAtCursor(double mouseX, double mouseY) {
        if (mouseX < polLeftX || mouseX > polLeftX + polColW) return -1;
        for (int i = 0; i < polSlotCount; i++) {
            int sy = polSlotsTop + i * (POLICY_SLOT_HEIGHT + 4);
            if (mouseY >= sy && mouseY <= sy + POLICY_SLOT_HEIGHT) return i;
        }
        return -1;
    }

    private void drawPolicyTooltip(GuiGraphics g, String id, int mouseX, int mouseY) {
        PolicyRegistry.Policy p = PolicyRegistry.get(id);
        if (p == null) return;
        java.util.List<Component> lines = new java.util.ArrayList<>();
        lines.add(Component.translatable(p.nameKey()).withStyle(ChatFormatting.WHITE));
        lines.add(Component.translatable("bannerbound.policy.type_line",
            Component.translatable(p.type().langKey())).withColor(p.type().color() & 0xFFFFFF));
        lines.add(Component.translatable(p.descriptionKey()).withStyle(ChatFormatting.GRAY));
        lines.add(Component.translatable(p.effectKey()).withStyle(ChatFormatting.GOLD));
        if (p.governmentType() == Settlement.Government.COUNCIL) {
            lines.add(Component.translatable("bannerbound.policy.council_only").withStyle(ChatFormatting.DARK_AQUA));
        } else if (p.governmentType() == Settlement.Government.CHIEFDOM) {
            lines.add(Component.translatable("bannerbound.policy.chiefdom_only").withStyle(ChatFormatting.DARK_AQUA));
        }
        g.renderComponentTooltip(this.font, lines, mouseX, mouseY);
    }

    /** Cap on player heads shown inside a policy-row suggestion badge — keeps it inside the
     *  narrow Available-list column. Overflow is folded into the {@code +N} count. */
    private static final int MAX_POLICY_FACES = 3;

    /** Pixel width the suggestion badge for {@code n} suggesters occupies, so the policy name can
     *  be clipped to leave room. Must match {@link #drawPolicySuggestionBadge}'s layout. */
    private int policySuggestBadgeWidth(int n) {
        int shown = Math.min(MAX_POLICY_FACES, n);
        return shown * (8 + 1) + 1 + this.font.width("+" + n) + 4;
    }

    /** The shared "+N + player heads" suggestion badge (same one the research screen uses),
     *  laid out right-aligned to END at {@code rightX} and centred on {@code centerY}: up to
     *  {@link #MAX_POLICY_FACES} 8×8 skin heads followed by a green {@code +N}. The local
     *  player's own head gets a brighter outline so they can spot their suggestion. */
    private void drawPolicySuggestionBadge(GuiGraphics g, int rightX, int centerY,
                                           java.util.List<java.util.UUID> suggesters) {
        int n = suggesters.size();
        int headSize = 8, padding = 1;
        int shown = Math.min(MAX_POLICY_FACES, n);
        String text = "+" + n;
        int textX = rightX - this.font.width(text);
        g.drawString(this.font, Component.literal(text).withStyle(ChatFormatting.GREEN),
            textX, centerY - this.font.lineHeight / 2, 0xFF66FF66, false);
        int faceY = centerY - headSize / 2;
        int firstX = textX - padding - shown * (headSize + padding);
        java.util.UUID localId = (this.minecraft != null && this.minecraft.player != null)
            ? this.minecraft.player.getUUID() : null;
        for (int i = 0; i < shown; i++) {
            java.util.UUID id = suggesters.get(i);
            int hx = firstX + i * (headSize + padding);
            net.minecraft.resources.ResourceLocation skin = resolveSkin(id);
            if (skin != null) {
                net.minecraft.client.gui.components.PlayerFaceRenderer.draw(
                    g, new net.minecraft.client.resources.PlayerSkin(
                        skin, null, null, null,
                        net.minecraft.client.resources.PlayerSkin.Model.WIDE, true),
                    hx, faceY, headSize);
            } else {
                g.fill(hx, faceY, hx + headSize, faceY + headSize, 0xFF55EE55);
            }
            if (id.equals(localId)) {
                g.renderOutline(hx - 1, faceY - 1, headSize + 2, headSize + 2, 0xFFEEFFEE);
            }
        }
    }

    /** Resolve a player UUID to its skin texture via the connection's PlayerInfo cache; null
     *  when offline + uncached (caller then falls back to a plain green square). */
    private net.minecraft.resources.ResourceLocation resolveSkin(java.util.UUID id) {
        if (this.minecraft == null || this.minecraft.getConnection() == null) return null;
        net.minecraft.client.multiplayer.PlayerInfo info =
            this.minecraft.getConnection().getPlayerInfo(id);
        return info == null ? null : info.getSkin().texture();
    }

    // ─── Palettes tab ─────────────────────────────────────────────────────────────────────
    // Structurally a clone of the Policies tab (same two-column drag/slot/vote/suggest flow,
    // driven by ClientPaletteState) with taller boxes: each slot/list row shows the palette's
    // name plus a row of its block icons, and hovering a block shows that palette's bonus.

    private void buildPalettesTab(int panelX, int bodyTop, int bodyBottom) {
        palColW = (PANEL_WIDTH - 28) / 2;
        palLeftX = panelX + 10;
        palRightX = panelX + PANEL_WIDTH / 2 + 4;
        int headerY = bodyTop + 2;
        palSlotsTop = headerY + 14;
        palSlotCount = era.activePaletteSlots();
        palListTop = headerY + 14;
        palListH = bodyBottom - palListTop - 4;

        palVisibleList.clear();
        for (String id : ClientPaletteState.getAvailableNotActive()) {
            if (id.equals(pStagedAddId)) continue;
            palVisibleList.add(id);
        }

        this.addRenderableOnly((g, mx, my, pt) -> drawPalettesTab(g, mx, my));

        // Action buttons pinned just above the shared Cancel button, so the (taller) slot column
        // can't push them off the panel at high eras.
        int actionY = bodyBottom - 18;
        int halfW = (palColW - 4) / 2;
        boolean pending = ClientPaletteState.hasPending();
        if (isCouncil() && pending) {
            Boolean ownVote = (this.minecraft != null && this.minecraft.player != null)
                ? ClientPaletteState.getOwnConfirmVote(this.minecraft.player.getUUID()) : null;
            Button agree = PolishButton.polished(
                Component.translatable("bannerbound.palette.agree"),
                b -> { PacketDistributor.sendToServer(new CastPaletteVotePayload(true)); paletteFeedback.spawnAtCursor(); })
                .bounds(palLeftX, actionY, halfW, 18).build();
            Button disagree = PolishButton.polished(
                Component.translatable("bannerbound.palette.disagree"),
                b -> { PacketDistributor.sendToServer(new CastPaletteVotePayload(false)); paletteFeedback.spawnAtCursor(); })
                .bounds(palLeftX + halfW + 4, actionY, halfW, 18).build();
            if (ownVote != null) { agree.active = false; disagree.active = false; }
            this.addRenderableWidget(agree);
            this.addRenderableWidget(disagree);
        } else if (!pending && !isSuggestMode() && (pStagedAddId != null || pStagedRemoveId != null)) {
            this.addRenderableWidget(PolishButton.polished(
                Component.translatable("bannerbound.palette.confirm"),
                b -> sendStagedPaletteChange())
                .bounds(palLeftX, actionY, halfW, 18).build());
            this.addRenderableWidget(PolishButton.polished(
                Component.translatable("gui.cancel"),
                b -> { clearPaletteStaging(); this.rebuildWidgets(); })
                .bounds(palLeftX + halfW + 4, actionY, halfW, 18).build());
        }
    }

    private void sendStagedPaletteChange() {
        PacketDistributor.sendToServer(new ProposePaletteChangePayload(
            pStagedSlot < 0 ? 0 : pStagedSlot,
            pStagedAddId == null ? "" : pStagedAddId,
            pStagedRemoveId == null ? "" : pStagedRemoveId));
        paletteFeedback.spawnAtCursor();
        clearPaletteStaging();
        this.rebuildWidgets();
    }

    private void clearPaletteStaging() {
        pStagedAddId = null;
        pStagedRemoveId = null;
        pStagedSlot = -1;
        draggingPaletteId = null;
    }

    private String paletteSlotDisplayId(int i) {
        java.util.List<String> active = ClientPaletteState.getActive();
        String base = i < active.size() ? active.get(i) : null;
        if (ClientPaletteState.hasPending()) {
            if (ClientPaletteState.getPendingSlot() == i
                    && !ClientPaletteState.getPendingAddId().isEmpty()) {
                return ClientPaletteState.getPendingAddId();
            }
            return base;
        }
        if (pStagedSlot == i && pStagedAddId != null) return pStagedAddId;
        if (base != null && base.equals(pStagedRemoveId)) return base;
        return base;
    }

    private void drawPalettesTab(GuiGraphics g, int mouseX, int mouseY) {
        g.drawString(this.font, Component.translatable("bannerbound.palette.active_header"),
            palLeftX, palSlotsTop - 12, 0xFFD8C070, false);
        g.drawString(this.font, Component.translatable("bannerbound.palette.available_header"),
            palRightX, palListTop - 12, 0xFFD8C070, false);

        boolean pending = ClientPaletteState.hasPending();
        String hoverBlock = null;
        float hoverBonus = 0f;

        // Left: active slots — name line + a row of block icons.
        for (int i = 0; i < palSlotCount; i++) {
            int sy = palSlotsTop + i * (PALETTE_SLOT_HEIGHT + 4);
            String id = paletteSlotDisplayId(i);
            boolean isStagedAdd = !pending && pStagedSlot == i && pStagedAddId != null;
            boolean isPendingSlot = pending && ClientPaletteState.getPendingSlot() == i
                && !ClientPaletteState.getPendingAddId().isEmpty();
            boolean isRemoving = id != null && id.equals(pStagedRemoveId)
                || (pending && id != null && id.equals(ClientPaletteState.getPendingRemoveId()));
            int border = isPendingSlot ? 0xFFE0D055
                : isStagedAdd ? 0xFF55E055
                : isRemoving ? 0xFFE05555
                : 0xFF505050;
            g.fill(palLeftX, sy, palLeftX + palColW, sy + PALETTE_SLOT_HEIGHT, 0xFF000000);
            g.renderOutline(palLeftX, sy, palColW, PALETTE_SLOT_HEIGHT, border);
            if (id == null) {
                g.drawString(this.font,
                    clip(Component.translatable("bannerbound.palette.empty_slot")
                        .withStyle(ChatFormatting.DARK_GRAY), palColW - 8),
                    palLeftX + 4, sy + (PALETTE_SLOT_HEIGHT - this.font.lineHeight) / 2, 0xFF707070, false);
            } else {
                g.drawString(this.font, clip(Component.literal(ClientPaletteState.nameOf(id)), palColW - 8),
                    palLeftX + 4, sy + 3, 0xFFFFFFFF, false);
                String h = drawPaletteIcons(g, id, palLeftX + 4, sy + 3 + this.font.lineHeight + 1,
                    palColW - 8, mouseX, mouseY);
                if (h != null) { hoverBlock = h; hoverBonus = paletteBonus(id, h); }
            }
        }

        if (pending && isCouncil()) {
            int tallyY = palSlotsTop + palSlotCount * (PALETTE_SLOT_HEIGHT + 4) - 2;
            g.drawString(this.font, Component.translatable("bannerbound.palette.vote_progress",
                    ClientPaletteState.countVotesCast(), ClientPaletteState.getOnlineMemberCount(),
                    ClientPaletteState.countAgrees())
                    .withStyle(ChatFormatting.GRAY),
                palLeftX, tallyY, 0xFFAAAAAA, false);
        }

        // Right: available list (scrollable) — same name + icon-row layout per row.
        int x = palRightX, y = palListTop, w = palColW, h = palListH;
        g.fill(x, y, x + w, y + h, 0xFF181818);
        g.renderOutline(x, y, w, h, 0xFF2A2A2A);
        java.util.List<String> list = renderedPaletteList();
        int contentH = list.size() * PALETTE_ROW_HEIGHT;
        int maxScroll = Math.max(0, contentH - h + 4);
        if (palettesScrollY < 0) palettesScrollY = 0;
        if (palettesScrollY > maxScroll) palettesScrollY = maxScroll;
        if (list.isEmpty()) {
            java.util.List<net.minecraft.util.FormattedCharSequence> wrapped =
                this.font.split(Component.translatable("bannerbound.palette.none_available"), w - 12);
            int ty = y + h / 2 - (wrapped.size() * this.font.lineHeight) / 2;
            for (net.minecraft.util.FormattedCharSequence line : wrapped) {
                g.drawCenteredString(this.font, line, x + w / 2, ty, 0xFF808080);
                ty += this.font.lineHeight;
            }
        } else {
            g.enableScissor(scissorX(x + 1), scissorY(y + 1),
                scissorX(x + w - 1), scissorY(y + h - 1));
            for (int i = 0; i < list.size(); i++) {
                int rowY = y + 2 + i * PALETTE_ROW_HEIGHT - palettesScrollY;
                if (rowY + PALETTE_ROW_HEIGHT < y || rowY > y + h) continue;
                String id = list.get(i);
                g.fill(x + 2, rowY, x + w - 2, rowY + PALETTE_ROW_HEIGHT - 2, 0xFF000000);
                java.util.List<java.util.UUID> suggesters = ClientPaletteState.getSuggesters(id);
                int badgeW = suggesters.isEmpty() ? 0 : policySuggestBadgeWidth(suggesters.size());
                g.drawString(this.font, clip(Component.literal(ClientPaletteState.nameOf(id)), w - 10 - badgeW),
                    x + 5, rowY + 2, 0xFFE0E0E0, false);
                if (!suggesters.isEmpty()) {
                    drawPolicySuggestionBadge(g, x + w - 4, rowY + 2 + this.font.lineHeight / 2, suggesters);
                }
                String hh = drawPaletteIcons(g, id, x + 5, rowY + 2 + this.font.lineHeight + 1,
                    w - 10, mouseX, mouseY);
                if (hh != null) { hoverBlock = hh; hoverBonus = paletteBonus(id, hh); }
            }
            g.disableScissor();
            if (maxScroll > 0) {
                int trackX = x + w - SCROLLBAR_WIDTH - 1;
                g.fill(trackX, y + 1, trackX + SCROLLBAR_WIDTH, y + h - 1, 0xFF0A0A0A);
                int thumbH = Math.max(8, (h - 2) * h / contentH);
                int thumbY = y + 1 + (h - 2 - thumbH) * palettesScrollY / maxScroll;
                g.fill(trackX, thumbY, trackX + SCROLLBAR_WIDTH, thumbY + thumbH, 0xFF606060);
            }
        }

        // Per-block bonus tooltip (rendered last so it sits above the icons/scrollbar).
        if (hoverBlock != null) {
            net.minecraft.world.item.ItemStack st = blockStack(hoverBlock);
            Component line = Component.translatable("bannerbound.palette.block_bonus",
                    st.getHoverName().getString(), String.format("%+.2f", hoverBonus))
                .withStyle(ChatFormatting.LIGHT_PURPLE);
            g.renderTooltip(this.font, line, mouseX, mouseY);
        }
    }

    /** Renders {@code paletteId}'s block icons in a 16px row starting at ({@code x},{@code y}),
     *  clipped to {@code maxW}, and returns the block id under the cursor (or null). Bypasses the
     *  unknown-item swap so palette blocks always show their real icon, like the research tooltip. */
    private String drawPaletteIcons(GuiGraphics g, String paletteId, int x, int y, int maxW,
                                    int mouseX, int mouseY) {
        ClientPaletteState.Def def = ClientPaletteState.getDef(paletteId);
        if (def == null) return null;
        String hovered = null;
        int ix = x;
        int limit = x + maxW;
        UnknownItemHelper.setBypassUnknownSwap(true);
        try {
            for (String blockId : def.blockIds()) {
                if (ix + PALETTE_ICON > limit) break;
                net.minecraft.world.item.ItemStack stack = blockStack(blockId);
                if (!stack.isEmpty()) g.renderItem(stack, ix, y);
                if (mouseX >= ix && mouseX < ix + PALETTE_ICON
                        && mouseY >= y && mouseY < y + PALETTE_ICON) {
                    hovered = blockId;
                }
                ix += PALETTE_ICON + PALETTE_ICON_GAP;
            }
        } finally {
            UnknownItemHelper.setBypassUnknownSwap(false);
        }
        return hovered;
    }

    private static float paletteBonus(String paletteId, String blockId) {
        ClientPaletteState.Def def = ClientPaletteState.getDef(paletteId);
        return def == null ? 0f : def.bonusOf(blockId);
    }

    private static net.minecraft.world.item.ItemStack blockStack(String blockId) {
        net.minecraft.resources.ResourceLocation rl = net.minecraft.resources.ResourceLocation.tryParse(blockId);
        if (rl == null) return net.minecraft.world.item.ItemStack.EMPTY;
        net.minecraft.world.level.block.Block b =
            net.minecraft.core.registries.BuiltInRegistries.BLOCK.get(rl);
        return new net.minecraft.world.item.ItemStack(b);
    }

    private java.util.List<String> renderedPaletteList() {
        if (draggingPaletteId == null) return palVisibleList;
        java.util.List<String> out = new java.util.ArrayList<>(palVisibleList.size());
        for (String id : palVisibleList) {
            if (!id.equals(draggingPaletteId)) out.add(id);
        }
        return out;
    }

    private boolean isOverPaletteList(double mouseX, double mouseY) {
        return mouseX >= palRightX && mouseX <= palRightX + palColW
            && mouseY >= palListTop && mouseY <= palListTop + palListH;
    }

    private int paletteSlotAtCursor(double mouseX, double mouseY) {
        if (mouseX < palLeftX || mouseX > palLeftX + palColW) return -1;
        for (int i = 0; i < palSlotCount; i++) {
            int sy = palSlotsTop + i * (PALETTE_SLOT_HEIGHT + 4);
            if (mouseY >= sy && mouseY <= sy + PALETTE_SLOT_HEIGHT) return i;
        }
        return -1;
    }

    /** Palette drag card — twin of {@link #drawDragCard}, labelled with the palette's name. */
    private void drawPaletteDragCard(GuiGraphics graphics, int mouseX, int mouseY) {
        double vx = Double.isNaN(dragPrevMouseX) ? 0.0 : (mouseX - dragPrevMouseX);
        dragPrevMouseX = mouseX;
        double target = Math.max(-18.0, Math.min(18.0, vx * 1.2));
        dragSwayAngle += (target - dragSwayAngle) * 0.35;

        Component name = Component.literal(ClientPaletteState.nameOf(draggingPaletteId));
        int cardW = this.font.width(name) + 12;
        int cardH = this.font.lineHeight + 8;
        int halfW = cardW / 2;

        var pose = graphics.pose();
        pose.pushPose();
        pose.translate(mouseX, mouseY + 2.0, 350.0);
        pose.mulPose(com.mojang.math.Axis.ZP.rotationDegrees((float) dragSwayAngle));
        graphics.fill(-halfW, 0, halfW, cardH, 0xF0202830);
        graphics.renderOutline(-halfW, 0, cardW, cardH, 0xFFD8C070);
        graphics.drawString(this.font, name, -halfW + 6, 4, 0xFFFFFF55, false);
        pose.popPose();
    }

    /** Localised display name for a food production source ("farming" → "Fields", etc.). Falls back to
     *  the raw source id for any source without a lang key. */
    private static Component foodSourceName(String source) {
        String key = "bannerbound.townhall.food_source." + source;
        return Component.translatableWithFallback(key, source);
    }

    private void drawStatsPanel(GuiGraphics graphics, int x, int y, int width, int mouseX, int mouseY) {
        int population = ClientPopulationState.getPopulation();
        double foodPerSec = ClientPopulationState.getFoodPerSecond();
        double culturePerSec = ClientPopulationState.getCulturePerSecond();
        double foodStored = ClientPopulationState.getFoodStored();
        double cultureStored = ClientPopulationState.getCultureStored();
        double storedFoodValue = ClientPopulationState.getStoredFoodValue();
        double storedFoodRate = ClientPopulationState.getStoredFoodPerSecond();
        double foodConsumption = ClientPopulationState.getFoodConsumptionPerSecond();
        java.util.Map<String, Double> foodSourceRates = ClientPopulationState.getFoodSourceRates();
        double cultureCost = ClientPopulationState.getNextCultureCost();
        double foodCap = ClientPopulationState.getFoodCap();
        double cultureCap = ClientPopulationState.getCultureCap();

        // Population + rate lines render with an outline (black 1-px stroke). The food/culture
        // lines embed icon glyphs from the custom font; outline gives those icons a clean
        // silhouette against the moss-flecked stone instead of a drop-shadow blur that gets
        // lost in the texture.
        int populationMax = ClientPopulationState.getPopulationMax();
        MutableComponent popLine = Component.translatable("bannerbound.townhall.population_x_of_max",
                population, populationMax)
            .withStyle(ChatFormatting.WHITE);
        OutlinedText.draw(graphics, this.font, popLine, x, y, 0xFFFFFFFF);

        // Food block: title ("Food <icon>:") above rate + stored / cost (/ cap when boosted).
        int lineY = y + 15;
        MutableComponent foodTitle = Component.translatable("bannerbound.townhall.food_title")
            .append(Icons.food(era))
            .append(Component.literal(":"));
        int foodHoverTop = lineY;
        OutlinedText.draw(graphics, this.font, foodTitle, x, lineY, 0xFFE2C065);
        lineY += 16;
        // Food is a survival BUFFER + a net trend — NOT a per-citizen cost. Growth is paced by the
        // Culture bar below; food only has to be in SURPLUS for the settlement to grow (you can't take
        // in newcomers you can't feed). So lead with the net food/sec and a plain status — Surplus
        // (green light to grow), Draining (deficit, cushion shrinking) or Starving (empty + eating) —
        // and gauge the stored buffer: the cushion that keeps everyone fed while production dips.
        boolean consuming = foodConsumption > 0.0001;
        boolean surplus = foodPerSec >= -0.0001;
        int statusColor = !consuming ? 0xFF7FE07F
            : foodStored <= 0.0 ? 0xFFE05050            // empty buffer + eating → starving
            : surplus ? 0xFF7FE07F                       // producing ≥ eaten → fed, cushion holds/grows
            : 0xFFE2C065;                                // deficit, cushion draining → warning
        MutableComponent foodStatus = consuming && foodStored <= 0.0
            ? Component.translatable("bannerbound.townhall.food.starving")
            : consuming && !surplus
                ? Component.translatable("bannerbound.townhall.food.draining")
                : Component.translatable("bannerbound.townhall.food.surplus");
        MutableComponent foodLine = Component.literal(String.format("%+.2f", foodPerSec))
            .append(Icons.food(era))
            .append(Component.literal("/s  "))
            .append(foodStatus);
        OutlinedText.draw(graphics, this.font, foodLine, x, lineY, statusColor);
        // Buffer gauge: stored food cushion (0..cap), coloured by health. Full = well-stocked;
        // draining = eaten faster than produced; empty = starving.
        drawStockpileBar(graphics, x, lineY + 13, width, foodStored, foodCap, foodCap, statusColor);
        if (mouseX >= x && mouseX <= x + width && mouseY >= foodHoverTop && mouseY <= lineY + 24) {
            java.util.List<Component> tooltip = new java.util.ArrayList<>();
            if (foodConsumption > 0.0001) {
                // Organized settlement: net trend, passive stored-food income, the population's drain,
                // and the runway (or self-sufficient when income covers it).
                tooltip.add(Component.translatable("bannerbound.townhall.food_tooltip.net",
                    String.format("%.2f", foodPerSec))
                    .withStyle(foodPerSec >= 0 ? ChatFormatting.GOLD : ChatFormatting.RED));
                tooltip.add(Component.translatable("bannerbound.townhall.food_tooltip.stored_rate",
                    String.format("%.2f", storedFoodRate)).withStyle(ChatFormatting.GREEN));
                tooltip.add(Component.translatable("bannerbound.townhall.food_tooltip.consumed",
                    String.format("%.2f", foodConsumption)).withStyle(ChatFormatting.RED));
                if (foodPerSec < -0.0001) {
                    tooltip.add(Component.translatable("bannerbound.townhall.statistics.runway",
                        String.format("%.1f", foodStored / (-foodPerSec) / 1200.0))
                        .withStyle(ChatFormatting.RED));
                } else {
                    tooltip.add(Component.translatable("bannerbound.townhall.statistics.sustainable")
                        .withStyle(ChatFormatting.GREEN));
                }
            } else {
                // Pre-government Hearth: split the +X/s into the founding foraging trickle ("pioneering
                // spirit") and any passive stored-food income, so neither looks like magic.
                double pioneer = Math.max(0.0, foodPerSec - storedFoodRate);
                tooltip.add(Component.translatable("bannerbound.townhall.food_tooltip.pioneering",
                    String.format("%.2f", pioneer)).withStyle(ChatFormatting.GREEN));
                if (storedFoodRate > 0.0001) {
                    tooltip.add(Component.translatable("bannerbound.townhall.food_tooltip.stored_rate",
                        String.format("%.2f", storedFoodRate)).withStyle(ChatFormatting.GREEN));
                }
                tooltip.add(Component.translatable("bannerbound.townhall.food_tooltip.pioneering_desc")
                    .withStyle(ChatFormatting.DARK_GRAY));
            }
            tooltip.add(Component.translatable("bannerbound.townhall.food_tooltip.stored_value",
                String.format("%.1f", storedFoodValue)).withStyle(ChatFormatting.GRAY));
            tooltip.add(Component.translatable("bannerbound.townhall.food_tooltip.buffer",
                String.format("%.1f", foodStored), String.format("%.0f", foodCap))
                .withStyle(ChatFormatting.GRAY));
            // Food gates growth (it is no longer a per-citizen cost): a surplus is the green light for
            // newcomers, a deficit pauses immigration until production recovers.
            tooltip.add((foodPerSec >= -0.0001
                ? Component.translatable("bannerbound.townhall.food_tooltip.gate_surplus")
                : Component.translatable("bannerbound.townhall.food_tooltip.gate_deficit"))
                .withStyle(foodPerSec >= -0.0001 ? ChatFormatting.GREEN : ChatFormatting.GOLD));
            // Per-source food income — where the settlement's food is coming from.
            java.util.List<java.util.Map.Entry<String, Double>> sources = foodSourceRates.entrySet().stream()
                .filter(e -> e.getValue() != null && e.getValue() > 0.0001)
                .sorted((a, b) -> Double.compare(b.getValue(), a.getValue()))
                .toList();
            if (!sources.isEmpty()) {
                tooltip.add(Component.translatable("bannerbound.townhall.food_tooltip.sources_header")
                    .withStyle(ChatFormatting.AQUA));
                for (java.util.Map.Entry<String, Double> e : sources) {
                    tooltip.add(Component.translatable("bannerbound.townhall.food_tooltip.source_line",
                        foodSourceName(e.getKey()), String.format("%.1f", e.getValue() * 60.0))
                        .withStyle(ChatFormatting.GRAY));
                }
            }
            graphics.renderComponentTooltip(this.font, tooltip, mouseX, mouseY);
        }

        // Culture block: same structure as food.
        lineY += 31;
        int cultureHoverTop = lineY;
        MutableComponent cultureTitle = Component.translatable("bannerbound.townhall.culture_title")
            .append(Icons.culture(era))
            .append(Component.literal(":"));
        OutlinedText.draw(graphics, this.font, cultureTitle, x, lineY, 0xFFC97FFF);
        lineY += 16;
        String cultureAmounts = cultureCap > cultureCost + 0.001
            ? String.format("/s  %.1f / %.1f / %.0f", cultureStored, cultureCost, cultureCap)
            : String.format("/s  %.1f / %.1f", cultureStored, cultureCost);
        MutableComponent cultureLine = Component.literal(String.format("%.2f", culturePerSec))
            .append(Icons.culture(era))
            .append(Component.literal(cultureAmounts))
            .append(Icons.culture(era));
        OutlinedText.draw(graphics, this.font, cultureLine, x, lineY, 0xFFC97FFF);
        drawStockpileBar(graphics, x, lineY + 13, width, cultureStored, cultureCost, cultureCap, 0xFFC97FFF);
        // Hovering the culture block breaks the rate down so it's clear that claimed-territory block
        // appeal feeds culture: the total (which already folds appeal in), then the signed appeal share
        // (+ from attractive chunks, − from ugly ones) and a nudge to beautify the territory.
        if (mouseX >= x && mouseX <= x + width && mouseY >= cultureHoverTop && mouseY <= lineY + 24) {
            double appealCulture = ClientPopulationState.getAppealCulturePerSecond();
            // Everything that isn't territory appeal: the flat baseline (DEFAULT_CULTURE_PER_SECOND)
            // plus any culture research / faith / status bonuses folded into culturePerSecond.
            double baseCulture = culturePerSec - appealCulture;
            java.util.List<Component> tooltip = new java.util.ArrayList<>();
            tooltip.add(Component.translatable("bannerbound.townhall.culture_tooltip.total",
                String.format("%.2f", culturePerSec)).withStyle(ChatFormatting.LIGHT_PURPLE));
            tooltip.add(Component.translatable("bannerbound.townhall.culture_tooltip.base",
                String.format("%+.2f", baseCulture))
                .withStyle(baseCulture > 0.0001 ? ChatFormatting.GREEN
                    : baseCulture < -0.0001 ? ChatFormatting.RED
                    : ChatFormatting.GRAY));
            tooltip.add(Component.translatable("bannerbound.townhall.culture_tooltip.appeal",
                String.format("%+.2f", appealCulture))
                .withStyle(appealCulture > 0.0001 ? ChatFormatting.GREEN
                    : appealCulture < -0.0001 ? ChatFormatting.RED
                    : ChatFormatting.GRAY));
            tooltip.add(Component.translatable("bannerbound.townhall.culture_tooltip.appeal_desc")
                .withStyle(ChatFormatting.DARK_GRAY));
            graphics.renderComponentTooltip(this.font, tooltip, mouseX, mouseY);
        }

        // Devotion block — directly below culture generation (FAITH_PLAN.md), shown once a
        // faith is followed. Same title-above-values rhythm as Food/Culture (no stockpile
        // bar yet — devotion has no cap until shrines land). Silver-gold faith palette.
        if (ClientFaithState.hasFaith()) {
            lineY += 31;
            MutableComponent devotionTitle = Component.translatable("bannerbound.townhall.devotion_title")
                .append(Component.literal(" "))
                .append(Icons.faith(era))
                .append(Component.literal(":"));
            OutlinedText.draw(graphics, this.font, devotionTitle, x, lineY, 0xFFE8D9A0);
            lineY += 16;
            MutableComponent devotionLine = Component.literal(
                    String.format("%.2f", ClientFaithState.devotionPerSecond()))
                .append(Icons.faith(era))
                .append(Component.literal(String.format("/s  %.1f", ClientFaithState.devotionStored())))
                .append(Icons.faith(era));
            OutlinedText.draw(graphics, this.font, devotionLine, x, lineY, 0xFFE8D9A0);
        }
    }

    /**
     * Bar with two zones: the main color from 0..cost (immigration target), then a darker shade
     * from cost..cap (stockpile overflow zone). A thin tick line marks the immigration threshold.
     */
    private void drawStockpileBar(GuiGraphics graphics, int x, int y, int width,
                                  double value, double cost, double cap, int color) {
        graphics.fill(x, y, x + width, y + 4, 0xFF202020);
        graphics.renderOutline(x - 1, y - 1, width + 2, 6, 0xFF404040);
        if (cap <= 0) return;

        double effectiveMax = Math.max(cap, cost);
        int costX = (int) Math.round(width * (cost / effectiveMax));

        double fillVal = Math.min(value, cost);
        int fillWidth = (int) Math.round(width * (fillVal / effectiveMax));
        graphics.fill(x, y, x + fillWidth, y + 4, color);

        if (value > cost) {
            int overflowWidth = (int) Math.round(width * ((value - cost) / effectiveMax));
            // Dim the overflow portion so the eye still reads the cost threshold as "primary".
            int overflowColor = (color & 0xFF000000) | ((color & 0xFEFEFE) >> 1);
            graphics.fill(x + costX, y, x + costX + overflowWidth, y + 4, overflowColor);
        }

        // Tick line at the immigration threshold — only visible when cap > cost.
        if (cap > cost + 0.001 && costX > 0 && costX < width) {
            graphics.fill(x + costX, y - 1, x + costX + 1, y + 5, 0xFFFFFFFF);
        }
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // Two background passes, both OUTSIDE the animation pose: this screen has always rendered
        // the dim/blur twice (its own call + the one inside Screen.render), so the doubled pass is
        // kept deliberately to preserve the established darkness — but the darkening must never
        // zoom with the panel, hence the renderables are looped manually below instead of letting
        // super.render() re-run the background inside the pose.
        this.renderBackground(graphics, mouseX, mouseY, partialTick);
        this.renderBackground(graphics, mouseX, mouseY, partialTick);

        // Auto-fit: scale the whole panel to a consistent fraction of the screen so it reads right
        // at ANY vanilla GUI Scale (the fixed-pixel panel was "too small" at scale 2 and "too big"
        // at scale 3). Mouse coords are mapped into the same scaled space (see virtualX/Y + the
        // mouse handlers) so clicks and hover stay aligned with the scaled visuals.
        float fit = fitScale();
        boolean fitted = Math.abs(fit - 1f) > 0.001f;
        int tmx = fitted ? (int) Math.round(virtualX(mouseX)) : mouseX;
        int tmy = fitted ? (int) Math.round(virtualY(mouseY)) : mouseY;

        // Polish: the panel settles in on open (zoom 0.96→1 + 10px upward drift, ~160ms ease-out)
        // and gives a tiny 2px slide on tab switches. Config.UI_ANIMATIONS reverts to static.
        boolean animate = com.bannerbound.core.Config.UI_ANIMATIONS.get();
        float open = animate ? easeOutCubic(animProgress(openedAtMs, 160f)) : 1f;
        float sw = animate ? easeOutCubic(animProgress(tabSwitchedAtMs, 90f)) : 1f;
        boolean posed = animate && (open < 1f || sw < 1f);
        final float cx = this.width / 2f;
        final float cy = this.height / 2f;
        if (fitted) {
            graphics.pose().pushPose();
            graphics.pose().translate(cx, cy, 0);
            graphics.pose().scale(fit, fit, 1f);
            graphics.pose().translate(-cx, -cy, 0);
        }
        if (posed) {
            float scale = 0.96f + 0.04f * open;
            graphics.pose().pushPose();
            graphics.pose().translate(cx, cy, 0);
            graphics.pose().scale(scale, scale, 1f);
            graphics.pose().translate(-cx, -cy + (1f - open) * 10f + (1f - sw) * 1f, 0);
        }
        for (net.minecraft.client.gui.components.Renderable renderable : this.renderables) {
            renderable.render(graphics, tmx, tmy, partialTick);
        }
        // Drag ghost: the lifted policy renders as a small card hanging from the cursor, swaying
        // with a little physical lag as the player moves it horizontally.
        if (activeTab == Tab.POLICIES && draggingPolicyId != null) {
            drawDragCard(graphics, tmx, tmy);
        }
        if (activeTab == Tab.POLICIES) policyFeedback.render(graphics);
        if (activeTab == Tab.PALETTES && draggingPaletteId != null) {
            drawPaletteDragCard(graphics, tmx, tmy);
        }
        if (activeTab == Tab.PALETTES) paletteFeedback.render(graphics);
        if (posed) {
            graphics.pose().popPose();
        }
        if (fitted) {
            graphics.pose().popPose();
        }
        // Wall-system feedback banner (e.g. a save error arriving as the designer closed
        // back to this screen) — outside the pose so it never zooms.
        if (activeTab == Tab.WALLS) {
            ClientWallStatus.render(graphics, this.font, this.width / 2, this.height / 2 - 116);
        }
    }

    /** Uniform scale applied to the whole Town Hall panel so it fills a consistent ~82% of screen
     *  height (and fits the width) regardless of the vanilla GUI Scale setting — fixes the panel
     *  reading "too small" at GUI Scale 2 and "too big" at 3. Clamped to a sane range. */
    private float fitScale() {
        float byH = (this.height * 0.82f) / PANEL_HEIGHT;
        float byW = (this.width * 0.90f) / PANEL_WIDTH;
        return Math.max(0.5f, Math.min(Math.min(byH, byW), 2.5f));
    }

    /** Map a real (screen) mouse coordinate into the auto-fit panel space (inverse of
     *  {@link #fitScale} about screen centre), so the custom hit-tests line up with the visuals. */
    private double virtualX(double screenX) { return (screenX - this.width / 2.0) / fitScale() + this.width / 2.0; }
    private double virtualY(double screenY) { return (screenY - this.height / 2.0) / fitScale() + this.height / 2.0; }

    /** Map a panel-layout coordinate to the real screen coordinate the auto-fit pose draws it at
     *  (the forward of {@link #virtualX}/{@link #virtualY}). {@link GuiGraphics#enableScissor}
     *  takes raw screen-space coords and does NOT honour the current pose, so any list that
     *  scissor-clips inside the scaled panel must pre-map its bounds through these — otherwise the
     *  clip rect sits at the unscaled position/size and crops the scaled content on every edge. */
    private int scissorX(double layoutX) {
        return (int) Math.round((layoutX - this.width / 2.0) * fitScale() + this.width / 2.0);
    }
    private int scissorY(double layoutY) {
        return (int) Math.round((layoutY - this.height / 2.0) * fitScale() + this.height / 2.0);
    }

    /** 0→1 progress of an animation started at {@code startedMs} lasting {@code durationMs};
     *  1 when the start stamp is 0 (never triggered). */
    private static float animProgress(long startedMs, float durationMs) {
        if (startedMs <= 0L) return 1f;
        return Math.min(1f, (net.minecraft.Util.getMillis() - startedMs) / durationMs);
    }

    private static float easeOutCubic(float t) {
        float inv = 1f - t;
        return 1f - inv * inv * inv;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        mouseX = virtualX(mouseX); mouseY = virtualY(mouseY); // map into auto-fit panel space
        if (activeTab == Tab.STATISTICS && button == 0 && handleStatsClick(mouseX, mouseY)) return true;
        if (activeTab == Tab.POLICIES) {
            // Pick up a policy from the Available list to start a drag — the row lifts out of
            // the list (which compacts) and follows the cursor as a swaying card.
            if (button == 0 && !ClientPolicyState.hasPending()) {
                if (isOverPolicyList(mouseX, mouseY)) {
                    java.util.List<String> list = renderedPolicyList();
                    int idx = (int) ((mouseY - (polListTop + 2) + policiesScrollY) / POLICY_ROW_HEIGHT);
                    if (idx >= 0 && idx < list.size()) {
                        String id = list.get(idx);
                        if (isSuggestMode()) {
                            // Chiefdom non-chief: one click on the row toggles a suggestion — no
                            // drag, no Suggest button. The "+N + heads" badge is the feedback.
                            PacketDistributor.sendToServer(new SuggestPolicyPayload(id));
                            policyFeedback.spawnAtCursor();
                            playDragSound(1.0f);
                            return true;
                        }
                        draggingPolicyId = id;
                        dragPrevMouseX = mouseX;   // seed velocity tracking
                        dragSwayAngle = 0.0;
                        playDragSound(1.0f);
                        return true;
                    }
                }
                // Click a filled slot (no drag) → toggle staging its removal. Suggest-mode
                // players can't propose removals, so slots are inert for them.
                if (!isSuggestMode()) {
                    int slot = slotAtCursor(mouseX, mouseY);
                    if (slot >= 0) {
                        String[] assign = slotAssignment();
                        String base = slot < assign.length ? assign[slot] : null;
                        if (base != null) {
                            stagedRemoveId = base.equals(stagedRemoveId) ? null : base;
                            this.rebuildWidgets();
                            return true;
                        }
                    }
                }
            }
        }
        if (activeTab == Tab.PALETTES) {
            if (button == 0 && !ClientPaletteState.hasPending()) {
                if (isOverPaletteList(mouseX, mouseY)) {
                    java.util.List<String> list = renderedPaletteList();
                    int idx = (int) ((mouseY - (palListTop + 2) + palettesScrollY) / PALETTE_ROW_HEIGHT);
                    if (idx >= 0 && idx < list.size()) {
                        String id = list.get(idx);
                        if (isSuggestMode()) {
                            PacketDistributor.sendToServer(new SuggestPalettePayload(id));
                            paletteFeedback.spawnAtCursor();
                            playDragSound(1.0f);
                            return true;
                        }
                        draggingPaletteId = id;
                        dragPrevMouseX = mouseX;
                        dragSwayAngle = 0.0;
                        playDragSound(1.0f);
                        return true;
                    }
                }
                if (!isSuggestMode()) {
                    int slot = paletteSlotAtCursor(mouseX, mouseY);
                    if (slot >= 0) {
                        java.util.List<String> active = ClientPaletteState.getActive();
                        String base = slot < active.size() ? active.get(slot) : null;
                        if (base != null) {
                            pStagedRemoveId = base.equals(pStagedRemoveId) ? null : base;
                            this.rebuildWidgets();
                            return true;
                        }
                    }
                }
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        mouseX = virtualX(mouseX); mouseY = virtualY(mouseY); // map into auto-fit panel space
        if (activeTab == Tab.POLICIES && draggingPolicyId != null) {
            int slot = slotAtCursor(mouseX, mouseY);
            java.util.List<String> slotTypes = ClientPolicyState.getSlotTypes();
            String slotType = slot >= 0 && slot < slotTypes.size() ? slotTypes.get(slot) : "";
            // Only accept the drop into a slot whose type matches the dragged policy (signature
            // policies → the SIGNATURE slot). A type-mismatched drop just returns it to the list.
            if (slot >= 0 && slotAcceptsPolicy(slotType, draggingPolicyId)) {
                // If the slot holds an active policy, the drop replaces it (stage add + displaced
                // removal).
                String[] assign = slotAssignment();
                String occupant = slot < assign.length ? assign[slot] : null;
                stagedAddId = draggingPolicyId;
                stagedSlot = slot;
                stagedRemoveId = occupant;
                this.rebuildWidgets();
            }
            // else: dropped back over the list (or anywhere else) → the item simply returns to
            // the list, which the next rebuild/render restores. Either way it's a "drop".
            draggingPolicyId = null;
            dragPrevMouseX = Double.NaN;
            dragSwayAngle = 0.0;
            playDragSound(0.8f);
            return true;
        }
        if (activeTab == Tab.PALETTES && draggingPaletteId != null) {
            int slot = paletteSlotAtCursor(mouseX, mouseY);
            if (slot >= 0) {
                java.util.List<String> active = ClientPaletteState.getActive();
                String occupant = slot < active.size() ? active.get(slot) : null;
                pStagedAddId = draggingPaletteId;
                pStagedSlot = slot;
                pStagedRemoveId = occupant;
                this.rebuildWidgets();
            }
            draggingPaletteId = null;
            dragPrevMouseX = Double.NaN;
            dragSwayAngle = 0.0;
            playDragSound(0.8f);
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
