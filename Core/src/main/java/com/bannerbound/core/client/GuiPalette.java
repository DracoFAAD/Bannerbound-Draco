package com.bannerbound.core.client;

import java.util.ArrayList;
import java.util.List;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.network.ClaimEntry;

import net.minecraft.client.Minecraft;
import net.minecraft.world.level.ChunkPos;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * THE shared GUI color vocabulary. Every panel screen draws its chrome and text from these
 * roles instead of ad-hoc hex constants, so the whole mod shifts together when a value is
 * tuned. Screens with a deliberate theme of their own (codex gold, barbarian slate, minigame
 * warm-tones) keep their local palettes — this class is the default, not a straitjacket.
 *
 * <p>Identity accents: settlement-scoped screens wear the settlement's banner colors in their
 * chrome (border, dividers, header) via {@link #localIdentityAccents()} — resolved from the
 * claim under the player's feet, the same lookup the "Currently in …" HUD uses, so it needs no
 * per-screen network plumbing and degrades to the neutral chrome on unclaimed ground.
 */
@OnlyIn(Dist.CLIENT)
@ApiStatus.Internal
public final class GuiPalette {

    private GuiPalette() {}

    // ─── Panel chrome ────────────────────────────────────────────────────────────────────────
    /** Standard panel fill — the near-black every core panel screen sits on. */
    public static final int PANEL_BG = 0xFF101010;
    /** Neutral panel outline; identity screens blend this toward the banner primary. */
    public static final int PANEL_BORDER = 0xFF606060;
    /** Inset well fill (list boxes, slot wells) — one step lighter than the panel. */
    public static final int WELL_BG = 0xFF1A1A1A;

    // ─── Text roles ──────────────────────────────────────────────────────────────────────────
    /** Screen titles and primary values. */
    public static final int TITLE = 0xFFFFFFFF;
    /** Secondary headings / emphasized labels. */
    public static final int SUBTITLE = 0xFFCCCCCC;
    /** Field labels and de-emphasized body text. */
    public static final int LABEL = 0xFFAAAAAA;
    /** Hints, counts, disabled text. */
    public static final int MUTED = 0xFF999999;

    // ─── Status (already consistent across screens — canonized here) ─────────────────────────
    public static final int GOOD = 0xFF2EB872;
    public static final int WARN = 0xFFE9D24A;
    public static final int BAD = 0xFFE57761;

    // ─── Identity accents ────────────────────────────────────────────────────────────────────

    /** Banner-identity accents (ARGB, most-present dye first) of the settlement claiming the
     *  chunk the local player stands in — empty on unclaimed ground, so callers fall back to
     *  the neutral chrome. Resolve ONCE at screen construction, not per frame. */
    public static List<Integer> localIdentityAccents() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return List.of();
        ClaimEntry entry = ClientClaimState.getEntry(new ChunkPos(mc.player.blockPosition()).toLong());
        if (entry == null) return List.of();
        return identityAccents(entry.colorIndex());
    }

    /** Banner-identity accents (ARGB) for a known founding-color slot — for screens whose open
     *  payload already carries the settlement's color ordinal. Never empty (founding fallback). */
    public static List<Integer> identityAccents(int colorOrdinal) {
        List<Integer> rgbs = ClientIdentityState.rgbs(colorOrdinal);
        List<Integer> accents = new ArrayList<>(rgbs.size());
        for (int rgb : rgbs) accents.add(0xFF000000 | rgb);
        return List.copyOf(accents);
    }

    /** First accent of the list, or the neutral border when the list is empty. */
    public static int primary(List<Integer> accents) {
        return accents.isEmpty() ? PANEL_BORDER : accents.get(0);
    }
}
