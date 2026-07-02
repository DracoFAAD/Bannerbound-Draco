package com.bannerbound.antiquity.client;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.antiquity.BannerboundAntiquity;
import com.bannerbound.antiquity.network.CarpentryActionPayload;
import com.bannerbound.antiquity.network.OpenCarpentrySawPayload;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.BlockParticleOption;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Transparent input layer for the carpenter's-table saw minigame. The world and table animation stay
 * visible behind a compact fletching-style HUD: no container, no inventory grid.
 */
@OnlyIn(Dist.CLIENT)
@ApiStatus.Internal
public class WoodworkingTableSawScreen extends Screen {
    /** Pixels of vertical mouse travel that make up one saw stroke. */
    private static final double TRAVEL_PER_STROKE = 450.0;
    /** Minimum |dragY| (px) that counts as sawing motion. */
    private static final double MOTION_EPS = 1.5;

    private final BlockPos pos;
    private final int strokesNeeded;
    private double travelDone = 0.0;
    private int lastWholeStroke = 0;
    private net.minecraft.client.resources.sounds.SoundInstance sawSound;
    private boolean completed = false;

    private static final int COL_BG = 0xF01A1008;
    private static final int COL_BORDER_TOP = 0x60D0A060;
    private static final int COL_BORDER_BOTTOM = 0x60402010;
    private static final int COL_TRACK = 0xFF4D4032;
    private static final int COL_FILL = 0xFFD69A42;
    private static final int COL_CURSOR = 0xFFFFFFFF;
    private static final int COL_DONE = 0xFFB88744;
    private static final int COL_WAITING = 0xFF5A4632;
    private static final int COL_TITLE = 0xFFE8C99A;

    public WoodworkingTableSawScreen(OpenCarpentrySawPayload payload) {
        super(Component.translatable("bannerboundantiquity.carpentry.saw.title"));
        this.pos = payload.pos();
        this.strokesNeeded = Math.max(1, payload.strokesNeeded());
    }

    @Override
    protected void init() {
        float yaw = WoodworkingTableRenderer.snappedYawTowardCamera(
            pos, Minecraft.getInstance().gameRenderer.getMainCamera().getPosition());
        CarpentrySawState.begin(pos, strokesNeeded, yaw);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button == 0) {
            CarpentrySawState.holding = true;
            return true;
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (button == 0) {
            CarpentrySawState.holding = false;
            return true;
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (button == 0 && !completed) {
            CarpentrySawState.holding = true;
            if (Math.abs(dragY) >= MOTION_EPS) {
                travelDone += Math.abs(dragY);
                CarpentrySawState.updateProgress(travelDone, TRAVEL_PER_STROKE);
                sawPulse();

                int wholeStroke = Math.min(strokesNeeded, (int) Math.floor(travelDone / TRAVEL_PER_STROKE));
                if (wholeStroke > lastWholeStroke) {
                    lastWholeStroke = wholeStroke;
                    CarpentrySawState.pulse();
                }
                if (CarpentrySawState.progress >= 1.0F) finishComplete();
            }
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    private void sawPulse() {
        Minecraft mc = this.minecraft;
        if (mc == null) return;
        if (sawSound != null && mc.getSoundManager().isActive(sawSound)) return;
        sawSound = SimpleSoundInstance.forUI(BannerboundAntiquity.SAW_SOUND.get(), 1.0F, 0.9F);
        mc.getSoundManager().play(sawSound);
        if (mc.level == null) return;

        for (int i = 0; i < 5; i++) {
            mc.level.addParticle(
                new BlockParticleOption(ParticleTypes.BLOCK, Blocks.OAK_PLANKS.defaultBlockState()),
                pos.getX() + 0.5 + (Math.random() - 0.5) * 0.35,
                pos.getY() + 1.12,
                pos.getZ() + 0.5 + (Math.random() - 0.5) * 0.35,
                (Math.random() - 0.5) * 0.02, -0.045, (Math.random() - 0.5) * 0.02);
        }
    }

    private void finishComplete() {
        if (completed) return;
        completed = true;
        PacketDistributor.sendToServer(new CarpentryActionPayload(pos, CarpentryActionPayload.COMPLETE));
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

        float pulse = CarpentrySawState.holding ? 1.0F
            : 0.72F + 0.28F * (float) Math.sin(System.currentTimeMillis() / 280.0);
        int titleColor = ((int) (pulse * 255.0F) << 24) | (COL_TITLE & 0xFFFFFF);
        Component title = Component.translatable("bannerboundantiquity.carpentry.saw.title")
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

        int done = Math.min(CarpentrySawState.strokesDone, strokesNeeded);
        g.drawString(this.font, done + "/" + strokesNeeded, boxLeft + 9, boxTop + 8, 0xFFFFFFFF, false);
        Component hint = Component.translatable("bannerboundantiquity.carpentry.saw.hint")
            .withStyle(ChatFormatting.GRAY);
        g.drawCenteredString(this.font, hint, cx, boxTop + 8, 0xFFCCCCCC);

        int barLeft = cx - barW / 2;
        int barTop = boxTop + 26;
        int barBottom = barTop + barH;
        g.fill(barLeft - 1, barTop - 1, barLeft + barW + 1, barTop, 0xFF2C241C);
        g.fill(barLeft - 1, barTop - 1, barLeft, barBottom + 1, 0xFF2C241C);
        g.fill(barLeft, barBottom, barLeft + barW + 1, barBottom + 1, 0xFFE0C080);
        g.fill(barLeft + barW, barTop, barLeft + barW + 1, barBottom + 1, 0xFFE0C080);
        g.fill(barLeft, barTop, barLeft + barW, barBottom, COL_TRACK);
        int fillRight = barLeft + (int) (barW * CarpentrySawState.progress);
        g.fill(barLeft, barTop, fillRight, barBottom, COL_FILL);
        g.fill(fillRight - 1, barTop - 2, fillRight + 1, barBottom + 2, COL_CURSOR);

        drawStrokePips(g, cx, barBottom + 9, barW);
        g.drawCenteredString(this.font, Math.round(CarpentrySawState.progress * 100.0F) + "%",
            cx, boxBottom - 13, 0xFFFFFFFF);
    }

    private void drawStrokePips(GuiGraphics g, int cx, int y, int maxW) {
        int pipSize = strokesNeeded > 24 ? 4 : 5;
        int pipPitch = strokesNeeded > 24 ? 7 : 10;
        int totalW = strokesNeeded * pipPitch - (pipPitch - pipSize);
        if (totalW > maxW) {
            pipPitch = Math.max(5, maxW / Math.max(1, strokesNeeded));
            pipSize = Math.max(3, pipPitch - 2);
            totalW = strokesNeeded * pipPitch - (pipPitch - pipSize);
        }
        int left = cx - totalW / 2;
        int done = Math.min(CarpentrySawState.strokesDone, strokesNeeded);
        for (int i = 0; i < strokesNeeded; i++) {
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
            PacketDistributor.sendToServer(new CarpentryActionPayload(pos, CarpentryActionPayload.CANCEL));
        }
        super.onClose();
    }

    @Override
    public void removed() {
        CarpentrySawState.clear();
        super.removed();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
