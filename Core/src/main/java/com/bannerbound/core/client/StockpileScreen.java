package com.bannerbound.core.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.jetbrains.annotations.Nullable;

import com.bannerbound.core.api.settlement.Stockpile;
import com.bannerbound.core.menu.StockEntry;
import com.bannerbound.core.menu.StockpileMenu;
import com.bannerbound.core.network.StockpileDepositPayload;
import com.bannerbound.core.network.StockpileDetectPayload;
import com.bannerbound.core.network.StockpileTogglePayload;
import com.bannerbound.core.network.StockpileWithdrawPayload;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * The Stockpile terminal screen — Tom's-style but simpler. The top region is a virtual, scrollable,
 * searchable grid summed across all enclosed containers ({@link StockpileMenu#contents()}, synced
 * each tick): left-click a cell withdraws a stack, right-click/shift withdraws half. The bottom is
 * the player's real inventory; shift-click a player item to deposit it into the stockpile. A first
 * pass — the layout/colours are programmatic and may want polishing in-game.
 */
public class StockpileScreen extends AbstractContainerScreen<StockpileMenu> {
    private static final int COLS = 9;
    private static final int VISIBLE_ROWS = 5;
    private static final int CELL = 18;
    private static final int GRID_X = 8;
    private static final int GRID_Y = 44;
    private static final int PANEL = 0xFFC6C6C6;
    private static final int SLOT_BG = 0xFF8B8B8B;
    private static final int LIGHT = 0xFFFFFFFF;
    private static final int SHADOW = 0xFF555555;
    private static final int DARK = 0xFF373737;

    /** Identity trim only — the beige bevel stays (slots need that contrast); the settlement's
     *  banner colors are worn as a border over the same rect. Resolved once at construction. */
    private final java.util.List<Integer> identityAccents;

    private EditBox search;
    private int scrollRow;
    private List<StockEntry> filtered = List.of();
    @Nullable
    private StockEntry hovered;
    @Nullable
    private Button depositToggle;
    @Nullable
    private Button takeToggle;
    @Nullable
    private Button tradeToggle;

    public StockpileScreen(StockpileMenu menu, Inventory inv, Component title) {
        super(menu, inv, title);
        this.identityAccents = GuiPalette.localIdentityAccents();
        this.imageWidth = 176;
        // Tall enough for a worker-access toggle row between the storage grid and player inventory.
        this.imageHeight = 250;
        this.inventoryLabelY = this.imageHeight - 94;
    }

    @Override
    protected void init() {
        super.init();
        this.titleLabelY = 6;
        search = new EditBox(this.font, leftPos + GRID_X + 1, topPos + 30, COLS * CELL - 4, 12,
            Component.translatable("bannerbound.stockpile.search"));
        search.setMaxLength(50);
        search.setHint(Component.translatable("bannerbound.stockpile.search"));
        search.setResponder(s -> { scrollRow = 0; refilter(); });
        addRenderableWidget(search);
        // Detect button (top-right) — re-scans the enclosure and flashes its wireframe behind the GUI.
        addRenderableWidget(PolishButton.polished(
                Component.translatable("bannerbound.stockpile.detect"),
                b -> PacketDistributor.sendToServer(new StockpileDetectPayload(menu.menuId())))
            .bounds(leftPos + imageWidth - 50, topPos + 4, 46, 14).build());
        // Worker-access toggles (row between the storage grid and player inventory): whether
        // autonomous workers may deposit into / take from this stockpile. Loose baskets are always
        // open; this governs the managed stockpile only.
        int toggleY = topPos + GRID_Y + VISIBLE_ROWS * CELL + 2;
        depositToggle = addRenderableWidget(PolishButton.polished(
                toggleLabel(StockpileTogglePayload.TOGGLE_DEPOSIT, menu.allowDeposit()),
                b -> PacketDistributor.sendToServer(new StockpileTogglePayload(
                    menu.menuId(), StockpileTogglePayload.TOGGLE_DEPOSIT, !menu.allowDeposit())))
            .bounds(leftPos + GRID_X, toggleY, 78, 14).build());
        takeToggle = addRenderableWidget(PolishButton.polished(
                toggleLabel(StockpileTogglePayload.TOGGLE_TAKE, menu.allowTake()),
                b -> PacketDistributor.sendToServer(new StockpileTogglePayload(
                    menu.menuId(), StockpileTogglePayload.TOGGLE_TAKE, !menu.allowTake())))
            .bounds(leftPos + GRID_X + 82, toggleY, 78, 14).build());
        // Trade toggle on its own full-width row below — whether this stockpile's contents are
        // offered in settlement-to-settlement trade (visible to partners; deals draw/deliver here).
        tradeToggle = addRenderableWidget(PolishButton.polished(
                toggleLabel(StockpileTogglePayload.TOGGLE_TRADE, menu.showTrade()),
                b -> PacketDistributor.sendToServer(new StockpileTogglePayload(
                    menu.menuId(), StockpileTogglePayload.TOGGLE_TRADE, !menu.showTrade())))
            .bounds(leftPos + GRID_X, toggleY + 15, 160, 13).build());
        refilter();
    }

    /** Button label for a terminal toggle, e.g. "Deposit: On" / "Take: Off" / "Trade: Off". */
    private static Component toggleLabel(int toggle, boolean on) {
        String key = switch (toggle) {
            case StockpileTogglePayload.TOGGLE_DEPOSIT -> "bannerbound.stockpile.workers_deposit";
            case StockpileTogglePayload.TOGGLE_TAKE -> "bannerbound.stockpile.workers_take";
            default -> "bannerbound.stockpile.show_trade";
        };
        return Component.translatable(key,
            Component.translatable(on ? "bannerbound.stockpile.on" : "bannerbound.stockpile.off"));
    }

    private void refilter() {
        String q = search == null ? "" : search.getValue().trim().toLowerCase(Locale.ROOT);
        List<StockEntry> all = menu.contents();
        if (q.isEmpty()) { filtered = all; return; }
        List<StockEntry> out = new ArrayList<>();
        for (StockEntry e : all) {
            if (e.display().getHoverName().getString().toLowerCase(Locale.ROOT).contains(q)) out.add(e);
        }
        filtered = out;
    }

    private int maxScroll() {
        int rows = (filtered.size() + COLS - 1) / COLS;
        return Math.max(0, rows - VISIBLE_ROWS);
    }

    @Override
    protected void containerTick() {
        super.containerTick();
        refilter(); // contents sync each tick — keep the filtered view fresh
        if (scrollRow > maxScroll()) scrollRow = maxScroll();
        // Reflect the server-synced toggle state on the buttons (also confirms a click landed).
        if (depositToggle != null) depositToggle.setMessage(
            toggleLabel(StockpileTogglePayload.TOGGLE_DEPOSIT, menu.allowDeposit()));
        if (takeToggle != null) takeToggle.setMessage(
            toggleLabel(StockpileTogglePayload.TOGGLE_TAKE, menu.allowTake()));
        if (tradeToggle != null) tradeToggle.setMessage(
            toggleLabel(StockpileTogglePayload.TOGGLE_TRADE, menu.showTrade()));
    }

    @Override
    protected void renderBg(GuiGraphics g, float partialTick, int mouseX, int mouseY) {
        int x = leftPos;
        int y = topPos;
        // Raised beige window (vanilla-style bevel: light top/left, dark bottom/right).
        g.fill(x, y, x + imageWidth, y + imageHeight, PANEL);
        g.fill(x, y, x + imageWidth, y + 1, LIGHT);
        g.fill(x, y, x + 1, y + imageHeight, LIGHT);
        g.fill(x + imageWidth - 1, y, x + imageWidth, y + imageHeight, SHADOW);
        g.fill(x, y + imageHeight - 1, x + imageWidth, y + imageHeight, SHADOW);
        // Identity trim: the settlement's banner colors over the exact bevel rect (no-op unclaimed).
        if (!identityAccents.isEmpty()) {
            PolishedScreen.drawIdentityBorder(g, x, y, imageWidth, imageHeight, identityAccents);
        }
        // Recessed slots — pass each slot's 16x16 content origin.
        for (int row = 0; row < VISIBLE_ROWS; row++) {
            for (int col = 0; col < COLS; col++) {
                slot(g, x + GRID_X + col * CELL + 1, y + GRID_Y + row * CELL + 1);
            }
        }
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < COLS; col++) {
                slot(g, x + StockpileMenu.PLAYER_INV_X + col * CELL, y + StockpileMenu.PLAYER_INV_Y + row * CELL);
            }
        }
        for (int col = 0; col < COLS; col++) {
            slot(g, x + StockpileMenu.PLAYER_INV_X + col * CELL, y + StockpileMenu.PLAYER_INV_Y + 58);
        }
        // Virtual items + counts; track which cell the mouse is over for the tooltip.
        hovered = null;
        for (int row = 0; row < VISIBLE_ROWS; row++) {
            for (int col = 0; col < COLS; col++) {
                int idx = (scrollRow + row) * COLS + col;
                if (idx >= filtered.size()) continue;
                StockEntry e = filtered.get(idx);
                int cx = x + GRID_X + col * CELL + 1;
                int cy = y + GRID_Y + row * CELL + 1;
                g.renderItem(e.display(), cx, cy);
                g.renderItemDecorations(this.font, e.display(), cx, cy, abbreviate(e.total()));
                if (mouseX >= cx - 1 && mouseX < cx + 17 && mouseY >= cy - 1 && mouseY < cy + 17) {
                    hovered = e;
                }
            }
        }
        // Vanilla-style cursor highlight over the hovered storage cell (real slots get theirs from
        // super; the virtual cells aren't slots, so draw it ourselves — translucent white on top).
        if (insideGrid(mouseX, mouseY)) {
            int hc = (int) ((mouseX - (x + GRID_X)) / CELL);
            int hr = (int) ((mouseY - (y + GRID_Y)) / CELL);
            if (hc >= 0 && hc < COLS && hr >= 0 && hr < VISIBLE_ROWS) {
                int hx = x + GRID_X + hc * CELL + 1;
                int hy = y + GRID_Y + hr * CELL + 1;
                g.fillGradient(hx, hy, hx + 16, hy + 16, 0x80FFFFFF, 0x80FFFFFF);
            }
        }
    }

    private static String abbreviate(int n) {
        if (n >= 1_000_000) return (n / 1_000_000) + "m"; // ≤ ~14k realistically (8-container cap),
        if (n >= 1_000) return (n / 1_000) + "k";         // so this keeps the label to ≤ 3 chars and
        return Integer.toString(n);                        // never overflows the slot, like vanilla.
    }

    /** Draws a vanilla-style recessed slot whose 16x16 content sits at {@code (cx, cy)}. */
    private void slot(GuiGraphics g, int cx, int cy) {
        g.fill(cx - 1, cy - 1, cx + 17, cy + 17, DARK);   // dark frame → top + left edge
        g.fill(cx, cy, cx + 17, cy + 17, LIGHT);          // light → bottom + right edge
        g.fill(cx, cy, cx + 16, cy + 16, SLOT_BG);        // inner
    }

    @Override
    public void render(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        super.render(g, mouseX, mouseY, partialTick);
        if (hovered != null) {
            g.renderTooltip(this.font, hovered.display(), mouseX, mouseY);
        }
    }

    @Override
    protected void renderLabels(GuiGraphics g, int mouseX, int mouseY) {
        g.drawString(this.font, this.title, GRID_X, titleLabelY, 0x404040, false);
        g.drawString(this.font, this.playerInventoryTitle, StockpileMenu.PLAYER_INV_X, this.inventoryLabelY, 0x404040, false);
        // Status header gets the whole top line: "Storage N/8   <status>" with the status word coloured
        // green (ready) / red, so even long statuses ("Too many containers") have room to breathe.
        Stockpile.Status st = Stockpile.Status.fromOrdinalOrDefault(menu.statusOrdinal());
        String count = "Storage " + menu.containerCount() + "/" + Stockpile.MAX_CONTAINERS;
        g.drawString(this.font, count, GRID_X, 19, 0x404040, false);
        int sx = GRID_X + this.font.width(count) + 8;
        int color = st == Stockpile.Status.VALID ? 0x2E7D32 : 0xB00020;
        g.drawString(this.font, Component.translatable("bannerbound.stockpile.short." + st.name().toLowerCase()),
            sx, 19, color, false);
        // Capacity readout — how many slots are still empty across the enclosed containers. Lives on the
        // player-inventory label line (right-aligned, opposite "Inventory") rather than the crowded top
        // line: it sits next to where you deposit from, and never collides with the status word.
        if (menu.totalSlots() > 0) {
            int free = menu.freeSlots();
            Component cap = free == 0
                ? Component.translatable("bannerbound.stockpile.slots_full")
                : Component.translatable("bannerbound.stockpile.slots_free", free);
            // Green when roomy, amber under ~15% free, red when full.
            int capColor = free == 0 ? 0xB00020
                : (free * 100 < menu.totalSlots() * 15 ? 0xC08000 : 0x2E7D32);
            int cx = imageWidth - 8 - this.font.width(cap);
            g.drawString(this.font, cap, cx, this.inventoryLabelY, capColor, false);
        }
    }

    private boolean insideGrid(double mx, double my) {
        return mx >= leftPos + GRID_X && mx < leftPos + GRID_X + COLS * CELL
            && my >= topPos + GRID_Y && my < topPos + GRID_Y + VISIBLE_ROWS * CELL;
    }

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (insideGrid(mx, my) && (button == 0 || button == 1)) {
            // Holding an item → clicking the grid deposits it (left = all, right = one).
            if (!menu.getCarried().isEmpty()) {
                PacketDistributor.sendToServer(new StockpileDepositPayload(menu.menuId(), button == 1));
                return true;
            }
            // Empty cursor → clicking a filled cell withdraws (left = stack, right/shift = half).
            int col = (int) ((mx - (leftPos + GRID_X)) / CELL);
            int row = (int) ((my - (topPos + GRID_Y)) / CELL);
            int idx = (scrollRow + row) * COLS + col;
            if (col >= 0 && col < COLS && row >= 0 && row < VISIBLE_ROWS && idx < filtered.size()) {
                boolean half = button == 1 || hasShiftDown();
                PacketDistributor.sendToServer(
                    new StockpileWithdrawPayload(menu.menuId(), filtered.get(idx).display(), half));
            }
            return true; // consume all grid clicks (the cells aren't real slots)
        }
        return super.mouseClicked(mx, my, button);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double dx, double dy) {
        if (maxScroll() > 0) {
            scrollRow = Math.max(0, Math.min(maxScroll(), scrollRow - (int) Math.signum(dy)));
            return true;
        }
        return super.mouseScrolled(mx, my, dx, dy);
    }

    @Override
    public boolean keyPressed(int key, int scan, int mods) {
        // Let the search box capture typing (incl. the inventory key) while focused; ESC still closes.
        if (key != 256 && search.isFocused()) {
            search.keyPressed(key, scan, mods);
            return true;
        }
        return super.keyPressed(key, scan, mods);
    }

    @Override
    public boolean charTyped(char c, int mods) {
        if (search.isFocused()) {
            return search.charTyped(c, mods);
        }
        return super.charTyped(c, mods);
    }
}
