package com.bannerbound.core.client;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.Config;
import com.bannerbound.core.network.CodexToastPayload;
import com.bannerbound.core.network.ShowTutorialPopupPayload;

import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * Client-side presentation queue for tutorial popups (TUTORIAL_POPUP_PLAN.md): one modal at a
 * time, FIFO within order, and defer-not-drop. tick() opens the next queued popup only when it is
 * safe to interrupt: no other screen is open, the player is alive and has not just taken damage,
 * and at least MIN_SPACING_TICKS have passed since the last popup was dismissed (so a batch of
 * unlocks never machine-guns modals). The tutorialPopups config toggle downgrades every queued
 * popup to a Chronicle toast - the linked entry is already unlocked server-side, so the knowledge
 * still arrives quietly. The spacing clock uses the client tick counter, which never runs while a
 * single-player game is paused.
 */
@OnlyIn(Dist.CLIENT)
@ApiStatus.Internal
public final class ClientTutorialPopups {
    private static final int MIN_SPACING_TICKS = 20 * 45;
    private static final List<ShowTutorialPopupPayload> QUEUE = new ArrayList<>();
    private static long clientTicks;
    private static long lastDismissedTick = -MIN_SPACING_TICKS;
    private static boolean anyShownYet;

    private ClientTutorialPopups() {
    }

    public static void enqueue(ShowTutorialPopupPayload payload) {
        if (payload == null || payload.pages().isEmpty()) return;
        if (payload.immediate()) {
            // Replay (View Tutorial / command): open on top NOW, returning to the prior screen.
            Minecraft mc = Minecraft.getInstance();
            mc.setScreen(new TutorialPopupScreen(payload, mc.screen));
            return;
        }
        QUEUE.removeIf(existing -> existing.popupId().equals(payload.popupId()));
        QUEUE.add(payload);
        QUEUE.sort(Comparator.comparingInt(ShowTutorialPopupPayload::order));
    }

    public static void tick(Minecraft mc) {
        clientTicks++;
        if (QUEUE.isEmpty()) return;
        // No player = back in the menus; stale popups must not leak into the next session.
        if (mc.player == null) {
            QUEUE.clear();
            return;
        }
        if (!Config.TUTORIAL_POPUPS.get()) {
            for (ShowTutorialPopupPayload payload : QUEUE) {
                ClientChronicleState.enqueueToast(new CodexToastPayload(1,
                    payload.pages().get(0).title(), ""));
            }
            QUEUE.clear();
            return;
        }
        if (mc.screen != null || !mc.player.isAlive() || mc.player.hurtTime > 0) return;
        // The very first popup of a session may show immediately; afterwards enforce spacing.
        if (anyShownYet && clientTicks - lastDismissedTick < MIN_SPACING_TICKS) return;
        ShowTutorialPopupPayload next = QUEUE.remove(0);
        anyShownYet = true;
        mc.setScreen(new TutorialPopupScreen(next));
    }

    static void markDismissed() {
        lastDismissedTick = clientTicks;
    }

    public static void clear() {
        QUEUE.clear();
    }
}
