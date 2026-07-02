package com.bannerbound.antiquity.client;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.antiquity.block.entity.MortarAndPestleBlockEntity;
import com.bannerbound.antiquity.network.MortarGrindActionPayload;
import com.bannerbound.antiquity.network.OpenMortarGrindPayload;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ItemParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvent;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.item.ItemStack;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Transparent press-and-grind mortar minigame. Each beat: hold left-click to press the pestle into
 * the bowl, then drag the mouse in circles to grind; finish the loaded batch in {@code reps} beats.
 * Non-scored (like the pottery wheel) — the loaded ingredients and output are owned by the server
 * ({@code MortarGrind}). The pestle dips and circles in-world via {@link MortarGrindState}.
 */
@OnlyIn(Dist.CLIENT)
@ApiStatus.Internal
public class MortarGrindScreen extends Screen {
    private static final double FULL_TURN = Math.PI * 2.0;
    private static final double REP_QUOTA = 1.25 * FULL_TURN;
    private static final double MIN_RADIUS_FACTOR = 0.35;
    private static final double MIN_ANGLE_DELTA = 0.0025;
    private static final float PRESS_MS = 320.0F;

    private static final int COL_BG = 0xF012120E;
    private static final int COL_BORDER_TOP = 0x70B88955;
    private static final int COL_BORDER_BOTTOM = 0x70483422;
    private static final int COL_RING = 0xFF5B4A38;
    private static final int COL_RING_FILL = 0xFFD49A55;
    private static final int COL_CURSOR = 0xFFFFFFFF;
    private static final int COL_TITLE = 0xFFE6C7A0;
    private static final int COL_PRESS = 0xFF8FB85B;
    private static final int PANEL_MAX_W = 392;
    private static final int PANEL_H = 150;
    private static final int PANEL_BOTTOM_MARGIN = 24;

    private final BlockPos pos;
    private final int reps;
    private final int batch;

    private boolean holding = false;
    private boolean engaged = false;
    private boolean completed = false;
    /** After a beat lands, the player must physically release before the next press can begin. */
    private boolean needRelease = false;
    private float pressDepth = 0.0F;
    private double repGrindRadians = 0.0;
    private int motionSamples = 0;
    private int repsDone = 0;
    private double lastAngle = 0.0;
    private long lastFrameMs = 0L;

    public MortarGrindScreen(OpenMortarGrindPayload payload) {
        super(Component.translatable("bannerboundantiquity.mortar.title"));
        this.pos = payload.pos();
        this.reps = Math.max(1, payload.reps());
        this.batch = Math.max(1, payload.batch());
    }

    @Override
    protected void init() {
        MortarGrindState.begin(pos);
        lastFrameMs = net.minecraft.Util.getMillis();
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0 && !completed && !needRelease) {
            holding = true;
            lastAngle = angleOf(mouseX, mouseY);
            if (!engaged) {
                playUi(SoundEvents.NOTE_BLOCK_BASS.value(), 0.6F, 0.4F);
            }
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (button == 0) {
            holding = false;
            needRelease = false;
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (button != 0 || completed || needRelease) {
            return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
        }
        holding = true;
        if (!engaged) {
            return true; // press the pestle down first
        }

        double radius = radiusOf(mouseX, mouseY);
        if (radius < guideRadius() * MIN_RADIUS_FACTOR) {
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
        repGrindRadians += mag;
        MortarGrindState.addGrind(delta);
        if (++motionSamples % 6 == 0) {
            grindPulse();
        }
        if (repGrindRadians >= REP_QUOTA) {
            completeRep();
        }
        return true;
    }

    private void completeRep() {
        repsDone++;
        crushBurst();
        if (repsDone >= reps) {
            finishComplete();
            return;
        }
        // Next beat: lift the pestle and require a fresh press (release-then-press).
        engaged = false;
        holding = false;
        needRelease = true;
        pressDepth = 0.0F;
        repGrindRadians = 0.0;
        MortarGrindState.setPress(0.0F);
    }

    private void finishComplete() {
        if (completed) return;
        completed = true;
        playUi(SoundEvents.NOTE_BLOCK_CHIME.value(), 1.0F, 1.0F);
        sendAction(MortarGrindActionPayload.COMPLETE);
        if (minecraft != null) minecraft.setScreen(null);
    }

    private void sendAction(int action) {
        PacketDistributor.sendToServer(new MortarGrindActionPayload(pos, action));
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        tickPress();

        int cx = width / 2;
        int boxLeft = panelLeft();
        int boxRight = panelRight();
        int boxTop = panelTop();
        int boxBottom = panelBottom();

        // Title / step hint.
        Component hint = stepHint();
        float pulse = holding ? 1.0F : 0.72F + 0.28F * (float) Math.sin(net.minecraft.Util.getMillis() / 280.0);
        int titleColor = ((int) (pulse * 255.0F) << 24) | (COL_TITLE & 0xFFFFFF);
        g.pose().pushPose();
        g.pose().scale(1.45F, 1.45F, 1.0F);
        g.drawCenteredString(font, hint.copy().withStyle(ChatFormatting.BOLD),
            Math.round(cx / 1.45F), Math.round((boxTop - 21) / 1.45F), titleColor);
        g.pose().popPose();

        // Panel.
        g.fill(boxLeft, boxTop, boxRight, boxBottom, COL_BG);
        g.fillGradient(boxLeft, boxTop, boxLeft + 1, boxBottom, COL_BORDER_TOP, COL_BORDER_BOTTOM);
        g.fillGradient(boxRight - 1, boxTop, boxRight, boxBottom, COL_BORDER_TOP, COL_BORDER_BOTTOM);
        g.fill(boxLeft, boxTop, boxRight, boxTop + 1, COL_BORDER_TOP);
        g.fill(boxLeft, boxBottom - 1, boxRight, boxBottom, COL_BORDER_BOTTOM);

        // Header: batch on the left, beats in the middle, percent on the right.
        g.drawString(font, "× " + batch, boxLeft + 10, boxTop + 9, 0xFFFFFFFF, false);
        g.drawCenteredString(font,
            Component.translatable("bannerboundantiquity.mortar.beats", repsDone, reps),
            cx, boxTop + 9, 0xFFCCCCCC);
        g.drawString(font, Math.round(progress() * 100.0F) + "%", boxRight - 36, boxTop + 9, 0xFFFFFFFF, false);

        drawPressGauge(g);
        drawRing(g, progress());

        // Bottom progress bar.
        int barW = Math.min(310, panelWidth() - 70);
        int barLeft = cx - barW / 2;
        int barTop = panelBottom() - 22;
        int fillRight = barLeft + (int) (barW * progress());
        g.fill(barLeft - 1, barTop - 1, barLeft + barW + 1, barTop, 0xFF2D241B);
        g.fill(barLeft, barTop, barLeft + barW, barTop + 13, 0xFF4D4032);
        g.fill(barLeft, barTop, fillRight, barTop + 13, COL_RING_FILL);
        g.fill(fillRight - 1, barTop - 2, fillRight + 1, barTop + 15, COL_CURSOR);
    }

    /** Builds the press while the button is held (before grinding); decays it when lifted. */
    private void tickPress() {
        long now = net.minecraft.Util.getMillis();
        float dt = Math.min(120.0F, now - lastFrameMs);
        lastFrameMs = now;
        if (engaged) {
            pressDepth = 1.0F;
        } else if (holding && !needRelease) {
            pressDepth += dt / PRESS_MS;
            if (pressDepth >= 1.0F) {
                pressDepth = 1.0F;
                engaged = true;
                playUi(SoundEvents.GRINDSTONE_USE, 0.9F, 0.8F);
                crushBurst();
            }
        } else {
            pressDepth = Math.max(0.0F, pressDepth - dt / PRESS_MS);
        }
        MortarGrindState.setPress(pressDepth);
    }

    private float progress() {
        double repFrac = engaged ? Math.min(1.0, repGrindRadians / REP_QUOTA) : 0.0;
        return (float) clamp((repsDone + repFrac) / reps, 0.0, 1.0);
    }

    private Component stepHint() {
        if (needRelease) {
            return Component.translatable("bannerboundantiquity.mortar.release");
        }
        return Component.translatable(engaged
            ? "bannerboundantiquity.mortar.grind"
            : "bannerboundantiquity.mortar.press");
    }

    // ─── Visuals ─────────────────────────────────────────────────────────────────────────────────

    private void drawPressGauge(GuiGraphics g) {
        int gx = panelLeft() + 26;
        int top = panelTop() + 30;
        int bottom = panelBottom() - 34;
        int h = bottom - top;
        int fill = (int) (h * pressDepth);
        g.fill(gx - 5, top - 1, gx + 5, bottom + 1, 0xFF2D241B);
        g.fill(gx - 4, top, gx + 4, bottom, 0xFF4D4032);
        g.fill(gx - 4, bottom - fill, gx + 4, bottom, engaged ? COL_RING_FILL : COL_PRESS);
        // Pestle head marker riding the fill.
        int py = bottom - fill;
        g.fill(gx - 6, py - 2, gx + 6, py + 2, COL_CURSOR);
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
            g.fill(x - 2, y - 2, x + 2, y + 2, i <= done ? COL_RING_FILL : COL_RING);
        }
        int px = (int) Math.round(cx + Math.cos(lastAngle) * r);
        int py = (int) Math.round(cy + Math.sin(lastAngle) * r);
        if (engaged || progress > 0.0F) {
            g.fill(px - 3, py - 3, px + 3, py + 3, COL_CURSOR);
        }
    }

    // ─── Feedback (sound + in-world particles) ───────────────────────────────────────────────────

    private void grindPulse() {
        playUi(SoundEvents.NOTE_BLOCK_HAT.value(), 1.1F, 0.18F);
        spawnGrindParticles(2, 0.018);
    }

    private void crushBurst() {
        playUi(SoundEvents.GRINDSTONE_USE, 1.0F, 0.5F);
        spawnGrindParticles(8, 0.05);
    }

    private void spawnGrindParticles(int count, double speed) {
        Minecraft mc = minecraft;
        if (mc == null || mc.level == null) return;
        ItemStack ground = groundStack(mc);
        if (ground.isEmpty()) return;
        for (int i = 0; i < count; i++) {
            mc.level.addParticle(new ItemParticleOption(ParticleTypes.ITEM, ground),
                pos.getX() + 0.5 + (Math.random() - 0.5) * 0.4,
                pos.getY() + 0.72 + Math.random() * 0.12,
                pos.getZ() + 0.5 + (Math.random() - 0.5) * 0.4,
                (Math.random() - 0.5) * speed, speed * 0.6, (Math.random() - 0.5) * speed);
        }
    }

    private ItemStack groundStack(Minecraft mc) {
        return mc.level != null
                && mc.level.getBlockEntity(pos) instanceof MortarAndPestleBlockEntity be
            ? be.getIngredient() : ItemStack.EMPTY;
    }

    // ─── Geometry helpers ────────────────────────────────────────────────────────────────────────

    private double guideCx() {
        return width / 2.0;
    }

    private double guideCy() {
        return (panelTop() + panelBottom()) / 2.0;
    }

    private double guideRadius() {
        double topClearance = guideCy() - (panelTop() + 32.0);
        double bottomClearance = (panelBottom() - 22) - 8.0 - guideCy();
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

    @Override
    public void onClose() {
        if (!completed) {
            sendAction(MortarGrindActionPayload.CANCEL);
        }
        super.onClose();
    }

    @Override
    public void removed() {
        MortarGrindState.clear();
        super.removed();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
