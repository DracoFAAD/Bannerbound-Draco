package com.bannerbound.core.client;

import java.util.List;
import java.util.UUID;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

import com.bannerbound.core.network.CastChiefNominationPayload;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Chief-election sub-screen. Two stages, same shape as
 * {@link ChooseGovernmentScreen}:
 * <ol>
 *   <li>Click any candidate row to <i>select</i> that player — purely local, no packet sent.
 *       Re-clicking another row changes the selection. The selected row gets a check + the
 *       Cast Vote button enables.</li>
 *   <li>Click <b>Cast Vote</b> to send {@link CastChiefNominationPayload}. All buttons lock;
 *       the player cannot change their nomination.</li>
 * </ol>
 *
 * <p>A small crown icon (the same {@code crown.png} that will become the in-world Chief
 * nametag glyph in Step 7) draws beside each candidate's name, reinforcing that this vote is
 * about *who wears the crown*.
 */
@OnlyIn(Dist.CLIENT)
@ApiStatus.Internal
public class NominateChiefScreen extends PolishedScreen {
    private static final int PANEL_W = 240;
    private static final int PANEL_H = 240;
    private static final int BTN_W = 200;
    private static final int BTN_H = 22;
    private static final int BTN_PITCH = 28;

    /** {@code assets/bannerbound/textures/gui/crown.png}. 16×16. */
    private static final ResourceLocation CROWN_TEX =
        ResourceLocation.fromNamespaceAndPath("bannerbound", "textures/gui/crown.png");

    @Nullable private final Screen parent;
    private final List<UUID> candidates;
    private final List<String> candidateNames;
    /** Mutable so the casting player's own +1 lands on their button immediately. The payload
     *  may hand us an immutable list, so we copy into an ArrayList in the constructor. */
    private final java.util.ArrayList<Integer> candidateVotes;
    private final int onlineMembers;

    /** Local selection (all-zero UUID = none). Free to change until {@link #hasCast}. */
    private UUID selected;
    private boolean hasCast;

    private Button[] candidateButtons;
    private Button castBtn;
    private final TransientClickFeedback feedback = new TransientClickFeedback();

    /** Geometry for the crown icons (drawn in render()), mirrors the candidate buttons. */
    private int firstRowY;
    private int rowX;

    public NominateChiefScreen(@Nullable Screen parent, List<UUID> candidates,
                                List<String> candidateNames, List<Integer> candidateVotes,
                                int onlineMembers, UUID playerNomination) {
        super(Component.translatable("bannerbound.chief.election.title"));
        this.parent = parent;
        this.candidates = candidates;
        this.candidateNames = candidateNames;
        this.candidateVotes = new java.util.ArrayList<>(candidateVotes);
        this.onlineMembers = onlineMembers;
        this.selected = playerNomination;
        this.hasCast = playerNomination != null
            && playerNomination.getMostSignificantBits() != 0L
            && playerNomination.getLeastSignificantBits() != 0L;
    }

    @Override
    protected void init() {
        int panelX = (this.width - PANEL_W) / 2;
        int panelY = (this.height - PANEL_H) / 2;
        int btnX = panelX + (PANEL_W - BTN_W) / 2;
        rowX = btnX;
        firstRowY = panelY + 50;

        candidateButtons = new Button[candidates.size()];
        for (int i = 0; i < candidates.size(); i++) {
            final UUID candidateId = candidates.get(i);
            int row = i;
            Button b = PolishButton.polished(buildRowLabel(row), bt -> {
                if (hasCast) return;
                selected = candidateId;
                refreshButtons();
            }).bounds(btnX, firstRowY + i * BTN_PITCH, BTN_W, BTN_H).accent(primaryAccent()).build();
            candidateButtons[i] = b;
            addRenderableWidget(b);
        }

        int castY = firstRowY + candidates.size() * BTN_PITCH + 8;
        castBtn = PolishButton.polished(
            Component.translatable("bannerbound.vote.cast"),
            b -> {
                if (hasCast || selected == null
                    || (selected.getMostSignificantBits() == 0L
                        && selected.getLeastSignificantBits() == 0L)) {
                    return;
                }
                PacketDistributor.sendToServer(new CastChiefNominationPayload(selected));
                // Local +1 on the selected candidate's tally so the caster's button updates
                // immediately. Other members still need a server refresh to see this vote.
                int idx = candidates.indexOf(selected);
                if (idx >= 0 && idx < candidateVotes.size()) {
                    candidateVotes.set(idx, candidateVotes.get(idx) + 1);
                }
                hasCast = true;
                refreshButtons();
                feedback.spawnAtCursor();
            }
        ).bounds(btnX, castY, BTN_W, 24).accent(primaryAccent()).build();
        addRenderableWidget(castBtn);

        addRenderableWidget(PolishButton.polished(
            Component.translatable("gui.cancel"),
            b -> this.onClose()
        ).bounds(btnX, panelY + PANEL_H - 28, BTN_W, 20).accent(primaryAccent()).build());

        refreshButtons();
    }

    private void refreshButtons() {
        if (candidateButtons != null) {
            for (int i = 0; i < candidateButtons.length; i++) {
                Button b = candidateButtons[i];
                if (b == null) continue;
                b.setMessage(buildRowLabel(i));
                b.active = !hasCast;
            }
        }
        if (castBtn != null) {
            boolean hasSelection = selected != null
                && !(selected.getMostSignificantBits() == 0L
                    && selected.getLeastSignificantBits() == 0L);
            castBtn.active = !hasCast && hasSelection;
            castBtn.setMessage(hasCast
                ? Component.translatable("bannerbound.vote.cast.locked").withStyle(ChatFormatting.GRAY)
                : Component.translatable("bannerbound.vote.cast"));
        }
    }

    private Component buildRowLabel(int row) {
        UUID id = candidates.get(row);
        String name = candidateNames.get(row);
        int votes = candidateVotes.get(row);
        boolean myPick = id.equals(selected);
        Component prefix = myPick
            ? Component.literal("✓ ").withStyle(ChatFormatting.GREEN)
            : Component.literal("");
        // The leading spaces leave room for the crown icon that render() draws on top of the
        // button. Without them the icon would overlap the first character of the name.
        return Component.empty()
            .append(prefix)
            .append(Component.literal("    " + name))
            .append(Component.literal(" — " + votes + " / " + onlineMembers));
    }

    @Override
    protected void renderPolishedBackdrop(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        // Panel chrome must be drawn BEFORE the widgets — drawIdentityPanel's fill is opaque and
        // would hide the candidate buttons if it stayed in renderPolishedExtras.
        int panelX = (this.width - PANEL_W) / 2;
        int panelY = (this.height - PANEL_H) / 2;
        drawIdentityPanel(g, panelX, panelY, PANEL_W, PANEL_H, identityAccents);
    }

    @Override
    protected void renderPolishedExtras(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        int panelX = (this.width - PANEL_W) / 2;
        int panelY = (this.height - PANEL_H) / 2;

        g.drawCenteredString(this.font,
            Component.translatable("bannerbound.chief.election.title")
                .withStyle(ChatFormatting.GOLD),
            panelX + PANEL_W / 2, panelY + 14, GuiPalette.TITLE);
        Component subtitle = hasCast
            ? Component.translatable("bannerbound.vote.locked.subtitle").withStyle(ChatFormatting.GRAY)
            : Component.translatable("bannerbound.chief.election.subtitle").withStyle(ChatFormatting.GRAY);
        g.drawCenteredString(this.font, subtitle,
            panelX + PANEL_W / 2, panelY + 28, 0xFFCCCCCC);

        // Crown icon overlays — one per candidate row, sitting just inside the left edge of
        // the button. Draw AFTER super.render() so the icon paints on top of the button face.
        int iconSize = 12;
        int iconY = firstRowY + (BTN_H - iconSize) / 2;
        for (int i = 0; i < candidates.size(); i++) {
            int y = iconY + i * BTN_PITCH;
            g.blit(CROWN_TEX, rowX + 4, y, 0f, 0f, iconSize, iconSize, iconSize, iconSize);
        }

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
