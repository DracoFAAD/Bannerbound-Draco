package com.bannerbound.core.client;

import java.util.List;

import org.jetbrains.annotations.ApiStatus;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * Cosmetic citizen-vote reveal screen, opened to every settlement member when a chief
 * election ties and the citizens are breaking it. The actual winner has already been picked
 * server-side — this screen exists to make the tiebreak feel like a dramatic moment instead
 * of a silent random pick.
 *
 * <p><b>Pacing</b>: the first vote takes ~{@link #FIRST_DELAY_MS} ms to appear, and each
 * subsequent vote arrives sooner (multiplied by {@link #DECAY_FACTOR} per step, floored at
 * {@link #MIN_DELAY_MS}). For 7 citizens that reads as "ta...ta..ta.ta.tatata" — slow
 * opening beat, then accelerating to a flourish.
 *
 * <p>Each vote plays a {@code NOTE_BLOCK_BASEDRUM} kick (pitch 1.0, vol 0.3) as it appears.
 *
 * <p>The screen does <b>not</b> auto-close. Players who want to keep staring at the result
 * can; pressing Esc dismisses it. The server-side chief enactment is on its own timer
 * (see {@code SettlementManager.TRIBE_VOTE_REVEAL_MS}) so the actual outcome happens
 * regardless of when the player closes the screen.
 */
@OnlyIn(Dist.CLIENT)
@ApiStatus.Internal
public class TribeVoteScreen extends PolishedScreen {
    // Timing constants live in TribeVoteTiming so the server can compute the same total
    // duration and schedule the enactment to land exactly when the last row reveals.
    private static final long FIRST_DELAY_MS = TribeVoteTiming.FIRST_DELAY_MS;
    private static final double DECAY_FACTOR = TribeVoteTiming.DECAY_FACTOR;
    private static final long MIN_DELAY_MS = TribeVoteTiming.MIN_DELAY_MS;

    private static final int ROW_W = 260;
    private static final int ROW_H = 22;
    private static final int ROW_PITCH = 26;

    /** Same {@code crown.png} the Chief nametag glyph will use in Step 7. */
    private static final ResourceLocation CROWN_TEX =
        ResourceLocation.fromNamespaceAndPath("bannerbound", "textures/gui/crown.png");

    private final List<String> voterNames;
    private final List<String> candidateNames;
    private final long openedAtMs;
    /** Per-vote reveal-at timestamps, pre-computed in {@link #init} so the math doesn't run
     *  every render frame. {@code revealAtMs[i]} = absolute wall-clock ms when vote {@code i}
     *  should be visible. */
    private long[] revealAtMs = new long[0];
    /** How many votes have *already played their sound*. The render loop advances this as
     *  rows reveal and fires one kick per new vote. */
    private int soundedCount = 0;

    public TribeVoteScreen(List<String> voterNames, List<String> candidateNames) {
        super(Component.translatable("bannerbound.tribe_vote.title"));
        this.voterNames = voterNames;
        this.candidateNames = candidateNames;
        this.openedAtMs = System.currentTimeMillis();
    }

    @Override
    protected void init() {
        // Pre-compute the cumulative reveal times. delay[0] = FIRST_DELAY_MS,
        // delay[i] = max(MIN, delay[i-1] * DECAY).
        int n = voterNames.size();
        revealAtMs = new long[n];
        double delay = FIRST_DELAY_MS;
        long accum = openedAtMs;
        for (int i = 0; i < n; i++) {
            accum += (long) delay;
            revealAtMs[i] = accum;
            delay = Math.max(MIN_DELAY_MS, delay * DECAY_FACTOR);
        }
    }

    /** Cinematic overlay: the vote reveal plays over the LIVE world (citizens visibly gathered),
     *  so the dim/blur background pass is skipped — the settle animation still applies. */
    @Override
    protected boolean drawsDimmedBackground() {
        return false;
    }

    @Override
    protected void renderPolishedExtras(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        long now = System.currentTimeMillis();
        int revealed = 0;
        for (int i = 0; i < revealAtMs.length; i++) {
            if (now >= revealAtMs[i]) revealed++;
            else break;
        }

        // Fire one kick per newly-revealed vote since the last frame.
        if (revealed > soundedCount) {
            Minecraft mc = this.minecraft;
            if (mc != null && mc.getSoundManager() != null) {
                int newlyRevealed = revealed - soundedCount;
                for (int i = 0; i < newlyRevealed; i++) {
                    mc.getSoundManager().play(SimpleSoundInstance.forUI(
                        SoundEvents.NOTE_BLOCK_BASEDRUM.value(), 1.0f, 0.3f));
                }
            }
            soundedCount = revealed;
        }

        int cx = this.width / 2;

        // Title — large, centred near the top. Crown icon flanks it on either side to
        // signal "this is about who becomes Chief".
        Component title = Component.translatable("bannerbound.tribe_vote.title")
            .withStyle(ChatFormatting.WHITE);
        g.pose().pushPose();
        g.pose().scale(2.0f, 2.0f, 1.0f);
        g.drawCenteredString(this.font, title, cx / 2, 40 / 2, 0xFFFFFFFF);
        g.pose().popPose();

        // Crown icons either side of the (scaled-2x) title. 24px size to match the doubled text.
        int crownSize = 24;
        int titleWidth = this.font.width(title.getString()) * 2;
        // Was 40 - crownSize/2 = 28, which sat the crown ABOVE the title baseline. The
        // scaled-2x title spans roughly y=20..36; centering the 24px crown on y=36 (its
        // bottom) plants it AT the baseline so the silhouette reads as headwear, not as a
        // floating ornament above the words.
        int crownY = 36 - crownSize / 2;
        g.blit(CROWN_TEX, cx - titleWidth / 2 - crownSize - 8, crownY,
            0f, 0f, crownSize, crownSize, crownSize, crownSize);
        g.blit(CROWN_TEX, cx + titleWidth / 2 + 8, crownY,
            0f, 0f, crownSize, crownSize, crownSize, crownSize);

        if (revealed == 0) {
            g.drawCenteredString(this.font,
                Component.translatable("bannerbound.tribe_vote.waiting")
                    .withStyle(ChatFormatting.GRAY),
                cx, this.height / 2 - 4, 0xFF888888);
            return;
        }

        // Stack of revealed vote rows, centred vertically around mid-screen.
        int stackHeight = revealed * ROW_PITCH;
        int topY = this.height / 2 - stackHeight / 2;
        int rowX = cx - ROW_W / 2;
        for (int i = 0; i < revealed; i++) {
            int y = topY + i * ROW_PITCH;
            drawVoteRow(g, rowX, y, voterNames.get(i), candidateNames.get(i));
        }

        // Footer hint once every vote is in — tells the player they can close when they're done.
        if (revealed >= revealAtMs.length) {
            g.drawCenteredString(this.font,
                Component.translatable("bannerbound.tribe_vote.press_esc")
                    .withStyle(ChatFormatting.GRAY),
                cx, this.height - 24, 0xFF888888);
        }
    }

    /** One vote row — flat slab with voter name (white) on the left and candidate name
     *  (gold) on the right. Reads like a ballot rather than a button. */
    private void drawVoteRow(GuiGraphics g, int x, int y, String voter, String candidate) {
        g.fill(x, y, x + ROW_W, y + ROW_H, 0xC0808080);
        g.renderOutline(x, y, ROW_W, ROW_H, 0xFFAAAAAA);
        g.drawString(this.font, voter, x + 10, y + (ROW_H - 8) / 2, 0xFFFFFFFF, false);
        int candidateW = this.font.width(candidate);
        g.drawString(this.font, Component.literal(candidate).withStyle(ChatFormatting.GOLD),
            x + ROW_W - candidateW - 10, y + (ROW_H - 8) / 2, 0xFFFFAA00, false);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
