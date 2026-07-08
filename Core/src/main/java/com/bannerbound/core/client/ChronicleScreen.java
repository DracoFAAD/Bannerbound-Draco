package com.bannerbound.core.client;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.api.research.ResearchPonderBridge;
import com.bannerbound.core.network.CodexSyncPayload;
import com.bannerbound.core.network.MenuOpenedPayload;
import com.bannerbound.core.network.ToggleCodexPinPayload;
import com.mojang.blaze3d.platform.NativeImage;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Player-facing Chronicle/codex screen opened with J. Two independently scrolled panes: a
 * sidebar listing entries grouped by category (locked entries render "???" and are inert) and
 * an article pane rendering the selected entry's page elements (heading / items / link / clip /
 * image / recipe / rich text). Unlock and read/unread state live in {@link ClientChronicleState};
 * pin state in {@link ClientJournalState}.
 * <p>
 * Rich text supports {@code {entry:id|label}} inline links that word-wrap token by token and,
 * when the target is unlocked, become clickable cross-references. applyAmp turns '&' into the
 * section sign so entry text can carry vanilla formatting codes. The header pin and the auto-pin
 * toggle round-trip through {@link ToggleCodexPinPayload}; the {@code <} / {@code >} buttons flip
 * through unlocked entries in sidebar order honouring the search filter (the shipped browse model
 * that replaced the older back/forward click history, whose stacks still exist here).
 * <p>
 * init() re-runs on every resize, so {@code announcedOpen} gates the one-shot
 * {@link MenuOpenedPayload} (which fires the "menu_opened" tutorial trigger) to send exactly
 * once per screen instance. Decoded image dimensions are cached in IMAGE_INFO across instances.
 */
@OnlyIn(Dist.CLIENT)
@ApiStatus.Internal
public final class ChronicleScreen extends PolishedScreen {
    private static final int MARGIN = 20;
    private static final int SIDEBAR_W = 235;
    private static final int GAP = 12;
    private static final int ROW_H = 22;
    private static final ResourceLocation PIN_ICON =
        ResourceLocation.fromNamespaceAndPath("bannerbound", "textures/gui/pin_icon.png");
    private static final ResourceLocation PIN_ICON_SELECTED =
        ResourceLocation.fromNamespaceAndPath("bannerbound", "textures/gui/pin_icon_selected.png");
    private static final int HEADER_PIN_SIZE = 22;
    private static final int SIDEBAR_PIN_SIZE = 10;
    private static final Map<ResourceLocation, ImageInfo> IMAGE_INFO = new HashMap<>();

    private final List<EntryRow> entryRows = new ArrayList<>();
    private final List<LinkRect> linkRects = new ArrayList<>();
    private final List<String> backStack = new ArrayList<>();
    private final List<String> forwardStack = new ArrayList<>();

    private EditBox searchBox;
    private String selectedId;
    private boolean announcedOpen;
    private PinRect pinRect;
    private int sidebarScroll;
    private int articleScroll;
    private int maxSidebarScroll;
    private int maxArticleScroll;

    public ChronicleScreen() {
        this("");
    }

    public ChronicleScreen(String focusEntry) {
        super(Component.translatable("bannerbound.chronicle.title"));
        this.selectedId = focusEntry == null ? "" : focusEntry;
    }

    @Override
    protected void init() {
        if ((selectedId == null || selectedId.isBlank() || !ClientChronicleState.isUnlocked(selectedId))
                && firstUnlockedEntry() != null) {
            selectedId = firstUnlockedEntry().id();
        }
        rebuildChronicleWidgets();
        if (selectedId != null && ClientChronicleState.isUnlocked(selectedId)) {
            ClientChronicleState.markSeen(selectedId);
        }
        if (!announcedOpen) {
            announcedOpen = true;
            PacketDistributor.sendToServer(new MenuOpenedPayload("chronicle"));
        }
    }

    private void rebuildChronicleWidgets() {
        String priorSearch = searchBox == null ? "" : searchBox.getValue();
        clearWidgets();
        int left = MARGIN;
        int top = MARGIN;
        searchBox = new EditBox(this.font, left, top + 30, SIDEBAR_W, 20,
            Component.translatable("bannerbound.chronicle.search"));
        searchBox.setMaxLength(64);
        searchBox.setValue(priorSearch);
        searchBox.setHint(Component.translatable("bannerbound.chronicle.search"));
        searchBox.setResponder(s -> {
            sidebarScroll = 0;
            articleScroll = 0;
        });
        addRenderableWidget(searchBox);

        boolean autoPin = ClientChronicleState.autoPinTutorial();
        int autoPinW = Math.min(150, this.width - 2 * MARGIN);
        addRenderableWidget(PolishButton.polished(
                Component.translatable(autoPin
                    ? "bannerbound.chronicle.autopin.on"
                    : "bannerbound.chronicle.autopin.off"),
                b -> {
                    ClientChronicleState.toggleAutoPinTutorial();
                    rebuildChronicleWidgets();
                })
            .bounds(this.width - MARGIN - autoPinW, top - 2, autoPinW, 18)
            .build());
        boolean popups = ClientChronicleState.tutorialPopupsEnabled();
        int popupsW = Math.min(130, this.width - 2 * MARGIN);
        addRenderableWidget(PolishButton.polished(
                Component.translatable(popups
                    ? "bannerbound.chronicle.popups.on"
                    : "bannerbound.chronicle.popups.off"),
                b -> {
                    ClientChronicleState.toggleTutorialPopups();
                    rebuildChronicleWidgets();
                })
            .bounds(this.width - MARGIN - autoPinW - 4 - popupsW, top - 2, popupsW, 18)
            .build());

        int mainX = left + SIDEBAR_W + GAP;
        addRenderableWidget(PolishButton.polished(Component.literal("<"), b -> navigateToAdjacent(-1))
            .bounds(mainX, top + 30, 24, 20)
            .build());
        addRenderableWidget(PolishButton.polished(Component.literal(">"), b -> navigateToAdjacent(1))
            .bounds(mainX + 28, top + 30, 24, 20)
            .build());

        CodexSyncPayload.Entry selected = selected();
        int rightBtnX = this.width - MARGIN - 92;
        if (selected != null && !selected.ponder().isBlank() && ResearchPonderBridge.isAvailable()) {
            addRenderableWidget(PolishButton.polished(Component.translatable("bannerbound.chronicle.ponder"),
                    b -> ResearchPonderBridge.open(selected.ponder()))
                .bounds(rightBtnX, top + 30, 92, 20)
                .build());
            rightBtnX -= 96;
        }
        if (selected != null && !selected.tutorial().isBlank()
                && ClientChronicleState.isUnlocked(selected.id())) {
            String popupId = selected.tutorial();
            addRenderableWidget(PolishButton.polished(
                    Component.translatable("bannerbound.chronicle.view_tutorial"),
                    b -> PacketDistributor.sendToServer(
                        new com.bannerbound.core.network.RequestTutorialPopupPayload(popupId)))
                .bounds(rightBtnX, top + 30, 92, 20)
                .build());
        }
    }

    @Override
    protected void renderPolishedBackdrop(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        int left = MARGIN;
        int top = MARGIN;
        int bottom = this.height - MARGIN;
        int mainX = left + SIDEBAR_W + GAP;
        int mainW = this.width - mainX - MARGIN;

        graphics.fill(left - 8, top - 8, this.width - MARGIN + 8, bottom + 8, 0xD80E1117);
        graphics.renderOutline(left - 8, top - 8, this.width - (left - 8) - MARGIN + 8, bottom - top + 16, 0x996F6A55);
        graphics.drawString(this.font, Component.translatable("bannerbound.chronicle.title")
            .withStyle(ChatFormatting.GOLD), left, top, 0xFFFFD36A, false);
        graphics.drawString(this.font, Component.translatable("bannerbound.chronicle.key_hint")
            .withStyle(ChatFormatting.DARK_GRAY), left + 102, top + 2, 0xFF888888, false);

        graphics.fill(left, top + 58, left + SIDEBAR_W, bottom, 0xBB141922);
        graphics.renderOutline(left, top + 58, SIDEBAR_W, bottom - (top + 58), 0x665F6B72);
        graphics.fill(mainX, top + 58, mainX + mainW, bottom, 0xBB10151D);
        graphics.renderOutline(mainX, top + 58, mainW, bottom - (top + 58), 0x665F6B72);

        renderSidebar(graphics, mouseX, mouseY, left, top + 64, SIDEBAR_W, bottom - top - 70);
        renderArticle(graphics, mouseX, mouseY, mainX + 14, top + 66, mainW - 28, bottom - top - 76);
    }

    private void renderSidebar(GuiGraphics graphics, int mouseX, int mouseY, int x, int y, int w, int h) {
        entryRows.clear();
        List<CodexSyncPayload.Entry> visible = ClientChronicleState.visibleEntries(search());
        Set<String> usedCategories = new HashSet<>();
        for (CodexSyncPayload.Entry entry : visible) usedCategories.add(entry.category());

        graphics.enableScissor(x + 1, y, x + w - 1, y + h);
        int rowY = y - sidebarScroll;
        for (CodexSyncPayload.Category category : ClientChronicleState.categories()) {
            if (!usedCategories.contains(category.id())) continue;
            rowY = drawCategory(graphics, category, x + 8, rowY, w - 16);
            for (CodexSyncPayload.Entry entry : visible) {
                if (!entry.category().equals(category.id())) continue;
                rowY = drawEntryRow(graphics, entry, x + 6, rowY, w - 12, mouseX, mouseY);
            }
            rowY += 6;
        }
        for (CodexSyncPayload.Entry entry : visible) {
            if (categoryExists(entry.category())) continue;
            if (!usedCategories.contains(entry.category())) continue;
            rowY = drawEntryRow(graphics, entry, x + 6, rowY, w - 12, mouseX, mouseY);
        }
        graphics.disableScissor();
        maxSidebarScroll = Math.max(0, rowY - (y - sidebarScroll) - h + 12);
        sidebarScroll = Math.min(sidebarScroll, maxSidebarScroll);
    }

    private int drawCategory(GuiGraphics graphics, CodexSyncPayload.Category category, int x, int y, int w) {
        if (y > 54 && y < this.height - MARGIN) {
            graphics.drawString(this.font, category.title(), x, y, 0xFFFFD36A, false);
            if (ClientChronicleState.categoryHasUnread(category.id())) {
                graphics.drawString(this.font, "+", x + w - 8, y, 0xFF7EE0FF, false);
            }
        }
        return y + 14;
    }

    private int drawEntryRow(GuiGraphics graphics, CodexSyncPayload.Entry entry, int x, int y, int w,
                             int mouseX, int mouseY) {
        boolean unlocked = ClientChronicleState.isUnlocked(entry.id());
        boolean selected = entry.id().equals(selectedId);
        boolean hovered = mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + ROW_H;
        int fill = selected ? 0x88435A68 : hovered ? 0x5530404A : 0x00000000;
        if (fill != 0) graphics.fill(x, y, x + w, y + ROW_H, fill);
        graphics.renderOutline(x, y, w, ROW_H, selected ? 0xAA9FC8D6 : 0x33374249);

        renderItemUnmasked(graphics, iconStack(entry.icon()), x + 4, y + 3);
        boolean pinned = ClientJournalState.isChroniclePinned(entry.id());
        String label = entry.title();
        int color = 0xFFE6E6E6;
        int rightReserved = (pinned ? SIDEBAR_PIN_SIZE + 4 : 0)
            + (ClientChronicleState.isUnread(entry.id()) ? 10 : 0);
        graphics.drawString(this.font, trim(this.font, label, w - 34 - rightReserved),
            x + 25, y + 7, color, false);
        if (pinned) {
            graphics.blit(PIN_ICON_SELECTED, x + w - 13 - (ClientChronicleState.isUnread(entry.id()) ? 10 : 0),
                y + 6, SIDEBAR_PIN_SIZE, SIDEBAR_PIN_SIZE, 0f, 0f, 16, 16, 16, 16);
        }
        if (ClientChronicleState.isUnread(entry.id())) {
            graphics.drawString(this.font, "+", x + w - 10, y + 7, 0xFF7EE0FF, false);
        }
        entryRows.add(new EntryRow(entry.id(), x, y, w, ROW_H, unlocked));
        return y + ROW_H + 3;
    }

    private void renderArticle(GuiGraphics graphics, int mouseX, int mouseY, int x, int y, int w, int h) {
        linkRects.clear();
        pinRect = null;
        CodexSyncPayload.Entry entry = selected();
        if (entry == null || !ClientChronicleState.isUnlocked(entry.id())) {
            graphics.drawString(this.font, Component.translatable("bannerbound.chronicle.empty"),
                x, y + 12, 0xFFAAAAAA, false);
            return;
        }

        graphics.enableScissor(x, y, x + w, y + h);
        int drawY = y - articleScroll;
        graphics.drawString(this.font, trim(this.font, entry.title(), w), x, drawY, 0xFFFFF2C8, false);
        // Synthesized tutorial-archive entries (id == tutorial) have no server-side entry to pin.
        if (!entry.tutorial().equals(entry.id())) {
            drawPin(graphics, entry, x + w - HEADER_PIN_SIZE - 2, drawY - 5, mouseX, mouseY);
        }
        drawY += 15;
        if (!entry.subtitle().isBlank()) {
            graphics.drawString(this.font, trim(this.font, entry.subtitle(), w), x, drawY, 0xFF9EAAB0, false);
            drawY += 16;
        }
        drawY += 4;
        for (CodexSyncPayload.PageElement page : entry.pages()) {
            drawY = renderPageElement(graphics, page, x, drawY, w, mouseX, mouseY) + 8;
        }
        graphics.disableScissor();
        maxArticleScroll = Math.max(0, drawY - (y - articleScroll) - h + 16);
        articleScroll = Math.min(articleScroll, maxArticleScroll);
    }

    private int renderPageElement(GuiGraphics graphics, CodexSyncPayload.PageElement page,
                                  int x, int y, int w, int mouseX, int mouseY) {
        return switch (page.type().toLowerCase(Locale.ROOT)) {
            case "heading" -> {
                graphics.drawString(this.font, applyAmp(page.text()), x, y, 0xFFFFD36A, false);
                yield y + 14;
            }
            case "items" -> renderItems(graphics, page, x, y, w);
            case "link" -> renderBlockLink(graphics, page, x, y, w, mouseX, mouseY);
            case "clip" -> renderClip(graphics, page, x, y, w, mouseX, mouseY);
            case "image" -> renderImage(graphics, page.image(), page.caption(), x, y, w);
            case "recipe" -> renderRecipe(graphics, page, x, y, w);
            default -> renderRichText(graphics, page.text(), x, y, w, mouseX, mouseY);
        };
    }

    private int renderRecipe(GuiGraphics graphics, CodexSyncPayload.PageElement page, int x, int y, int w) {
        String title = page.caption().isBlank() ? "Recipe" : applyAmp(page.caption());
        int boxH = page.items().isEmpty() ? 42 : 56;
        graphics.fill(x, y, x + w, y + boxH, 0x33141A20);
        graphics.renderOutline(x, y, w, boxH, 0x665F6B72);
        graphics.drawString(this.font, title, x + 8, y + 7, 0xFFFFD36A, false);
        if (page.items().isEmpty()) {
            graphics.drawString(this.font, trim(this.font, page.recipe(), w - 16), x + 8, y + 24,
                0xFFB8C0C6, false);
            return y + boxH;
        }

        int iconY = y + 27;
        int iconX = x + 8;
        int maxInputW = Math.max(30, w - 72);
        int used = 0;
        for (String itemId : page.items()) {
            if (used + 18 > maxInputW) break;
            renderItemUnmasked(graphics, iconStack(itemId), iconX + used, iconY);
            used += 20;
        }
        int arrowX = x + Math.min(maxInputW + 8, Math.max(42, used + 16));
        graphics.drawString(this.font, ">", arrowX, iconY + 4, 0xFF7EE0FF, false);
        renderItemUnmasked(graphics, iconStack(page.recipe()), Math.min(x + w - 24, arrowX + 18), iconY);
        return y + boxH;
    }

    private void drawPin(GuiGraphics graphics, CodexSyncPayload.Entry entry, int x, int y, int mouseX, int mouseY) {
        boolean pinned = ClientJournalState.isChroniclePinned(entry.id());
        boolean hot = mouseX >= x - 2 && mouseX < x + HEADER_PIN_SIZE + 2
            && mouseY >= y - 2 && mouseY < y + HEADER_PIN_SIZE + 2;
        ResourceLocation icon = pinned || hot ? PIN_ICON_SELECTED : PIN_ICON;
        graphics.blit(icon, x, y, HEADER_PIN_SIZE, HEADER_PIN_SIZE, 0f, 0f, 16, 16, 16, 16);
        pinRect = new PinRect(entry.id(), x - 2, y - 2, HEADER_PIN_SIZE + 4, HEADER_PIN_SIZE + 4);
    }

    private int renderItems(GuiGraphics graphics, CodexSyncPayload.PageElement page, int x, int y, int w) {
        if (!page.caption().isBlank()) {
            graphics.drawString(this.font, applyAmp(page.caption()), x, y, 0xFFB8C0C6, false);
            y += 12;
        }
        int col = 0;
        int perRow = Math.max(1, w / 22);
        for (String id : page.items()) {
            renderItemUnmasked(graphics, iconStack(id), x + col * 22, y);
            col++;
            if (col >= perRow) {
                col = 0;
                y += 22;
            }
        }
        return y + (col == 0 ? 0 : 22);
    }

    private int renderBlockLink(GuiGraphics graphics, CodexSyncPayload.PageElement page,
                                int x, int y, int w, int mouseX, int mouseY) {
        CodexSyncPayload.Entry target = ClientChronicleState.get(page.entry());
        // Locked targets vanish entirely - undiscovered entries must leave no trace.
        if (target == null || !ClientChronicleState.isUnlocked(target.id())) return y;
        String label = !page.text().isBlank() ? page.text() : target.title();
        boolean hot = mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + 18;
        graphics.fill(x, y, x + w, y + 18, hot ? 0x33407078 : 0x22182024);
        graphics.drawString(this.font, "> " + trim(this.font, label, w - 12), x + 6, y + 5, 0xFF7EE0FF, false);
        linkRects.add(new LinkRect(target.id(), x, y, w, 18));
        return y + 20;
    }

    private int renderClip(GuiGraphics graphics, CodexSyncPayload.PageElement page,
                           int x, int y, int w, int mouseX, int mouseY) {
        // Clips autoplay looping with no chrome, sized to a fixed 16:9 inset (never the full
        // article width). Live video when the decoder has frames, the poster or a plain dark box
        // while it warms up; a clip whose assets are absent renders nothing - pure text article.
        ClientCodexClips.Clip clip = ClientCodexClips.get(page.clip());
        ResourceLocation video = clip.present() ? ResourceLocation.tryParse(clip.video()) : null;
        boolean hasVideo = video != null && resourceExists(video);
        ResourceLocation poster = clip.posterLocation();
        boolean hasPoster = poster != null && resourceExists(poster);
        if (!hasVideo && !hasPoster) return y;

        int videoW = Math.min(w, 340);
        int videoH = Math.round(videoW * 9 / 16f);
        int drawX = x + (w - videoW) / 2;
        graphics.fill(drawX, y, drawX + videoW, y + videoH, 0xFF0C1114);
        ResourceLocation live = hasVideo ? ClipPlaybackManager.videoTexture(clip) : null;
        if (live != null) {
            graphics.blit(live, drawX, y, videoW, videoH, 0f, 0f, 16, 16, 16, 16);
        } else if (hasPoster) {
            graphics.blit(poster, drawX, y, videoW, videoH, 0f, 0f, 16, 16, 16, 16);
        }
        graphics.renderOutline(drawX, y, videoW, videoH, 0x665F6B72);
        if (page.caption().isBlank()) return y + videoH;
        graphics.drawString(this.font, applyAmp(page.caption()), drawX + 4, y + videoH + 4,
            0xFFB8C0C6, false);
        return y + videoH + 16;
    }

    private int renderImage(GuiGraphics graphics, String image, String caption, int x, int y, int w) {
        ResourceLocation texture = ResourceLocation.tryParse(image == null ? "" : image);
        if (texture == null) {
            return renderPlaceholder(graphics, caption.isBlank() ? "Image: " + image : caption, x, y, w, 54);
        }
        ImageInfo info = imageInfo(texture);
        if (!info.exists()) {
            return renderPlaceholder(graphics, caption.isBlank() ? "Image: " + image : caption, x, y, w, 54);
        }
        int imageH = Math.max(48, Math.min(190, Math.round(w * (info.height() / (float) info.width()))));
        graphics.fill(x, y, x + w, y + imageH, 0x33141A20);
        graphics.blit(texture, x, y, w, imageH, 0f, 0f, info.width(), info.height(), info.width(), info.height());
        graphics.renderOutline(x, y, w, imageH, 0x665F6B72);
        if (caption == null || caption.isBlank()) return y + imageH;
        graphics.drawString(this.font, applyAmp(caption), x + 4, y + imageH + 4, 0xFFB8C0C6, false);
        return y + imageH + 16;
    }

    private int renderPlaceholder(GuiGraphics graphics, String label, int x, int y, int w, int h) {
        graphics.fill(x, y, x + w, y + h, 0x33141A20);
        graphics.renderOutline(x, y, w, h, 0x555F6B72);
        graphics.drawString(this.font, trim(this.font, label, w - 16), x + 8, y + h / 2 - 4,
            0xFF9EAAB0, false);
        return y + h;
    }

    private int renderRichText(GuiGraphics graphics, String text, int x, int y, int w, int mouseX, int mouseY) {
        List<Token> tokens = tokenize(TutorialPopupScreen.substitute(text));
        int cursorX = x;
        int cursorY = y;
        int lineH = this.font.lineHeight + 2;
        // Tokens draw one drawString each, so active '&' codes must be re-applied as a carried
        // prefix or a colored span would drop its color at the first space / line wrap.
        String carry = "";
        for (Token token : tokens) {
            if (token.text().equals("\n")) {
                cursorX = x;
                cursorY += lineH;
                continue;
            }
            String draw = applyAmp(carry + token.text());
            int tw = this.font.width(draw);
            if (cursorX > x && cursorX + tw > x + w) {
                cursorX = x;
                cursorY += lineH;
            }
            if (token.entryId().isBlank()) {
                graphics.drawString(this.font, draw, cursorX, cursorY, 0xFFD8D8D8, false);
                carry = advanceCarry(carry, token.text());
            } else {
                CodexSyncPayload.Entry target = ClientChronicleState.get(token.entryId());
                boolean clickable = target != null && ClientChronicleState.isUnlocked(target.id());
                int color = clickable ? 0xFF7EE0FF : 0xFFD8D8D8;
                graphics.drawString(this.font, applyAmp(token.text()), cursorX, cursorY, color, false);
                if (clickable) {
                    graphics.fill(cursorX, cursorY + this.font.lineHeight, cursorX + tw,
                        cursorY + this.font.lineHeight + 1, color);
                    linkRects.add(new LinkRect(target.id(), cursorX, cursorY, tw, lineH));
                }
            }
            cursorX += tw;
        }
        return cursorY + lineH;
    }

    /** Rolls the active legacy-code prefix forward past one token: a color code restarts the
     *  prefix, format codes stack onto it, and a reset clears it. */
    private static String advanceCarry(String carry, String rawToken) {
        String result = carry;
        for (int i = 0; i + 1 < rawToken.length(); i++) {
            if (rawToken.charAt(i) != '&') continue;
            char code = rawToken.charAt(i + 1);
            ChatFormatting format = ChatFormatting.getByCode(code);
            if (format == null) continue;
            if (format == ChatFormatting.RESET) result = "";
            else if (format.isColor()) result = "&" + code;
            else result = result + "&" + code;
            i++;
        }
        return result;
    }

    private List<Token> tokenize(String text) {
        List<Token> tokens = new ArrayList<>();
        String s = text == null ? "" : text;
        int i = 0;
        while (i < s.length()) {
            int start = s.indexOf("{entry:", i);
            if (start < 0) {
                addWords(tokens, s.substring(i), "");
                break;
            }
            addWords(tokens, s.substring(i, start), "");
            int end = s.indexOf('}', start);
            if (end < 0) {
                addWords(tokens, s.substring(start), "");
                break;
            }
            String body = s.substring(start + 7, end);
            String[] parts = body.split("\\|", 2);
            String id = parts[0].trim();
            String label = parts.length > 1 ? parts[1].trim() : linkTitle(id);
            addWords(tokens, label, id);
            i = end + 1;
        }
        return tokens;
    }

    private void addWords(List<Token> tokens, String text, String entryId) {
        if (text == null || text.isEmpty()) return;
        String[] pieces = text.replace("\r", "").split("(?<=\\s)|(?=\\s)");
        for (String piece : pieces) {
            if (piece.isEmpty()) continue;
            if (piece.equals("\n")) tokens.add(new Token("\n", ""));
            else tokens.add(new Token(piece, entryId));
        }
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (pinRect != null && pinRect.contains(mouseX, mouseY)) {
            PacketDistributor.sendToServer(new ToggleCodexPinPayload(pinRect.entryId()));
            Minecraft mc = Minecraft.getInstance();
            if (mc.player != null) mc.player.playSound(SoundEvents.BOOK_PAGE_TURN, 0.35f, 1.25f);
            return true;
        }
        for (LinkRect link : linkRects) {
            if (link.contains(mouseX, mouseY)) {
                select(link.entryId(), true);
                return true;
            }
        }
        for (EntryRow row : entryRows) {
            if (row.unlocked() && row.contains(mouseX, mouseY)) {
                select(row.entryId(), true);
                return true;
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        int left = MARGIN;
        int top = MARGIN + 58;
        int bottom = this.height - MARGIN;
        int mainX = left + SIDEBAR_W + GAP;
        if (mouseX >= left && mouseX <= left + SIDEBAR_W && mouseY >= top && mouseY <= bottom) {
            sidebarScroll = clamp(sidebarScroll - (int) (scrollY * 18), 0, maxSidebarScroll);
            return true;
        }
        if (mouseX >= mainX && mouseX <= this.width - MARGIN && mouseY >= top && mouseY <= bottom) {
            articleScroll = clamp(articleScroll - (int) (scrollY * 24), 0, maxArticleScroll);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    private void select(String id, boolean pushHistory) {
        if (id == null || id.isBlank() || id.equals(selectedId) || !ClientChronicleState.isUnlocked(id)) return;
        if (pushHistory && selectedId != null && !selectedId.isBlank()) {
            backStack.add(selectedId);
            forwardStack.clear();
        }
        selectedId = id;
        articleScroll = 0;
        ClientChronicleState.markSeen(id);
        rebuildChronicleWidgets();
    }

    private void navigateToAdjacent(int dir) {
        java.util.List<CodexSyncPayload.Entry> list = new java.util.ArrayList<>();
        for (CodexSyncPayload.Entry e
                : ClientChronicleState.visibleEntries(searchBox == null ? "" : searchBox.getValue())) {
            if (ClientChronicleState.isUnlocked(e.id())) list.add(e);
        }
        if (list.isEmpty()) return;
        int idx = -1;
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).id().equals(selectedId)) { idx = i; break; }
        }
        int next = idx < 0 ? (dir > 0 ? 0 : list.size() - 1) : idx + dir;
        if (next < 0 || next >= list.size()) return;
        select(list.get(next).id(), false);
    }

    private void navigateBack() {
        if (backStack.isEmpty()) return;
        if (selectedId != null && !selectedId.isBlank()) forwardStack.add(selectedId);
        selectedId = backStack.remove(backStack.size() - 1);
        articleScroll = 0;
        ClientChronicleState.markSeen(selectedId);
        rebuildChronicleWidgets();
    }

    private void navigateForward() {
        if (forwardStack.isEmpty()) return;
        if (selectedId != null && !selectedId.isBlank()) backStack.add(selectedId);
        selectedId = forwardStack.remove(forwardStack.size() - 1);
        articleScroll = 0;
        ClientChronicleState.markSeen(selectedId);
        rebuildChronicleWidgets();
    }

    private CodexSyncPayload.Entry selected() {
        return selectedId == null ? null : ClientChronicleState.get(selectedId);
    }

    private CodexSyncPayload.Entry firstUnlockedEntry() {
        for (CodexSyncPayload.Entry entry : ClientChronicleState.entries()) {
            if (ClientChronicleState.isUnlocked(entry.id())) return entry;
        }
        return null;
    }

    private boolean categoryExists(String id) {
        for (CodexSyncPayload.Category category : ClientChronicleState.categories()) {
            if (category.id().equals(id)) return true;
        }
        return false;
    }

    private String linkTitle(String id) {
        CodexSyncPayload.Entry target = ClientChronicleState.get(id);
        return target == null || target.secret() ? id : target.title();
    }

    private String search() {
        return searchBox == null ? "" : searchBox.getValue();
    }

    private static ItemStack iconStack(String id) {
        ResourceLocation rl = ResourceLocation.tryParse(id == null ? "" : id);
        Item item = rl == null ? Items.PAPER : BuiltInRegistries.ITEM.get(rl);
        if (item == Items.AIR) item = Items.PAPER;
        return new ItemStack(item);
    }

    /** Chronicle icons always show the real item: an unlocked entry naming a not-yet-obtained
     *  item must not render as the question-mark disguise. */
    private static void renderItemUnmasked(GuiGraphics graphics, ItemStack stack, int x, int y) {
        UnknownItemHelper.setBypassUnknownSwap(true);
        try {
            graphics.renderItem(stack, x, y);
        } finally {
            UnknownItemHelper.setBypassUnknownSwap(false);
        }
    }

    private static String applyAmp(String text) {
        return text == null ? "" : text.replace('&', '\u00a7');
    }

    private static String trim(Font font, String text, int width) {
        if (font.width(text) <= width) return text;
        String ellipsis = "...";
        return font.plainSubstrByWidth(text, Math.max(0, width - font.width(ellipsis))) + ellipsis;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static ImageInfo imageInfo(ResourceLocation texture) {
        return IMAGE_INFO.computeIfAbsent(texture, ChronicleScreen::readImageInfo);
    }

    private static boolean resourceExists(ResourceLocation texture) {
        if (texture == null) return false;
        return Minecraft.getInstance().getResourceManager().getResource(texture).isPresent();
    }

    private static ImageInfo readImageInfo(ResourceLocation texture) {
        try {
            Resource resource = Minecraft.getInstance().getResourceManager().getResource(texture).orElse(null);
            if (resource == null) return ImageInfo.missing();
            try (NativeImage image = NativeImage.read(resource.open())) {
                return new ImageInfo(true, Math.max(1, image.getWidth()), Math.max(1, image.getHeight()));
            }
        } catch (Exception ex) {
            return ImageInfo.missing();
        }
    }

    private record EntryRow(String entryId, int x, int y, int w, int h, boolean unlocked) {
        boolean contains(double px, double py) {
            return px >= x && px < x + w && py >= y && py < y + h;
        }
    }

    private record LinkRect(String entryId, int x, int y, int w, int h) {
        boolean contains(double px, double py) {
            return px >= x && px < x + w && py >= y && py < y + h;
        }
    }

    private record PinRect(String entryId, int x, int y, int w, int h) {
        boolean contains(double px, double py) {
            return px >= x && px < x + w && py >= y && py < y + h;
        }
    }

    private record ImageInfo(boolean exists, int width, int height) {
        static ImageInfo missing() {
            return new ImageInfo(false, 16, 9);
        }
    }

    private record Token(String text, String entryId) {
        Token {
            text = text == null ? "" : text;
            entryId = entryId == null ? "" : entryId;
        }
    }
}
