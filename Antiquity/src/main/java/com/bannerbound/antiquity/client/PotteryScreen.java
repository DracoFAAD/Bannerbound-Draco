package com.bannerbound.antiquity.client;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.antiquity.network.OpenPotteryPayload;
import com.bannerbound.antiquity.network.PotteryActionPayload;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Transparent pottery-wheel minigame. Hold left click and move the mouse in circles around the
 * guide ring until the recipe's spin count is complete.
 */
@OnlyIn(Dist.CLIENT)
@ApiStatus.Internal
public class PotteryScreen extends Screen {
    private static final double FULL_TURN = Math.PI * 2.0;
    private static final double MIN_RADIUS_FACTOR = 0.35;
    private static final double MIN_ANGLE_DELTA = 0.0025;
    private static final int COL_BG = 0xF012120E;
    private static final int COL_BORDER_TOP = 0x70B88955;
    private static final int COL_BORDER_BOTTOM = 0x70483422;
    private static final int COL_RING = 0xFF5B4A38;
    private static final int COL_RING_FILL = 0xFFD49A55;
    private static final int COL_CURSOR = 0xFFFFFFFF;
    private static final int COL_TITLE = 0xFFE6C7A0;
    private static final int PANEL_MAX_W = 392;
    private static final int PANEL_H = 150;
    private static final int PANEL_BOTTOM_MARGIN = 24;
    private static final int BAR_H = 12;

    private final BlockPos pos;
    private final int spins;

    private boolean holding = false;
    private boolean completed = false;
    private double totalRadians = 0.0;
    private int motionSamples = 0;
    private double lastAngle = 0.0;

    public PotteryScreen(OpenPotteryPayload payload) {
        super(Component.translatable("bannerboundantiquity.pottery.title"));
        this.pos = payload.pos();
        this.spins = Math.max(1, payload.spins());
    }

    @Override
    protected void init() {
        PotterySpinState.begin(pos);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0 && !completed) {
            holding = true;
            PotterySpinState.holding = true;
            lastAngle = angleOf(mouseX, mouseY);
            playUi(SoundEvents.NOTE_BLOCK_HAT.value(), 0.8F, 0.55F);
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (button == 0) {
            holding = false;
            PotterySpinState.holding = false;
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (button != 0 || completed) {
            return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
        }
        if (!holding) {
            holding = true;
            PotterySpinState.holding = true;
        }

        double radius = radiusOf(mouseX, mouseY);
        double targetRadius = guideRadius();
        if (radius < targetRadius * MIN_RADIUS_FACTOR) {
            return true;
        }

        double oldAngle = angleOf(mouseX - dragX, mouseY - dragY);
        double newAngle = angleOf(mouseX, mouseY);
        double delta = wrapRadians(newAngle - oldAngle);
        double mag = Math.abs(delta);
        if (mag < MIN_ANGLE_DELTA) {
            return true;
        }

        lastAngle = newAngle;
        totalRadians += mag;
        PotterySpinState.addRadians(delta);

        motionSamples++;
        PotterySpinState.progress = progress();

        if (motionSamples % 8 == 0) {
            spinPulse();
        }
        if (totalRadians >= spins * FULL_TURN) {
            finishComplete();
        }
        return true;
    }

    private void finishComplete() {
        if (completed) return;
        completed = true;
        playUi(SoundEvents.NOTE_BLOCK_CHIME.value(), 1.0F, 1.0F);
        sendAction(PotteryActionPayload.COMPLETE);
        if (minecraft != null) minecraft.setScreen(null);
    }

    private void sendAction(int action) {
        PacketDistributor.sendToServer(new PotteryActionPayload(pos, action));
    }

    private float progress() {
        return (float) clamp(totalRadians / Math.max(FULL_TURN, spins * FULL_TURN), 0.0, 1.0);
    }

    private double guideCx() {
        return width / 2.0;
    }

    private double guideCy() {
        return (panelTop() + panelBottom()) / 2.0;
    }

    private double guideRadius() {
        double topClearance = guideCy() - (panelTop() + 32.0);
        double bottomClearance = progressBarTop() - 8.0 - guideCy();
        return Math.max(32.0, Math.min(48.0, Math.min(topClearance, bottomClearance)));
    }

    private int panelWidth() {
        return Math.min(PANEL_MAX_W, Math.max(220, width - 32));
    }

    private int panelLeft() {
        return width / 2 - panelWidth() / 2;
    }

    private int panelRight() {
        return panelLeft() + panelWidth();
    }

    private int panelBottom() {
        return height - PANEL_BOTTOM_MARGIN;
    }

    private int panelTop() {
        return panelBottom() - Math.min(PANEL_H, Math.max(118, height - 42));
    }

    private int progressBarTop() {
        return panelBottom() - 22;
    }

    private int progressBarWidth() {
        return Math.min(310, panelWidth() - 70);
    }

    private double angleOf(double mouseX, double mouseY) {
        return Math.atan2(mouseY - guideCy(), mouseX - guideCx());
    }

    private double radiusOf(double mouseX, double mouseY) {
        double dx = mouseX - guideCx();
        double dy = mouseY - guideCy();
        return Math.sqrt(dx * dx + dy * dy);
    }

    private static double wrapRadians(double radians) {
        while (radians <= -Math.PI) radians += FULL_TURN;
        while (radians > Math.PI) radians -= FULL_TURN;
        return radians;
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private void playUi(SoundEvent sound, float pitch, float volume) {
        if (minecraft != null && minecraft.getSoundManager() != null) {
            minecraft.getSoundManager().play(SimpleSoundInstance.forUI(sound, pitch, volume));
        }
    }

    private void spinPulse() {
        Minecraft mc = minecraft;
        if (mc == null || mc.level == null) return;
        playUi(SoundEvents.NOTE_BLOCK_HAT.value(), 1.2F, 0.25F);
        for (int i = 0; i < 3; i++) {
            mc.level.addParticle(
                new BlockParticleOption(ParticleTypes.BLOCK, Blocks.CLAY.defaultBlockState()),
                pos.getX() + 0.5 + (Math.random() - 0.5) * 0.42,
                pos.getY() + 0.75 + Math.random() * 0.22,
                pos.getZ() + 0.5 + (Math.random() - 0.5) * 0.42,
                (Math.random() - 0.5) * 0.015, 0.018, (Math.random() - 0.5) * 0.015);
        }
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        int cx = width / 2;
        int boxLeft = panelLeft();
        int boxRight = panelRight();
        int boxTop = panelTop();
        int boxBottom = panelBottom();

        float pulse = holding ? 1.0F : 0.72F + 0.28F * (float) Math.sin(System.currentTimeMillis() / 280.0);
        int titleColor = ((int) (pulse * 255.0F) << 24) | (COL_TITLE & 0xFFFFFF);
        Component title = Component.translatable("bannerboundantiquity.pottery.hint")
            .withStyle(ChatFormatting.BOLD);
        g.pose().pushPose();
        g.pose().scale(1.45F, 1.45F, 1.0F);
        g.drawCenteredString(font, title, Math.round(cx / 1.45F), Math.round((boxTop - 21) / 1.45F),
            titleColor);
        g.pose().popPose();

        g.fill(boxLeft, boxTop, boxRight, boxBottom, COL_BG);
        g.fillGradient(boxLeft, boxTop, boxLeft + 1, boxBottom, COL_BORDER_TOP, COL_BORDER_BOTTOM);
        g.fillGradient(boxRight - 1, boxTop, boxRight, boxBottom, COL_BORDER_TOP, COL_BORDER_BOTTOM);
        g.fill(boxLeft, boxTop, boxRight, boxTop + 1, COL_BORDER_TOP);
        g.fill(boxLeft, boxBottom - 1, boxRight, boxBottom, COL_BORDER_BOTTOM);

        g.drawString(font, String.format("%.1f/%d", totalRadians / FULL_TURN, spins),
            boxLeft + 10, boxTop + 9, 0xFFFFFFFF, false);
        g.drawCenteredString(font, Component.translatable("bannerboundantiquity.pottery.progress"),
            cx, boxTop + 9, 0xFFCCCCCC);
        g.drawString(font, Math.round(progress() * 100.0F) + "%",
            boxRight - 36, boxTop + 9, 0xFFFFFFFF, false);

        drawRing(g, progress());

        int barW = progressBarWidth();
        int barLeft = cx - barW / 2;
        int barTop = progressBarTop();
        int fillRight = barLeft + (int) (barW * progress());
        g.fill(barLeft - 1, barTop - 1, barLeft + barW + 1, barTop, 0xFF2D241B);
        g.fill(barLeft - 1, barTop - 1, barLeft, barTop + 14, 0xFF2D241B);
        g.fill(barLeft, barTop + 13, barLeft + barW + 1, barTop + 14, 0xFFE6C18D);
        g.fill(barLeft + barW, barTop, barLeft + barW + 1, barTop + 14, 0xFFE6C18D);
        g.fill(barLeft, barTop, barLeft + barW, barTop + 13, 0xFF4D4032);
        g.fill(barLeft, barTop, fillRight, barTop + 13, COL_RING_FILL);
        g.fill(fillRight - 1, barTop - 2, fillRight + 1, barTop + 15, COL_CURSOR);
    }

    private void drawRing(GuiGraphics g, float progress) {
        double cx = guideCx();
        double cy = guideCy();
        double r = guideRadius();
        int steps = 72;
        int done = Math.round(steps * progress);
        for (int i = 0; i < steps; i++) {
            double a = -Math.PI / 2.0 + i * FULL_TURN / steps;
            int x = (int) Math.round(cx + Math.cos(a) * r);
            int y = (int) Math.round(cy + Math.sin(a) * r);
            int color = i <= done ? COL_RING_FILL : COL_RING;
            g.fill(x - 2, y - 2, x + 2, y + 2, color);
        }
        int px = (int) Math.round(cx + Math.cos(lastAngle) * r);
        int py = (int) Math.round(cy + Math.sin(lastAngle) * r);
        if (holding || progress > 0.0F) {
            g.fill(px - 3, py - 3, px + 3, py + 3, COL_CURSOR);
        }
    }

    @Override
    public void onClose() {
        if (!completed) {
            sendAction(PotteryActionPayload.CANCEL);
        }
        super.onClose();
    }

    @Override
    public void removed() {
        PotterySpinState.clear();
        super.removed();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
