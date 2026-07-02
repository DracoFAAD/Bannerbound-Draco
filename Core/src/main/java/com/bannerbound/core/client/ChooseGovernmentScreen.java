package com.bannerbound.core.client;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

import com.bannerbound.core.network.CastGovernmentVotePayload;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Sub-screen opened from the town hall's "Choose Government" button after the Code of Laws
 * prompt fires. Two stages:
 * <ol>
 *   <li>The player toggles between {@code COUNCIL} and {@code CHIEFDOM} as often as they want
 *       — these buttons just update the local selection, nothing leaves the client.</li>
 *   <li>Pressing <b>Cast Vote</b> (greyed until a selection exists) fires the
 *       {@link CastGovernmentVotePayload}. Once sent, the option buttons + Cast Vote button
 *       all lock; the player cannot change their pick.</li>
 * </ol>
 *
 * <p>The screen does NOT auto-refresh tallies — the values shown are a snapshot from the
 * payload that opened the town hall. Re-opening the town hall after a vote will show the
 * updated numbers; closing and re-opening this screen is the intended "refresh" gesture.
 */
@OnlyIn(Dist.CLIENT)
@ApiStatus.Internal
public class ChooseGovernmentScreen extends PolishedScreen {
    private static final int PANEL_W = 220;
    private static final int PANEL_H = 200;
    private static final int BTN_W = 180;
    private static final int BTN_H = 28;
    private static final int BTN_PITCH = 36;

    @Nullable private final Screen parent;
    /** Mutable so the casting player's own +1 can be reflected locally the instant they
     *  press Cast Vote — without waiting for the server to push a fresh snapshot. The other
     *  player's vote still requires a server-side refresh. */
    private int councilVotes;
    private int chiefdomVotes;
    private final int onlineMembers;

    /** Local-only: what the player has currently *selected* (0 = none, 1 = council, 2 = chiefdom).
     *  Initialised from the server snapshot so a player whose vote is already in shows their
     *  previous pick highlighted. Toggling between 1 and 2 is free until {@link #hasCast} flips. */
    private int selected;
    /** True once this player has cast their vote (either initially or via this screen).
     *  Locks every button — no take-backsies. */
    private boolean hasCast;

    private Button councilBtn;
    private Button chiefdomBtn;
    private Button castBtn;
    private final TransientClickFeedback feedback = new TransientClickFeedback();

    public ChooseGovernmentScreen(@Nullable Screen parent, int councilVotes, int chiefdomVotes,
                                   int onlineMembers, int playerVote) {
        super(Component.translatable("bannerbound.government.choose.title"));
        this.parent = parent;
        this.councilVotes = councilVotes;
        this.chiefdomVotes = chiefdomVotes;
        this.onlineMembers = onlineMembers;
        this.selected = playerVote;
        this.hasCast = playerVote > 0;
    }

    @Override
    protected void init() {
        int panelX = (this.width - PANEL_W) / 2;
        int panelY = (this.height - PANEL_H) / 2;
        int btnX = panelX + (PANEL_W - BTN_W) / 2;
        int firstY = panelY + 50;

        councilBtn = PolishButton.polished(
            buildOptionLabel("bannerbound.government.council", councilVotes, 1),
            b -> {
                if (hasCast) return;
                selected = 1;
                refreshButtons();
            }
        ).bounds(btnX, firstY, BTN_W, BTN_H).accent(primaryAccent()).build();
        if (onlineMembers <= 1) {
            // A council of one is just a chief with extra steps — and its vote thresholds assume
            // multiple members. Solo settlements must pick Chiefdom (server enforces this too).
            councilBtn.setTooltip(net.minecraft.client.gui.components.Tooltip.create(
                Component.translatable("bannerbound.government.council.solo_tooltip")));
        }
        addRenderableWidget(councilBtn);

        chiefdomBtn = PolishButton.polished(
            buildOptionLabel("bannerbound.government.chiefdom", chiefdomVotes, 2),
            b -> {
                if (hasCast) return;
                selected = 2;
                refreshButtons();
            }
        ).bounds(btnX, firstY + BTN_PITCH, BTN_W, BTN_H).accent(primaryAccent()).build();
        addRenderableWidget(chiefdomBtn);

        castBtn = PolishButton.polished(
            Component.translatable("bannerbound.vote.cast"),
            b -> {
                if (hasCast || selected <= 0) return;
                PacketDistributor.sendToServer(new CastGovernmentVotePayload(selected));
                // Bump the local tally so the caster's own button immediately shows their +1.
                // Other members still need a server refresh to see this vote.
                if (selected == 1) councilVotes++;
                else if (selected == 2) chiefdomVotes++;
                hasCast = true;
                refreshButtons();
                feedback.spawnAtCursor();
            }
        ).bounds(btnX, firstY + BTN_PITCH * 2 + 8, BTN_W, BTN_H).accent(primaryAccent()).build();
        addRenderableWidget(castBtn);

        addRenderableWidget(PolishButton.polished(
            Component.translatable("gui.cancel"),
            b -> this.onClose()
        ).bounds(btnX, panelY + PANEL_H - 28, BTN_W, 20).accent(primaryAccent()).build());

        refreshButtons();
    }

    /** Re-syncs button labels + enabled state with {@link #selected} and {@link #hasCast}. */
    private void refreshButtons() {
        if (councilBtn != null) {
            councilBtn.setMessage(buildOptionLabel("bannerbound.government.council", councilVotes, 1));
            councilBtn.active = !hasCast && onlineMembers > 1;   // solo settlements can't pick council
        }
        if (chiefdomBtn != null) {
            chiefdomBtn.setMessage(buildOptionLabel("bannerbound.government.chiefdom", chiefdomVotes, 2));
            chiefdomBtn.active = !hasCast;
        }
        if (castBtn != null) {
            castBtn.active = !hasCast && selected > 0;
            castBtn.setMessage(hasCast
                ? Component.translatable("bannerbound.vote.cast.locked")
                    .withStyle(ChatFormatting.GRAY)
                : Component.translatable("bannerbound.vote.cast"));
        }
    }

    private Component buildOptionLabel(String labelKey, int votes, int optionId) {
        Component label = Component.translatable(labelKey);
        Component tally = Component.literal(" — " + votes + " / " + onlineMembers);
        Component prefix = (selected == optionId)
            ? Component.literal("✓ ").withStyle(ChatFormatting.GREEN)
            : Component.literal("");
        return Component.empty().append(prefix).append(label).append(tally);
    }

    @Override
    protected void renderPolishedBackdrop(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        // Panel chrome must be drawn BEFORE the widgets — drawIdentityPanel's fill is opaque and
        // would hide the vote buttons if it stayed in renderPolishedExtras.
        int panelX = (this.width - PANEL_W) / 2;
        int panelY = (this.height - PANEL_H) / 2;
        drawIdentityPanel(g, panelX, panelY, PANEL_W, PANEL_H, identityAccents);
    }

    @Override
    protected void renderPolishedExtras(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        int panelX = (this.width - PANEL_W) / 2;
        int panelY = (this.height - PANEL_H) / 2;

        g.drawCenteredString(this.font,
            Component.translatable("bannerbound.government.choose.title")
                .withStyle(ChatFormatting.GOLD),
            panelX + PANEL_W / 2, panelY + 14, GuiPalette.TITLE);

        // Subtitle shifts to a "locked" hint once the player has voted, since the live tallies
        // shown are still the snapshot from when the screen opened — they won't reflect this
        // player's own newly-cast vote until the town hall is re-opened.
        Component subtitle = hasCast
            ? Component.translatable("bannerbound.vote.locked.subtitle").withStyle(ChatFormatting.GRAY)
            : Component.translatable("bannerbound.government.choose.subtitle").withStyle(ChatFormatting.GRAY);
        g.drawCenteredString(this.font, subtitle, panelX + PANEL_W / 2, panelY + 28, 0xFFCCCCCC);

        feedback.render(g);
    }

    @Override
    public void onClose() {
        if (this.minecraft != null && parent != null) {
            this.minecraft.setScreen(parent);
        } else {
            super.onClose();
        }
    }
}
