package com.bannerbound.antiquity.client;

import org.jetbrains.annotations.ApiStatus;
import org.lwjgl.glfw.GLFW;

import com.bannerbound.antiquity.network.OpenTanningPayload;
import com.bannerbound.antiquity.network.TanningActionPayload;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Transparent input layer for the tanning-rack scrape minigame. The OS cursor is hidden and replaced
 * by the equipped knife (drawn large); hold left-click and swipe <b>over the hide</b> (centre screen,
 * where you were aiming when you opened it — the view is frozen while the screen is up). Non-skill,
 * but raking the SAME direction has diminishing returns, so you must scrape back and forth across the
 * hide. Output quantity comes from the hide's quality, not accuracy.
 */
@OnlyIn(Dist.CLIENT)
@ApiStatus.Internal
public class TanningRackScreen extends Screen {
    /** Pixels of (direction-weighted) swipe travel that make up one scrape. */
    private static final double TRAVEL_PER_SWIPE = 200.0;
    /** Minimum motion (px) that counts as a swipe. */
    private static final double MOTION_EPS = 1.5;
    /** How strongly repeating the same direction is penalised (0 = none, 1 = fully). */
    private static final double SAME_DIR_PENALTY = 0.85;
    /** Knife cursor scale. */
    private static final float KNIFE_SCALE = 4.0F;

    private static final int COL_BG = 0xF01A1008;
    private static final int COL_BORDER_TOP = 0x60C8A878;
    private static final int COL_BORDER_BOTTOM = 0x60402814;
    private static final int COL_TRACK = 0xFF4D4032;
    private static final int COL_FILL = 0xFFC9A26A;
    private static final int COL_CURSOR = 0xFFFFFFFF;
    private static final int COL_TITLE = 0xFFE8D7B8;

    private final BlockPos pos;
    private final int swipesNeeded;
    private final ItemStack knife;
    private double travelDone = 0.0;
    private int lastWholeSwipe = 0;
    private boolean completed = false;
    private double cursorX;
    private double cursorY;
    /** Exponential moving average of recent swipe direction (for the same-direction penalty). */
    private double dirX = 0.0;
    private double dirY = 0.0;

    public TanningRackScreen(OpenTanningPayload payload) {
        super(Component.translatable("bannerboundantiquity.tanning.title"));
        this.pos = payload.pos();
        this.swipesNeeded = Math.max(1, payload.swipes());
        Minecraft mc = Minecraft.getInstance();
        ItemStack held = mc.player != null ? mc.player.getMainHandItem() : ItemStack.EMPTY;
        this.knife = held.isEmpty() ? new ItemStack(Items.STICK) : held.copy();
    }

    @Override
    protected void init() {
        cursorX = this.width / 2.0;
        cursorY = this.height / 2.0;
        // Hide the OS cursor (still reports position) — the knife stands in for it.
        if (minecraft != null) {
            GLFW.glfwSetInputMode(minecraft.getWindow().getWindow(), GLFW.GLFW_CURSOR, GLFW.GLFW_CURSOR_HIDDEN);
        }
    }

    /** The hide sits at screen centre (where the crosshair was when the rack was right-clicked, and
     *  the view is frozen). Radius scales with the smaller screen dimension. */
    private double scrapeRadius() {
        return Math.min(this.width, this.height) * 0.30;
    }

    private boolean overHide(double x, double y) {
        double dx = x - this.width / 2.0;
        double dy = y - this.height / 2.0;
        return dx * dx + dy * dy <= scrapeRadius() * scrapeRadius();
    }

    private int swipesDone() {
        return Math.min(swipesNeeded, (int) Math.floor(travelDone / TRAVEL_PER_SWIPE));
    }

    private float progress() {
        return Math.min(1.0F, (float) (travelDone / (TRAVEL_PER_SWIPE * swipesNeeded)));
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        cursorX = mouseX;
        cursorY = mouseY;
        if (button == 0 && !completed) {
            double len = Math.sqrt(dragX * dragX + dragY * dragY);
            if (len >= MOTION_EPS && overHide(mouseX, mouseY)) {
                double ux = dragX / len;
                double uy = dragY / len;
                // Alignment with the recent direction (0 = new direction, 1 = same direction).
                double sim = Math.max(0.0, dirX * ux + dirY * uy);
                double mult = 1.0 - SAME_DIR_PENALTY * sim;
                travelDone += len * mult;
                dirX = dirX * 0.7 + ux * 0.3;
                dirY = dirY * 0.7 + uy * 0.3;

                int whole = swipesDone();
                if (whole > lastWholeSwipe) {
                    lastWholeSwipe = whole;
                    scrapePulse();
                }
                if (progress() >= 1.0F) finishComplete();
            }
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dragX, dragY);
    }

    @Override
    public void mouseMoved(double mouseX, double mouseY) {
        cursorX = mouseX;
        cursorY = mouseY;
        super.mouseMoved(mouseX, mouseY);
    }

    private void scrapePulse() {
        Minecraft mc = this.minecraft;
        if (mc == null) return;
        mc.getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.SHEEP_SHEAR, 1.1F, 0.5F));
    }

    private void finishComplete() {
        if (completed) return;
        completed = true;
        PacketDistributor.sendToServer(new TanningActionPayload(pos, TanningActionPayload.COMPLETE));
        if (minecraft != null) minecraft.setScreen(null);
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        cursorX = mouseX;
        cursorY = mouseY;
        int cx = this.width / 2;
        int barW = 320;
        int boxLeft = cx - barW / 2 - 14;
        int boxRight = cx + barW / 2 + 14;
        int boxTop = this.height - 104;
        int boxBottom = boxTop + 64;

        Component title = Component.translatable("bannerboundantiquity.tanning.title")
            .withStyle(ChatFormatting.BOLD);
        g.drawCenteredString(this.font, title, cx, boxTop - 16, COL_TITLE);

        g.fill(boxLeft, boxTop, boxRight, boxBottom, COL_BG);
        g.fill(boxLeft, boxTop, boxRight, boxTop + 1, COL_BORDER_TOP);
        g.fill(boxLeft, boxBottom - 1, boxRight, boxBottom, COL_BORDER_BOTTOM);

        g.drawString(this.font, swipesDone() + "/" + swipesNeeded, boxLeft + 9, boxTop + 8, 0xFFFFFFFF, false);
        g.drawCenteredString(this.font,
            Component.translatable("bannerboundantiquity.tanning.hint").withStyle(ChatFormatting.GRAY),
            cx, boxTop + 8, 0xFFCCCCCC);

        int barLeft = cx - barW / 2;
        int barTop = boxTop + 26;
        int barH = 16;
        g.fill(barLeft, barTop, barLeft + barW, barTop + barH, COL_TRACK);
        int fillRight = barLeft + (int) (barW * progress());
        g.fill(barLeft, barTop, fillRight, barTop + barH, COL_FILL);
        g.fill(fillRight - 1, barTop - 2, fillRight + 1, barTop + barH + 2, COL_CURSOR);
        g.drawCenteredString(this.font, Math.round(progress() * 100.0F) + "%", cx, boxBottom - 12, 0xFFFFFFFF);

        // The knife stands in for the (hidden) cursor — drawn large, dimmed when off the hide.
        boolean onHide = overHide(cursorX, cursorY);
        g.pose().pushPose();
        g.pose().translate(cursorX, cursorY, 250.0);
        g.pose().scale(KNIFE_SCALE, KNIFE_SCALE, 1.0F);
        if (!onHide) {
            g.setColor(1.0F, 1.0F, 1.0F, 0.45F);
        }
        g.renderItem(knife, -8, -8);
        g.setColor(1.0F, 1.0F, 1.0F, 1.0F);
        g.pose().popPose();
    }

    @Override
    public void removed() {
        // Restore the OS cursor; MC re-grabs the mouse for gameplay after the screen closes.
        if (minecraft != null) {
            GLFW.glfwSetInputMode(minecraft.getWindow().getWindow(), GLFW.GLFW_CURSOR, GLFW.GLFW_CURSOR_NORMAL);
        }
        super.removed();
    }

    @Override
    public void onClose() {
        if (!completed) {
            PacketDistributor.sendToServer(new TanningActionPayload(pos, TanningActionPayload.CANCEL));
        }
        super.onClose();
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
