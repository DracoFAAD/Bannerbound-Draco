package com.bannerbound.core.client;

import java.util.List;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.codex.TutorialPopup;
import com.bannerbound.core.network.ShowTutorialPopupPayload;

import net.minecraft.ChatFormatting;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.network.chat.Style;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.sounds.SoundEvents;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;

/**
 * The AAA-style tutorial popup modal (TUTORIAL_POPUP_PLAN.md): a centered portrait panel with
 * the page's media (clip poster or image) up top, title and wrapped text below, and Back / Next /
 * Close paging along the bottom. Pages come fully materialized in the ShowTutorialPopupPayload,
 * so this screen reads no datapack state. Clips render their poster with a play badge until the
 * Phase 2 decoder lands (ClientCodexClips supplies the metadata); a missing poster degrades to a
 * captioned placeholder box, never a failure. Text supports the Chronicle's '&' formatting codes
 * plus {key:<mapping>} substitution, which resolves the player's actual current keybind. Panel
 * height re-derives per page in init()/rebuild() since text length varies; vanilla pause
 * semantics are kept deliberately (pauses only a local single-player world, like any screen).
 * Dismissing routes through ClientTutorialPopups.markDismissed so queued popups honor spacing.
 * The linked Chronicle entry (payload.entry) is already unlocked server-side for re-reading.
 */
@OnlyIn(Dist.CLIENT)
@ApiStatus.Internal
public final class TutorialPopupScreen extends PolishedScreen {
    private static final int PANEL_W = 270;
    private static final int MEDIA_H = 140;
    private static final int PAD = 10;
    private static final int BTN_H = 18;

    private final ShowTutorialPopupPayload payload;
    private final net.minecraft.client.gui.screens.Screen returnTo;
    private int page;
    private int panelX;
    private int panelY;
    private int panelH;
    private int mediaH;

    public TutorialPopupScreen(ShowTutorialPopupPayload payload) {
        this(payload, null);
    }

    public TutorialPopupScreen(ShowTutorialPopupPayload payload, net.minecraft.client.gui.screens.Screen returnTo) {
        super(Component.literal(payload.pages().isEmpty() ? "" : payload.pages().get(0).title()));
        this.payload = payload;
        this.returnTo = returnTo;
    }

    @Override
    protected void init() {
        rebuild();
    }

    private void rebuild() {
        clearWidgets();
        TutorialPopup.Page current = currentPage();
        boolean hasMedia = pageHasMedia(current);
        Component body = richText(current.text());
        int textW = PANEL_W - PAD * 2;
        int textH = wrappedLineCount(this.font, body, textW) * (this.font.lineHeight + 1);
        mediaH = hasMedia ? Math.min(MEDIA_H, Math.max(80, this.height - textH - 110)) : 0;
        panelH = PAD + mediaH + (hasMedia ? 8 : 0) + 14 + 6 + textH + PAD + BTN_H + PAD;
        panelH = Math.min(panelH, this.height - 12);
        panelX = (this.width - PANEL_W) / 2;
        panelY = (this.height - panelH) / 2;

        int btnY = panelY + panelH - PAD - BTN_H;
        boolean last = page >= payload.pages().size() - 1;
        if (page > 0) {
            addRenderableWidget(PolishButton.polished(
                Component.translatable("bannerbound.tutorial_popup.back"), btn -> turnTo(page - 1))
                .bounds(panelX + PAD, btnY, 62, BTN_H)
                .build());
        }
        addRenderableWidget(PolishButton.polished(
            last ? Component.translatable("bannerbound.tutorial_popup.close")
                 : Component.translatable("bannerbound.tutorial_popup.next"),
            btn -> {
                if (last) onClose();
                else turnTo(page + 1);
            })
            .bounds(panelX + PANEL_W - PAD - 62, btnY, 62, BTN_H)
            .build());
    }

    private void turnTo(int newPage) {
        page = Math.max(0, Math.min(payload.pages().size() - 1, newPage));
        Minecraft mc = Minecraft.getInstance();
        if (mc.player != null) mc.player.playSound(SoundEvents.BOOK_PAGE_TURN, 0.4f, 1.1f);
        rebuild();
    }

    private TutorialPopup.Page currentPage() {
        List<TutorialPopup.Page> pages = payload.pages();
        if (pages.isEmpty()) return new TutorialPopup.Page("", "", "", "");
        return pages.get(Math.max(0, Math.min(pages.size() - 1, page)));
    }

    @Override
    protected void renderPolishedBackdrop(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        TutorialPopup.Page current = currentPage();

        graphics.fill(panelX - 1, panelY - 1, panelX + PANEL_W + 1, panelY + panelH + 1, 0xFF060A0D);
        graphics.fill(panelX, panelY, panelX + PANEL_W, panelY + panelH, 0xF5161C22);
        graphics.renderOutline(panelX, panelY, PANEL_W, panelH, 0xFF3A444C);

        int y = panelY + PAD;
        if (mediaH > 0) {
            y = renderMedia(graphics, current, panelX + PAD, y, PANEL_W - PAD * 2) + 8;
        }
        graphics.drawString(this.font, richText(current.title()), panelX + PAD, y, 0xFFFFD36A, false);
        y += 20;
        drawWrapped(graphics, this.font, richText(current.text()),
            panelX + PAD, y, PANEL_W - PAD * 2, 0xFFD8D8D8);

        int total = payload.pages().size();
        if (total > 1) {
            int dotsW = total * 8 - 3;
            int dx = panelX + (PANEL_W - dotsW) / 2;
            int dy = panelY + panelH - PAD - BTN_H + BTN_H / 2 - 2;
            for (int i = 0; i < total; i++) {
                graphics.fill(dx + i * 8, dy, dx + i * 8 + 5, dy + 5,
                    i == page ? 0xFFFFD36A : 0xFF4A555E);
            }
        }
    }

    /** Whether this page has renderable media RIGHT NOW: a decodable video, a poster, or an
     *  image texture. A clip whose assets have not landed yet renders as a text-only page
     *  instead of advertising a placeholder. */
    private static boolean pageHasMedia(TutorialPopup.Page page) {
        if (!page.clip().isBlank()) {
            ClientCodexClips.Clip clip = ClientCodexClips.get(page.clip());
            if (!clip.present()) return false;
            ResourceLocation video = ResourceLocation.tryParse(clip.video());
            if (video != null && resourceExists(video)) return true;
            ResourceLocation poster = clip.posterLocation();
            return poster != null && resourceExists(poster);
        }
        ResourceLocation image = mediaTexture(page);
        return image != null && resourceExists(image);
    }

    private int renderMedia(GuiGraphics graphics, TutorialPopup.Page current, int x, int y, int w) {
        graphics.fill(x, y, x + w, y + mediaH, 0xFF0C1114);
        // Clips autoplay chrome-free: live video once the decoder has a frame, the poster or a
        // plain dark box while it spins up. Pages without assets never reach here (pageHasMedia).
        ResourceLocation live = current.clip().isBlank() ? null
            : ClipPlaybackManager.videoTexture(ClientCodexClips.get(current.clip()));
        if (live != null) {
            graphics.blit(live, x, y, w, mediaH, 0f, 0f, 16, 16, 16, 16);
        } else {
            ResourceLocation still = mediaTexture(current);
            if (still != null && resourceExists(still)) {
                graphics.blit(still, x, y, w, mediaH, 0f, 0f, 16, 16, 16, 16);
            }
        }
        graphics.renderOutline(x, y, w, mediaH, 0xFF3A444C);
        return y + mediaH;
    }

    private static ResourceLocation mediaTexture(TutorialPopup.Page page) {
        if (!page.clip().isBlank()) {
            return ClientCodexClips.get(page.clip()).posterLocation();
        }
        if (page.image().isBlank()) return null;
        ResourceLocation raw = ResourceLocation.tryParse(page.image());
        if (raw == null) return null;
        // Bare ids (no path folders) resolve into the codex images folder; full texture paths pass through.
        if (!raw.getPath().contains("/")) {
            return ResourceLocation.fromNamespaceAndPath(raw.getNamespace(),
                "textures/codex/images/" + raw.getPath() + ".png");
        }
        return raw;
    }

    private static boolean resourceExists(ResourceLocation location) {
        return location != null
            && Minecraft.getInstance().getResourceManager().getResource(location).isPresent();
    }

    static String substitute(String text) {
        String s = text == null ? "" : text;
        int guard = 0;
        while (guard++ < 32) {
            int start = s.indexOf("{key:");
            if (start < 0) break;
            int end = s.indexOf('}', start);
            if (end < 0) break;
            String mapping = s.substring(start + 5, end).trim();
            s = s.substring(0, start) + keyLabel(mapping) + s.substring(end + 1);
        }
        return s;
    }

    private static String keyLabel(String mappingName) {
        for (KeyMapping mapping : Minecraft.getInstance().options.keyMappings) {
            if (mapping.getName().equals(mappingName)) {
                return "[" + mapping.getTranslatedKeyMessage().getString().toUpperCase() + "]";
            }
        }
        return "[" + mappingName + "]";
    }

    /**
     * Parses '&' formatting codes into explicitly styled Component segments instead of legacy
     * section signs. Legacy codes fed through Font.split are broken for wrapped text: the
     * splitter re-iterates each wrapped remainder with the carried style as the new reset
     * baseline, so a reset code after the first line break restores the carried color rather
     * than the default. Explicit per-segment styles survive wrapping correctly.
     */
    private static Component richText(String raw) {
        String s = substitute(raw);
        MutableComponent out = Component.empty();
        StringBuilder run = new StringBuilder();
        Style style = Style.EMPTY;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '&' && i + 1 < s.length()) {
                ChatFormatting format = ChatFormatting.getByCode(s.charAt(i + 1));
                if (format != null) {
                    if (run.length() > 0) {
                        out.append(Component.literal(run.toString()).setStyle(style));
                        run.setLength(0);
                    }
                    style = format == ChatFormatting.RESET ? Style.EMPTY : style.applyLegacyFormat(format);
                    i++;
                    continue;
                }
            }
            run.append(c);
        }
        if (run.length() > 0) {
            out.append(Component.literal(run.toString()).setStyle(style));
        }
        return out;
    }

    @Override
    public void onClose() {
        ClientTutorialPopups.markDismissed();
        if (returnTo != null) {
            Minecraft.getInstance().setScreen(returnTo);
        } else {
            super.onClose();
        }
    }
}
