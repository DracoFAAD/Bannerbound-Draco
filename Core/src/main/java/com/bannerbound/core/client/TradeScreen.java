package com.bannerbound.core.client;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.network.BarterEntry;
import com.bannerbound.core.network.OpenTradeScreenPayload;
import com.bannerbound.core.network.RequestTradeStoragePayload;
import com.bannerbound.core.network.TradeActionPayload;
import com.bannerbound.core.network.TradeStoragePayload;
import com.bannerbound.core.trade.TradeDeal;
import com.bannerbound.core.trade.TradeManager;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Settlement-to-settlement trade — two nations at one table. The {@code BarbarianBarterScreen}
 * four-quadrant layout, but each column wears its settlement's banner identity: your cards carry
 * your banner accents (left), theirs carry theirs (right), with both names + pennants facing each
 * other across the header. NO item valuation anywhere — worth is whatever two players agree to.
 *
 * <p>Negotiation states: fresh screen = compose + Propose. Their terms awaiting you = the offer is
 * shown READ-ONLY with Accept / Counter / Reject; pressing Counter unlocks editing (Accept greys
 * out, Counter becomes "Send counter", and "Nevermind" restores their original terms). Your offer
 * pending = Withdraw. Agreed/in-transit = read-only status. The server re-validates everything.
 */
@OnlyIn(Dist.CLIENT)
@ApiStatus.Internal
public final class TradeScreen extends PolishedScreen {
    private static final int PANEL_W = 440;
    private static final int PANEL_H = 256;
    private static final int HEADER_H = 50;
    private static final int QUAD_W = 150;
    private static final int QUAD_H = 92;
    private static final int SLOT = 18;
    private static final int GRID_PAD = 5;
    private static final int ROWS_TOP = 17;
    private static final int GAP = 12;
    private static final int POLL_INTERVAL = 10;

    // Base palette — matches BarbarianBarterScreen; the per-side identity accents sit on top.
    private static final int BODY_TOP = 0xF016202C;
    private static final int BODY_BOT = 0xF00B1018;
    private static final int EDGE = 0xFF3A4A5E;
    private static final int EDGE_DARK = 0xFF070B11;
    private static final int CARD_TOP = 0xFF131C28;
    private static final int CARD_BOT = 0xFF0C131C;
    private static final int TXT_DIM = 0xFF93A0B0;
    private static final int TXT_OFF = 0xFF59636F;
    private static final int GOLD = 0xFFE0B85A;

    private final String targetId;
    private final String targetName;
    private final String myName;
    private final List<Integer> myAccents;
    private final List<Integer> theirAccents;
    private final String dealId;
    private final int dealState;    // TradeDeal.State ordinal, or -1 for none
    private final boolean awaitingUs;
    private final boolean canAct;

    private final LinkedHashMap<String, Integer> give = new LinkedHashMap<>();
    private final LinkedHashMap<String, Integer> get = new LinkedHashMap<>();
    /** Their terms as proposed — restored by "Nevermind" when a counter is abandoned. */
    private final LinkedHashMap<String, Integer> originalGive = new LinkedHashMap<>();
    private final LinkedHashMap<String, Integer> originalGet = new LinkedHashMap<>();
    private final LinkedHashMap<String, Integer> storage = new LinkedHashMap<>();
    private final LinkedHashMap<String, Integer> goods = new LinkedHashMap<>();

    private int panelX, panelY;
    private int storageScroll, goodsScroll;
    private int pollTimer;
    /** True while the player is composing a counter to the terms on the table. */
    private boolean counterMode;

    private net.minecraft.client.gui.components.Button primaryBtn;

    public TradeScreen(OpenTradeScreenPayload p) {
        super(Component.literal(p.targetName()));
        this.targetId = p.targetId();
        this.targetName = p.targetName();
        this.myName = p.myName();
        this.myAccents = GuiPalette.identityAccents(p.myColorIndex());
        this.theirAccents = GuiPalette.identityAccents(p.targetColorIndex());
        this.dealId = p.dealId();
        this.dealState = p.dealState();
        this.awaitingUs = p.awaitingUs();
        this.canAct = p.canAct();
        for (BarterEntry e : p.myOffer()) give.merge(e.itemId(), e.count(), Integer::sum);
        for (BarterEntry e : p.theirOffer()) get.merge(e.itemId(), e.count(), Integer::sum);
        originalGive.putAll(give);
        originalGet.putAll(get);
        for (BarterEntry e : p.myPool()) storage.put(e.itemId(), e.count());
        for (BarterEntry e : p.theirPool()) goods.put(e.itemId(), e.count());
    }

    // Auto-fit: the fixed panel scales to the window (PolishedScreen opt-in); all mouse events
    // below remap through virtualX/virtualY so hit-tests track the scaled layout.
    @Override
    protected int fitPanelWidth() {
        return PANEL_W;
    }

    @Override
    protected int fitPanelHeight() {
        return PANEL_H;
    }

    private boolean hasDeal() {
        return dealState >= 0 && !dealId.isEmpty();
    }

    private TradeDeal.State state() {
        return hasDeal() ? TradeDeal.State.fromOrdinalOrDefault(dealState) : null;
    }

    private boolean negotiable() {
        TradeDeal.State st = state();
        return st == TradeDeal.State.PROPOSED || st == TradeDeal.State.COUNTERED;
    }

    /** The offer quadrants take clicks only while composing: a fresh proposal, or a counter the
     *  player explicitly opened with the Counter button. */
    private boolean editable() {
        if (!canAct) return false;
        if (!hasDeal()) return true;
        return awaitingUs && negotiable() && counterMode;
    }

    @Override
    protected void init() {
        panelX = (this.width - PANEL_W) / 2;
        panelY = (this.height - PANEL_H) / 2;
        int cx = panelX + PANEL_W / 2;
        int by = bottomY() + 8;
        int bw = 92;

        if (!hasDeal()) {
            primaryBtn = addRenderableWidget(PolishButton.polished(
                Component.translatable("bannerbound.trade.button.propose").withStyle(ChatFormatting.GREEN),
                b -> submit(TradeManager.ACTION_PROPOSE)).bounds(cx - bw / 2, by, bw, 18).build());
        } else if (awaitingUs && negotiable() && !counterMode) {
            primaryBtn = addRenderableWidget(PolishButton.polished(
                Component.translatable("bannerbound.trade.button.accept").withStyle(ChatFormatting.GREEN),
                b -> submit(TradeManager.ACTION_ACCEPT)).bounds(cx - bw / 2, by, bw, 18).build());
            addRenderableWidget(PolishButton.polished(
                Component.translatable("bannerbound.trade.button.counter").withStyle(ChatFormatting.YELLOW),
                b -> {
                    counterMode = true;
                    rebuildWidgets();
                }).bounds(cx - bw / 2, by + 22, bw, 16).build()).active = canAct;
            addRenderableWidget(PolishButton.polished(
                Component.translatable("bannerbound.trade.button.reject").withStyle(ChatFormatting.RED),
                b -> submit(TradeManager.ACTION_REJECT)).bounds(cx - bw / 2, by + 42, bw, 16).build())
                .active = canAct;
        } else if (awaitingUs && negotiable()) { // counterMode
            primaryBtn = addRenderableWidget(PolishButton.polished(
                Component.translatable("bannerbound.trade.button.send_counter").withStyle(ChatFormatting.GREEN),
                b -> submit(TradeManager.ACTION_COUNTER)).bounds(cx - bw / 2, by, bw, 18).build());
            addRenderableWidget(PolishButton.polished(
                Component.translatable("bannerbound.trade.button.nevermind").withStyle(ChatFormatting.GRAY),
                b -> {
                    counterMode = false;
                    give.clear();
                    give.putAll(originalGive);
                    get.clear();
                    get.putAll(originalGet);
                    rebuildWidgets();
                }).bounds(cx - bw / 2, by + 22, bw, 16).build());
        } else if (negotiable()) { // our terms pending on their side
            primaryBtn = addRenderableWidget(PolishButton.polished(
                Component.translatable("bannerbound.trade.button.withdraw").withStyle(ChatFormatting.RED),
                b -> submit(TradeManager.ACTION_CANCEL)).bounds(cx - bw / 2, by, bw, 18).build());
        }
        addRenderableWidget(PolishButton.polished(
            Component.translatable("bannerbound.trade.button.close").withStyle(ChatFormatting.GRAY),
            b -> this.onClose()).bounds(cx - bw / 2, by + QUAD_H - 24, bw, 16).build());
        refreshButtons();
    }

    private boolean affordable() {
        for (Map.Entry<String, Integer> e : give.entrySet()) {
            if (e.getValue() > storage.getOrDefault(e.getKey(), 0)) return false;
        }
        return true;
    }

    private void refreshButtons() {
        if (primaryBtn == null) return;
        if (!canAct) {
            primaryBtn.active = false;
            return;
        }
        if (!hasDeal() || counterMode) {
            // Propose / Send counter: something on the table, backed by my pool.
            primaryBtn.active = affordable() && !(give.isEmpty() && get.isEmpty());
        }
    }

    @Override
    public void tick() {
        super.tick();
        if (++pollTimer >= POLL_INTERVAL) {
            pollTimer = 0;
            PacketDistributor.sendToServer(new RequestTradeStoragePayload(targetId));
        }
    }

    public void applyStorageUpdate(TradeStoragePayload p) {
        if (!p.targetId().equals(targetId)) return;
        storage.clear();
        for (BarterEntry e : p.myPool()) storage.put(e.itemId(), e.count());
        goods.clear();
        for (BarterEntry e : p.theirPool()) goods.put(e.itemId(), e.count());
        clampScroll();
        refreshButtons();
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        mx = virtualX(mx);
        my = virtualY(my); // map into auto-fit panel space (widgets included via super)
        if (button == 0 && editable()) {
            int step = hasShiftDown() ? 5 : 1;
            String sId = slotAt(storage, storageScroll, leftX(), bottomY(), mx, my);
            if (sId != null) { addTo(give, sId, step, storage.getOrDefault(sId, 0)); return true; }
            String gId = slotAt(goods, goodsScroll, rightX(), bottomY(), mx, my);
            if (gId != null) { addTo(get, gId, step, goods.getOrDefault(gId, 0)); return true; }
            String giveId = slotAt(give, 0, leftX(), topY(), mx, my);
            if (giveId != null) { removeFrom(give, giveId, hasShiftDown() ? 9999 : 1); return true; }
            String getId = slotAt(get, 0, rightX(), topY(), mx, my);
            if (getId != null) { removeFrom(get, getId, hasShiftDown() ? 9999 : 1); return true; }
        }
        return super.mouseClicked(mx, my, button);
    }

    @Override
    public boolean mouseReleased(double mx, double my, int button) {
        return super.mouseReleased(virtualX(mx), virtualY(my), button);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double dx, double dy) {
        mx = virtualX(mx);
        my = virtualY(my); // map into auto-fit panel space
        if (inPanel(leftX(), bottomY(), mx, my)) {
            storageScroll = clampRows(storageScroll - (int) Math.signum(dy), storage.size());
            return true;
        }
        if (inPanel(rightX(), bottomY(), mx, my)) {
            goodsScroll = clampRows(goodsScroll - (int) Math.signum(dy), goods.size());
            return true;
        }
        return super.mouseScrolled(mx, my, dx, dy);
    }

    private void addTo(Map<String, Integer> offer, String id, int step, int poolMax) {
        int cur = offer.getOrDefault(id, 0);
        int room = Math.max(0, poolMax - cur);
        if (room <= 0) return;
        offer.put(id, cur + Math.min(step, room));
        refreshButtons();
    }

    private void removeFrom(Map<String, Integer> offer, String id, int step) {
        int cur = offer.getOrDefault(id, 0);
        int next = cur - step;
        if (next <= 0) offer.remove(id); else offer.put(id, next);
        refreshButtons();
    }

    private void submit(int action) {
        PacketDistributor.sendToServer(new TradeActionPayload(targetId, dealId, action,
            toEntries(give), toEntries(get)));
        this.onClose();
    }

    private List<BarterEntry> toEntries(Map<String, Integer> offer) {
        List<BarterEntry> out = new ArrayList<>();
        for (Map.Entry<String, Integer> e : offer.entrySet()) {
            if (e.getValue() > 0) out.add(new BarterEntry(e.getKey(), e.getValue(), 0));
        }
        return out;
    }

    // ─── Rendering ──────────────────────────────────────────────────────────────────────────────────

    @Override
    protected void renderPolishedBackdrop(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        int myAccent = GuiPalette.primary(myAccents);
        int theirAccent = GuiPalette.primary(theirAccents);

        // Panel: drop shadow, gradient body, bevelled border. The top stripe is split — your
        // banner colour flows in from the left, theirs from the right, meeting in the middle.
        g.fill(panelX + 4, panelY + 5, panelX + PANEL_W + 4, panelY + PANEL_H + 5, 0x80000000);
        g.fillGradient(panelX, panelY, panelX + PANEL_W, panelY + PANEL_H, BODY_TOP, BODY_BOT);
        g.renderOutline(panelX, panelY, PANEL_W, PANEL_H, EDGE_DARK);
        g.renderOutline(panelX + 1, panelY + 1, PANEL_W - 2, PANEL_H - 2, EDGE);
        int half = (PANEL_W - 4) / 2;
        drawHorizontalGradient(g, panelX + 2, panelY + 2, half, 2,
            myAccent, myAccent & 0x30FFFFFF);
        drawHorizontalGradient(g, panelX + 2 + half, panelY + 2, half, 2,
            theirAccent & 0x30FFFFFF, theirAccent);

        // Header: the two nations face each other — pennant + name on each side, ⇄ between.
        int nameY = panelY + 10;
        drawPennant(g, panelX + 14, nameY - 1, myAccents);
        g.drawString(this.font, Component.literal(myName), panelX + 28, nameY, myAccent, true);
        String theirs = targetName;
        int theirW = this.font.width(theirs);
        g.drawString(this.font, Component.literal(theirs),
            panelX + PANEL_W - 28 - theirW, nameY, theirAccent, true);
        drawPennant(g, panelX + PANEL_W - 24, nameY - 1, theirAccents);
        drawCentred(g, "⇄", panelX + PANEL_W / 2, nameY, GOLD);

        g.drawString(this.font, Component.translatable("bannerbound.trade.subtitle")
            .withStyle(ChatFormatting.GRAY), panelX + 14, panelY + 23, TXT_DIM, false);
        g.drawString(this.font, trim(stateLine(), PANEL_W - 24), panelX + 14, panelY + 35,
            0xFFA9B0BA, false);
        // Divider mirrors the top stripe: your colour fading right, theirs fading left.
        int divY = panelY + HEADER_H - 4;
        drawHorizontalGradient(g, panelX + 12, divY, (PANEL_W - 24) / 2, 1,
            myAccent & 0xCCFFFFFF, 0x10FFFFFF);
        drawHorizontalGradient(g, panelX + 12 + (PANEL_W - 24) / 2, divY, (PANEL_W - 24) / 2, 1,
            0x10FFFFFF, theirAccent & 0xCCFFFFFF);

        boolean offersLocked = hasDeal() && !editable();
        card(g, leftX(), topY(), "bannerbound.trade.your_offer", give, 0, false,
            mouseX, mouseY, myAccent, offersLocked);
        card(g, rightX(), topY(), "bannerbound.trade.their_offer", get, 0, false,
            mouseX, mouseY, theirAccent, offersLocked);
        card(g, leftX(), bottomY(), "bannerbound.trade.your_pool", storage, storageScroll, true,
            mouseX, mouseY, myAccent, !editable());
        card(g, rightX(), bottomY(), "bannerbound.trade.their_pool", goods, goodsScroll, true,
            mouseX, mouseY, theirAccent, !editable());

        centreExchange(g);
        buttonBacking(g);
    }

    /** A little swallow-tailed banner in the settlement's accent colours, hung from a pole. */
    private void drawPennant(GuiGraphics g, int x, int y, List<Integer> accents) {
        List<Integer> bands = accents.isEmpty() ? List.of(0xFF888888) : accents;
        int w = 8, h = 8, tail = 3;
        g.fill(x - 2, y - 1, x - 1, y + h + tail + 1, 0xFF6B5B45);         // pole
        for (int i = 0; i < h; i++) {                                       // banded cloth
            int color = bands.get(Math.min(bands.size() - 1, i * bands.size() / h));
            g.fill(x, y + i, x + w, y + i + 1, color);
        }
        int last = bands.get(bands.size() - 1);
        g.fill(x, y + h, x + 3, y + h + tail, last);                        // swallow tails
        g.fill(x + w - 3, y + h, x + w, y + h + tail, last);
        g.renderOutline(x - 1, y - 1, w + 2, h + 2, 0x66000000);
    }

    /** The deal status line under the header. */
    private Component stateLine() {
        if (!hasDeal()) {
            return Component.translatable("bannerbound.trade.state.fresh").withStyle(ChatFormatting.ITALIC);
        }
        if (counterMode) {
            return Component.translatable("bannerbound.trade.state.countering").withStyle(ChatFormatting.ITALIC);
        }
        TradeDeal.State st = state();
        String key = switch (st) {
            case PROPOSED, COUNTERED -> awaitingUs
                ? "bannerbound.trade.state.awaiting_you" : "bannerbound.trade.state.awaiting_them";
            case ACCEPTED -> "bannerbound.trade.state.gathering";
            case IN_TRANSIT -> "bannerbound.trade.state.in_transit";
            default -> "bannerbound.trade.state.done";
        };
        return Component.translatable(key).withStyle(ChatFormatting.ITALIC);
    }

    /** Centre column: a large exchange glyph and plain-language status. No numbers — worth is
     *  whatever the two settlements agree it is. */
    private void centreExchange(GuiGraphics g) {
        int cx = panelX + PANEL_W / 2;
        int y = topY() + QUAD_H / 2 - 16;
        var pose = g.pose();
        pose.pushPose();
        pose.translate(cx - this.font.width("⇄"), y, 0);
        pose.scale(2f, 2f, 1f);
        g.drawString(this.font, "⇄", 0, 0, GOLD, false);
        pose.popPose();

        Component status;
        int statusCol;
        if (!canAct) {
            status = Component.translatable("bannerbound.trade.chief_only");
            statusCol = 0xFFE0C060;
        } else if (editable() && !affordable()) {
            status = Component.translatable("bannerbound.trade.short_goods");
            statusCol = 0xFFE08A8A;
        } else {
            status = Component.translatable("bannerbound.trade.judged_by_you");
            statusCol = TXT_OFF;
        }
        // The centre column is narrow (~116px) — wrap and centre each line so status prose can
        // never overflow into the offer cards (the house drawWrapped is left-aligned).
        int centreLeft = leftX() + QUAD_W + 8;
        int centreWidth = rightX() - 8 - centreLeft;
        int ty = y + 24;
        for (net.minecraft.util.FormattedCharSequence line : this.font.split(status, centreWidth)) {
            g.drawString(this.font, line, cx - this.font.width(line) / 2, ty, statusCol, false);
            ty += this.font.lineHeight + 1;
        }
    }

    private void buttonBacking(GuiGraphics g) {
        int cx = panelX + PANEL_W / 2;
        int half = 92 / 2 + 5;
        int top = bottomY() + 3;
        int bot = bottomY() + QUAD_H - 3;
        g.fill(cx - half, top, cx + half, bot, 0x33000000);
        g.renderOutline(cx - half, top, half * 2, bot - top, 0x22FFFFFF);
    }

    /** One quadrant card, tinted with its side's banner accent. {@code locked} dims the content
     *  (read-only terms / pools while not composing). */
    private void card(GuiGraphics g, int x, int y, String titleKey, Map<String, Integer> rows,
                      int scroll, boolean pool, int mouseX, int mouseY, int accent, boolean locked) {
        g.fillGradient(x, y, x + QUAD_W, y + QUAD_H, CARD_TOP, CARD_BOT);
        g.renderOutline(x, y, QUAD_W, QUAD_H, blend(accent, 0xFF2B3A4C, 0.55f));
        g.fill(x + 1, y + 1, x + QUAD_W - 1, y + 2, (accent & 0xFFFFFF) | 0x28000000);

        g.drawString(this.font, Component.translatable(titleKey),
            x + 5, y + 4, blend(accent, 0xFFDDDDDD, 0.45f), false);
        drawHorizontalGradient(g, x + 4, y + 14, QUAD_W - 8, 1, accent & 0x66FFFFFF, accent & 0x08FFFFFF);

        // Quiet slot grid (soft cell fills + hairline separators — the bright bevel read harsh),
        // clipped with a pre-mapped scissor so scrolled items can never bleed past the card.
        List<String> ids = new ArrayList<>(rows.keySet());
        int cols = gridCols(), vis = gridRows();
        int gx = x + GRID_PAD, gy = y + ROWS_TOP;
        int gw = cols * SLOT, gh = vis * SLOT;
        g.fill(gx, gy, gx + gw, gy + gh, 0x30000000);
        for (int c = 1; c < cols; c++) g.fill(gx + c * SLOT, gy, gx + c * SLOT + 1, gy + gh, 0x12FFFFFF);
        for (int r = 1; r < vis; r++) g.fill(gx, gy + r * SLOT, gx + gw, gy + r * SLOT + 1, 0x12FFFFFF);
        g.renderOutline(gx, gy, gw, gh, 0x22FFFFFF);
        g.enableScissor(scissorX(gx), scissorY(gy), scissorX(gx + gw), scissorY(gy + gh));
        int start = scroll * cols;
        for (int s = 0; s < cols * vis; s++) {
            int idx = start + s;
            if (idx >= ids.size()) break;
            int sx = gx + (s % cols) * SLOT, sy = gy + (s / cols) * SLOT;
            String id = ids.get(idx);
            int avail = rows.get(id);
            boolean usable = !pool || avail - offerHeld(id) > 0;
            boolean hovered = mouseX >= sx && mouseX < sx + SLOT && mouseY >= sy && mouseY < sy + SLOT;
            if (hovered && !locked) g.fill(sx + 1, sy + 1, sx + SLOT - 1, sy + SLOT - 1, 0x40FFFFFF);
            ItemStack st = new ItemStack(item(id), avail);
            g.renderItem(st, sx + 1, sy + 1);
            g.renderItemDecorations(this.font, st, sx + 1, sy + 1);
            if (!usable) g.fill(sx + 1, sy + 1, sx + SLOT - 1, sy + SLOT - 1, 0x88121820);
        }
        g.disableScissor();
        gridScrollbar(g, x, y, ids.size(), scroll);
        if (ids.isEmpty()) {
            drawCentred(g, Component.translatable("bannerbound.trade.empty").getString(),
                x + QUAD_W / 2, y + QUAD_H / 2 + 4, TXT_OFF);
        }
        if (locked) {
            // Read-only veil over the grid area — terms on the table can't be nudged by accident.
            g.fill(gx, gy, gx + gw, gy + gh, 0x50060A10);
        }
    }

    private int gridCols() { return (QUAD_W - 2 * GRID_PAD) / SLOT; }
    private int gridRows() { return (QUAD_H - ROWS_TOP - 2) / SLOT; }

    private void gridScrollbar(GuiGraphics g, int x, int y, int total, int scroll) {
        int cols = gridCols(), vis = gridRows();
        int totalRows = (total + cols - 1) / cols;
        if (totalRows <= vis) return;
        int trackX = x + QUAD_W - 3, trackTop = y + ROWS_TOP, trackH = vis * SLOT;
        g.fill(trackX, trackTop, trackX + 2, trackTop + trackH, 0x50000000);
        int thumbH = Math.max(10, trackH * vis / totalRows);
        int thumbY = trackTop + (trackH - thumbH) * scroll / Math.max(1, totalRows - vis);
        g.fill(trackX, thumbY, trackX + 2, thumbY + thumbH, 0x99FFFFFF);
    }

    private int offerHeld(String id) {
        return give.getOrDefault(id, 0) + get.getOrDefault(id, 0);
    }

    /** Linear blend of two ARGB colours ({@code t} toward {@code b}). */
    private static int blend(int a, int b, float t) {
        int ar = (a >> 16) & 0xFF, ag = (a >> 8) & 0xFF, ab = a & 0xFF;
        int br = (b >> 16) & 0xFF, bg = (b >> 8) & 0xFF, bb = b & 0xFF;
        int r = (int) (ar + (br - ar) * t);
        int gc = (int) (ag + (bg - ag) * t);
        int bl = (int) (ab + (bb - ab) * t);
        return 0xFF000000 | (r << 16) | (gc << 8) | bl;
    }

    @Override
    protected void renderPolishedExtras(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        String id = hoveredId(mouseX, mouseY);
        if (id != null) {
            g.renderTooltip(this.font, itemName(id), mouseX, mouseY);
        }
    }

    private String hoveredId(double mx, double my) {
        String id = slotAt(storage, storageScroll, leftX(), bottomY(), mx, my);
        if (id == null) id = slotAt(goods, goodsScroll, rightX(), bottomY(), mx, my);
        if (id == null) id = slotAt(give, 0, leftX(), topY(), mx, my);
        if (id == null) id = slotAt(get, 0, rightX(), topY(), mx, my);
        return id;
    }

    // ─── Geometry helpers ────────────────────────────────────────────────────────────────────────────

    private int leftX() { return panelX + 12; }
    private int rightX() { return panelX + PANEL_W - 12 - QUAD_W; }
    private int topY() { return panelY + HEADER_H; }
    private int bottomY() { return panelY + HEADER_H + QUAD_H + GAP; }

    private boolean inPanel(int x, int y, double mx, double my) {
        return mx >= x && mx < x + QUAD_W && my >= y + ROWS_TOP && my < y + QUAD_H;
    }

    private String slotAt(Map<String, Integer> rows, int scroll, int x, int y, double mx, double my) {
        int cols = gridCols(), vis = gridRows();
        int gx = x + GRID_PAD, gy = y + ROWS_TOP;
        if (mx < gx || my < gy) return null;
        int col = (int) ((mx - gx) / SLOT), row = (int) ((my - gy) / SLOT);
        if (col < 0 || col >= cols || row < 0 || row >= vis) return null;
        int idx = (scroll + row) * cols + col;
        List<String> ids = new ArrayList<>(rows.keySet());
        return idx >= 0 && idx < ids.size() ? ids.get(idx) : null;
    }

    private void clampScroll() {
        storageScroll = clampRows(storageScroll, storage.size());
        goodsScroll = clampRows(goodsScroll, goods.size());
    }

    private int clampRows(int scrollRows, int size) {
        int totalRows = (size + gridCols() - 1) / gridCols();
        return Math.max(0, Math.min(scrollRows, Math.max(0, totalRows - gridRows())));
    }

    private void drawCentred(GuiGraphics g, String s, int cx, int y, int color) {
        g.drawString(this.font, s, cx - this.font.width(s) / 2, y, color, false);
    }

    private static Item item(String id) {
        ResourceLocation rl = ResourceLocation.tryParse(id);
        return rl == null ? Items.AIR : BuiltInRegistries.ITEM.get(rl);
    }

    private Component itemName(String id) {
        ItemStack stack = new ItemStack(item(id));
        return UnknownItemHelper.isUnknownForLocalPlayer(stack) ? item(id).getDescription()
            : stack.getHoverName();
    }

    private Component trim(Component c, int maxWidth) {
        return this.font.width(c) <= maxWidth ? c
            : Component.literal(this.font.plainSubstrByWidth(c.getString(), maxWidth - 6) + "…");
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
