package com.bannerbound.core.client;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.barbarian.CampRelationState;
import com.bannerbound.core.network.BarterActionPayload;
import com.bannerbound.core.network.BarterEntry;
import com.bannerbound.core.network.BarterStoragePayload;
import com.bannerbound.core.network.OpenBarterPayload;
import com.bannerbound.core.network.RequestBarterStoragePayload;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * The barbarian barter: a four-quadrant counter-offer screen. Top row = the deal on the table
 * ("Your offer" = what you'd give from the town storage, "Their offer" = what the camp would hand
 * over); bottom row = the two pools you draw from ("Storage" = your pool, "Their storage" = the camp's
 * goods). Left-click a storage row to add a unit to that side's offer (shift = +5); left-click an offer
 * row to take one back (shift = clear). A live verdict + balance bar score the deal against the camp's
 * value bar, and the screen polls live storage so Accept + the add rows grey out the instant the pool
 * can't back the offer. The server re-validates everything on submit.
 */
@OnlyIn(Dist.CLIENT)
@ApiStatus.Internal
public final class BarbarianBarterScreen extends PolishedScreen {
    private static final int PANEL_W = 440;
    private static final int PANEL_H = 256;
    private static final int HEADER_H = 50;
    private static final int QUAD_W = 150;
    private static final int QUAD_H = 92;
    private static final int SLOT = 18;       // inventory-grid cell size
    private static final int GRID_PAD = 5;    // inset of the slot grid from the card edges
    private static final int ROWS_TOP = 17;   // grid starts this far below a card's top (under its header)
    private static final int GAP = 12;        // vertical gap between the offer row and the pool row
    private static final int POLL_INTERVAL = 10;

    // Palette ─ a cohesive dark-slate panel with a warm "worth" accent.
    private static final int BODY_TOP = 0xF016202C;
    private static final int BODY_BOT = 0xF00B1018;
    private static final int EDGE = 0xFF3A4A5E;
    private static final int EDGE_DARK = 0xFF070B11;
    private static final int CARD_TOP = 0xFF131C28;
    private static final int CARD_BOT = 0xFF0C131C;
    private static final int CARD_EDGE = 0xFF2B3A4C;
    private static final int TXT_DIM = 0xFF93A0B0;
    private static final int TXT_OFF = 0xFF59636F;
    private static final int GOLD = 0xFFE0B85A;
    private static final int DIVIDER = 0x28FFFFFF;

    private final int messengerEntityId;
    private final String campName;
    private final int campColor;
    private final String typeName;
    private final String greetingKey;
    private final int relState;
    private final boolean isDemand;
    private final boolean canDefer;
    private final int tributeFloor;
    private final int marginPercent;

    private final LinkedHashMap<String, Integer> give = new LinkedHashMap<>();
    private final LinkedHashMap<String, Integer> get = new LinkedHashMap<>();
    private final LinkedHashMap<String, Integer> storage = new LinkedHashMap<>();
    private final LinkedHashMap<String, Integer> goods = new LinkedHashMap<>();
    private final Map<String, Integer> unitValues = new java.util.HashMap<>();

    private int panelX, panelY;
    private int storageScroll, goodsScroll;
    private int pollTimer;

    private net.minecraft.client.gui.components.Button acceptBtn, counterBtn;

    public BarbarianBarterScreen(OpenBarterPayload p) {
        super(Component.literal(p.campName()));
        this.messengerEntityId = p.messengerEntityId();
        this.campName = p.campName();
        this.campColor = p.campColor();
        this.typeName = p.typeName();
        this.greetingKey = p.greetingKey();
        this.relState = p.relState();
        this.isDemand = p.isDemand();
        this.canDefer = p.canDefer();
        this.tributeFloor = p.tributeFloor();
        this.marginPercent = p.marginPercent();
        for (BarterEntry e : p.youGive()) { give.merge(e.itemId(), e.count(), Integer::sum); cache(e); }
        for (BarterEntry e : p.youGet()) { get.merge(e.itemId(), e.count(), Integer::sum); cache(e); }
        for (BarterEntry e : p.yourStorage()) { storage.put(e.itemId(), e.count()); cache(e); }
        for (BarterEntry e : p.theirGoods()) { goods.put(e.itemId(), e.count()); cache(e); }
    }

    private void cache(BarterEntry e) {
        unitValues.put(e.itemId(), e.unitValue());
    }

    // Auto-fit: the fixed panel scales to the window (PolishedScreen opt-in); the mouse events
    // below remap through virtualX/virtualY so hit-tests track the scaled layout.
    @Override
    protected int fitPanelWidth() {
        return PANEL_W;
    }

    @Override
    protected int fitPanelHeight() {
        return PANEL_H;
    }

    @Override
    protected void init() {
        panelX = (this.width - PANEL_W) / 2;
        panelY = (this.height - PANEL_H) / 2;
        int cx = panelX + PANEL_W / 2;
        int by = bottomY() + 8;                  // button column, centred between the bottom panels
        int bw = 92;
        acceptBtn = addRenderableWidget(PolishButton.polished(
            Component.translatable("bannerbound.barbarian.barter.accept").withStyle(ChatFormatting.GREEN),
            b -> submitPropose()).bounds(cx - bw / 2, by, bw, 18).build());
        counterBtn = addRenderableWidget(PolishButton.polished(
            Component.translatable("bannerbound.barbarian.barter.counter").withStyle(ChatFormatting.YELLOW),
            b -> submitPropose()).bounds(cx - bw / 2, by + 22, bw, 16).build());
        if (isDemand && canDefer) {
            addRenderableWidget(PolishButton.polished(
                Component.translatable("bannerbound.barbarian.barter.defer"),
                b -> submit(BarterActionPayload.DEFER)).bounds(cx - bw / 2, by + 42, bw, 16).build());
        }
        addRenderableWidget(PolishButton.polished(
            Component.translatable("bannerbound.barbarian.barter.decline").withStyle(ChatFormatting.GRAY),
            b -> submit(BarterActionPayload.DECLINE))
            .bounds(cx - bw / 2, by + (isDemand && canDefer ? 62 : 42), bw, 16).build());
        refreshButtons();
    }

    // ─── Valuation ────────────────────────────────────────────────────────────────────────────────

    private int valueOf(Map<String, Integer> offer) {
        int v = 0;
        for (Map.Entry<String, Integer> e : offer.entrySet()) {
            v += unitValues.getOrDefault(e.getKey(), 0) * e.getValue();
        }
        return v;
    }

    private int neededGive() {
        return Math.floorDiv(valueOf(get) * marginPercent, 100) + tributeFloor;
    }

    private boolean affordable() {
        for (Map.Entry<String, Integer> e : give.entrySet()) {
            if (e.getValue() > storage.getOrDefault(e.getKey(), 0)) return false;
        }
        return true;
    }

    private boolean acceptable() {
        if (give.isEmpty() && get.isEmpty()) return false;
        return valueOf(give) >= neededGive();
    }

    private void refreshButtons() {
        boolean afford = affordable();
        boolean ok = acceptable();
        acceptBtn.active = afford && ok;
        counterBtn.active = afford && !ok;
    }

    // ─── Live storage poll + update ─────────────────────────────────────────────────────────────────

    @Override
    public void tick() {
        super.tick();
        if (++pollTimer >= POLL_INTERVAL) {
            pollTimer = 0;
            PacketDistributor.sendToServer(new RequestBarterStoragePayload(messengerEntityId));
        }
    }

    public void applyStorageUpdate(BarterStoragePayload p) {
        storage.clear();
        for (BarterEntry e : p.yourStorage()) { storage.put(e.itemId(), e.count()); cache(e); }
        goods.clear();
        for (BarterEntry e : p.theirGoods()) { goods.put(e.itemId(), e.count()); cache(e); }
        clampScroll();
        refreshButtons();
    }

    // ─── Interaction ────────────────────────────────────────────────────────────────────────────────

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        mx = virtualX(mx);
        my = virtualY(my); // map into auto-fit panel space (widgets included via super)
        if (button == 0) {
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

    private void submitPropose() {
        submit(BarterActionPayload.PROPOSE);
    }

    private void submit(int action) {
        PacketDistributor.sendToServer(new BarterActionPayload(messengerEntityId, action,
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
        int accent = 0xFF000000 | (campColor & 0xFFFFFF);

        // Panel: drop shadow, gradient body, bevelled border, camp-colour accent stripe along the top.
        g.fill(panelX + 4, panelY + 5, panelX + PANEL_W + 4, panelY + PANEL_H + 5, 0x80000000);
        g.fillGradient(panelX, panelY, panelX + PANEL_W, panelY + PANEL_H, BODY_TOP, BODY_BOT);
        g.renderOutline(panelX, panelY, PANEL_W, PANEL_H, EDGE_DARK);
        g.renderOutline(panelX + 1, panelY + 1, PANEL_W - 2, PANEL_H - 2, EDGE);
        drawHorizontalGradient(g, panelX + 2, panelY + 2, PANEL_W - 4, 2,
            accent & 0x88FFFFFF, accent & 0x00FFFFFF);

        // Header: name (camp colour) + type · stance, with an accent divider beneath.
        g.drawString(this.font, Component.literal(campName).withStyle(s -> s.withColor(accent)),
            panelX + 12, panelY + 9, accent, true);
        g.drawString(this.font, Component.literal(typeName).withStyle(ChatFormatting.GRAY)
                .append(Component.literal(" · ").withStyle(ChatFormatting.DARK_GRAY)).append(relationLabel()),
            panelX + 12, panelY + 21, TXT_DIM, false);
        g.drawString(this.font, trim(Component.translatable(greetingKey).withStyle(ChatFormatting.ITALIC),
            PANEL_W - 24), panelX + 12, panelY + 33, 0xFFA9B0BA, false);
        verdictPill(g, panelX + PANEL_W - 12, panelY + 8);
        drawHorizontalGradient(g, panelX + 12, panelY + HEADER_H - 4, PANEL_W - 24, 1,
            accent & 0xCCFFFFFF, accent & 0x10FFFFFF);

        // Four cards — offer cards show their total worth in the header so "worth" is always visible.
        card(g, leftX(), topY(), "bannerbound.barbarian.barter.your_offer", give, 0, false, mouseX, mouseY,
            valueOf(give));
        card(g, rightX(), topY(), "bannerbound.barbarian.barter.their_offer", get, 0, false, mouseX, mouseY,
            valueOf(get));
        card(g, leftX(), bottomY(), "bannerbound.barbarian.barter.storage", storage, storageScroll, true,
            mouseX, mouseY, -1);
        card(g, rightX(), bottomY(), "bannerbound.barbarian.barter.their_storage", goods, goodsScroll, true,
            mouseX, mouseY, -1);

        centreExchange(g);
        buttonBacking(g);
    }

    /** Labelled "deal balance" meter in the gap between the offer cards: how the worth you've put on the
     *  table compares to what the camp will accept, with plain-language guidance on the shortfall. */
    private void centreExchange(GuiGraphics g) {
        int cx = panelX + PANEL_W / 2;
        int gx0 = leftX() + QUAD_W + 8;
        int gx1 = rightX() - 8;
        int gw = gx1 - gx0;
        int y = topY() + QUAD_H / 2 - 18;
        int giveV = valueOf(give), need = neededGive();
        int statusCol = !affordable() ? 0xFFE08A8A : (acceptable() ? 0xFF8FE08F : 0xFFE0C060);

        drawCentred(g, Component.translatable("bannerbound.barbarian.barter.balance").getString(),
            cx, y, TXT_OFF);
        // Worth you've offered vs the worth the camp wants, as a progress fraction.
        drawCentred(g, giveV + " / " + need, cx, y + 11, statusCol);

        int barY = y + 22;
        float frac = need <= 0 ? 1f : Math.min(1f, giveV / (float) need);
        g.fill(gx0, barY, gx1, barY + 5, 0x80000000);
        g.fill(gx0, barY, gx0 + (int) (gw * frac), barY + 5, 0xFF000000 | (statusCol & 0xFFFFFF));
        g.renderOutline(gx0, barY, gw, 5, 0x40FFFFFF);

        Component status = !affordable()
            ? Component.translatable("bannerbound.barbarian.barter.short_goods")
            : acceptable()
                ? Component.translatable("bannerbound.barbarian.barter.fair")
                : Component.translatable("bannerbound.barbarian.barter.need_more", need - giveV);
        drawCentred(g, status.getString(), cx, barY + 9, statusCol);
    }

    /** A faint plate behind the central button column so it reads as one control group. */
    private void buttonBacking(GuiGraphics g) {
        int cx = panelX + PANEL_W / 2;
        int half = 92 / 2 + 5;
        int top = bottomY() + 3;
        int bot = bottomY() + QUAD_H - 3;
        g.fill(cx - half, top, cx + half, bot, 0x33000000);
        g.renderOutline(cx - half, top, half * 2, bot - top, 0x22FFFFFF);
    }

    private void card(GuiGraphics g, int x, int y, String titleKey, Map<String, Integer> rows, int scroll,
                      boolean pool, int mouseX, int mouseY, int worth) {
        g.fillGradient(x, y, x + QUAD_W, y + QUAD_H, CARD_TOP, CARD_BOT);
        g.renderOutline(x, y, QUAD_W, QUAD_H, CARD_EDGE);
        g.fill(x + 1, y + 1, x + QUAD_W - 1, y + 2, 0x12FFFFFF);          // soft top inner highlight

        g.drawString(this.font, Component.translatable(titleKey).withStyle(ChatFormatting.GRAY),
            x + 5, y + 4, TXT_DIM, false);
        if (worth >= 0) {                                                 // offer cards label their worth
            String w = Component.translatable("bannerbound.barbarian.barter.worth", worth).getString();
            g.drawString(this.font, w, x + QUAD_W - 5 - this.font.width(w), y + 4, GOLD, false);
        }
        g.fill(x + 4, y + 14, x + QUAD_W - 4, y + 15, DIVIDER);

        // Items as a packed inventory-slot grid (bundle-style), names → hover tooltip, count in the
        // corner. Scissor is pre-mapped through scissorX/Y — it ignores the auto-fit pose.
        List<String> ids = new ArrayList<>(rows.keySet());
        int cols = gridCols(), vis = gridRows();
        int gx = x + GRID_PAD, gy = y + ROWS_TOP;
        g.enableScissor(scissorX(gx), scissorY(gy),
            scissorX(gx + cols * SLOT), scissorY(gy + vis * SLOT));
        int start = scroll * cols;
        for (int s = 0; s < cols * vis; s++) {
            int idx = start + s;
            if (idx >= ids.size()) break;
            int sx = gx + (s % cols) * SLOT, sy = gy + (s / cols) * SLOT;
            String id = ids.get(idx);
            int avail = rows.get(id);
            boolean usable = !pool || avail - offerHeld(id) > 0;
            boolean hovered = mouseX >= sx && mouseX < sx + SLOT && mouseY >= sy && mouseY < sy + SLOT;
            drawSlot(g, sx, sy);
            if (hovered) g.fill(sx + 1, sy + 1, sx + SLOT - 1, sy + SLOT - 1, 0x40FFFFFF);
            ItemStack st = new ItemStack(item(id), avail);
            g.renderItem(st, sx + 1, sy + 1);
            g.renderItemDecorations(this.font, st, sx + 1, sy + 1);
            if (!usable) g.fill(sx + 1, sy + 1, sx + SLOT - 1, sy + SLOT - 1, 0x88121820);
        }
        g.disableScissor();
        gridScrollbar(g, x, y, ids.size(), scroll);
        if (ids.isEmpty()) {
            drawCentred(g, Component.translatable("bannerbound.barbarian.barter.empty").getString(),
                x + QUAD_W / 2, y + QUAD_H / 2 - 3, TXT_OFF);
        }
    }

    /** A recessed inventory slot cell, themed to the panel (dark fill, shadow top-left, highlight bot-right). */
    private void drawSlot(GuiGraphics g, int x, int y) {
        g.fill(x, y, x + SLOT, y + SLOT, 0x55000000);
        g.fill(x, y, x + SLOT, y + 1, 0xFF0C1118);
        g.fill(x, y, x + 1, y + SLOT, 0xFF0C1118);
        g.fill(x + SLOT - 1, y, x + SLOT, y + SLOT, 0xFF313E4D);
        g.fill(x, y + SLOT - 1, x + SLOT, y + SLOT, 0xFF313E4D);
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

    private void verdictPill(GuiGraphics g, int rightEdge, int y) {
        Component text = verdict();
        boolean ok = affordable() && acceptable();
        boolean cant = !affordable();
        int fg = ok ? 0xFFADE9AD : 0xFFE9ADAD;
        int bg = ok ? 0x3338C038 : (cant ? 0x33C0A038 : 0x33C03838);
        int border = ok ? 0xFF3FA63F : (cant ? 0xFFA68A3F : 0xFFA63F3F);
        int w = this.font.width(text) + 12;
        int x = rightEdge - w;
        g.fill(x, y, x + w, y + 14, bg);
        g.renderOutline(x, y, w, 14, border);
        g.drawString(this.font, text, x + 6, y + 3, fg, false);
    }

    private int offerHeld(String id) {
        return give.getOrDefault(id, 0) + get.getOrDefault(id, 0);
    }

    // ─── Tooltips (drawn AFTER widgets so they sit on top) ──────────────────────────────────────────

    @Override
    protected void renderPolishedExtras(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        String id = hoveredId(mouseX, mouseY);
        if (id != null) {
            g.renderComponentTooltip(this.font, List.of(itemName(id),
                Component.translatable("bannerbound.barbarian.barter.worth_each",
                    unitValues.getOrDefault(id, 0)).withStyle(ChatFormatting.GRAY)), mouseX, mouseY);
            return;
        }
        // Hovering the centre meter explains what "worth" means.
        if (mouseX >= leftX() + QUAD_W && mouseX < rightX() && mouseY >= topY() && mouseY < topY() + QUAD_H) {
            g.renderComponentTooltip(this.font, List.of(
                Component.translatable("bannerbound.barbarian.barter.help.title").withStyle(ChatFormatting.GOLD),
                Component.translatable("bannerbound.barbarian.barter.help.1").withStyle(ChatFormatting.GRAY),
                Component.translatable("bannerbound.barbarian.barter.help.2").withStyle(ChatFormatting.GRAY)),
                mouseX, mouseY);
        }
    }

    private String hoveredId(double mx, double my) {
        String id = slotAt(storage, storageScroll, leftX(), bottomY(), mx, my);
        if (id == null) id = slotAt(goods, goodsScroll, rightX(), bottomY(), mx, my);
        if (id == null) id = slotAt(give, 0, leftX(), topY(), mx, my);
        if (id == null) id = slotAt(get, 0, rightX(), topY(), mx, my);
        return id;
    }

    private Component verdict() {
        if (!affordable()) {
            return Component.translatable("bannerbound.barbarian.barter.verdict.cant_pay");
        }
        return acceptable()
            ? Component.translatable("bannerbound.barbarian.barter.verdict.ok")
            : Component.translatable("bannerbound.barbarian.barter.verdict.bad");
    }

    private Component relationLabel() {
        CampRelationState[] vals = CampRelationState.values();
        CampRelationState st = vals[Math.max(0, Math.min(vals.length - 1, relState))];
        ChatFormatting c = switch (st) {
            case HOSTILE -> ChatFormatting.RED;
            case FRIENDLY -> ChatFormatting.GREEN;
            default -> ChatFormatting.YELLOW;
        };
        return Component.translatable("bannerbound.barbarian.relation." + st.name().toLowerCase(Locale.ROOT))
            .withStyle(c);
    }

    // ─── Geometry helpers ────────────────────────────────────────────────────────────────────────────

    private int leftX() { return panelX + 12; }
    private int rightX() { return panelX + PANEL_W - 12 - QUAD_W; }
    private int topY() { return panelY + HEADER_H; }
    private int bottomY() { return panelY + HEADER_H + QUAD_H + GAP; }

    private boolean inPanel(int x, int y, double mx, double my) {
        return mx >= x && mx < x + QUAD_W && my >= y + ROWS_TOP && my < y + QUAD_H;
    }

    /** The item id of the grid cell under the cursor (scroll is a ROW offset), or null. */
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

    /** Clamp a ROW scroll offset so the last grid row can't scroll past the visible window. */
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

    /** Reveal the real name even for un-researched goods (consistent with the parley) so a barter is readable. */
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
