package com.bannerbound.antiquity.client;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.antiquity.network.MasonryActionPayload;
import com.bannerbound.antiquity.network.OpenMasonChiselPayload;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.util.Mth;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Transparent input layer for the mason's-bench chisel-strike minigame. A marker sweeps left-right
 * across a bar; left-click (or space) when it's inside the centre strike zone to land a strike —
 * miss and nothing happens (non-skill: building materials carry no quality, so a miss just costs a
 * beat, never the batch). N strikes finish the queued batch. The world and bench animation stay
 * visible behind a compact HUD.
 */
@OnlyIn(Dist.CLIENT)
@ApiStatus.Internal
public class MasonChiselScreen extends Screen {
    /** Full left→right→left sweep period (ms). */
    private static final double SWEEP_MS = 1100.0;
    /** Half-width of the centre strike zone as a fraction of the bar (0–1, from centre). */
    private static final double ZONE_HALF = 0.13;

    private final BlockPos pos;
    private final int strikesNeeded;
    private int strikesDone = 0;
    private boolean completed = false;
    private long missFlashUntil = 0L;
    private long hitFlashUntil = 0L;
    private long openedAt = 0L;

    private static final int COL_BG = 0xF0140F0A;
    private static final int COL_BORDER_TOP = 0x60B8B0A0;
    private static final int COL_BORDER_BOTTOM = 0x60302820;
    private static final int COL_TRACK = 0xFF3A352E;
    private static final int COL_ZONE = 0x80C8C0A8;
    private static final int COL_ZONE_HIT = 0xC0E8E0C0;
    private static final int COL_MARKER = 0xFFFFFFFF;
    private static final int COL_DONE = 0xFFC8C0A8;
    private static final int COL_WAITING = 0xFF5A5246;
    private static final int COL_TITLE = 0xFFE0DAC8;

    public MasonChiselScreen(OpenMasonChiselPayload payload) {
        super(Component.translatable("bannerboundantiquity.masonry.chisel.title"));
        this.pos = payload.pos();
        this.strikesNeeded = Math.max(1, payload.strikesNeeded());
    }

    @Override
    protected void init() {
        float yaw = MasonsBenchRenderer.snappedYawTowardCamera(
            pos, Minecraft.getInstance().gameRenderer.getMainCamera().getPosition());
        MasonChiselState.begin(pos, strikesNeeded, yaw);
        openedAt = System.currentTimeMillis();
    }

    /** Marker position 0–1 across the bar, derived from the sweep clock (triangle wave). */
    private double markerPos() {
        double phase = ((System.currentTimeMillis() - openedAt) % (long) SWEEP_MS) / SWEEP_MS; // 0–1
        return phase < 0.5 ? phase * 2.0 : (1.0 - phase) * 2.0; // 0→1→0
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0 && !completed) {
            attemptStrike();
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (!completed && (keyCode == 32 /* space */ || keyCode == 257 /* enter */)) {
            attemptStrike();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    private void attemptStrike() {
        double dist = Math.abs(markerPos() - 0.5);
        if (dist <= ZONE_HALF) {
            strikesDone = Math.min(strikesNeeded, strikesDone + 1);
            hitFlashUntil = System.currentTimeMillis() + 150L;
            MasonChiselState.strike();
            strikeFeedback();
            if (strikesDone >= strikesNeeded) finishComplete();
        } else {
            missFlashUntil = System.currentTimeMillis() + 150L;
            Minecraft mc = this.minecraft;
            if (mc != null) {
                mc.getSoundManager().play(
                    SimpleSoundInstance.forUI(SoundEvents.STONE_HIT, 0.7F, 0.7F));
            }
        }
    }

    private void strikeFeedback() {
        Minecraft mc = this.minecraft;
        if (mc == null) return;
        mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.STONE_BREAK, 0.9F, 1.0F));
        if (mc.level == null) return;
        for (int i = 0; i < 6; i++) {
            mc.level.addParticle(
                new BlockParticleOption(ParticleTypes.BLOCK, Blocks.STONE.defaultBlockState()),
                pos.getX() + 0.5 + (Math.random() - 0.5) * 0.35,
                pos.getY() + 1.12,
                pos.getZ() + 0.5 + (Math.random() - 0.5) * 0.35,
                (Math.random() - 0.5) * 0.03, -0.04, (Math.random() - 0.5) * 0.03);
        }
    }

    private void finishComplete() {
        if (completed) return;
        completed = true;
        PacketDistributor.sendToServer(new MasonryActionPayload(pos, MasonryActionPayload.COMPLETE));
        if (minecraft != null) minecraft.setScreen(null);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        int cx = this.width / 2;
        int barW = 360;
        int barH = 18;
        int boxLeft = cx - barW / 2 - 14;
        int boxRight = cx + barW / 2 + 14;
        int boxTop = this.height - 108;
        int boxBottom = boxTop + 72;

        float pulse = 0.72F + 0.28F * (float) Math.sin(System.currentTimeMillis() / 280.0);
        int titleColor = ((int) (pulse * 255.0F) << 24) | (COL_TITLE & 0xFFFFFF);
        Component title = Component.translatable("bannerboundantiquity.masonry.chisel.title")
            .withStyle(ChatFormatting.BOLD);
        g.pose().pushPose();
        g.pose().scale(1.6F, 1.6F, 1.0F);
        g.drawCenteredString(this.font, title, Math.round(cx / 1.6F), Math.round((boxTop - 24) / 1.6F),
            titleColor);
        g.pose().popPose();

        g.fill(boxLeft, boxTop, boxRight, boxBottom, COL_BG);
        g.fillGradient(boxLeft, boxTop, boxLeft + 1, boxBottom, COL_BORDER_TOP, COL_BORDER_BOTTOM);
        g.fillGradient(boxRight - 1, boxTop, boxRight, boxBottom, COL_BORDER_TOP, COL_BORDER_BOTTOM);
        g.fill(boxLeft, boxTop, boxRight, boxTop + 1, COL_BORDER_TOP);
        g.fill(boxLeft, boxBottom - 1, boxRight, boxBottom, COL_BORDER_BOTTOM);

        int done = Math.min(strikesDone, strikesNeeded);
        g.drawString(this.font, done + "/" + strikesNeeded, boxLeft + 9, boxTop + 8, 0xFFFFFFFF, false);
        Component hint = Component.translatable("bannerboundantiquity.masonry.chisel.hint")
            .withStyle(ChatFormatting.GRAY);
        g.drawCenteredString(this.font, hint, cx, boxTop + 8, 0xFFCCCCCC);

        int barLeft = cx - barW / 2;
        int barTop = boxTop + 26;
        int barBottom = barTop + barH;
        g.fill(barLeft - 1, barTop - 1, barLeft + barW + 1, barTop, 0xFF26221C);
        g.fill(barLeft - 1, barTop - 1, barLeft, barBottom + 1, 0xFF26221C);
        g.fill(barLeft, barBottom, barLeft + barW + 1, barBottom + 1, 0xFFD0C8B0);
        g.fill(barLeft + barW, barTop, barLeft + barW + 1, barBottom + 1, 0xFFD0C8B0);
        g.fill(barLeft, barTop, barLeft + barW, barBottom, COL_TRACK);

        // The centre strike zone.
        boolean hitFlash = System.currentTimeMillis() < hitFlashUntil;
        int zoneLeft = barLeft + (int) ((0.5 - ZONE_HALF) * barW);
        int zoneRight = barLeft + (int) ((0.5 + ZONE_HALF) * barW);
        g.fill(zoneLeft, barTop, zoneRight, barBottom, hitFlash ? COL_ZONE_HIT : COL_ZONE);

        // The sweeping marker.
        int markerX = barLeft + (int) (markerPos() * barW);
        boolean missFlash = System.currentTimeMillis() < missFlashUntil;
        g.fill(markerX - 2, barTop - 3, markerX + 2, barBottom + 3, missFlash ? 0xFFE06040 : COL_MARKER);

        drawStrikePips(g, cx, barBottom + 9, barW);
        g.drawCenteredString(this.font, Math.round((float) done / strikesNeeded * 100.0F) + "%",
            cx, boxBottom - 13, 0xFFFFFFFF);
    }

    private void drawStrikePips(GuiGraphics g, int cx, int y, int maxW) {
        int pipSize = strikesNeeded > 20 ? 4 : 5;
        int pipPitch = strikesNeeded > 20 ? 7 : 10;
        int totalW = strikesNeeded * pipPitch - (pipPitch - pipSize);
        if (totalW > maxW) {
            pipPitch = Math.max(5, maxW / Math.max(1, strikesNeeded));
            pipSize = Math.max(3, pipPitch - 2);
            totalW = strikesNeeded * pipPitch - (pipPitch - pipSize);
        }
        int left = cx - totalW / 2;
        int done = Math.min(strikesDone, strikesNeeded);
        for (int i = 0; i < strikesNeeded; i++) {
            int x = left + i * pipPitch;
            if (i < done) {
                g.fill(x, y, x + pipSize, y + pipSize, COL_DONE);
            } else if (i == done) {
                g.renderOutline(x, y, pipSize, pipSize, 0xFFFFFFFF);
            } else {
                g.renderOutline(x, y, pipSize, pipSize, COL_WAITING);
            }
        }
    }

    @Override
    public void onClose() {
        if (!completed) {
            PacketDistributor.sendToServer(new MasonryActionPayload(pos, MasonryActionPayload.CANCEL));
        }
        super.onClose();
    }

    @Override
    public void removed() {
        MasonChiselState.clear();
        super.removed();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
