package com.bannerbound.core.client;

import java.util.List;
import java.util.function.BooleanSupplier;

import org.jetbrains.annotations.ApiStatus;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.sounds.SoundEvents;

/**
 * 3D-software-style menu bar (File · Edit · View · Go) shared by the wall screens — the
 * "stuff above, as if it's a real 3D software" navigation layer (playtest 2026-06-12).
 *
 * <p>Deliberately NOT an AbstractWidget: the owning screen calls {@link #mouseClicked} FIRST
 * in its own handler (so the dropdown wins over widgets underneath) and {@link #render} LAST
 * in its render pass (so the dropdown draws over everything). One menu open at a time;
 * clicking elsewhere closes it and swallows that click.
 */
@ApiStatus.Internal
public final class WallMenuBar {

    public record Item(String label, Runnable action, BooleanSupplier enabled) {
        public static Item of(String label, Runnable action) {
            return new Item(label, action, () -> true);
        }
    }

    public record Menu(String label, List<Item> items) {
    }

    private static final int PAD_X = 7;
    private static final int BAR_H = 14;
    private static final int ROW_H = 13;
    private static final int DROP_W = 150;

    private final Font font;
    private final List<Menu> menus;
    private final int x;
    private final int y;
    private int openIndex = -1;

    public WallMenuBar(Font font, int x, int y, List<Menu> menus) {
        this.font = font;
        this.x = x;
        this.y = y;
        this.menus = menus;
    }

    public boolean isOpen() {
        return openIndex >= 0;
    }

    public void close() {
        openIndex = -1;
    }

    private int labelX(int index) {
        int lx = x;
        for (int i = 0; i < index; i++) {
            lx += font.width(menus.get(i).label()) + PAD_X * 2;
        }
        return lx;
    }

    private int labelW(int index) {
        return font.width(menus.get(index).label()) + PAD_X * 2;
    }

    private int barW() {
        int w = 0;
        for (int i = 0; i < menus.size(); i++) w += labelW(i);
        return w;
    }

    public void render(GuiGraphics g, int mouseX, int mouseY) {
        // The whole bar + dropdown rides high above every screen's depth bands (panel text,
        // viewport blocks at ~400, overlays at 600-700) — the dropdown rendered UNDER the
        // inspector labels without this (playtest 2026-06-12).
        g.pose().pushPose();
        g.pose().translate(0, 0, 950);
        // Bar pill.
        g.fill(x - 2, y, x + barW() + 2, y + BAR_H, 0xC8101016);
        g.fill(x - 2, y + BAR_H - 1, x + barW() + 2, y + BAR_H, 0xFF2E2E36);
        for (int i = 0; i < menus.size(); i++) {
            int lx = labelX(i);
            int lw = labelW(i);
            boolean hot = openIndex == i
                || (mouseY >= y && mouseY < y + BAR_H && mouseX >= lx && mouseX < lx + lw);
            if (hot) {
                g.fill(lx, y, lx + lw, y + BAR_H, openIndex == i ? 0xFF2E2E3A : 0x602E2E3A);
            }
            g.drawString(font, menus.get(i).label(), lx + PAD_X, y + 3,
                hot ? 0xFFFFFFFF : 0xFFB8B8C0);
        }
        // Dropdown.
        if (openIndex >= 0) {
            Menu menu = menus.get(openIndex);
            int dx = labelX(openIndex);
            int dy = y + BAR_H;
            int dh = menu.items().size() * ROW_H + 4;
            g.fill(dx, dy, dx + DROP_W, dy + dh, 0xF0141419);
            g.fill(dx, dy, dx + DROP_W, dy + 1, 0xFF2E2E36);
            g.fill(dx, dy + dh - 1, dx + DROP_W, dy + dh, 0xFF2E2E36);
            g.fill(dx, dy, dx + 1, dy + dh, 0xFF2E2E36);
            g.fill(dx + DROP_W - 1, dy, dx + DROP_W, dy + dh, 0xFF2E2E36);
            int ry = dy + 2;
            for (Item item : menu.items()) {
                boolean enabled = item.enabled().getAsBoolean();
                boolean hot = enabled && mouseX >= dx && mouseX < dx + DROP_W
                    && mouseY >= ry && mouseY < ry + ROW_H;
                if (hot) {
                    g.fill(dx + 1, ry, dx + DROP_W - 1, ry + ROW_H, 0xFF2E2E3A);
                }
                g.drawString(font, item.label(), dx + 8, ry + 2,
                    !enabled ? 0xFF5A5A60 : hot ? 0xFFFFFFFF : 0xFFC8C8D0);
                ry += ROW_H;
            }
        }
        g.pose().popPose();
    }

    /** Call FIRST in the owning screen's mouseClicked. Returns true when consumed. */
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (button != 0) {
            if (openIndex >= 0) {
                openIndex = -1;
                return true;
            }
            return false;
        }
        // Bar labels toggle their menu.
        if (mouseY >= y && mouseY < y + BAR_H) {
            for (int i = 0; i < menus.size(); i++) {
                int lx = labelX(i);
                if (mouseX >= lx && mouseX < lx + labelW(i)) {
                    openIndex = openIndex == i ? -1 : i;
                    click(1.2f);
                    return true;
                }
            }
        }
        if (openIndex >= 0) {
            Menu menu = menus.get(openIndex);
            int dx = labelX(openIndex);
            int dy = y + BAR_H;
            int dh = menu.items().size() * ROW_H + 4;
            if (mouseX >= dx && mouseX < dx + DROP_W && mouseY >= dy && mouseY < dy + dh) {
                int index = (int) ((mouseY - dy - 2) / ROW_H);
                if (index >= 0 && index < menu.items().size()) {
                    Item item = menu.items().get(index);
                    if (item.enabled().getAsBoolean()) {
                        openIndex = -1;
                        click(1.0f);
                        item.action().run();
                    }
                }
                return true;
            }
            // Click elsewhere: close and SWALLOW so the map/viewport doesn't also react.
            openIndex = -1;
            return true;
        }
        return false;
    }

    private static void click(float pitch) {
        Minecraft.getInstance().getSoundManager().play(
            SimpleSoundInstance.forUI(SoundEvents.NOTE_BLOCK_HAT.value(), pitch));
    }
}
