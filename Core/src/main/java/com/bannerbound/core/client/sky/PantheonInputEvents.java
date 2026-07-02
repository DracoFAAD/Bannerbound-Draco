package com.bannerbound.core.client.sky;

import com.bannerbound.core.BannerboundCore;

import org.jetbrains.annotations.ApiStatus;
import org.lwjgl.glfw.GLFW;

import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.InputEvent;

/**
 * Pantheon-mode input (FAITH_PLAN Part 3 — crosshair-aim, not a freed cursor): normal FPS
 * look stays active; we intercept clicks/keys only while the mode is on and no screen is
 * open. Mouse events are consumed so drawing the heavens never punches a block.
 */
@EventBusSubscriber(modid = BannerboundCore.MODID, value = Dist.CLIENT)
@ApiStatus.Internal
public final class PantheonInputEvents {
    private PantheonInputEvents() {
    }

    @SubscribeEvent
    public static void onMouse(InputEvent.MouseButton.Pre event) {
        Minecraft mc = Minecraft.getInstance();
        if (!PantheonMode.isActive() || mc.screen != null || mc.player == null) return;
        if (event.getAction() == GLFW.GLFW_PRESS) {
            if (event.getButton() == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
                PantheonMode.clickPrimary();
            } else if (event.getButton() == GLFW.GLFW_MOUSE_BUTTON_RIGHT) {
                PantheonMode.undo();
            }
        }
        event.setCanceled(true);
    }

    @SubscribeEvent
    public static void onKey(InputEvent.Key event) {
        Minecraft mc = Minecraft.getInstance();
        if (!PantheonMode.isActive() || mc.screen != null || mc.player == null) return;
        if (event.getAction() != GLFW.GLFW_PRESS) return;
        switch (event.getKey()) {
            case GLFW.GLFW_KEY_R -> PantheonMode.undo();
            case GLFW.GLFW_KEY_ENTER -> {
                if (PantheonMode.canConfirm()) {
                    mc.setScreen(new com.bannerbound.core.client.NameConstellationScreen());
                }
            }
            default -> { }
        }
    }

    /** ESC in-game opens the pause menu BEFORE InputEvent.Key fires — so an ESC exit has
     *  to intercept the screen itself: while the mode is active, cancel the pause screen
     *  and leave the mode instead. Pressing ESC again (mode off) pauses as normal. */
    @SubscribeEvent
    public static void onScreenOpening(net.neoforged.neoforge.client.event.ScreenEvent.Opening event) {
        if (!PantheonMode.isActive()) return;
        if (event.getNewScreen() instanceof net.minecraft.client.gui.screens.PauseScreen) {
            PantheonMode.exit();
            event.setCanceled(true);
        }
    }
}
