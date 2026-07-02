package com.bannerbound.core.creative;

import java.util.ArrayList;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

import net.minecraft.world.item.CreativeModeTab;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ItemLike;

/**
 * Adds Create-Aeronautics-style labelled "sections" to any creative-mode tab without touching that
 * tab's existing item list. A tab keeps populating items exactly as before (its
 * {@code displayItems(...)} generator is the single source of truth for <em>which</em> items appear);
 * this class only re-orders the already-built list into labelled bands separated by blank "banner"
 * rows.
 *
 * <p><b>How the pieces fit:</b>
 * <ul>
 *   <li>Mods register sections + item membership here via {@link #forTab} (common-side, at mod init).</li>
 *   <li>{@code CreativeModeTabMixin} calls {@link #layout} at the tail of {@code buildContents} and swaps
 *       in the re-ordered list (with {@link ItemStack#EMPTY} spacer rows — which is why the field must be
 *       replaced wholesale: vanilla's backing set dedupes and rejects empties).</li>
 *   <li>{@code CreativeModeInventoryScreenMixin} draws the banners over the blank rows at render-tail,
 *       using {@link TabSections#bannerRow} and {@link #currentRow}.</li>
 *   <li>{@code CreativeItemPickerMenuMixin} keeps {@link #currentRow} in sync with the scrollbar.</li>
 * </ul>
 *
 * <p>Items not assigned to any section are kept (rendered first, ungrouped), so a forgotten item can
 * never silently vanish from a tab.
 */
public final class CreativeSections {

    private CreativeSections() {}

    /** Slots per grid row in the creative menu. */
    public static final int COLUMNS = 9;
    /** Visible item rows in the creative menu (vanilla {@code NUM_ROWS}). */
    public static final int VISIBLE_ROWS = 5;

    /** Top visible grid row, captured from the creative scrollbar (set client-side, read by the renderer). */
    public static int currentRow = 0;

    /** Section-enabled tabs in registration order, keyed by the (lazy) tab supplier. */
    private static final Map<Supplier<CreativeModeTab>, TabSections> REGISTERED = new LinkedHashMap<>();
    /** Identity cache: resolved tab instance -> its sections (or {@link #ABSENT}). */
    private static final Map<CreativeModeTab, TabSections> RESOLVED = new IdentityHashMap<>();
    /** Sentinel so "this tab has no sections" is cached instead of re-scanned every rebuild. */
    private static final TabSections ABSENT = new TabSections();

    /** Ordered sections + item membership for one tab, plus the banner rows computed each rebuild. */
    public static final class TabSections {
        final List<CreativeSection> order = new ArrayList<>();
        final Map<Item, CreativeSection> itemToSection = new IdentityHashMap<>();
        final Map<CreativeSection, Integer> bannerRows = new IdentityHashMap<>();

        public List<CreativeSection> order() {
            return order;
        }

        /** Grid row the section's banner sits on for the current rebuild, or {@code -1} if not shown. */
        public int bannerRow(CreativeSection section) {
            return bannerRows.getOrDefault(section, -1);
        }
    }

    // ---- registration ------------------------------------------------------------------------------

    /**
     * Begin (or continue) defining sections for a tab. Pass the same supplier you registered the tab
     * with — a {@code DeferredHolder<CreativeModeTab, CreativeModeTab>} works directly.
     */
    public static Builder forTab(Supplier<CreativeModeTab> tab) {
        return new Builder(REGISTERED.computeIfAbsent(tab, t -> new TabSections()));
    }

    /** Fluent builder: open a {@link #section}, then {@link #add} items to it. */
    public static final class Builder {
        private final TabSections ts;
        private CreativeSection current;

        private Builder(TabSections ts) {
            this.ts = ts;
        }

        /** Open a new band. Subsequent {@code add(...)} calls assign items to it. */
        public Builder section(CreativeSection section) {
            ts.order.add(section);
            this.current = section;
            return this;
        }

        /** Assign registered items/blocks (suppliers) to the current section. */
        @SafeVarargs
        public final Builder add(Supplier<? extends ItemLike>... items) {
            for (Supplier<? extends ItemLike> sup : items) {
                ItemLike like = sup.get();
                if (like != null) {
                    ts.itemToSection.put(like.asItem(), current);
                }
            }
            return this;
        }

        /** Assign a whole collection of suppliers (e.g. {@code SOME_MAP.values()}). */
        public Builder add(Collection<? extends Supplier<? extends ItemLike>> items) {
            for (Supplier<? extends ItemLike> sup : items) {
                ItemLike like = sup.get();
                if (like != null) {
                    ts.itemToSection.put(like.asItem(), current);
                }
            }
            return this;
        }

        /**
         * Assign raw items to the current section. Use this for display stacks built from a vanilla item
         * carrying custom components (e.g. poison-coated {@code Items.ARROW}) so they land in the right band.
         */
        public Builder addItems(ItemLike... items) {
            for (ItemLike like : items) {
                ts.itemToSection.put(like.asItem(), current);
            }
            return this;
        }
    }

    // ---- lookup ------------------------------------------------------------------------------------

    /** The sections for a resolved tab instance, or {@code null} if the tab has none. */
    public static TabSections forResolvedTab(CreativeModeTab tab) {
        TabSections cached = RESOLVED.get(tab);
        if (cached != null) {
            return cached == ABSENT ? null : cached;
        }
        TabSections found = ABSENT;
        for (Map.Entry<Supplier<CreativeModeTab>, TabSections> e : REGISTERED.entrySet()) {
            CreativeModeTab resolved;
            try {
                resolved = e.getKey().get();
            } catch (RuntimeException ex) {
                continue; // not registered yet; try again next rebuild
            }
            if (resolved == tab) {
                found = e.getValue();
                break;
            }
        }
        RESOLVED.put(tab, found);
        return found == ABSENT ? null : found;
    }

    // ---- layout ------------------------------------------------------------------------------------

    /** Result of a re-layout: the new ordered display list (with spacers) and the search-tab set. */
    public static final class Built {
        public final List<ItemStack> display;
        public final Set<ItemStack> search;

        Built(List<ItemStack> display, Set<ItemStack> search) {
            this.display = display;
            this.search = search;
        }
    }

    /**
     * Re-lay a tab's vanilla-built display list into labelled sections separated by blank banner rows,
     * recording each banner's grid row into {@code ts.bannerRows} for the renderer. Within a section,
     * the original (vanilla) ordering is preserved. Unassigned items are emitted first, ungrouped.
     */
    public static Built layout(TabSections ts, Collection<ItemStack> original) {
        Map<CreativeSection, List<ItemStack>> grouped = new IdentityHashMap<>();
        List<ItemStack> unassigned = new ArrayList<>();
        for (ItemStack stack : original) {
            CreativeSection s = ts.itemToSection.get(stack.getItem());
            (s == null ? unassigned : grouped.computeIfAbsent(s, k -> new ArrayList<>())).add(stack);
        }

        List<ItemStack> display = new LinkedList<>();
        Set<ItemStack> search = new LinkedHashSet<>();
        ts.bannerRows.clear();

        if (!unassigned.isEmpty()) {
            for (ItemStack st : unassigned) {
                display.add(st);
                search.add(st);
            }
            padToRow(display);
        }

        for (CreativeSection s : ts.order) {
            List<ItemStack> items = grouped.get(s);
            if (items == null || items.isEmpty()) {
                continue; // a defined-but-empty section draws no banner
            }
            int bannerRow = display.size() / COLUMNS; // list is always row-aligned here
            for (int i = 0; i < COLUMNS; i++) {
                display.add(ItemStack.EMPTY); // the blank row the banner is drawn over
            }
            ts.bannerRows.put(s, bannerRow);
            for (ItemStack st : items) {
                display.add(st);
                search.add(st);
            }
            padToRow(display);
        }
        return new Built(display, search);
    }

    /** Pad with EMPTY up to the next full row so the list stays row-aligned. */
    private static void padToRow(List<ItemStack> display) {
        int rem = display.size() % COLUMNS;
        if (rem != 0) {
            for (int i = rem; i < COLUMNS; i++) {
                display.add(ItemStack.EMPTY);
            }
        }
    }
}
