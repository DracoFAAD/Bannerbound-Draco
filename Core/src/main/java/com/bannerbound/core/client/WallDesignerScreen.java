package com.bannerbound.core.client;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

import com.bannerbound.core.api.walls.WallDesign;
import com.bannerbound.core.network.WallScreenPayloads;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.math.Axis;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.renderer.LightTexture;
import net.minecraft.client.renderer.MultiBufferSource;
import net.minecraft.client.renderer.RenderType;
import net.minecraft.client.renderer.texture.OverlayTexture;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * The Wall Designer (WALLS_PLAN.md Phase 5, mockup image 1): a 3D voxel editor for the
 * settlement's active wall / corner / gate designs. Axonometric orbit viewport (the
 * Jade/Patchouli multiblock-preview technique — invertible transforms make picking exact;
 * true perspective is a later polish), DDA ray-pick into the grid, panels for the block
 * picker (settlement-known blocks only) and Required Blocks.
 *
 * <p>Controls (shown in the hint bar): LEFT-click a face to place the picker block;
 * SHIFT+LEFT-click a block to cycle its state property; RIGHT-click removes. Middle-drag or
 * A/D + W/S orbits, scroll zooms. Save sends the design to the server, which re-validates
 * and makes it the settlement's active design for that kind.
 *
 * <p>Extends {@link PolishedScreen} — its render path draws the blur/dim background exactly
 * once, OUTSIDE the content (a hand-rolled render that ended with {@code super.render()}
 * re-ran vanilla's blur pass over everything drawn before it — playtest 2026-06-11). Picking
 * is pixel-exact once the 160ms settle finishes; during it the offset is imperceptible.
 */
@OnlyIn(Dist.CLIENT)
@ApiStatus.Internal
public class WallDesignerScreen extends PolishedScreen {

    private static final int[] LENGTHS = {2, 4, 8, 16};
    /** Screen-depth compression for the viewport scene — see the note in renderViewport. */
    private static final float DEPTH_SQUASH = 0.12f;
    private static final int PANEL_LEFT_W = 130;
    private static final int PANEL_RIGHT_W = 120;
    private static final int PICKER_COLS = 6;
    private static final int SLOT = 18;

    /** Mutable working copy of one design. */
    private static final class EditModel {
        WallDesign.Kind kind;
        int length;
        int depth;
        int height;
        BlockState[] cells; // (h * depth + d) * length + l; null = air
        BlockState foundation;
        /** Player-given design name — the server slugs it into the library id, so saving
         *  under a NEW name creates a new design (variant) instead of overwriting. */
        String name = "";

        static EditModel of(WallDesign design) {
            EditModel m = new EditModel();
            m.kind = design.kind();
            m.length = design.length();
            m.depth = design.depth();
            m.height = design.height();
            m.cells = new BlockState[m.length * m.depth * m.height];
            for (int l = 0; l < m.length; l++) {
                for (int d = 0; d < m.depth; d++) {
                    for (int h = 0; h < m.height; h++) {
                        m.cells[m.idx(l, d, h)] = design.stateAt(l, d, h);
                    }
                }
            }
            m.foundation = design.foundation();
            m.name = design.name();
            return m;
        }

        int idx(int l, int d, int h) {
            return (h * depth + d) * length + l;
        }

        boolean inBounds(int l, int d, int h) {
            return l >= 0 && l < length && d >= 0 && d < depth && h >= 0 && h < height;
        }

        @Nullable
        BlockState get(int l, int d, int h) {
            return inBounds(l, d, h) ? cells[idx(l, d, h)] : null;
        }

        void resize(int newLength, int newDepth, int newHeight) {
            BlockState[] next = new BlockState[newLength * newDepth * newHeight];
            for (int l = 0; l < Math.min(length, newLength); l++) {
                for (int d = 0; d < Math.min(depth, newDepth); d++) {
                    for (int h = 0; h < Math.min(height, newHeight); h++) {
                        next[(h * newDepth + d) * newLength + l] = cells[idx(l, d, h)];
                    }
                }
            }
            length = newLength;
            depth = newDepth;
            height = newHeight;
            cells = next;
        }

        WallDesign toDesign() {
            String designName = name == null || name.isBlank()
                ? "Custom " + kind.name().toLowerCase(Locale.ROOT) : name.trim();
            WallDesign.Builder b = WallDesign.builder(
                "custom_" + kind.name().toLowerCase(Locale.ROOT), // server re-keys by name slug
                designName, kind, length, depth, height);
            for (int l = 0; l < length; l++) {
                for (int d = 0; d < depth; d++) {
                    for (int h = 0; h < height; h++) {
                        BlockState s = cells[idx(l, d, h)];
                        if (s != null) b.set(l, d, h, s);
                    }
                }
            }
            return b.foundation(foundation).build();
        }
    }

    private record Hit(int l, int d, int h, boolean filled, int nl, int nd, int nh) {
        // filled hit: (l,d,h) is a block, (nl,nd,nh) is the face-adjacent placement cell.
    }

    private final EditModel[] models = new EditModel[3]; // SEGMENT, CORNER, GATE
    private final List<Block> knownBlocks = new ArrayList<>();
    /** The OWNED subset of {@link #knownBlocks}: count > 0 in stockpiles + inventory. */
    private final List<Block> allBlocks = new ArrayList<>();
    private int tab = 0;
    private Block selectedBlock;
    /** Exact state placement uses — picker sets the default state, K (eyedropper) samples the
     *  hovered cell's FULL state so B keeps building with rotations/halves intact. */
    private BlockState selectedState;
    private float yaw = 45f;
    private float pitch = 30f;
    private float zoom = 18f;
    private float panX;
    private float panY;
    private boolean orbiting;
    private boolean panning;
    private EditBox searchBox;
    private int pickerScroll;
    private String search = "";
    private boolean onlyOwned = true;
    /** "OUTSIDE" text on the outward edge(s) — toggleable, default on. */
    private boolean showOutsideLabel = true;
    /** Corner/Gate tabs: ghost the LIVE wall design continuing from the piece, joined exactly
     *  as the layout engine joins them — coherent transitions without guesswork. */
    private boolean contextWalls = true;
    /** Connection-simulated DISPLAY states for the whole viewport scene (authored cells +
     *  context ghosts), keyed scene-local (x=l, y=h, z=d; ghosts offset outside the grid).
     *  Fences/walls/panes render connected as they would in the real wall — picking, editing
     *  and Required Blocks keep using the raw authored states. Rebuilt when the key changes. */
    private java.util.Map<net.minecraft.core.BlockPos, BlockState> displayScene = java.util.Map.of();
    private int displaySceneKey;
    private boolean displaySceneReady;

    // ─── Viewport juice (playtest 2026-06-12): place/break particle bursts + place pop ──────
    private static final long FX_MS = 450;
    private static final long POP_MS = 150;

    private record CellFx(int l, int d, int h, long atMs, int color, boolean place) {
    }

    private final List<CellFx> cellFx = new ArrayList<>();

    private void spawnCellFx(int l, int d, int h, BlockState state, boolean place) {
        cellFx.add(new CellFx(l, d, h, net.minecraft.Util.getMillis(),
            state.getBlock().defaultMapColor().col, place));
    }

    /** 0.6→1 ease-out for a just-placed cell; 1 = no pop. */
    private float placePopScale(int l, int d, int h, long now) {
        for (CellFx f : cellFx) {
            if (f.place() && f.l() == l && f.d() == d && f.h() == h) {
                float t = (now - f.atMs()) / (float) POP_MS;
                if (t < 1f) {
                    float ease = 1f - (1f - t) * (1f - t);
                    return 0.6f + 0.4f * ease;
                }
            }
        }
        return 1f;
    }
    @Nullable
    private Hit hover;

    // ─── Select mode (V) — Blender/Axiom-style editing ────────────────────────────────────
    private boolean selectMode = false;
    /** Selected cell indices into the current model (cleared on tab switch / resize). */
    private final java.util.Set<Integer> selection = new java.util.HashSet<>();
    /** Gizmo drag: 0 = X(length), 1 = Y(height), 2 = Z(depth); -1 = none. */
    private int dragAxis = -1;
    private double dragAccum;
    private boolean rubberBanding;
    private double rubberX0, rubberY0, rubberX1, rubberY1;
    /** Inspector property-row hit rects for the selected block: {x0, y0, x1, y1, propIndex}. */
    private final List<int[]> propertyRows = new ArrayList<>();
    @Nullable
    private Button modeButton;
    @Nullable
    private Button selectButton;

    // ─── Undo/redo (Ctrl+Z / Ctrl+Shift+Z), one stack pair per design tab ─────────────────
    private record Snapshot(int length, int depth, int height, BlockState[] cells,
                            BlockState foundation) {
    }

    private final List<java.util.ArrayDeque<Snapshot>> undoStacks =
        List.of(new java.util.ArrayDeque<>(), new java.util.ArrayDeque<>(), new java.util.ArrayDeque<>());
    private final List<java.util.ArrayDeque<Snapshot>> redoStacks =
        List.of(new java.util.ArrayDeque<>(), new java.util.ArrayDeque<>(), new java.util.ArrayDeque<>());

    private Snapshot snap(EditModel m) {
        return new Snapshot(m.length, m.depth, m.height, m.cells.clone(), m.foundation);
    }

    /** Call BEFORE any mutation of the current model. Clears the redo branch. */
    private void pushUndo() {
        java.util.ArrayDeque<Snapshot> stack = undoStacks.get(tab);
        stack.push(snap(model()));
        while (stack.size() > 64) stack.removeLast();
        redoStacks.get(tab).clear();
    }

    private void restore(Snapshot s) {
        EditModel m = model();
        m.length = s.length();
        m.depth = s.depth();
        m.height = s.height();
        m.cells = s.cells().clone();
        m.foundation = s.foundation();
        selection.clear();
        dragAxis = -1;
    }

    private void undo() {
        if (undoStacks.get(tab).isEmpty()) return;
        redoStacks.get(tab).push(snap(model()));
        restore(undoStacks.get(tab).pop());
        uiClick(net.minecraft.sounds.SoundEvents.NOTE_BLOCK_HAT.value(), 0.75f);
    }

    private void redo() {
        if (redoStacks.get(tab).isEmpty()) return;
        undoStacks.get(tab).push(snap(model()));
        restore(redoStacks.get(tab).pop());
        uiClick(net.minecraft.sounds.SoundEvents.NOTE_BLOCK_HAT.value(), 1.3f);
    }

    public WallDesignerScreen(WallScreenPayloads.OpenWallDesigner payload) {
        super(Component.translatable("bannerbound.wall_designer.title"));
        for (WallDesign design : payload.activeSet()) {
            models[design.kind().ordinal()] = EditModel.of(design);
        }
        // Drafts (autosaved working copies, persisted in world data) override the active set
        // — closing/escaping the designer never loses work (playtest 2026-06-12).
        boolean restored = false;
        for (WallDesign draft : payload.drafts()) {
            EditModel active = models[draft.kind().ordinal()];
            models[draft.kind().ordinal()] = EditModel.of(draft);
            restored |= active == null || !sameContent(models[draft.kind().ordinal()], active);
        }
        if (restored) {
            ClientWallStatus.set("Draft restored — Save & Set Active to apply it.", false);
        }
        // Researched blocks are ALWAYS the ceiling; "Owned" narrows to blocks actually on
        // hand (stockpiles + inventory counts ride the payload in parallel).
        int[] ids = payload.knownBlockItemIds();
        int[] counts = payload.ownedCounts();
        for (int i = 0; i < ids.length; i++) {
            Item item = Item.byId(ids[i]);
            if (item instanceof BlockItem blockItem) {
                knownBlocks.add(blockItem.getBlock());
                if (i < counts.length && counts[i] > 0) {
                    allBlocks.add(blockItem.getBlock()); // repurposed: the OWNED subset
                }
            }
        }
        selectedBlock = knownBlocks.isEmpty()
            ? net.minecraft.world.level.block.Blocks.COBBLESTONE : knownBlocks.get(0);
        selectedState = selectedBlock.defaultBlockState();
        refreshLibrary(payload);
        rescanFileDesigns();
    }

    private EditModel model() {
        return models[tab];
    }

    /** Same dimensions + identical cells (states are canonical — reference equality works). */
    private static boolean sameContent(EditModel a, EditModel b) {
        return a.length == b.length && a.depth == b.depth && a.height == b.height
            && java.util.Arrays.equals(a.cells, b.cells);
    }

    /** Screen to return to on close/Escape (Town Hall walls tab or the wall preview) —
     *  set by ClientPayloadHandler when the designer opens. Null = close to the world. */
    @Nullable
    private net.minecraft.client.gui.screens.Screen parentScreen;

    /** File · Edit · View · Go — built in init(), clicks checked FIRST, drawn LAST. */
    @Nullable
    private WallMenuBar menuBar;

    /** Sends all three tabs for validation + activation (the Save button and File menu). */
    private void saveAllDesigns() {
        for (EditModel m : models) {
            if (m != null) {
                PacketDistributor.sendToServer(
                    new WallScreenPayloads.SaveWallDesign(m.toDesign(), false));
            }
        }
    }

    // ─── Design library (playtest 2026-06-12: variants = designs the player made and saved,
    //     plus a file loader so designs can be shared between worlds) ───────────────────────

    /** The settlement's saved designs (server-synced; refreshed in place after save/delete). */
    private List<WallDesign> library = new ArrayList<>();
    /** Active design id per kind ordinal — "•" markers in the library list. */
    private final String[] activeIdsByKind = new String[3];
    /** Designs read from disk ({@code <gameDir>/bannerbound/wall_designs/*.nbt}). */
    private final List<WallDesign> fileDesigns = new ArrayList<>();
    @Nullable
    private EditBox nameBox;

    /** Server pushed a fresh designer payload (after save/delete): refresh the library list
     *  in place — models, drafts and camera stay untouched. */
    public void refreshLibrary(WallScreenPayloads.OpenWallDesigner payload) {
        this.library = new ArrayList<>(payload.library());
        for (WallDesign design : payload.activeSet()) {
            activeIdsByKind[design.kind().ordinal()] = design.id();
        }
    }

    private static java.nio.file.Path designsFolder() {
        return Minecraft.getInstance().gameDirectory.toPath()
            .resolve("bannerbound").resolve("wall_designs");
    }

    /** Re-reads every {@code .nbt} design in the folder; unreadable files are skipped. */
    private void rescanFileDesigns() {
        fileDesigns.clear();
        Minecraft mc = Minecraft.getInstance();
        if (mc.level == null) return;
        try {
            java.nio.file.Path dir = designsFolder();
            java.nio.file.Files.createDirectories(dir);
            var blocks = mc.level.registryAccess()
                .lookupOrThrow(net.minecraft.core.registries.Registries.BLOCK);
            try (var stream = java.nio.file.Files.list(dir)) {
                stream.filter(p -> p.getFileName().toString().endsWith(".nbt"))
                    .sorted()
                    .forEach(p -> {
                        try {
                            net.minecraft.nbt.CompoundTag tag = net.minecraft.nbt.NbtIo.read(p);
                            if (tag != null) fileDesigns.add(WallDesign.load(tag, blocks));
                        } catch (Exception ignored) {
                            // corrupt/foreign file — skip, never crash the designer
                        }
                    });
            }
        } catch (java.io.IOException ignored) {
        }
    }

    /** Writes the current tab's design to the designs folder (slugged name + kind). */
    private void exportCurrentTab() {
        try {
            WallDesign design = model().toDesign();
            java.nio.file.Path dir = designsFolder();
            java.nio.file.Files.createDirectories(dir);
            String fileName = clientSlug(design.name()) + "_"
                + design.kind().name().toLowerCase(Locale.ROOT) + ".nbt";
            net.minecraft.nbt.NbtIo.write(design.save(), dir.resolve(fileName));
            ClientWallStatus.set("Exported bannerbound/wall_designs/" + fileName, false);
            rescanFileDesigns();
        } catch (Exception e) {
            ClientWallStatus.set("Export failed: " + e.getMessage(), true);
        }
    }

    private static String clientSlug(String name) {
        String s = name.toLowerCase(Locale.ROOT)
            .replaceAll("[^a-z0-9]+", "_").replaceAll("^_+|_+$", "");
        return s.isEmpty() ? "design" : s;
    }

    /** Library + file designs matching the CURRENT tab's kind, in display order. */
    private List<WallDesign> visibleLibrary() {
        List<WallDesign> rows = new ArrayList<>();
        WallDesign.Kind kind = model().kind;
        for (WallDesign design : library) {
            if (design.kind() == kind) rows.add(design);
        }
        for (WallDesign design : fileDesigns) {
            if (design.kind() == kind) rows.add(design);
        }
        return rows;
    }

    /** True when the row index (into visibleLibrary) is a FILE design, not a library one. */
    private boolean isFileRow(int index) {
        int libraryCount = 0;
        WallDesign.Kind kind = model().kind;
        for (WallDesign design : library) {
            if (design.kind() == kind) libraryCount++;
        }
        return index >= libraryCount;
    }

    private void loadIntoTab(WallDesign design) {
        pushUndo();
        models[tab] = EditModel.of(design);
        if (nameBox != null) nameBox.setValue(models[tab].name);
        selection.clear();
        uiClick(net.minecraft.sounds.SoundEvents.NOTE_BLOCK_PLING.value(), 1.1f);
        ClientWallStatus.set("Loaded \"" + design.name() + "\" — Save & Set Active to apply.", false);
    }

    public void setParentScreen(@Nullable net.minecraft.client.gui.screens.Screen parent) {
        this.parentScreen = parent;
    }

    @Override
    public void onClose() {
        if (parentScreen != null && this.minecraft != null) {
            this.minecraft.setScreen(parentScreen);
            // The preview underneath shows design-derived data (gate spans) — refresh it.
            if (parentScreen instanceof WallPreviewScreen) {
                PacketDistributor.sendToServer(new WallScreenPayloads.RequestWallPreview());
            }
        } else {
            super.onClose();
        }
    }

    @Override
    public void removed() {
        // Autosave drafts into world data on EVERY way out of this screen — Escape, Close,
        // menu-bar navigation, the server force-opening another screen. removed() fires on
        // all of them; onClose alone missed setScreen() swaps (playtest 2026-06-12).
        for (EditModel m : models) {
            if (m != null) {
                PacketDistributor.sendToServer(
                    new WallScreenPayloads.SaveWallDesign(m.toDesign(), true));
            }
        }
        super.removed();
    }

    // ─── Layout ──────────────────────────────────────────────────────────────────────────────

    private int viewLeft() { return PANEL_LEFT_W + 4; }
    private int viewRight() { return this.width - PANEL_RIGHT_W - 4; }
    private int viewTop() { return 40; }
    private int viewBottom() { return this.height - 30; }
    private int viewCx() { return (viewLeft() + viewRight()) / 2; }
    private int viewCy() { return (viewTop() + viewBottom()) / 2; }

    @Override
    protected void init() {
        // 3D-software menu bar (File · Edit · View · Go) — also the navigation layer between
        // the wall menus (playtest 2026-06-12). Drafts autosave via removed() on any exit.
        menuBar = new WallMenuBar(this.font, 8, 4, List.of(
            new WallMenuBar.Menu("File", List.of(
                WallMenuBar.Item.of("Save & Set Active", this::saveAllDesigns),
                WallMenuBar.Item.of("Export Tab to File", this::exportCurrentTab),
                WallMenuBar.Item.of("Reload Design Files", () -> {
                    rescanFileDesigns();
                    ClientWallStatus.set(fileDesigns.size() + " design file(s) loaded.", false);
                }),
                WallMenuBar.Item.of("Open Designs Folder", () -> {
                    try {
                        java.nio.file.Files.createDirectories(designsFolder());
                        net.minecraft.Util.getPlatform().openUri(designsFolder().toUri());
                    } catch (Exception e) {
                        ClientWallStatus.set("Couldn't open folder: " + e.getMessage(), true);
                    }
                }),
                WallMenuBar.Item.of("Close", this::onClose))),
            new WallMenuBar.Menu("Edit", List.of(
                new WallMenuBar.Item("Undo  (Ctrl+Z)", this::undo,
                    () -> !undoStacks.get(tab).isEmpty()),
                new WallMenuBar.Item("Redo  (Ctrl+Shift+Z)", this::redo,
                    () -> !redoStacks.get(tab).isEmpty()),
                WallMenuBar.Item.of("Select All  (Ctrl+A)", () -> {
                    setMode(true);
                    EditModel m = model();
                    selection.clear();
                    for (int i = 0; i < m.cells.length; i++) {
                        if (m.cells[i] != null) selection.add(i);
                    }
                }),
                new WallMenuBar.Item("Deselect", selection::clear, () -> !selection.isEmpty()),
                new WallMenuBar.Item("Delete Selection  (Del)", this::deleteSelection,
                    () -> !selection.isEmpty()))),
            new WallMenuBar.Menu("View", List.of(
                WallMenuBar.Item.of("Reset Camera", () -> {
                    yaw = 45f;
                    pitch = 30f;
                    zoom = 18f;
                    panX = 0;
                    panY = 0;
                }))),
            new WallMenuBar.Menu("Go", List.of(
                WallMenuBar.Item.of("Wall Preview", () -> PacketDistributor.sendToServer(
                    new WallScreenPayloads.RequestWallPreview())),
                new WallMenuBar.Item("Back  (Esc)", this::onClose, () -> parentScreen != null)))));
        // Tabs.
        Component[] tabNames = {
            Component.translatable("bannerbound.wall_designer.tab.segment"),
            Component.translatable("bannerbound.wall_designer.tab.corner"),
            Component.translatable("bannerbound.wall_designer.tab.gate")};
        for (int i = 0; i < 3; i++) {
            final int index = i;
            addRenderableWidget(PolishButton.polished(tabNames[i], b -> {
                tab = index;
                hover = null;
                selection.clear(); // indices are per-model
                dragAxis = -1;
                if (nameBox != null) nameBox.setValue(model().name == null ? "" : model().name);
                uiClick(net.minecraft.sounds.SoundEvents.NOTE_BLOCK_HAT.value(), 1.0f);
            }).bounds(PANEL_LEFT_W + 8 + i * 92, 8, 88, 18).accent(primaryAccent()).build());
        }
        // Size controls (left panel): LEFT-click steps up, RIGHT-click steps down (user
        // request 2026-06-12 — sizes were increase-only and wrapped).
        addRenderableWidget(new StepButton(8, 40, PANEL_LEFT_W - 16, 18,
            sizeLabel("bannerbound.wall_designer.length", () -> model().length),
            () -> stepLength(false), () -> stepLength(true)));
        addRenderableWidget(new StepButton(8, 62, PANEL_LEFT_W - 16, 18,
            sizeLabel("bannerbound.wall_designer.depth", () -> model().depth),
            () -> stepDepth(false), () -> stepDepth(true)));
        addRenderableWidget(new StepButton(8, 84, PANEL_LEFT_W - 16, 18,
            sizeLabel("bannerbound.wall_designer.height", () -> model().height),
            () -> stepHeight(false), () -> stepHeight(true)));
        // Tool selects in the top bar (mirrors keybinds: B = build, V = select; K = eyedropper).
        int toolX = PANEL_LEFT_W + 8 + 3 * 92 + 12;
        modeButton = addRenderableWidget(PolishButton.polished(
            toolLabel("bannerbound.wall_designer.build", !selectMode), b -> setMode(false))
            .bounds(toolX, 8, 64, 18)
            .tooltip(net.minecraft.client.gui.components.Tooltip.create(
                Component.translatable("bannerbound.wall_designer.build.tooltip")))
            .accent(primaryAccent())
            .build());
        selectButton = addRenderableWidget(PolishButton.polished(
            toolLabel("bannerbound.wall_designer.select", selectMode), b -> setMode(true))
            .bounds(toolX + 68, 8, 64, 18)
            .tooltip(net.minecraft.client.gui.components.Tooltip.create(
                Component.translatable("bannerbound.wall_designer.select.tooltip")))
            .accent(primaryAccent())
            .build());
        // Toggles are CHECKBOXES now — toggle buttons read as "buttons thrown together"
        // (playtest 2026-06-12).
        addRenderableWidget(net.minecraft.client.gui.components.Checkbox.builder(
                Component.translatable("bannerbound.wall_designer.owned_only"), this.font)
            .pos(this.width - PANEL_RIGHT_W + 8, 42)
            .selected(onlyOwned)
            .onValueChange((cb, value) -> {
                onlyOwned = value;
                pickerScroll = 0;
                uiClick(net.minecraft.sounds.SoundEvents.NOTE_BLOCK_HAT.value(), value ? 1.2f : 0.8f);
            })
            .tooltip(net.minecraft.client.gui.components.Tooltip.create(
                Component.translatable("bannerbound.wall_designer.owned_only.tooltip")))
            .build());
        // (No foundation picker anymore — each column's BOTTOM BLOCK continues down to the
        // terrain automatically, playtest 2026-06-12.)
        addRenderableWidget(net.minecraft.client.gui.components.Checkbox.builder(
                Component.translatable("bannerbound.wall_designer.outside_label"), this.font)
            .pos(8, 112)
            .selected(showOutsideLabel)
            .onValueChange((cb, value) -> {
                showOutsideLabel = value;
                uiClick(net.minecraft.sounds.SoundEvents.NOTE_BLOCK_HAT.value(), value ? 1.2f : 0.8f);
            })
            .build());
        // Design NAME — the server keys library entries on its slug, so a new name saves a
        // NEW design (your variants: "2 steps", "3 steps", "10 steps"…).
        nameBox = new EditBox(this.font, 8, 162, PANEL_LEFT_W - 16, 14,
            Component.translatable("bannerbound.wall_designer.design_name"));
        nameBox.setMaxLength(32);
        nameBox.setValue(model().name == null ? "" : model().name);
        nameBox.setResponder(text -> model().name = text);
        nameBox.setTooltip(net.minecraft.client.gui.components.Tooltip.create(
            Component.translatable("bannerbound.wall_designer.design_name.tooltip")));
        addRenderableWidget(nameBox);
        addRenderableWidget(net.minecraft.client.gui.components.Checkbox.builder(
                Component.translatable("bannerbound.wall_designer.wall_context"), this.font)
            .pos(8, 131)
            .selected(contextWalls)
            .onValueChange((cb, value) -> {
                contextWalls = value;
                uiClick(net.minecraft.sounds.SoundEvents.NOTE_BLOCK_HAT.value(), value ? 1.2f : 0.8f);
            })
            .tooltip(net.minecraft.client.gui.components.Tooltip.create(
                Component.translatable("bannerbound.wall_designer.wall_context.tooltip")))
            .build());
        // Save / close (bottom). Saves ALL THREE tabs, not just the open one — "I did NOT
        // know save is only for the one I looked at" (playtest 2026-06-12); the server
        // validates and confirms each kind independently.
        addRenderableWidget(PolishButton.polished(
                Component.translatable("bannerbound.wall_designer.save_set_active"),
                b -> saveAllDesigns())
            .bounds(viewCx() - 102, this.height - 24, 130, 18)
            .tooltip(net.minecraft.client.gui.components.Tooltip.create(
                Component.translatable("bannerbound.wall_designer.save_set_active.tooltip")))
            .accent(primaryAccent())
            .build());
        addRenderableWidget(PolishButton.polished(
                Component.translatable("bannerbound.wall_designer.close"), b -> onClose())
            .bounds(viewCx() + 32, this.height - 24, 70, 18).accent(primaryAccent()).build());
        // Picker search.
        searchBox = new EditBox(this.font, this.width - PANEL_RIGHT_W + 8, 26, PANEL_RIGHT_W - 16, 14,
            Component.translatable("bannerbound.wall_designer.search"));
        searchBox.setResponder(text -> {
            search = text.toLowerCase(Locale.ROOT);
            pickerScroll = 0;
        });
        addRenderableWidget(searchBox);
    }

    private java.util.function.Supplier<Component> sizeLabel(String key, java.util.function.IntSupplier v) {
        return () -> Component.translatable(key, v.getAsInt());
    }

    /** Tool-select label: the "▶ " active marker stays a literal glyph, the word translates. */
    private static Component toolLabel(String key, boolean active) {
        Component word = Component.translatable(key);
        return active ? Component.literal("▶ ").append(word) : word;
    }

    /** 1px vertical identity edge along a panel border (neutral line when no identity). */
    private void identityEdge(GuiGraphics g, int x, int y0, int y1) {
        if (identityAccents.isEmpty()) {
            g.fill(x, y0, x + 1, y1, GuiPalette.PANEL_BORDER);
        } else {
            drawIdentityBorder(g, x, y0, 1, y1 - y0, identityAccents);
        }
    }

    private static int nextLength(int current) {
        for (int i = 0; i < LENGTHS.length; i++) {
            if (LENGTHS[i] == current) return LENGTHS[(i + 1) % LENGTHS.length];
        }
        return LENGTHS[0];
    }

    private static int prevLength(int current) {
        for (int i = 0; i < LENGTHS.length; i++) {
            if (LENGTHS[i] == current) return LENGTHS[(i + LENGTHS.length - 1) % LENGTHS.length];
        }
        return LENGTHS[0];
    }

    /** Corners aren't run-tiled — free 1..16 in steps of one (square: depth follows length);
     *  segments/gates stay on the 2/4/8/16 ladder. */
    private void stepLength(boolean back) {
        EditModel m = model();
        int next = m.kind == WallDesign.Kind.CORNER
            ? (back ? (m.length == 1 ? 16 : m.length - 1) : (m.length % 16) + 1)
            : (back ? prevLength(m.length) : nextLength(m.length));
        pushUndo();
        if (m.kind == WallDesign.Kind.CORNER) {
            m.resize(next, next, m.height);
        } else {
            m.resize(next, m.depth, m.height);
        }
        uiClick(net.minecraft.sounds.SoundEvents.NOTE_BLOCK_HAT.value(), back ? 0.9f : 1.1f);
    }

    private void stepDepth(boolean back) {
        EditModel m = model();
        if (m.kind == WallDesign.Kind.CORNER) return; // square footprint
        int next = back
            ? (m.depth <= 1 ? WallDesign.MAX_DEPTH : m.depth - 1)
            : (m.depth >= WallDesign.MAX_DEPTH ? 1 : m.depth + 1);
        pushUndo();
        m.resize(m.length, next, m.height);
        uiClick(net.minecraft.sounds.SoundEvents.NOTE_BLOCK_HAT.value(), back ? 0.9f : 1.1f);
    }

    private void stepHeight(boolean back) {
        EditModel m = model();
        int next = back
            ? (m.height <= 1 ? WallDesign.MAX_HEIGHT : m.height - 1)
            : (m.height >= WallDesign.MAX_HEIGHT ? 1 : m.height + 1);
        pushUndo();
        m.resize(m.length, m.depth, next);
        uiClick(net.minecraft.sounds.SoundEvents.NOTE_BLOCK_HAT.value(), back ? 0.9f : 1.1f);
    }

    /** Size stepper: LEFT-click steps forward, RIGHT-click steps BACK (playtest 2026-06-12);
     *  the label re-reads its supplier after every step. */
    private static final class StepButton extends PolishButton {
        private final java.util.function.Supplier<Component> label;
        private final Runnable forward;
        private final Runnable back;
        private int lastButton;

        StepButton(int x, int y, int w, int h, java.util.function.Supplier<Component> label,
                   Runnable forward, Runnable back) {
            super(x, y, w, h, label.get(), btn -> {}, Button.DEFAULT_NARRATION);
            this.label = label;
            this.forward = forward;
            this.back = back;
            setTooltip(net.minecraft.client.gui.components.Tooltip.create(
                Component.translatable("bannerbound.wall_designer.step.tooltip")));
        }

        @Override
        protected boolean isValidClickButton(int button) {
            return button == 0 || button == 1;
        }

        @Override
        public boolean mouseClicked(double mouseX, double mouseY, int button) {
            lastButton = button;
            return super.mouseClicked(mouseX, mouseY, button);
        }

        @Override
        public void onPress() {
            super.onPress(); // press pop animation; the OnPress handler is a no-op
            (lastButton == 1 ? back : forward).run();
            setMessage(label.get());
        }
    }

    // ─── Rendering ───────────────────────────────────────────────────────────────────────────

    @Override
    protected void renderPolishedBackdrop(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        // Panels + a single full-height center fill (margins/tab strip/hint band included —
        // partial fills left the world peeking through the seams, playtest 2026-06-11).
        g.fill(0, 0, PANEL_LEFT_W, this.height, 0xFF202024);
        g.fill(this.width - PANEL_RIGHT_W, 0, this.width, this.height, 0xFF202024);
        g.fill(PANEL_LEFT_W, 0, this.width - PANEL_RIGHT_W, this.height, 0xFF111116);
        // Identity chrome: the banner colors run down the panels' inner edges (the panels
        // themselves keep their tool-palette fills — this is an editor, not a Town Hall tab).
        identityEdge(g, PANEL_LEFT_W - 1, 0, this.height);
        identityEdge(g, this.width - PANEL_RIGHT_W, 0, this.height);
        // Inspector label sits below the File/Edit/View/Go menu bar (drawn in extras).
        g.drawString(this.font, "Inspector", 8, 24, 0xFFFFFF);
        g.drawString(this.font, "Block Picker", this.width - PANEL_RIGHT_W + 8, 8, 0xFFFFFF);

        hover = pick(mouseX, mouseY);
        renderViewport(g);
        // Rubber-band rectangle (screen space, raised above the blocks' depth range — drawn
        // at z 0 it depth-tested BEHIND the geometry, playtest 2026-06-12).
        g.pose().pushPose();
        g.pose().translate(0, 0, 700);
        if (rubberBanding) {
            int x0 = (int) Math.min(rubberX0, rubberX1);
            int x1 = (int) Math.max(rubberX0, rubberX1);
            int y0 = (int) Math.min(rubberY0, rubberY1);
            int y1 = (int) Math.max(rubberY0, rubberY1);
            g.fill(x0, y0, x1, y1, 0x3040A0FF);
            g.fill(x0, y0, x1, y0 + 1, 0xFF80C0FF);
            g.fill(x0, y1 - 1, x1, y1, 0xFF80C0FF);
            g.fill(x0, y0, x0 + 1, y1, 0xFF80C0FF);
            g.fill(x1 - 1, y0, x1, y1, 0xFF80C0FF);
        }
        g.pose().popPose();
        renderPicker(g, mouseX, mouseY);
        renderRequired(g);
        renderInspector(g);
    }

    @Override
    protected void renderPolishedExtras(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        g.drawCenteredString(this.font, selectMode
            ? "LMB select · Shift+LMB multi · drag empty = box-select · drag XYZ arrows = move · Del remove · B build mode"
            : "LMB place · Shift+LMB rotate · RMB remove · K sample block · V select mode · MMB orbit · Shift+MMB pan · scroll zoom",
            viewCx(), this.height - 40, 0xA0A0A0);
        ClientWallStatus.render(g, this.font, viewCx(), 32);
        if (menuBar != null) menuBar.render(g, mouseX, mouseY);
    }

    private void renderViewport(GuiGraphics g) {
        EditModel m = model();
        rebuildDisplaySceneIfNeeded();
        g.enableScissor(viewLeft(), viewTop(), viewRight(), viewBottom());
        PoseStack pose = g.pose();
        pose.pushPose();
        pose.translate(viewCx() + panX, viewCy() + panY, 400);
        // Squash SCREEN DEPTH only (position matrix, never the normal matrix — lighting stays
        // computed on true geometry). At zoom 30 with context ghosts the scene spanned ±960
        // GUI-z: blocks punched past the 600/700 overlay band and flirted with the clip
        // planes from some orbit angles ("faces inverted", playtest 2026-06-12). Squashed,
        // the whole scene stays inside ~400±130 with relative depth ordering untouched.
        pose.last().pose().scale(1f, 1f, DEPTH_SQUASH);
        pose.scale(zoom, -zoom, zoom);
        pose.mulPose(Axis.XP.rotationDegrees(pitch));
        pose.mulPose(Axis.YP.rotationDegrees(-yaw));
        pose.translate(-m.length / 2.0, -m.height / 2.0, -m.depth / 2.0);

        Minecraft mc = Minecraft.getInstance();
        MultiBufferSource.BufferSource buffers = mc.renderBuffers().bufferSource();

        // Blocks FIRST — lines drawn after depth-test correctly against them (drawn before,
        // the grid vanished through blocks / rendered scuffed, playtest 2026-06-11).
        // Entity-in-inventory lighting: the viewport's scale(zoom, -zoom, zoom) Y-flip
        // inverts normals, which made setupFor3DItems light every face from behind (near-
        // black blocks); this rig is built for exactly that flipped space.
        // No-cull render types: the mirror + GUI-ortho stack makes winding-based culling
        // unreliable from some orbit angles — draw both faces, depth test sorts them.
        com.mojang.blaze3d.platform.Lighting.setupForEntityInInventory();
        MultiBufferSource solid = type -> buffers.getBuffer(noCullBlockType(type, false));
        net.minecraft.core.BlockPos.MutableBlockPos sceneCursor =
            new net.minecraft.core.BlockPos.MutableBlockPos();
        long fxNow = net.minecraft.Util.getMillis();
        for (int l = 0; l < m.length; l++) {
            for (int d = 0; d < m.depth; d++) {
                for (int h = 0; h < m.height; h++) {
                    BlockState state = m.cells[m.idx(l, d, h)];
                    if (state == null) continue;
                    pose.pushPose();
                    pose.translate(l, h, d);
                    // Place pop: a just-placed block scales 0.6→1 around its center.
                    float pop = placePopScale(l, d, h, fxNow);
                    if (pop < 1f) {
                        pose.translate(0.5, 0.5, 0.5);
                        pose.scale(pop, pop, pop);
                        pose.translate(-0.5, -0.5, -0.5);
                    }
                    mc.getBlockRenderer().renderSingleBlock(
                        displayState(sceneCursor.set(l, h, d), state), pose, solid,
                        LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY);
                    pose.popPose();
                }
            }
        }
        // Wall-context ghosts (Corner/Gate tabs): the LIVE wall working copy continuing from
        // this piece, joined exactly as the layout engine joins them (gate: a segment on each
        // side; corner: the east run as-authored + the south run rotated CCW for the west
        // face). Translucent, outside the editable grid — not pickable, not in Required.
        // Connection-simulated like the authored cells, so fences/walls join ACROSS the seam.
        MultiBufferSource ghost = type -> new ContextGhostConsumer(
            buffers.getBuffer(noCullBlockType(type, true)));
        forEachContextGhost((pos, raw) -> ghostBlock(mc, pose, ghost,
            displayState(pos, raw), pos.getX(), pos.getY(), pos.getZ()));
        buffers.endBatch(); // flush block geometry before the line pass

        // Depth-tested guide geometry: grid floor, bounds posts and the red outward strip(s)
        // render BEHIND the model (always-on-top grid read as visual noise, playtest
        // 2026-06-12). Interactive overlays (hover/selection/gizmo) stay screen-space below.
        VertexConsumer lines3d = buffers.getBuffer(RenderType.lines());
        for (int l = 0; l <= m.length; l++) {
            line(pose, lines3d, l, 0, 0, l, 0, m.depth, 0.45f, 0.45f, 0.5f);
        }
        for (int d = 0; d <= m.depth; d++) {
            line(pose, lines3d, 0, 0, d, m.length, 0, d, 0.45f, 0.45f, 0.5f);
        }
        line(pose, lines3d, 0, 0, 0, 0, m.height, 0, 0.35f, 0.35f, 0.4f);
        line(pose, lines3d, m.length, 0, 0, m.length, m.height, 0, 0.35f, 0.35f, 0.4f);
        line(pose, lines3d, 0, 0, m.depth, 0, m.height, m.depth, 0.35f, 0.35f, 0.4f);
        line(pose, lines3d, m.length, 0, m.depth, m.length, m.height, m.depth, 0.35f, 0.35f, 0.4f);
        // Outward strip: -Z edge always; corners face TWO ways (north AND west), so they get
        // a second strip on the -X edge.
        line(pose, lines3d, 0, 0.02f, -0.25f, m.length, 0.02f, -0.25f, 0.95f, 0.25f, 0.25f);
        if (m.kind == WallDesign.Kind.CORNER) {
            line(pose, lines3d, -0.25f, 0.02f, 0, -0.25f, 0.02f, m.depth, 0.95f, 0.25f, 0.25f);
        }
        buffers.endBatch();
        com.mojang.blaze3d.platform.Lighting.setupForFlatItems();
        pose.popPose();

        // ─── 2D OVERLAY: interactive markers drawn screen-space ABOVE the blocks (hover,
        // selection, gizmo, labels) — these must never hide. Still scissored to the viewport.
        pose.pushPose();
        pose.translate(0, 0, 600); // above the block geometry's depth range
        if (showOutsideLabel) {
            double[] labelAt = localToScreen(m.length / 2.0, 0, -1.0, m);
            g.drawCenteredString(this.font, "OUTSIDE", (int) labelAt[0], (int) labelAt[1] - 4, 0xFFF24040);
            if (m.kind == WallDesign.Kind.CORNER) {
                double[] labelAt2 = localToScreen(-1.0, 0, m.depth / 2.0, m);
                g.drawCenteredString(this.font, "OUTSIDE", (int) labelAt2[0], (int) labelAt2[1] - 4, 0xFFF24040);
            }
        }

        // Place/break particle bursts — deterministic screen-space fragments flying out of
        // the cell, tinted by the block's map color, with a little gravity droop.
        long fxOverlayNow = net.minecraft.Util.getMillis();
        cellFx.removeIf(f -> fxOverlayNow - f.atMs() > FX_MS);
        for (CellFx f : cellFx) {
            float t = (fxOverlayNow - f.atMs()) / (float) FX_MS;
            double[] center = localToScreen(f.l() + 0.5, f.h() + 0.5, f.d() + 0.5, m);
            int rgb = f.color() & 0xFFFFFF;
            int a = Math.max(0, (int) ((1f - t) * 220f));
            int seed = f.l() * 31 + f.d() * 17 + f.h() * 7 + (int) (f.atMs() & 1023);
            for (int i = 0; i < 10; i++) {
                double ang = (seed * 0.7 + i) * (Math.PI * 2 / 10) + ((seed >> 3 & 7) * 0.1);
                double speed = (10 + ((seed + i * 7) % 9)) * (f.place() ? 0.8 : 1.2);
                double px = center[0] + Math.cos(ang) * speed * t;
                double py = center[1] + Math.sin(ang) * speed * t * 0.6 + 16 * t * t;
                g.fill((int) px, (int) py, (int) px + 2, (int) py + 2, (a << 24) | rgb);
            }
        }

        if (hover != null && !selectMode) {
            // Build hover: green placement box only; the white hit box appears while SHIFT is
            // held (the rotate gesture); red = face can't take a block.
            if (hover.filled()) {
                boolean canPlace = m.inBounds(hover.nl(), hover.nd(), hover.nh())
                    && m.get(hover.nl(), hover.nd(), hover.nh()) == null;
                if (hasShiftDown()) {
                    box2D(g, m, hover.l(), hover.h(), hover.d(), 0xFFFFFFFF, 1.5f);
                } else if (canPlace) {
                    box2D(g, m, hover.nl(), hover.nh(), hover.nd(), 0xFF4DE066, 1.5f);
                } else {
                    box2D(g, m, hover.l(), hover.h(), hover.d(), 0xFFF24D4D, 1.5f);
                }
            } else {
                box2D(g, m, hover.l(), hover.h(), hover.d(), 0xFF4DE066, 1.5f);
            }
        }
        if (selectMode) {
            // Drop indices invalidated by a resize or an external edit.
            selection.removeIf(i -> i < 0 || i >= m.cells.length || m.cells[i] == null);
            for (int idx : selection) {
                box2D(g, m, idxL(m, idx), idxH(m, idx), idxD(m, idx), 0xFFFFD940, 1.5f);
            }
            // Live preview of what a rubber-band release would select.
            if (rubberBanding) {
                double minX = Math.min(rubberX0, rubberX1);
                double maxX = Math.max(rubberX0, rubberX1);
                double minY = Math.min(rubberY0, rubberY1);
                double maxY = Math.max(rubberY0, rubberY1);
                for (int l = 0; l < m.length; l++) {
                    for (int d = 0; d < m.depth; d++) {
                        for (int h = 0; h < m.height; h++) {
                            if (m.cells[m.idx(l, d, h)] == null) continue;
                            double[] s = localToScreen(l + 0.5, h + 0.5, d + 0.5, m);
                            if (s[0] >= minX && s[0] <= maxX && s[1] >= minY && s[1] <= maxY) {
                                box2D(g, m, l, h, d, 0xFFB8A030, 1f);
                            }
                        }
                    }
                }
            } else if (hover != null && hover.filled()) {
                box2D(g, m, hover.l(), hover.h(), hover.d(), 0xFFE8E8E8, 1f);
            }
            // Move gizmo: thick 2D arrows (X red, Y green, Z blue) from the selection centroid.
            double[] c = selectionCentroid(m);
            if (c != null) {
                double[] base = localToScreen(c[0], c[1], c[2], m);
                int[] axisColors = {0xFFFF4545, 0xFF45E045, 0xFF5588FF};
                for (int axis = 0; axis < 3; axis++) {
                    double[] tip = gizmoTip(c, axis, m);
                    int color = dragAxis == axis ? 0xFFFFFFFF : axisColors[axis]; // grabbed = white
                    line2D(g, base, tip, color, 3f);
                    arrowhead2D(g, base, tip, color);
                }
            }
        }
        pose.popPose();
        g.disableScissor();
    }

    /**
     * Visits every wall-context ghost as (scene-local pos, state) — the SINGLE source of ghost
     * placement geometry, shared by rendering and the connection-sim scene so they can never
     * drift apart. Gate: a wall segment on each side along the run. Corner (authored as the
     * land's NW corner): the east run as-authored plus the south run rotated CCW for the west
     * face (engine: outward WEST = CCW_90, along = north, start at the south end — mirror of
     * placeCorner).
     */
    private void forEachContextGhost(java.util.function.BiConsumer<net.minecraft.core.BlockPos, BlockState> out) {
        if (!contextWalls || tab == 0 || models[0] == null) return;
        EditModel m = model();
        EditModel w = models[0];
        // First-wins dedup: a tiny corner with a deeper wall makes the two runs share a
        // column — emitting both would z-fight in the render and disagree with the sim map.
        java.util.HashSet<net.minecraft.core.BlockPos> emitted = new java.util.HashSet<>();
        java.util.function.BiConsumer<net.minecraft.core.BlockPos, BlockState> sink =
            (pos, state) -> {
                if (emitted.add(pos)) out.accept(pos, state);
            };
        for (int l = 0; l < w.length; l++) {
            for (int d = 0; d < w.depth; d++) {
                for (int h = 0; h < w.height; h++) {
                    BlockState state = w.cells[w.idx(l, d, h)];
                    if (state == null) continue;
                    if (tab == 2) {
                        sink.accept(new net.minecraft.core.BlockPos(l - w.length, h, d), state);
                        sink.accept(new net.minecraft.core.BlockPos(m.length + l, h, d), state);
                    } else {
                        sink.accept(new net.minecraft.core.BlockPos(m.length + l, h, d), state);
                        sink.accept(new net.minecraft.core.BlockPos(d, h, m.depth + (w.length - 1 - l)),
                            state.rotate(net.minecraft.world.level.block.Rotation.COUNTERCLOCKWISE_90));
                    }
                }
            }
        }
    }

    /** Cheap scene fingerprint — block states are canonical singletons, so identity hashes
     *  catch every paint/move/resize/undo without wiring a dirty flag through each edit site. */
    private int sceneKey() {
        EditModel m = model();
        int key = java.util.Objects.hash(tab, contextWalls, m.length, m.depth, m.height);
        key = 31 * key + java.util.Arrays.hashCode(m.cells);
        if (tab != 0 && contextWalls && models[0] != null) {
            EditModel w = models[0];
            key = 31 * key + java.util.Objects.hash(w.length, w.depth, w.height);
            key = 31 * key + java.util.Arrays.hashCode(w.cells);
        }
        return key;
    }

    /** Re-runs the connection simulation when the scene changed (authored cells + ghosts as
     *  ONE neighbor graph — fences join across the piece/wall seam, exactly like the layout). */
    private void rebuildDisplaySceneIfNeeded() {
        int key = sceneKey();
        if (displaySceneReady && key == displaySceneKey) return;
        displaySceneKey = key;
        displaySceneReady = true;
        EditModel m = model();
        java.util.Map<net.minecraft.core.BlockPos, BlockState> raw = new java.util.HashMap<>();
        for (int l = 0; l < m.length; l++) {
            for (int d = 0; d < m.depth; d++) {
                for (int h = 0; h < m.height; h++) {
                    BlockState state = m.cells[m.idx(l, d, h)];
                    if (state != null) raw.put(new net.minecraft.core.BlockPos(l, h, d), state);
                }
            }
        }
        forEachContextGhost(raw::put);
        net.minecraft.world.level.Level level = Minecraft.getInstance().level;
        displayScene = level == null ? raw
            : com.bannerbound.core.api.walls.WallConnectivity.simulate(raw, level, false);
    }

    /** Display state for a scene position: connected if the sim has one, raw otherwise. */
    private BlockState displayState(net.minecraft.core.BlockPos pos, BlockState raw) {
        BlockState display = displayScene.get(pos);
        return display == null || display.isAir() ? raw : display;
    }

    /**
     * Viewport block render types, decided per requested type: translucent stays translucent,
     * everything else renders cutout — both NO-CULL. The viewport's mirror + GUI-ortho stack
     * makes winding-based culling unreliable from some orbit angles ("inverted faces",
     * playtests 2026-06-11..12); drawing both faces costs nothing at GUI scale and the depth
     * test picks the right one. Entity sheet types accept block-model geometry (it's the same
     * path vanilla uses for block items in GUIs).
     */
    private static RenderType noCullBlockType(RenderType requested, boolean forceTranslucent) {
        boolean translucent = forceTranslucent
            || requested == RenderType.translucent()
            || requested == net.minecraft.client.renderer.Sheets.translucentCullBlockSheet()
            || requested == net.minecraft.client.renderer.Sheets.translucentItemSheet();
        return translucent
            ? RenderType.entityTranslucent(net.minecraft.world.inventory.InventoryMenu.BLOCK_ATLAS)
            : RenderType.entityCutoutNoCull(net.minecraft.world.inventory.InventoryMenu.BLOCK_ATLAS);
    }

    private static void ghostBlock(Minecraft mc, PoseStack pose, MultiBufferSource ghost,
                                   BlockState state, int x, int h, int z) {
        pose.pushPose();
        pose.translate(x, h, z);
        mc.getBlockRenderer().renderSingleBlock(state, pose, ghost,
            LightTexture.FULL_BRIGHT, OverlayTexture.NO_OVERLAY);
        pose.popPose();
    }

    /** Alpha-scaling consumer — the ghost-silhouette technique from the in-world preview. */
    private record ContextGhostConsumer(com.mojang.blaze3d.vertex.VertexConsumer delegate)
        implements com.mojang.blaze3d.vertex.VertexConsumer {
        @Override
        public com.mojang.blaze3d.vertex.VertexConsumer addVertex(float x, float y, float z) {
            delegate.addVertex(x, y, z);
            return this;
        }

        @Override
        public com.mojang.blaze3d.vertex.VertexConsumer setColor(int r, int g, int b, int a) {
            delegate.setColor(r, g, b, a * 110 / 255);
            return this;
        }

        @Override
        public com.mojang.blaze3d.vertex.VertexConsumer setUv(float u, float v) {
            delegate.setUv(u, v);
            return this;
        }

        @Override
        public com.mojang.blaze3d.vertex.VertexConsumer setUv1(int u, int v) {
            delegate.setUv1(u, v);
            return this;
        }

        @Override
        public com.mojang.blaze3d.vertex.VertexConsumer setUv2(int u, int v) {
            delegate.setUv2(u, v);
            return this;
        }

        @Override
        public com.mojang.blaze3d.vertex.VertexConsumer setNormal(float x, float y, float z) {
            delegate.setNormal(x, y, z);
            return this;
        }
    }

    /** Screen-space line via a rotated thin fill (GuiGraphics has no line primitive). */
    private static void line2D(GuiGraphics g, double[] a, double[] b, int color, float px) {
        double dx = b[0] - a[0];
        double dy = b[1] - a[1];
        double len = Math.hypot(dx, dy);
        if (len < 0.5) return;
        PoseStack pose = g.pose();
        pose.pushPose();
        pose.translate(a[0], a[1], 0);
        pose.mulPose(Axis.ZP.rotation((float) Math.atan2(dy, dx)));
        int half = Math.max(1, Math.round(px / 2f));
        g.fill(0, -half, (int) Math.ceil(len), half, color);
        pose.popPose();
    }

    /** Two short barbs at the tip, perpendicular-ish to the arrow direction. */
    private static void arrowhead2D(GuiGraphics g, double[] base, double[] tip, int color) {
        double dx = tip[0] - base[0];
        double dy = tip[1] - base[1];
        double len = Math.hypot(dx, dy);
        if (len < 1) return;
        double ux = dx / len;
        double uy = dy / len;
        // Barbs start slightly PAST the tip so the point itself renders solid.
        double[] tipE = {tip[0] + ux * 2, tip[1] + uy * 2};
        double bx = tip[0] - ux * 7;
        double by = tip[1] - uy * 7;
        line2D(g, tipE, new double[]{bx - uy * 4, by + ux * 4}, color, 3f);
        line2D(g, tipE, new double[]{bx + uy * 4, by - ux * 4}, color, 3f);
    }

    /** Cell wireframe drawn as 12 screen-space edges (always on top, never depth-hidden). */
    private void box2D(GuiGraphics g, EditModel m, double l, double h, double d, int color, float px) {
        double[][] c = new double[8][];
        for (int i = 0; i < 8; i++) {
            c[i] = localToScreen(l + ((i & 1) != 0 ? 1 : 0), h + ((i & 2) != 0 ? 1 : 0),
                d + ((i & 4) != 0 ? 1 : 0), m);
        }
        int[][] edges = {{0, 1}, {2, 3}, {4, 5}, {6, 7}, {0, 2}, {1, 3}, {4, 6}, {5, 7},
            {0, 4}, {1, 5}, {2, 6}, {3, 7}};
        for (int[] e : edges) {
            line2D(g, c[e[0]], c[e[1]], color, px);
        }
    }

    private static void line(PoseStack pose, VertexConsumer vc, float x0, float y0, float z0,
                             float x1, float y1, float z1, float r, float gr, float b) {
        org.joml.Matrix4f mat = pose.last().pose();
        org.joml.Vector3f n = new org.joml.Vector3f(x1 - x0, y1 - y0, z1 - z0).normalize();
        vc.addVertex(mat, x0, y0, z0).setColor(r, gr, b, 1f)
            .setNormal(pose.last(), n.x, n.y, n.z);
        vc.addVertex(mat, x1, y1, z1).setColor(r, gr, b, 1f)
            .setNormal(pose.last(), n.x, n.y, n.z);
    }

    private static void box(PoseStack pose, VertexConsumer vc, float x, float y, float z,
                            float r, float g, float b) {
        float e = 0.002f;
        float x0 = x - e, y0 = y - e, z0 = z - e, x1 = x + 1 + e, y1 = y + 1 + e, z1 = z + 1 + e;
        line(pose, vc, x0, y0, z0, x1, y0, z0, r, g, b);
        line(pose, vc, x0, y0, z1, x1, y0, z1, r, g, b);
        line(pose, vc, x0, y1, z0, x1, y1, z0, r, g, b);
        line(pose, vc, x0, y1, z1, x1, y1, z1, r, g, b);
        line(pose, vc, x0, y0, z0, x0, y0, z1, r, g, b);
        line(pose, vc, x1, y0, z0, x1, y0, z1, r, g, b);
        line(pose, vc, x0, y1, z0, x0, y1, z1, r, g, b);
        line(pose, vc, x1, y1, z0, x1, y1, z1, r, g, b);
        line(pose, vc, x0, y0, z0, x0, y1, z0, r, g, b);
        line(pose, vc, x1, y0, z0, x1, y1, z0, r, g, b);
        line(pose, vc, x0, y0, z1, x0, y1, z1, r, g, b);
        line(pose, vc, x1, y0, z1, x1, y1, z1, r, g, b);
    }

    private void renderPicker(GuiGraphics g, int mouseX, int mouseY) {
        List<Block> filtered = filteredBlocks();
        int x0 = this.width - PANEL_RIGHT_W + 8;
        int y0 = 62;
        int rows = (viewBottom() - y0) / SLOT;
        int start = pickerScroll * PICKER_COLS;
        for (int i = 0; i < rows * PICKER_COLS && start + i < filtered.size(); i++) {
            Block block = filtered.get(start + i);
            int x = x0 + (i % PICKER_COLS) * SLOT;
            int y = y0 + (i / PICKER_COLS) * SLOT;
            if (block == selectedBlock) {
                g.fill(x - 1, y - 1, x + 17, y + 17, 0xFF4080FF);
            } else if (mouseX >= x && mouseX < x + 16 && mouseY >= y && mouseY < y + 16) {
                g.fill(x - 1, y - 1, x + 17, y + 17, 0x60FFFFFF);
            }
            g.renderItem(new ItemStack(block), x, y);
        }
        g.drawString(this.font, "Picked: " + name(selectedBlock),
            x0, viewBottom() + 4, 0xC0C0C0);
    }

    private void renderRequired(GuiGraphics g) {
        EditModel m = model();
        Map<Item, Integer> counts = new LinkedHashMap<>();
        for (BlockState s : m.cells) {
            if (s != null) counts.merge(s.getBlock().asItem(), 1, Integer::sum);
        }
        int y = viewTop() + 2;
        g.drawString(this.font, "Required / piece:", PANEL_LEFT_W + 8, y, 0xFFD080);
        y += 11;
        int shown = 0;
        for (Map.Entry<Item, Integer> e : counts.entrySet()) {
            if (shown++ >= 6) break;
            g.drawString(this.font,
                e.getValue() + "× " + e.getKey().getDescription().getString(),
                PANEL_LEFT_W + 8, y, 0xC0C0C0);
            y += 10;
        }
    }

    /** Library list geometry — shared by render and click handling so they can't drift. */
    private static final int LIBRARY_TOP = 196;
    private static final int LIBRARY_ROW_H = 11;

    private void renderInspector(GuiGraphics g) {
        propertyRows.clear();
        EditModel m = model();
        g.drawString(this.font, "Name:", 8, 152, 0xFFD080);
        int y = 182;
        // Saved-design library (Build mode; Select mode uses this space for the inspector).
        if (!selectMode) {
            List<WallDesign> rows = visibleLibrary();
            g.drawString(this.font, "Library — click to load:", 8, LIBRARY_TOP - 12, 0xFFD080);
            if (rows.isEmpty()) {
                g.drawString(this.font, "(save designs by name)", 8, LIBRARY_TOP, 0x808088);
            }
            int maxRows = Math.max(0, (this.height - 40 - LIBRARY_TOP) / LIBRARY_ROW_H);
            String activeId = activeIdsByKind[m.kind.ordinal()];
            for (int i = 0; i < rows.size() && i < maxRows; i++) {
                WallDesign design = rows.get(i);
                int ry = LIBRARY_TOP + i * LIBRARY_ROW_H;
                boolean file = isFileRow(i);
                boolean active = !file && design.id().equals(activeId);
                String label = (active ? "• " : "") + design.name()
                    + " (" + design.length() + "×" + design.depth() + "×" + design.height() + ")"
                    + (file ? " [file]" : "");
                g.fill(6, ry - 1, PANEL_LEFT_W - 6, ry + LIBRARY_ROW_H - 2, 0x30FFFFFF);
                g.drawString(this.font, this.font.plainSubstrByWidth(label, PANEL_LEFT_W - 18),
                    8, ry, active ? 0xFFFFE060 : file ? 0xFFA8C8E0 : 0xFFE0E0E0);
            }
            if (!rows.isEmpty()) {
                g.drawString(this.font, "Shift+click = delete", 8, this.height - 38, 0x707078);
            }
            return;
        }
        // Select mode, single selection: EVERY blockstate property as a clickable row.
        if (selectMode && selection.size() == 1) {
            int idx = selection.iterator().next();
            BlockState state = idx >= 0 && idx < m.cells.length ? m.cells[idx] : null;
            if (state != null) {
                y += 26;
                g.drawString(this.font, "Selected:", 8, y, 0xFFD080);
                g.drawString(this.font, name(state.getBlock()) + " ("
                    + idxL(m, idx) + "," + idxH(m, idx) + "," + idxD(m, idx) + ")", 8, y + 10, 0xC0C0C0);
                y += 22;
                int i = 0;
                for (Property<?> p : state.getProperties()) {
                    g.fill(6, y - 1, PANEL_LEFT_W - 6, y + 9, 0x40FFFFFF);
                    g.drawString(this.font, p.getName() + " = "
                        + state.getValue(p).toString().toLowerCase(Locale.ROOT), 8, y, 0xE0E0E0);
                    propertyRows.add(new int[]{6, y - 1, PANEL_LEFT_W - 6, y + 9, i});
                    i++;
                    y += 12;
                }
                g.drawString(this.font, i == 0 ? "(no properties)" : "click a row to cycle",
                    8, y + 2, 0x808080);
                y += 12;
            }
            return; // selection details replace the hover readout
        }
        if (selectMode && selection.size() > 1) {
            y += 26;
            g.drawString(this.font, selection.size() + " blocks selected", 8, y, 0xFFD080);
            g.drawString(this.font, "Drag XYZ arrows to move,", 8, y + 12, 0x909090);
            g.drawString(this.font, "Delete to remove.", 8, y + 22, 0x909090);
            return;
        }
        if (hover != null && hover.filled()) {
            BlockState state = m.get(hover.l(), hover.d(), hover.h());
            if (state != null) {
                y += 26;
                g.drawString(this.font, "Hover:", 8, y, 0xFFD080);
                g.drawString(this.font, name(state.getBlock())
                    + " (" + hover.l() + "," + hover.h() + "," + hover.d() + ")", 8, y + 10, 0xC0C0C0);
                boolean canPlace = m.inBounds(hover.nl(), hover.nd(), hover.nh())
                    && m.get(hover.nl(), hover.nd(), hover.nh()) == null;
                y += 10;
                g.drawString(this.font, canPlace
                    ? "Place → (" + hover.nl() + "," + hover.nh() + "," + hover.nd() + ")"
                    : (!m.inBounds(hover.nl(), hover.nd(), hover.nh())
                        ? "Place: outside grid (raise Height/size?)"
                        : "Place: cell occupied"),
                    8, y + 10, canPlace ? 0x80FF80 : 0xFF8080);
                y += 10;
                int line = 0;
                for (Property<?> p : state.getProperties()) {
                    if (line++ >= 5) break;
                    g.drawString(this.font, p.getName() + " = "
                        + state.getValue(p).toString().toLowerCase(Locale.ROOT), 8, y + 20 + line * 10 - 10, 0x909090);
                }
            }
        }
        if (m.kind == WallDesign.Kind.GATE) {
            g.drawString(this.font, "Gate: needs a fence", 8, this.height - 56, 0x80FF80);
            g.drawString(this.font, "gate or door block.", 8, this.height - 46, 0x80FF80);
        }
    }

    private static String name(Block block) {
        return block.getName().getString();
    }

    private List<Block> filteredBlocks() {
        List<Block> source = onlyOwned ? allBlocks : knownBlocks; // allBlocks = owned subset
        if (search.isEmpty()) return source;
        List<Block> out = new ArrayList<>();
        for (Block block : source) {
            if (name(block).toLowerCase(Locale.ROOT).contains(search)
                || String.valueOf(BuiltInRegistries.BLOCK.getKey(block)).contains(search)) {
                out.add(block);
            }
        }
        return out;
    }

    // ─── Picking (inverse of the viewport transform + Amanatides–Woo DDA) ────────────────────

    @Nullable
    private Hit pick(int mouseX, int mouseY) {
        if (mouseX < viewLeft() || mouseX >= viewRight() || mouseY < viewTop() || mouseY >= viewBottom()) {
            return null;
        }
        EditModel m = model();
        double vx = (mouseX - viewCx() - panX) / zoom;
        double vy = -(mouseY - viewCy() - panY) / zoom;
        // Orthographic view ray, marched in fine steps. GUI space: HIGHER z is CLOSER to the
        // viewer (the camera looks along decreasing z), so the ray must start at +z and march
        // toward -z — cast the other way it hits the REARMOST block and offers the cell
        // behind it as the placement neighbour, which broke placement almost everywhere
        // (playtest 2026-06-11). The grid is ≤16³ and this runs once per frame.
        double[] origin = viewToLocal(vx, vy, 64, m);
        double[] far = viewToLocal(vx, vy, -64, m);
        double len = 128.0;
        double[] dir = {(far[0] - origin[0]) / len, (far[1] - origin[1]) / len, (far[2] - origin[2]) / len};

        // Exact floor-plane crossing (y = 0, descending): clicking the grid ground places at
        // height 0 — the bread-and-butter way to start a design on an empty grid.
        double floorT = Double.MAX_VALUE;
        int floorL = 0;
        int floorD = 0;
        if (dir[1] < -1.0e-6) {
            double t0 = (0 - origin[1]) / dir[1];
            int l = (int) Math.floor(origin[0] + dir[0] * t0);
            int d = (int) Math.floor(origin[2] + dir[2] * t0);
            if (t0 > 0 && l >= 0 && l < m.length && d >= 0 && d < m.depth
                && m.cells[m.idx(l, d, 0)] == null) {
                floorT = t0;
                floorL = l;
                floorD = d;
            }
        }

        // First FILLED cell along the ray; the placement neighbour is the previous sampled
        // cell REGARDLESS of bounds (clicking a boundary block's outer face yields an
        // out-of-grid neighbour, which the click handler correctly rejects — the old
        // "last in-bounds empty cell" rule silently broke every outer-face placement).
        int prevL = Integer.MIN_VALUE, prevD = Integer.MIN_VALUE, prevH = Integer.MIN_VALUE;
        for (double t = 0; t <= len; t += 0.02) {
            double px = origin[0] + dir[0] * t;
            double py = origin[1] + dir[1] * t;
            double pz = origin[2] + dir[2] * t;
            int l = (int) Math.floor(px);
            int h = (int) Math.floor(py);
            int d = (int) Math.floor(pz);
            if (l == prevL && d == prevD && h == prevH) continue;
            if (m.inBounds(l, d, h) && m.cells[m.idx(l, d, h)] != null) {
                if (floorT < t) {
                    return new Hit(floorL, floorD, 0, false, floorL, floorD, 0);
                }
                return new Hit(l, d, h, true, prevL, prevD, prevH);
            }
            prevL = l;
            prevD = d;
            prevH = h;
        }
        if (floorT < Double.MAX_VALUE) {
            return new Hit(floorL, floorD, 0, false, floorL, floorD, 0);
        }
        return null;
    }

    /** view (vx, vy, vz) → design-local coords: undo yaw/pitch and re-add the grid center. */
    private double[] viewToLocal(double vx, double vy, double vz, EditModel m) {
        double pr = Math.toRadians(pitch);
        double yr = Math.toRadians(-yaw);
        // Inverse pitch (rotX by -pitch).
        double y1 = vy * Math.cos(pr) + vz * Math.sin(pr);
        double z1 = -vy * Math.sin(pr) + vz * Math.cos(pr);
        // Inverse yaw (rotY by -(-yaw) = +yaw).
        double x2 = vx * Math.cos(yr) - z1 * Math.sin(yr);
        double z2 = vx * Math.sin(yr) + z1 * Math.cos(yr);
        return new double[]{x2 + m.length / 2.0, y1 + m.height / 2.0, z2 + m.depth / 2.0};
    }

    // ─── Input ───────────────────────────────────────────────────────────────────────────────

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        // Menu bar first — its dropdown overlays everything, including the viewport.
        if (menuBar != null && menuBar.mouseClicked(mouseX, mouseY, button)) return true;
        // Library rows (Build mode): click = load into this tab, Shift+click = delete.
        if (!selectMode && button == 0 && mouseX >= 6 && mouseX < PANEL_LEFT_W - 6
            && mouseY >= LIBRARY_TOP - 1) {
            List<WallDesign> rows = visibleLibrary();
            int index = (int) ((mouseY - (LIBRARY_TOP - 1)) / LIBRARY_ROW_H);
            int maxRows = Math.max(0, (this.height - 40 - LIBRARY_TOP) / LIBRARY_ROW_H);
            if (index >= 0 && index < rows.size() && index < maxRows) {
                WallDesign design = rows.get(index);
                if (hasShiftDown()) {
                    if (isFileRow(index)) {
                        ClientWallStatus.set(
                            "File designs live on disk — File → Open Designs Folder to remove.",
                            true);
                    } else {
                        PacketDistributor.sendToServer(
                            new WallScreenPayloads.DeleteWallDesign(design.id()));
                    }
                } else {
                    loadIntoTab(design);
                }
                return true;
            }
        }
        // Viewport clicks are handled BEFORE the widget pass: no widget overlaps the
        // viewport, and vanilla focus handling was eating LEFT clicks before they reached
        // the editor (right clicks worked because isValidClickButton only accepts button 0 —
        // which is exactly why remove worked while place didn't, playtest 2026-06-11).
        boolean inViewport = mouseX >= viewLeft() && mouseX < viewRight()
            && mouseY >= viewTop() && mouseY < viewBottom();
        if (inViewport && handleViewportClick(mouseX, mouseY, button)) {
            this.setFocused(null);
            return true;
        }
        if (super.mouseClicked(mouseX, mouseY, button)) return true;
        // Picker clicks.
        if (mouseX >= this.width - PANEL_RIGHT_W && mouseY >= 62 && mouseY < viewBottom()) {
            List<Block> filtered = filteredBlocks();
            int x0 = this.width - PANEL_RIGHT_W + 8;
            int col = (int) ((mouseX - x0) / SLOT);
            int row = (int) ((mouseY - 62) / SLOT);
            if (col >= 0 && col < PICKER_COLS) {
                int index = pickerScroll * PICKER_COLS + row * PICKER_COLS + col;
                if (index >= 0 && index < filtered.size()) {
                    selectedBlock = filtered.get(index);
                    selectedState = selectedBlock.defaultBlockState();
                    uiClick(net.minecraft.sounds.SoundEvents.NOTE_BLOCK_HAT.value(), 1.05f);
                    return true;
                }
            }
            return false;
        }
        // Inspector property rows (select mode, single selection): click cycles that property.
        if (mouseX < PANEL_LEFT_W && button == 0 && selectMode && selection.size() == 1) {
            for (int[] row : propertyRows) {
                if (mouseX >= row[0] && mouseX < row[2] && mouseY >= row[1] && mouseY < row[3]) {
                    int cellIdx = selection.iterator().next();
                    EditModel m = model();
                    BlockState state = m.cells[cellIdx];
                    if (state != null) {
                        int i = 0;
                        for (Property<?> p : state.getProperties()) {
                            if (i++ == row[4]) {
                                pushUndo();
                                m.cells[cellIdx] = state.cycle(p);
                                uiClick(net.minecraft.sounds.SoundEvents.NOTE_BLOCK_HAT.value(), 1.05f);
                                break;
                            }
                        }
                    }
                    return true;
                }
            }
        }
        return false;
    }

    private boolean handleViewportClick(double mouseX, double mouseY, int button) {
        if (button == 2) {
            // Blender-style: MMB orbits, Shift+MMB pans.
            if (hasShiftDown()) panning = true;
            else orbiting = true;
            return true;
        }
        EditModel m = model();
        if (selectMode) {
            if (button != 0) return true;
            // Gizmo arrows take priority over selection clicks.
            int axis = pickGizmoAxis(mouseX, mouseY);
            if (axis >= 0) {
                pushUndo();
                dragAxis = axis;
                dragAccum = 0;
                uiClick(net.minecraft.sounds.SoundEvents.NOTE_BLOCK_HAT.value(), 1.05f); // grab feedback
                return true;
            }
            Hit hit = pick((int) mouseX, (int) mouseY);
            if (hit != null && hit.filled()) {
                int idx = m.idx(hit.l(), hit.d(), hit.h());
                if (hasShiftDown()) {
                    if (!selection.remove(idx)) selection.add(idx);
                } else {
                    selection.clear();
                    selection.add(idx);
                }
                uiClick(net.minecraft.sounds.SoundEvents.NOTE_BLOCK_HAT.value(), 1.05f);
            } else {
                // Empty space: start a rubber-band drag select.
                rubberBanding = true;
                rubberX0 = rubberX1 = mouseX;
                rubberY0 = rubberY1 = mouseY;
            }
            return true;
        }
        Hit hit = pick((int) mouseX, (int) mouseY);
        if (hit == null) return button == 0 || button == 1; // consume, but nothing to do
        if (button == 1) {
            if (hit.filled()) {
                pushUndo();
                BlockState removed = m.cells[m.idx(hit.l(), hit.d(), hit.h())];
                m.cells[m.idx(hit.l(), hit.d(), hit.h())] = null;
                uiClick(removed.getSoundType().getBreakSound());
                spawnCellFx(hit.l(), hit.d(), hit.h(), removed, false);
            }
            return true;
        }
        if (button == 0) {
            if (hit.filled() && hasShiftDown()) {
                // Build-mode quick-cycle: ROTATION properties only (facing/axis/rotation) —
                // full blockstate editing lives in Select mode's inspector.
                BlockState state = m.cells[m.idx(hit.l(), hit.d(), hit.h())];
                for (Property<?> p : state.getProperties()) {
                    String pn = p.getName();
                    if ((pn.equals("facing") || pn.equals("axis") || pn.equals("rotation"))
                        && p.getPossibleValues().size() > 1) {
                        pushUndo();
                        m.cells[m.idx(hit.l(), hit.d(), hit.h())] = state.cycle(p);
                        uiClick(net.minecraft.sounds.SoundEvents.NOTE_BLOCK_HAT.value(), 1.15f);
                        break;
                    }
                }
                return true;
            }
            int placeL = hit.filled() ? hit.nl() : hit.l();
            int placeD = hit.filled() ? hit.nd() : hit.d();
            int placeH = hit.filled() ? hit.nh() : hit.h();
            if (m.inBounds(placeL, placeD, placeH) && m.get(placeL, placeD, placeH) == null) {
                pushUndo();
                // The face the player clicked drives orientation — the REAL placement code
                // path runs against a virtual level (torch → wall torch, slab halves,
                // stair facing; "ALL block states", playtest 2026-06-12).
                net.minecraft.core.Direction face = hit.filled()
                    ? net.minecraft.core.Direction.fromDelta(
                        hit.nl() - hit.l(), hit.nh() - hit.h(), hit.nd() - hit.d())
                    : net.minecraft.core.Direction.UP;
                if (face == null) face = net.minecraft.core.Direction.UP;
                BlockState placed = derivePlacementState(placeL, placeD, placeH, face);
                m.cells[m.idx(placeL, placeD, placeH)] = placed;
                uiClick(placed.getSoundType().getPlaceSound());
                spawnCellFx(placeL, placeD, placeH, placed, true);
            }
            return true;
        }
        return false;
    }

    // ─── Placement-state fidelity (playtest 2026-06-12) ─────────────────────────────────────

    private static java.lang.reflect.Field wallBlockField;

    /** {@code StandingAndWallBlockItem.wallBlock} — NeoForge runs mojmap names at runtime,
     *  so plain reflection holds in dev AND production. Null = fall back to standing. */
    @Nullable
    private static net.minecraft.world.level.block.Block wallBlockOf(
            net.minecraft.world.item.StandingAndWallBlockItem item) {
        try {
            if (wallBlockField == null) {
                wallBlockField = net.minecraft.world.item.StandingAndWallBlockItem.class
                    .getDeclaredField("wallBlock");
                wallBlockField.setAccessible(true);
            }
            return (net.minecraft.world.level.block.Block) wallBlockField.get(item);
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }

    /**
     * Runs the genuine {@code getStateForPlacement} code path against a {@link DesignerLevel}
     * backed by THIS tab's grid: the clicked face drives the context's "looking direction"
     * (the real player's camera is unrelated to the viewport), standing/wall item pairs try
     * the wall variant on horizontal faces exactly like vanilla right-click placement, and
     * canSurvive checks read the design's own blocks. K-sampled exact states still place
     * verbatim — that's the eyedropper contract.
     */
    private BlockState derivePlacementState(int pl, int pd, int ph,
                                            net.minecraft.core.Direction face) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return selectedState;
        if (selectedState != selectedBlock.defaultBlockState()) return selectedState;
        EditModel m = model();
        DesignerLevel vlevel = new DesignerLevel(mc.level, new DesignerLevel.Grid() {
            @Override
            @Nullable
            public BlockState get(net.minecraft.core.BlockPos pos) {
                return m.get(pos.getX(), pos.getZ(), pos.getY());
            }

            @Override
            public void set(net.minecraft.core.BlockPos pos, @Nullable BlockState state) {
                if (m.inBounds(pos.getX(), pos.getZ(), pos.getY())) {
                    m.cells[m.idx(pos.getX(), pos.getZ(), pos.getY())] = state;
                }
            }
        });
        net.minecraft.core.BlockPos pos = new net.minecraft.core.BlockPos(pl, ph, pd);
        // Click point on the shared plane between support and placement cell — gives slabs/
        // stairs a sane half (face center = bottom half on side clicks).
        net.minecraft.world.phys.Vec3 click = new net.minecraft.world.phys.Vec3(
            pl + 0.5 - face.getStepX() * 0.5,
            ph + 0.5 - face.getStepY() * 0.5,
            pd + 0.5 - face.getStepZ() * 0.5);
        net.minecraft.world.phys.BlockHitResult hitResult =
            new net.minecraft.world.phys.BlockHitResult(click, face, pos, false);
        net.minecraft.world.item.context.BlockPlaceContext ctx =
            new FaceFirstPlaceContext(vlevel, mc.player,
                new net.minecraft.world.item.ItemStack(selectedBlock), hitResult, face);
        BlockState derived = null;
        if (selectedBlock.asItem()
                instanceof net.minecraft.world.item.StandingAndWallBlockItem wallItem
            && face.getAxis().isHorizontal()) {
            net.minecraft.world.level.block.Block wall = wallBlockOf(wallItem);
            if (wall != null) {
                BlockState wallState = wall.getStateForPlacement(ctx);
                if (wallState != null && wallState.canSurvive(vlevel, pos)) {
                    derived = wallState;
                }
            }
        }
        if (derived == null) derived = selectedBlock.getStateForPlacement(ctx);
        return derived == null || derived.isAir() ? selectedState : derived;
    }

    /** Placement context whose "looking direction" is the CLICKED FACE, not the real player
     *  camera — directional blocks (torches, ladders, buttons, furnaces) orient to the face. */
    private static final class FaceFirstPlaceContext
        extends net.minecraft.world.item.context.BlockPlaceContext {
        private final net.minecraft.core.Direction face;

        FaceFirstPlaceContext(net.minecraft.world.level.Level level,
                              net.minecraft.world.entity.player.Player player,
                              net.minecraft.world.item.ItemStack stack,
                              net.minecraft.world.phys.BlockHitResult hit,
                              net.minecraft.core.Direction face) {
            super(level, player, net.minecraft.world.InteractionHand.MAIN_HAND, stack, hit);
            this.face = face;
        }

        @Override
        public net.minecraft.core.Direction getNearestLookingDirection() {
            return face.getOpposite();
        }

        @Override
        public net.minecraft.core.Direction[] getNearestLookingDirections() {
            net.minecraft.core.Direction primary = face.getOpposite();
            net.minecraft.core.Direction[] base = super.getNearestLookingDirections();
            net.minecraft.core.Direction[] out = new net.minecraft.core.Direction[base.length];
            out[0] = primary;
            int i = 1;
            for (net.minecraft.core.Direction d : base) {
                if (d != primary && i < out.length) out[i++] = d;
            }
            return out;
        }

        @Override
        public net.minecraft.core.Direction getHorizontalDirection() {
            return face.getAxis().isHorizontal() ? face.getOpposite()
                : super.getHorizontalDirection();
        }
    }

    private static void uiClick(net.minecraft.sounds.SoundEvent sound) {
        uiClick(sound, 1.0f);
    }

    private static void uiClick(net.minecraft.sounds.SoundEvent sound, float pitch) {
        Minecraft.getInstance().getSoundManager().play(
            net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(sound, pitch));
    }

    // ─── Select-mode helpers ─────────────────────────────────────────────────────────────────

    private int idxL(EditModel m, int idx) { return idx % m.length; }
    private int idxD(EditModel m, int idx) { return (idx / m.length) % m.depth; }
    private int idxH(EditModel m, int idx) { return idx / (m.length * m.depth); }

    /** Forward projection (mirror of the viewport pose): design-local point → screen px. */
    private double[] localToScreen(double x, double y, double z, EditModel m) {
        double px = x - m.length / 2.0;
        double py = y - m.height / 2.0;
        double pz = z - m.depth / 2.0;
        double b = Math.toRadians(-yaw);
        double x1 = px * Math.cos(b) + pz * Math.sin(b);
        double z1 = -px * Math.sin(b) + pz * Math.cos(b);
        double a = Math.toRadians(pitch);
        double y2 = py * Math.cos(a) - z1 * Math.sin(a);
        return new double[]{viewCx() + panX + zoom * x1, viewCy() + panY - zoom * y2};
    }

    @Nullable
    private double[] selectionCentroid(EditModel m) {
        if (selection.isEmpty()) return null;
        double sl = 0, sh = 0, sd = 0;
        for (int idx : selection) {
            sl += idxL(m, idx) + 0.5;
            sh += idxH(m, idx) + 0.5;
            sd += idxD(m, idx) + 0.5;
        }
        int n = selection.size();
        return new double[]{sl / n, sh / n, sd / n};
    }

    private static final double GIZMO_LEN = 1.8;

    /** Axis arrow under the mouse (0 = X/length, 1 = Y/height, 2 = Z/depth), or -1. */
    private int pickGizmoAxis(double mouseX, double mouseY) {
        EditModel m = model();
        double[] c = selectionCentroid(m);
        if (c == null) return -1;
        double[] base = localToScreen(c[0], c[1], c[2], m);
        int best = -1;
        double bestDist = 10.0; // generous — the arrows are 3px thick plus arrowheads
        for (int axis = 0; axis < 3; axis++) {
            double[] tip = gizmoTip(c, axis, m);
            // Extend the grab segment past the tip so the arrowhead itself is grabbable.
            double dx = tip[0] - base[0];
            double dy = tip[1] - base[1];
            double len = Math.hypot(dx, dy);
            if (len > 1.0e-3) {
                tip = new double[]{tip[0] + dx / len * 9, tip[1] + dy / len * 9};
            }
            double dist = pointToSegment2D(mouseX, mouseY, base[0], base[1], tip[0], tip[1]);
            if (dist < bestDist) {
                bestDist = dist;
                best = axis;
            }
        }
        return best;
    }

    private double[] gizmoTip(double[] c, int axis, EditModel m) {
        return localToScreen(c[0] + (axis == 0 ? GIZMO_LEN : 0),
            c[1] + (axis == 1 ? GIZMO_LEN : 0), c[2] + (axis == 2 ? GIZMO_LEN : 0), m);
    }

    private static double pointToSegment2D(double px, double py, double x0, double y0,
                                           double x1, double y1) {
        double dx = x1 - x0;
        double dy = y1 - y0;
        double lenSq = dx * dx + dy * dy;
        double t = lenSq == 0 ? 0 : Math.max(0, Math.min(1, ((px - x0) * dx + (py - y0) * dy) / lenSq));
        return Math.hypot(px - (x0 + t * dx), py - (y0 + t * dy));
    }

    /** Shift the whole selection one cell along an axis if every target is free/in-bounds. */
    private void tryMoveSelection(int axis, int dir) {
        EditModel m = model();
        Map<Integer, BlockState> moves = new LinkedHashMap<>();
        for (int idx : selection) {
            int l = idxL(m, idx) + (axis == 0 ? dir : 0);
            int h = idxH(m, idx) + (axis == 1 ? dir : 0);
            int d = idxD(m, idx) + (axis == 2 ? dir : 0);
            if (!m.inBounds(l, d, h)) return;
            int target = m.idx(l, d, h);
            if (m.cells[target] != null && !selection.contains(target)) return;
            moves.put(target, m.cells[idx]);
        }
        for (int idx : selection) m.cells[idx] = null;
        for (Map.Entry<Integer, BlockState> e : moves.entrySet()) m.cells[e.getKey()] = e.getValue();
        selection.clear();
        selection.addAll(moves.keySet());
        uiClick(net.minecraft.sounds.SoundEvents.NOTE_BLOCK_HAT.value(), 1.05f);
    }

    private void deleteSelection() {
        if (!selection.isEmpty()) pushUndo();
        EditModel m = model();
        for (int idx : selection) {
            BlockState gone = idx >= 0 && idx < m.cells.length ? m.cells[idx] : null;
            if (gone != null) {
                spawnCellFx(idxL(m, idx), idxD(m, idx), idxH(m, idx), gone, false);
            }
            if (idx >= 0 && idx < m.cells.length) m.cells[idx] = null;
        }
        if (!selection.isEmpty()) {
            uiClick(net.minecraft.sounds.SoundEvents.NOTE_BLOCK_HAT.value(), 1.05f);
        }
        selection.clear();
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (button == 2) {
            orbiting = false;
            panning = false;
        }
        if (button == 0) {
            dragAxis = -1;
            if (rubberBanding) {
                finishRubberBand();
            }
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }

    /** Selects every filled cell whose projected center lies inside the rubber rectangle. */
    private void finishRubberBand() {
        rubberBanding = false;
        EditModel m = model();
        double minX = Math.min(rubberX0, rubberX1);
        double maxX = Math.max(rubberX0, rubberX1);
        double minY = Math.min(rubberY0, rubberY1);
        double maxY = Math.max(rubberY0, rubberY1);
        if (maxX - minX < 3 && maxY - minY < 3) {
            // A click on empty space, not a drag — clear the selection.
            if (!hasShiftDown()) selection.clear();
            return;
        }
        if (!hasShiftDown()) selection.clear();
        for (int l = 0; l < m.length; l++) {
            for (int d = 0; d < m.depth; d++) {
                for (int h = 0; h < m.height; h++) {
                    if (m.cells[m.idx(l, d, h)] == null) continue;
                    double[] s = localToScreen(l + 0.5, h + 0.5, d + 0.5, m);
                    if (s[0] >= minX && s[0] <= maxX && s[1] >= minY && s[1] <= maxY) {
                        selection.add(m.idx(l, d, h));
                    }
                }
            }
        }
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dx, double dy) {
        if (button == 2) {
            if (panning) {
                panX -= (float) dx; // horizontal felt inverted grab-style (playtest 2026-06-12)
                panY += (float) dy;
                return true;
            }
            if (orbiting) {
                yaw += (float) dx * 0.8f;
                pitch = net.minecraft.util.Mth.clamp(pitch + (float) dy * 0.8f, 5f, 85f);
                return true;
            }
        }
        if (button == 0 && rubberBanding) {
            rubberX1 = mouseX;
            rubberY1 = mouseY;
            return true;
        }
        if (button == 0 && dragAxis >= 0 && !selection.isEmpty()) {
            // Project the mouse delta onto the dragged axis' screen direction; step the
            // selection one cell per cell-length of accumulated travel (snapped, like Axiom).
            EditModel m = model();
            double[] c = selectionCentroid(m);
            if (c == null) return true;
            double[] base = localToScreen(c[0], c[1], c[2], m);
            double[] tip = gizmoTip(c, dragAxis, m);
            double ax = tip[0] - base[0];
            double ay = tip[1] - base[1];
            double axisLen = Math.hypot(ax, ay);
            if (axisLen < 1.0e-3) return true;
            // Pixels of mouse travel along the axis, converted to cells (arrow = GIZMO_LEN cells).
            dragAccum += (dx * ax + dy * ay) / axisLen / (axisLen / GIZMO_LEN);
            while (dragAccum >= 1) {
                tryMoveSelection(dragAxis, 1);
                dragAccum -= 1;
            }
            while (dragAccum <= -1) {
                tryMoveSelection(dragAxis, -1);
                dragAccum += 1;
            }
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, dx, dy);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (mouseX >= this.width - PANEL_RIGHT_W) {
            int maxScroll = Math.max(0, (filteredBlocks().size() / PICKER_COLS) - 3);
            pickerScroll = net.minecraft.util.Mth.clamp(pickerScroll - (int) Math.signum(scrollY), 0, maxScroll);
            return true;
        }
        zoom = net.minecraft.util.Mth.clamp(zoom + (float) scrollY * 2f, 8f, 30f);
        return true;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (searchBox != null && searchBox.isFocused()) {
            return super.keyPressed(keyCode, scanCode, modifiers);
        }
        switch (keyCode) {
            case 65 -> {                                       // A orbit · Ctrl+A select all
                if (hasControlDown() && selectMode) {
                    EditModel m = model();
                    selection.clear();
                    for (int i = 0; i < m.cells.length; i++) {
                        if (m.cells[i] != null) selection.add(i);
                    }
                    uiClick(net.minecraft.sounds.SoundEvents.NOTE_BLOCK_HAT.value(), 1.05f);
                } else {
                    yaw -= 15f;
                }
                return true;
            }
            case 68 -> {                                       // D orbit · Ctrl+D deselect hovered
                if (hasControlDown() && selectMode) {
                    if (hover != null && hover.filled()) {
                        selection.remove(model().idx(hover.l(), hover.d(), hover.h()));
                        uiClick(net.minecraft.sounds.SoundEvents.NOTE_BLOCK_HAT.value(), 1.05f);
                    }
                } else {
                    yaw += 15f;
                }
                return true;
            }
            case 87 -> { pitch = Math.min(85f, pitch + 10f); return true; } // W
            case 83 -> { pitch = Math.max(5f, pitch - 10f); return true; }  // S
            case 66 -> { setMode(false); return true; }        // B = build
            case 86 -> { setMode(true); return true; }         // V = select
            case 75 -> {                                       // K = eyedropper (full state)
                if (hover != null && hover.filled()) {
                    BlockState sampled = model().get(hover.l(), hover.d(), hover.h());
                    if (sampled != null) {
                        selectedState = sampled;
                        selectedBlock = sampled.getBlock();
                        uiClick(net.minecraft.sounds.SoundEvents.NOTE_BLOCK_HAT.value(), 1.05f);
                    }
                }
                return true;
            }
            case 90 -> {                                       // Ctrl+Z / Ctrl+Shift+Z
                if (hasControlDown()) {
                    if (hasShiftDown()) redo();
                    else undo();
                    return true;
                }
            }
            case 261, 259 -> {                                 // Delete / Backspace
                if (selectMode && !selection.isEmpty()) {
                    deleteSelection();
                    return true;
                }
            }
            default -> { }
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    private void setMode(boolean select) {
        selectMode = select;
        selection.clear();
        dragAxis = -1;
        rubberBanding = false;
        if (modeButton != null) {
            modeButton.setMessage(toolLabel("bannerbound.wall_designer.build", !select));
        }
        if (selectButton != null) {
            selectButton.setMessage(toolLabel("bannerbound.wall_designer.select", select));
        }
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
