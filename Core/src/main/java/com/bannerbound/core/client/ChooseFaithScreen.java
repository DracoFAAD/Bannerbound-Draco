package com.bannerbound.core.client;

import java.util.ArrayList;
import java.util.List;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.api.faith.FaithManager;
import com.bannerbound.core.api.faith.FaithPath;
import com.bannerbound.core.network.CastFaithVotePayload;
import com.bannerbound.core.network.OpenChooseFaithScreenPayload;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * The Choose-Faith vote (FAITH_PLAN.md Part 1) — opened from the town hall Faith button
 * while the founding window is open. Same two-stage model as
 * {@link ChooseGovernmentScreen}: toggle a selection freely, Cast Vote locks it.
 * <p>
 * Options: found ASTROLOGY, found TOTEMIC, or adopt any faith already founded on the
 * server (the cross-faction list — empty on the world's first founding, which is fine:
 * first movers define the world's religions). Founding options carry a proposed NAME
 * typed here; the winning option's earliest proposal names the faith.
 */
@OnlyIn(Dist.CLIENT)
@ApiStatus.Internal
public class ChooseFaithScreen extends PolishedScreen {
    private static final int PANEL_W = 260;
    private static final int BTN_W = 220;
    private static final int BTN_H = 24;
    private static final int BTN_PITCH = 28;
    /** Adoptable faiths shown at once — server list beyond this is truncated for M1. */
    private static final int MAX_ADOPT_ROWS = 4;

    private final OpenChooseFaithScreenPayload snapshot;
    private int astrologyVotes;
    private int totemicVotes;
    private final List<Integer> adoptVotes;

    /** Option key currently selected ("" = none) — free to change until {@link #hasCast}. */
    private String selected;
    private boolean hasCast;

    private final List<Button> optionButtons = new ArrayList<>();
    private final List<String> optionKeys = new ArrayList<>();
    private EditBox nameBox;
    private Button castBtn;
    private int panelH;
    private final TransientClickFeedback feedback = new TransientClickFeedback();

    public ChooseFaithScreen(OpenChooseFaithScreenPayload snapshot) {
        super(Component.translatable("bannerbound.faith.choose.title"));
        this.snapshot = snapshot;
        this.astrologyVotes = snapshot.astrologyVotes();
        this.totemicVotes = snapshot.totemicVotes();
        this.adoptVotes = new ArrayList<>(snapshot.faithAdoptVotes());
        this.selected = snapshot.playerVote();
        this.hasCast = !snapshot.playerVote().isEmpty();
    }

    @Override
    protected void init() {
        optionButtons.clear();
        optionKeys.clear();
        int adoptRows = Math.min(snapshot.faithIds().size(), MAX_ADOPT_ROWS);
        // title + subtitle + 2 found rows + name box + adopt header + adopt rows + cast + cancel
        panelH = 44 + 2 * BTN_PITCH + 30
                + (adoptRows > 0 ? 16 + adoptRows * BTN_PITCH : 0) + BTN_PITCH + 36;
        int panelX = (this.width - PANEL_W) / 2;
        int panelY = (this.height - panelH) / 2;
        int btnX = panelX + (PANEL_W - BTN_W) / 2;
        int y = panelY + 44;

        addOption(FaithManager.OPTION_FOUND_ASTROLOGY, btnX, y);
        y += BTN_PITCH;
        addOption(FaithManager.OPTION_FOUND_TOTEMIC, btnX, y);
        y += BTN_PITCH;

        nameBox = new EditBox(this.font, btnX, y + 2, BTN_W, 18,
            Component.translatable("bannerbound.faith.name.hint"));
        nameBox.setMaxLength(CastFaithVotePayload.MAX_NAME_LENGTH);
        nameBox.setHint(Component.translatable("bannerbound.faith.name.hint")
            .withStyle(ChatFormatting.DARK_GRAY));
        // Founding REQUIRES a name — re-gate the Cast button on every keystroke.
        nameBox.setResponder(text -> refreshButtons());
        addRenderableWidget(nameBox);
        y += 30;

        if (adoptRows > 0) {
            y += 16; // "or adopt:" header drawn in renderPolishedExtras
            for (int i = 0; i < adoptRows; i++) {
                addOption(FaithManager.OPTION_ADOPT_PREFIX + snapshot.faithIds().get(i), btnX, y);
                y += BTN_PITCH;
            }
        }

        castBtn = PolishButton.polished(
            Component.translatable("bannerbound.vote.cast"),
            b -> {
                if (hasCast || selected.isEmpty()) return;
                PacketDistributor.sendToServer(new CastFaithVotePayload(
                    selected, nameBox.getValue().trim()));
                bumpLocalTally(selected);
                hasCast = true;
                refreshButtons();
                feedback.spawnAtCursor();
            }
        ).bounds(btnX, y + 4, BTN_W, BTN_H).build();
        addRenderableWidget(castBtn);

        addRenderableWidget(PolishButton.polished(
            Component.translatable("gui.cancel"),
            b -> this.onClose()
        ).bounds(btnX, panelY + panelH - 26, BTN_W, 20).build());

        refreshButtons();
    }

    private void addOption(String optionKey, int x, int y) {
        Button btn = PolishButton.polished(
            optionLabel(optionKey),
            b -> {
                if (hasCast) return;
                selected = optionKey;
                refreshButtons();
            }
        ).bounds(x, y, BTN_W, BTN_H).build();
        optionButtons.add(btn);
        optionKeys.add(optionKey);
        addRenderableWidget(btn);
    }

    private void bumpLocalTally(String optionKey) {
        if (FaithManager.OPTION_FOUND_ASTROLOGY.equals(optionKey)) astrologyVotes++;
        else if (FaithManager.OPTION_FOUND_TOTEMIC.equals(optionKey)) totemicVotes++;
        else {
            int i = adoptIndexOf(optionKey);
            if (i >= 0) adoptVotes.set(i, adoptVotes.get(i) + 1);
        }
    }

    private int adoptIndexOf(String optionKey) {
        if (!optionKey.startsWith(FaithManager.OPTION_ADOPT_PREFIX)) return -1;
        return snapshot.faithIds().indexOf(
            optionKey.substring(FaithManager.OPTION_ADOPT_PREFIX.length()));
    }

    private void refreshButtons() {
        for (int i = 0; i < optionButtons.size(); i++) {
            Button btn = optionButtons.get(i);
            btn.setMessage(optionLabel(optionKeys.get(i)));
            btn.active = !hasCast;
        }
        if (nameBox != null) {
            nameBox.setEditable(!hasCast && selected.startsWith("found:"));
        }
        if (castBtn != null) {
            // Founding options demand a name; adoption doesn't.
            boolean nameOk = !selected.startsWith("found:")
                || (nameBox != null && !nameBox.getValue().trim().isEmpty());
            castBtn.active = !hasCast && !selected.isEmpty() && nameOk;
            castBtn.setMessage(hasCast
                ? Component.translatable("bannerbound.vote.cast.locked").withStyle(ChatFormatting.GRAY)
                : Component.translatable("bannerbound.vote.cast"));
        }
    }

    private Component optionLabel(String optionKey) {
        Component prefix = optionKey.equals(selected)
            ? Component.literal("✓ ").withStyle(ChatFormatting.GREEN)
            : Component.literal("");
        Component body;
        int votes;
        if (FaithManager.OPTION_FOUND_ASTROLOGY.equals(optionKey)) {
            body = Component.translatable("bannerbound.faith.choose.found",
                Component.translatable("bannerbound.faith.path.astrology"));
            votes = astrologyVotes;
        } else if (FaithManager.OPTION_FOUND_TOTEMIC.equals(optionKey)) {
            body = Component.translatable("bannerbound.faith.choose.found",
                Component.translatable("bannerbound.faith.path.totemic"));
            votes = totemicVotes;
        } else {
            int i = adoptIndexOf(optionKey);
            if (i < 0) return Component.literal("?");
            String pathKey = snapshot.faithPaths().get(i) == FaithPath.TOTEMIC.ordinal()
                ? "bannerbound.faith.path.totemic" : "bannerbound.faith.path.astrology";
            body = Component.literal(snapshot.faithNames().get(i) + " (")
                .append(Component.translatable(pathKey))
                .append(Component.literal(", " + snapshot.faithMemberCounts().get(i) + ")"));
            votes = adoptVotes.get(i);
        }
        return Component.empty().append(prefix).append(body)
            .append(Component.literal(" — " + votes + "/" + snapshot.onlineMembers()));
    }

    @Override
    protected void renderPolishedExtras(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        int panelX = (this.width - PANEL_W) / 2;
        int panelY = (this.height - panelH) / 2;

        g.fill(panelX, panelY, panelX + PANEL_W, panelY + panelH, 0xC0202020);
        g.renderOutline(panelX, panelY, PANEL_W, panelH, 0xFFCCCCCC);

        g.drawCenteredString(this.font,
            Component.translatable("bannerbound.faith.choose.title").withStyle(ChatFormatting.GOLD),
            panelX + PANEL_W / 2, panelY + 12, 0xFFFFFFFF);
        Component subtitle = hasCast
            ? Component.translatable("bannerbound.vote.locked.subtitle").withStyle(ChatFormatting.GRAY)
            : Component.translatable("bannerbound.faith.choose.subtitle").withStyle(ChatFormatting.GRAY);
        g.drawCenteredString(this.font, subtitle, panelX + PANEL_W / 2, panelY + 26, 0xFFCCCCCC);

        if (Math.min(snapshot.faithIds().size(), MAX_ADOPT_ROWS) > 0) {
            int adoptHeaderY = panelY + 44 + 2 * BTN_PITCH + 30 + 4;
            g.drawCenteredString(this.font,
                Component.translatable("bannerbound.faith.choose.adopt_header")
                    .withStyle(ChatFormatting.GRAY),
                panelX + PANEL_W / 2, adoptHeaderY, 0xFFCCCCCC);
        }

        feedback.render(g);
    }
}
