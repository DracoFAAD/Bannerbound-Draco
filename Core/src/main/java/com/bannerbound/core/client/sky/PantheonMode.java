package com.bannerbound.core.client.sky;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.bannerbound.core.api.faith.FaithPath;
import com.bannerbound.core.client.ClientFaithState;

import org.jetbrains.annotations.ApiStatus;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * Pantheon mode (FAITH_PLAN Part 3) — in-world star drawing, no GUI star map: the sky IS
 * the screen. Client mode singleton in the ClientBirdseyeState mould. The draft chain is
 * CLIENT-ONLY until submitted; the server's confirm transaction is the arbiter.
 * <p>
 * Controls (mode active, no screen open): crosshair-hover picks the nearest star within
 * ~2.5°; LMB connects it (clicking an earlier chain star truncates back to it — the
 * branch gesture); R or RMB removes the last segment; ENTER opens the naming prompt at
 * 3+ stars; ESC leaves the mode.
 */
@OnlyIn(Dist.CLIENT)
@ApiStatus.Internal
public final class PantheonMode {
    public static final int MAX_STARS = 12;
    public static final int MIN_STARS = 3;
    public static final double PICK_CONE_DEG = 2.5;

    private static boolean active;
    private static final List<Integer> chain = new ArrayList<>();
    private static int hoveredStarId = -1;

    private PantheonMode() {
    }

    public static boolean isActive() {
        return active;
    }

    public static List<Integer> chain() {
        return Collections.unmodifiableList(chain);
    }

    public static int[] chainArray() {
        int[] out = new int[chain.size()];
        for (int i = 0; i < out.length; i++) {
            out[i] = chain.get(i);
        }
        return out;
    }

    public static int hoveredStarId() {
        return hoveredStarId;
    }

    /** Set each frame by the sky renderer's pick pass. */
    public static void setHovered(int starId) {
        hoveredStarId = starId;
    }

    /** Faith-tree node that unlocks Pantheon mode — you chart the stars before you bind them. */
    public static final String STAR_CHARTS_NODE = "bannerboundantiquity:star_charts";

    /** Entry checks (FAITH_PLAN: night + open sky + clear weather + astrology faith
     *  + Star Charts researched). */
    public static boolean enter(Minecraft mc) {
        if (mc.player == null || mc.level == null) return false;
        if (!ClientFaithState.hasFaith()
                || ClientFaithState.pathOrdinal() != FaithPath.ASTROLOGY.ordinal()) {
            return false;
        }
        if (!com.bannerbound.core.client.ClientFaithTreeState.isCompleted(STAR_CHARTS_NODE)) {
            mc.player.displayClientMessage(Component.translatable("bannerbound.pantheon.uncharted")
                .withStyle(ChatFormatting.YELLOW), false);
            return false;
        }
        float brightness = mc.level.getStarBrightness(1.0f) * (1.0f - mc.level.getRainLevel(1.0f));
        if (brightness <= 0.1f) {
            mc.player.displayClientMessage(Component.translatable("bannerbound.pantheon.veiled")
                .withStyle(ChatFormatting.YELLOW), false);
            return false;
        }
        if (!mc.level.canSeeSky(mc.player.blockPosition().above())) {
            mc.player.displayClientMessage(Component.translatable("bannerbound.pantheon.no_sky")
                .withStyle(ChatFormatting.YELLOW), false);
            return false;
        }
        active = true;
        chain.clear();
        hoveredStarId = -1;
        mc.player.displayClientMessage(Component.translatable("bannerbound.pantheon.entered")
            .withStyle(ChatFormatting.GOLD), false);
        return true;
    }

    public static void exit() {
        active = false;
        chain.clear();
        hoveredStarId = -1;
    }

    /** LMB: connect the hovered star — or branch by truncating back to an earlier one. */
    public static void clickPrimary() {
        if (hoveredStarId < 0) return;
        int existing = chain.indexOf(hoveredStarId);
        if (existing >= 0) {
            // Branch gesture: keep the chain up to and including this star.
            while (chain.size() > existing + 1) {
                chain.remove(chain.size() - 1);
            }
            return;
        }
        if (chain.size() >= MAX_STARS) return;
        if (ClientConstellationState.starUsed(hoveredStarId)) return;
        chain.add(hoveredStarId);
    }

    public static void undo() {
        if (!chain.isEmpty()) {
            chain.remove(chain.size() - 1);
        }
    }

    public static boolean canConfirm() {
        return chain.size() >= MIN_STARS;
    }
}
