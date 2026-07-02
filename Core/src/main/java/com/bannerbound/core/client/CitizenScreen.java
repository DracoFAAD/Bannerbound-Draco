package com.bannerbound.core.client;

import java.util.List;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.network.ExileCitizenPayload;
import com.bannerbound.core.network.OpenCitizenScreenPayload;
import com.bannerbound.core.network.RelationshipEntry;
import com.bannerbound.core.network.ThoughtEntry;
import com.bannerbound.core.social.RelationshipTier;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.util.FormattedCharSequence;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.api.distmarker.OnlyIn;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Per-citizen detail screen opened by right-clicking a CitizenEntity. Two tabs:
 * <ul>
 *   <li><b>Info</b> — name + heart row + happiness + stamina + workstation/exile buttons.</li>
 *   <li><b>Relationships</b> — one row per other citizen this one has met, with a horizontal
 *       {@code -100..+100} bar (red on the left, green on the right) and the tier label.</li>
 * </ul>
 * Tab navigation lives at the top of the panel; the cancel button persists on both tabs.
 */
@OnlyIn(Dist.CLIENT)
@ApiStatus.Internal
public class CitizenScreen extends PolishedScreen {
    private static final int PANEL_WIDTH = 284;
    /** Bumped from 232 in Step 9 to make room for the Compliance bar + Resentment list
     *  below the Stamina row. Buttons shifted down by the same amount. */
    /** Tall enough that the Stamina row stays above the Assign / Exile / Cancel button stack
     *  even when the pregnancy block is showing (PREGNANT_SHIFT pushes Stamina down 14 px). */
    /** The Farmer is still the tallest Job tab: its content runs down to the seed-cache slot row
     *  (~panelY+169). After the ⚙ Options de-bloat the employed button stack is at most a primary
     *  (Change Job) + one 2-wide action row (Drop-off + Options), bottom-anchored, so the topmost
     *  action button sits at HEIGHT-76. 300 leaves the seed cache a comfortable gap above it (down
     *  from 344 when every set-and-forget action was a separate main-view button). */
    private static final int PANEL_HEIGHT = 300;

    /** Effective panel height: the design height clamped to the window (minus the bookmark-tab
     *  strip protruding above), so the panel never overflows short GUI areas (guiScale 4 on
     *  1080p leaves only 270px). Bottom-anchored rows ride this instead of the raw constant. */
    private int panelHeight() {
        return Math.min(PANEL_HEIGHT, this.height - 8 - BookmarkTab.HEIGHT);
    }

    /** Panel top edge: centered together with the tab strip as one block (TownHall pattern),
     *  so the tabs protruding above the panel always stay on-screen. */
    private int panelTop() {
        return (this.height - panelHeight() + BookmarkTab.HEIGHT) / 2;
    }

    // Vanilla HUD heart sprites — same artwork the player health bar uses.
    private static final ResourceLocation HEART_FULL_SPRITE =
        ResourceLocation.withDefaultNamespace("hud/heart/full");
    private static final ResourceLocation HEART_HALF_SPRITE =
        ResourceLocation.withDefaultNamespace("hud/heart/half");
    // Vanilla 1.21.1 calls the empty-heart sprite "container"; "empty" doesn't exist and rendered
    // as the missing-texture purple-and-black checkerboard.
    private static final ResourceLocation HEART_EMPTY_SPRITE =
        ResourceLocation.withDefaultNamespace("hud/heart/container");

    /** Relationship-bar colors. Red half on negative side, green on positive — same hex the
     *  vanilla horse-bond / villager-popularity HUDs use, picked for contrast on a dark panel. */
    private static final int BAR_RED   = 0xFFE57761;
    private static final int BAR_GREEN = 0xFF2EB872;
    /** White marker dot showing the citizen's current score along the bar. */
    private static final int BAR_DOT   = 0xFFFFFFFF;

    /** Matches the TownHall statuses tab so the two list styles read identically. */
    private static final int THOUGHT_ROW_HEIGHT = 26;

    // ── Happiness bar palette ──────────────────────────────────────────────────────────────────
    // Segment thresholds match the ones Icons.happiness() already uses to pick the face glyph
    // — bar colour and face colour stay in agreement (red frown sits in the red region, etc.).
    private static final float HAPPINESS_MID_RATIO  = 0.40f;
    private static final float HAPPINESS_HIGH_RATIO = 0.70f;
    private static final int HAPPINESS_RED    = 0xFFE57761;
    private static final int HAPPINESS_YELLOW = 0xFFE9D24A;
    private static final int HAPPINESS_GREEN  = 0xFF2EB872;
    private static final int HAPPINESS_BAR_WIDTH = 160;
    private static final int HAPPINESS_BAR_HEIGHT = 6;
    /** Vertical shift applied to Happiness + Stamina when the citizen is pregnant — makes room
     *  for the "Pregnant" label + pink progress bar between Health and Happiness. */
    private static final int PREGNANT_SHIFT = 14;

    private enum Tab { INFO, RELATIONSHIPS, THOUGHTS, JOB }

    // ── Job tab state (from CitizenJobStatePayload; refreshed on the 1 Hz live poll) ─────────────
    private boolean jobStateReceived = false;
    private boolean jobCanManage = false;
    /** Stocker only: the trade-courier opt-in (synced; toggled optimistically on click). */
    private boolean jobTradingCourier = false;
    /** "" = unemployed. */
    private String jobTypeId = "";
    private boolean jobHasTool = false;
    private net.minecraft.world.item.ItemStack jobToolIcon = net.minecraft.world.item.ItemStack.EMPTY;
    /** Item id of the current job's icon (settlement's current tool-age tool for the job). */
    private int jobIconItemId = 0;
    private net.minecraft.world.level.block.Block jobPreferredLog =
        net.minecraft.world.level.block.Blocks.OAK_LOG;
    private boolean jobDropOffSet = false;
    private boolean jobSeedSourceSet = false;
    /** Settlement is in anarchy: the citizen self-organized into its job; the player may only request
     *  a switch to another gatherer job (compliance-gated) and set the drop-off — not freely
     *  assign/unassign. */
    private boolean jobAnarchy = false;
    /** Forester only: whether canopy saplings/apples/sticks are stored (true) or only logs are kept. */
    private boolean jobForesterKeepExtras = true;
    /** True when the player pinned this citizen's job (manual override; the labor distributor leaves
     *  them alone). Cleared by Unassign under a government. */
    private boolean jobPinned = false;
    /** True while the citizen is under a recent "won't work as that" refusal — the "Request switch"
     *  button stays greyed until it lapses so re-requests can't be spammed. */
    private boolean jobSwitchRefused = false;
    /** Live work-status verdict for the headline (derived server-side or goal-published). */
    private com.bannerbound.core.entity.CitizenWorkStatus jobWorkStatus =
        com.bannerbound.core.entity.CitizenWorkStatus.IDLE;
    /** Forester only: Silviculture researched, so the Options panel offers "Select plantation area". */
    private boolean jobPlantationUnlocked = false;
    /** Forager only: bitmask of categories the player has switched on, and of those the settlement
     *  has researched (the rest render LOCKED). */
    private int forageEnabledBits = 0;
    private int forageUnlockedBits = 0;
    /** Farmer only: the buffered seeds in the seed cache (read-only display). */
    private List<net.minecraft.world.item.ItemStack> seedCache = java.util.List.of();
    private List<String> jobUnlocked = java.util.List.of();
    /** Parallel to {@link #jobUnlocked}: each unlocked job's icon item id. */
    private List<Integer> jobUnlockedIcons = java.util.List.of();
    /** Item ids of the axes the tool slot will accept (current tool age or lower). */
    private java.util.Set<Integer> allowedToolItemIds = java.util.Set.of();
    // Quarryworker second (pickaxe) slot.
    private boolean jobPickaxeUnlocked = false;
    private boolean jobHasPickaxe = false;
    private net.minecraft.world.item.ItemStack jobPickaxeIcon = net.minecraft.world.item.ItemStack.EMPTY;
    private java.util.Set<Integer> allowedPickaxeItemIds = java.util.Set.of();
    /** Which slot the open TOOL_PICK overlay is filling: true = pickaxe (2nd) slot, false = primary. */
    private boolean toolPickPickaxe = false;
    /** Which Job-tab overlay (job picker / log picker / tool picker) is currently open. */
    private enum JobOverlay { NONE, JOB_PICK, LOG_PICK, TOOL_PICK, FORAGE_PICK, PREY_PICK, SETTINGS }

    /** One row of the ⚙ Options overlay: a set-and-forget action folded off the main view to keep
     *  it un-bloated. {@code enabled=false} greys the row (e.g. no Foreman's Rod in hand) and shows
     *  {@code tooltip}; the action fires the same payload the old main-view button did. */
    private record JobOption(Component label, Runnable action, boolean enabled, Component tooltip) {}
    private final java.util.List<JobOption> settingsOptions = new java.util.ArrayList<>();
    private JobOverlay jobOverlay = JobOverlay.NONE;
    private int jobOverlayScroll = 0;
    /** Hunter only: entity-type ids the player switched OFF in the prey picker (server-synced;
     *  everything in the huntable tag defaults ON). */
    private java.util.Set<String> hunterPreyOff = new java.util.HashSet<>();
    /** The huntable species rows for the prey picker — read from the (client-synced)
     *  {@code #bannerbound:huntable} tag when the overlay opens, sorted by id for a stable order. */
    private final java.util.List<net.minecraft.world.entity.EntityType<?>> preyList = new java.util.ArrayList<>();
    /** Cached valid log blocks for the preferred-wood picker (built lazily). */
    private final java.util.List<net.minecraft.world.level.block.Block> validLogs = new java.util.ArrayList<>();
    /** Player-inventory axe slots for the tool picker, rebuilt when the overlay opens.
     *  Each entry is the inventory slot index. */
    private final java.util.List<Integer> axeSlots = new java.util.ArrayList<>();

    private final int entityId;
    private final Component citizenName;
    private final int happinessMax;
    private final boolean canModify;
    private final List<RelationshipEntry> relationships;
    /** Frozen-at-open snapshot of the citizen's active thoughts. Thoughts decay slowly enough
     *  that re-fetching on every frame would be wasteful — re-open the screen to refresh. */
    private final List<ThoughtEntry> thoughts;
    /** Step 9: compliance + the viewer's own resentment (server-filtered for privacy).
     *  Updated live by a 1-Hz polling request fired from {@link #tick()} — see
     *  {@link #applyLiveState(com.bannerbound.core.network.CitizenLiveStatePayload)}.
     *  Initial values come from the open payload so the first render frame already has
     *  something to show. */
    private int compliance;
    private int viewerResentment;
    /** Counter so the 1-Hz refresh doesn't fire every tick. */
    private int liveStateTickCounter = 0;

    // Live-read stats: refreshed each render frame from the entity via SynchedEntityData / vanilla
    // health sync. We cache the last good values so the panel doesn't blank when the citizen
    // briefly leaves view distance.
    private float lastHealth;
    private float lastMaxHealth;
    private int lastStamina;
    private int lastStaminaMax;
    private int lastHappiness;
    /** Live pregnancy flag — read from the entity's synched data every render frame. The Info
     *  tab inserts a pink "Pregnant" line between Health and Happiness when this is true. */
    private boolean lastPregnant;
    /** Game-tick at which the pregnancy started, or {@code -1L} if not pregnant. Synced from
     *  the entity — paired with the live world game-time to render the pregnancy progress bar. */
    private long lastPregnantSinceTick = -1L;

    private Tab activeTab = Tab.INFO;
    /** Vertical scroll offset (pixels) for the Relationships list. Clamped on render. */
    private int relScroll = 0;
    /** Vertical scroll offset (pixels) for the Thoughts list. Clamped on render. */
    private int thoughtScroll = 0;

    public CitizenScreen(OpenCitizenScreenPayload payload) {
        this(payload, false);
    }

    public CitizenScreen(OpenCitizenScreenPayload payload, boolean openJobTab) {
        super(payload.name());
        this.entityId = payload.entityId();
        this.citizenName = payload.name();
        this.happinessMax = payload.happinessMax();
        this.canModify = payload.canModify();
        this.relationships = payload.relationships();
        this.thoughts = payload.thoughts();
        this.compliance = payload.compliance();
        this.viewerResentment = payload.viewerResentment();
        // Seed the cache with the packet snapshot so the first render frame has something to show
        // before the live lookup runs.
        this.lastHealth = payload.currentHealth();
        this.lastMaxHealth = payload.maxHealth();
        this.lastStamina = payload.stamina();
        this.lastStaminaMax = payload.staminaMax();
        this.lastHappiness = payload.happiness();
        if (openJobTab) this.activeTab = Tab.JOB;
    }

    public int entityId() {
        return entityId;
    }

    public boolean jobTabActive() {
        return activeTab == Tab.JOB;
    }

    /** Apply a server-sent live update for compliance + resentment. Called from the network
     *  handler; the request that triggers it is fired from {@link #tick()} once a second.
     *  Returning quickly when entityId doesn't match keeps stray updates from a previously
     *  open citizen screen from clobbering the current one. */
    public void applyLiveState(com.bannerbound.core.network.CitizenLiveStatePayload payload) {
        if (payload.entityId() != this.entityId) return;
        this.compliance = payload.compliance();
        this.viewerResentment = payload.viewerResentment();
    }

    @Override
    public void tick() {
        super.tick();
        // 1 Hz refresh — cheap; one VAR_INT to the server, ~20 bytes back. Cheaper than
        // adding compliance/resentment to SynchedEntityData since we only pay when a screen
        // is actually open.
        if (liveStateTickCounter++ % 20 == 0) {
            net.neoforged.neoforge.network.PacketDistributor.sendToServer(
                new com.bannerbound.core.network.RequestCitizenLiveStatePayload(entityId));
        }
    }

    /** Refreshes {@link #lastHealth}/{@link #lastStamina}/{@link #lastHappiness} from the live
     *  entity if the client has it loaded. Called once per render frame so the panel updates
     *  while open (health regen, stamina drain, happiness shifts from incoming thoughts). */
    private void refreshFromEntity() {
        if (this.minecraft == null || this.minecraft.level == null) return;
        net.minecraft.world.entity.Entity e = this.minecraft.level.getEntity(entityId);
        if (e instanceof com.bannerbound.core.entity.CitizenEntity c) {
            lastHealth = c.getHealth();
            lastMaxHealth = c.getMaxHealth();
            lastStamina = c.getStamina();
            lastStaminaMax = c.getStaminaMax();
            lastHappiness = c.getHappiness();
            lastPregnant = c.isPregnant();
            lastPregnantSinceTick = c.getPregnantSinceTick();
        }
    }

    @Override
    protected void init() {
        layoutForActiveTab();
    }

    /** Clears existing widgets and rebuilds them for the current {@link #activeTab}. Called
     *  initially and on every tab switch. (Named to not shadow {@code Screen.rebuildWidgets()}.) */
    private void layoutForActiveTab() {
        this.clearWidgets();
        final int panelX = (this.width - PANEL_WIDTH) / 2;
        final int panelY = panelTop();
        final int btnWidth = PANEL_WIDTH - 24;

        // ── Panel chrome + tab strip (drawn every frame for both tabs) ─────────────────────────
        this.addRenderableOnly((graphics, mouseX, mouseY, partialTick) -> {
            drawIdentityPanel(graphics, panelX, panelY, PANEL_WIDTH, panelHeight(), identityAccents);
            // Centered citizen name — rendered as the styled Component so the gender glyph
            // (custom-font) and settlement-color tint survive. Vertically centered in the
            // header block now that the tabs live above the panel as bookmarks.
            graphics.drawCenteredString(this.font, citizenName,
                panelX + PANEL_WIDTH / 2, panelY + 20, 0xFFFFFFFF);
            // Header divider — the standard identity gradient (TownHall treatment).
            drawIdentityDivider(graphics, panelX + 8, panelY + 48, PANEL_WIDTH - 16, identityAccents);
        });

        // ── Bookmark tabs (protrude above the panel's top edge — the house tab style) ──────────
        // Children have no Job tab (they can't be employed), so the strip is 3 tabs for them, 4 for
        // adults.
        boolean showJob = !isCitizenChild();
        if (activeTab == Tab.JOB && !showJob) activeTab = Tab.INFO;
        final java.util.List<Tab> tabOrder = new java.util.ArrayList<>(
            java.util.List.of(Tab.INFO, Tab.RELATIONSHIPS, Tab.THOUGHTS));
        if (showJob) tabOrder.add(Tab.JOB);
        java.util.List<Component> tabLabels = new java.util.ArrayList<>(tabOrder.size());
        for (Tab t : tabOrder) {
            tabLabels.add(Component.translatable(switch (t) {
                case INFO          -> "bannerbound.citizen.tab.info";
                case RELATIONSHIPS -> "bannerbound.citizen.tab.relationships";
                case THOUGHTS      -> "bannerbound.citizen.tab.thoughts";
                case JOB           -> "bannerbound.citizen.tab.job";
            }));
        }
        BookmarkTab.addRow(this::addRenderableWidget, panelX, PANEL_WIDTH, panelY,
            tabLabels, tabOrder.indexOf(activeTab), primaryAccent(), secondaryAccent(), i -> {
                Tab tab = tabOrder.get(i);
                if (activeTab != tab) { activeTab = tab; jobOverlay = JobOverlay.NONE; rebuildWidgets(); }
            });

        switch (activeTab) {
            case INFO          -> buildInfoTab(panelX, panelY, btnWidth);
            case RELATIONSHIPS -> buildRelationshipsTab(panelX, panelY);
            case THOUGHTS      -> buildThoughtsTab(panelX, panelY);
            case JOB           -> buildJobTab(panelX, panelY, btnWidth);
        }

        // Cancel button persists across tabs.
        this.addRenderableWidget(PolishButton.polished(
            Component.translatable("gui.cancel"),
            btn -> this.onClose())
            .bounds(panelX + 12, panelY + panelHeight() - 28, btnWidth, 20)
            .accent(primaryAccent())
            .build());
    }

    /** True if the viewed citizen is a child (read live from the entity). Children can't be
     *  employed, so they get no Job tab. */
    private boolean isCitizenChild() {
        if (this.minecraft != null && this.minecraft.level != null
            && this.minecraft.level.getEntity(entityId)
                instanceof com.bannerbound.core.entity.CitizenEntity c) {
            return c.isChild();
        }
        return false;
    }

    private void buildInfoTab(int panelX, int panelY, int btnWidth) {
        this.addRenderableOnly((graphics, mouseX, mouseY, partialTick) -> {
            int statsX = panelX + 14;
            int statsY = panelY + 60;
            drawHeartRow(graphics, statsX, statsY, "bannerbound.citizen.health",
                lastHealth, lastMaxHealth);
            // "Pregnant" label + a pink progress bar showing how far along she is. The bar
            // reads {@code (now - pregnantSinceTick) / PREGNANCY_DURATION_TICKS}, fills left
            // to right, refreshes live every frame (synced data carries the start tick). When
            // pregnant the Happiness + Stamina rows shift down by PREGNANT_SHIFT to make room
            // for the label + bar; when not pregnant the layout is unchanged.
            int shift = lastPregnant ? PREGNANT_SHIFT : 0;
            if (lastPregnant) {
                Component label = Component.translatable("bannerbound.citizen.pregnant")
                    .withStyle(ChatFormatting.LIGHT_PURPLE);
                graphics.drawCenteredString(this.font, label,
                    panelX + PANEL_WIDTH / 2, statsY + 12, 0xFFFFB1FF);
                int barW = HAPPINESS_BAR_WIDTH;
                int barX = panelX + PANEL_WIDTH / 2 - barW / 2;
                int barY = statsY + 24;
                int barH = 4;
                // Dark track + outline so the bar reads on the panel background.
                graphics.fill(barX, barY, barX + barW, barY + barH, 0xFF2A1A22);
                graphics.renderOutline(barX - 1, barY - 1, barW + 2, barH + 2, 0xFF000000);
                if (lastPregnantSinceTick > 0L
                    && this.minecraft != null && this.minecraft.level != null) {
                    long now = this.minecraft.level.getGameTime();
                    long elapsed = Math.max(0L, now - lastPregnantSinceTick);
                    long total = com.bannerbound.core.entity.CitizenEntity.PREGNANCY_DURATION_TICKS;
                    if (elapsed > total) elapsed = total;
                    int fill = (int) ((long) barW * elapsed / total);
                    graphics.fill(barX, barY, barX + fill, barY + barH, 0xFFFF7AC8);
                }
            }
            // Happiness gets a centered title + segmented bar block instead of a one-line row
            // so it reads as the citizen's primary mood, not just another stat. Takes ~30 px
            // vertical; stamina row slides down to compensate.
            Component happinessTip = drawHappinessBlock(graphics, panelX + PANEL_WIDTH / 2,
                statsY + 24 + shift, HAPPINESS_BAR_WIDTH, lastHappiness, happinessMax, mouseX, mouseY);
            drawStaminaRow(graphics, statsX, statsY + 64 + shift, "bannerbound.citizen.stamina",
                lastStamina, lastStaminaMax);
            // Step 9 rows. drawStaminaRow's three-tone palette works for compliance too —
            // green/yellow/red read "obedient/wavering/insubordinate" without retuning.
            drawStaminaRow(graphics, statsX, statsY + 80 + shift, "bannerbound.citizen.compliance",
                compliance, 100);
            // Resentment toward the viewing player only. Same bar shape, inverted palette
            // (high = red = "they hate you"). Clamped to 100 for the bar display since
            // resentment past 100 only deepens the same "hostile" reading.
            drawInvertedBar(graphics, statsX, statsY + 96 + shift,
                "bannerbound.citizen.resentment", Math.min(100, viewerResentment), 100);
            // Happiness explainer — drawn last so it floats above every row it might overlap.
            if (happinessTip != null) {
                graphics.renderTooltip(this.font, this.font.split(happinessTip, 200), mouseX, mouseY);
            }
        });

        // Buttons block sits below the Step-9 resentment list. Job assignment now lives on the Job
        // tab (the old "Assign to Workstation" button was removed with the workstation blocks), so
        // the Info tab just carries Exile.
        int btnY = panelY + 200;
        Button exile = PolishButton.polished(
            Component.translatable("bannerbound.citizen.exile").withStyle(ChatFormatting.RED),
            btn -> {
                PacketDistributor.sendToServer(new ExileCitizenPayload(entityId));
                this.onClose();
            })
            .bounds(panelX + 12, btnY, btnWidth, 20)
            .build();
        exile.active = canModify;
        this.addRenderableWidget(exile);
    }

    // ── Job tab ──────────────────────────────────────────────────────────────────────────────────

    private static final String FORESTER_TYPE = "foresters_log";
    private static final String DIGGER_TYPE = "diggers_slab";
    private static final String FARMER_TYPE = "farmers_granary";
    private static final String FISHER_TYPE = "fishers_creel";
    private static final String FORAGER_TYPE = "foragers_basket";
    private static final String HERDER_TYPE = "herders_pen";
    private static final String HUNTER_TYPE = "hunters_camp";

    /** True for jobs that take a held tool in the tool slot (forester axe, digger shovel, farmer hoe,
     *  fisher rod, herder fiber rope) — plus any {@link com.bannerbound.core.api.job.CitizenJobRegistry
     *  registry} job that declares {@code toolRequired} (e.g. the Antiquity spear fisher's bone spear).
     *  Tool-slot jobs render the tool row + a Set-drop-location button. */
    private static boolean jobHasToolSlot(String typeId) {
        if (FORESTER_TYPE.equals(typeId) || DIGGER_TYPE.equals(typeId)
            || FARMER_TYPE.equals(typeId) || FISHER_TYPE.equals(typeId)
            || HERDER_TYPE.equals(typeId)) return true;
        // The stocker is pure logistics — no tool, no drop-off; its Job tab shows the task list
        // instead (its icon role exists only for the BUNDLE job glyph, not a tool slot).
        if (com.bannerbound.core.entity.StockerWorkGoal.JOB_TYPE_ID.equals(typeId)) return false;
        // A registry job shows the tool slot whenever it declares a tool role — even when the tool is
        // OPTIONAL (e.g. the spear fisher works bare-handed but lets you install a better spear).
        com.bannerbound.core.api.job.CitizenJobRegistry.JobDef d =
            com.bannerbound.core.api.job.CitizenJobRegistry.byId(typeId);
        return d != null && d.iconRole() != null;
    }

    private boolean playerHasForemanRod() {
        if (this.minecraft == null || this.minecraft.player == null) return false;
        net.minecraft.world.entity.player.Inventory inv = this.minecraft.player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            if (inv.getItem(i).is(com.bannerbound.core.BannerboundCore.FOREMANS_ROD.get())) return true;
        }
        return false;
    }
    /** Slot / button geometry for the Job tab, relative to the panel. */
    private static final int JOB_CONTENT_Y = 58;
    private static final int JOB_TOOL_SLOT = 18;
    private static final int JOB_LOG_BTN = 24;
    /** Picker overlay metrics (shared by the job / log / tool overlays). */
    private static final int JOB_OVERLAY_ROW_H = 20;
    /** Farmer seed-cache slot count — mirrors the {@code SimpleContainer} size on the citizen. */
    private static final int SEED_CACHE_SLOTS = 6;

    // Workshop state plus current profession XP for the Job tab. Empty workshopId = workshop job
    // without a binding; non-workshop jobs still receive jobSkillXp from their job id bucket.
    private String jobWorkshopId = "";
    private String jobWorkshopName = "";
    private String jobWorkshopTypeId = "";
    private int jobSkillXp;

    // Stocker-only state: the settlement task board (queue order) for the "Active tasks" list.
    // States: 0 = open, 1 = claimed by another stocker, 2 = THIS citizen's current haul.
    private java.util.List<Integer> stockerTaskItems = java.util.List.of();
    private java.util.List<Integer> stockerTaskCounts = java.util.List.of();
    private java.util.List<String> stockerTaskDests = java.util.List.of();
    private java.util.List<Integer> stockerTaskStates = java.util.List.of();
    /** The citizen's work site is an outpost: storage is outpost-decided, drop button greys. */
    private boolean jobOutpostManaged;

    /** Apply a server job-state push (open + 1 Hz poll). Rebuilds widgets only when the structural
     *  layout changes (employed↔unemployed, permission flip, first packet) so the open overlay +
     *  scroll aren't reset on every poll. */
    public void applyJobState(com.bannerbound.core.network.CitizenJobStatePayload p) {
        if (p.entityId() != this.entityId) return;
        boolean prevEmployed = !this.jobTypeId.isEmpty();
        boolean prevCanManage = this.jobCanManage;
        boolean prevReceived = this.jobStateReceived;
        this.jobCanManage = p.canManageJobs();
        this.jobTypeId = p.jobTypeId() == null ? "" : p.jobTypeId();
        this.jobIconItemId = p.jobIconItemId();
        this.jobHasTool = p.hasTool();
        this.jobToolIcon = p.toolItemId() == 0
            ? net.minecraft.world.item.ItemStack.EMPTY
            : new net.minecraft.world.item.ItemStack(
                net.minecraft.core.registries.BuiltInRegistries.ITEM.byId(p.toolItemId()));
        this.jobPreferredLog = resolveLog(p.preferredLogId());
        this.jobDropOffSet = p.dropOffSet();
        this.jobOutpostManaged = p.outpostManaged();
        this.jobSeedSourceSet = p.seedSourceSet();
        this.jobAnarchy = p.anarchy();
        this.jobForesterKeepExtras = p.foresterKeepExtras();
        this.jobPinned = p.jobPinned();
        this.jobSwitchRefused = p.switchRefused();
        com.bannerbound.core.entity.CitizenWorkStatus[] statuses =
            com.bannerbound.core.entity.CitizenWorkStatus.values();
        int si = p.workStatus();
        this.jobWorkStatus = (si >= 0 && si < statuses.length)
            ? statuses[si] : com.bannerbound.core.entity.CitizenWorkStatus.IDLE;
        this.jobPlantationUnlocked = p.foresterPlantationUnlocked();
        this.jobWorkshopId = p.workshopId();
        this.jobWorkshopName = p.workshopName();
        this.jobWorkshopTypeId = p.workshopTypeId();
        this.jobSkillXp = p.jobXp();
        this.stockerTaskItems = p.stockerTaskItemIds();
        this.stockerTaskCounts = p.stockerTaskCounts();
        this.stockerTaskDests = p.stockerTaskDests();
        this.stockerTaskStates = p.stockerTaskStates();
        this.jobTradingCourier = p.tradingCourier();
        this.jobUnlocked = p.unlockedJobTypeIds();
        this.jobUnlockedIcons = p.unlockedJobIconItemIds();
        this.allowedToolItemIds = new java.util.HashSet<>(p.allowedToolItemIds());
        this.jobPickaxeUnlocked = p.pickaxeUnlocked();
        this.jobHasPickaxe = p.hasPickaxe();
        this.jobPickaxeIcon = p.pickaxeItemId() == 0
            ? net.minecraft.world.item.ItemStack.EMPTY
            : new net.minecraft.world.item.ItemStack(
                net.minecraft.core.registries.BuiltInRegistries.ITEM.byId(p.pickaxeItemId()));
        this.allowedPickaxeItemIds = new java.util.HashSet<>(p.allowedPickaxeItemIds());
        this.forageEnabledBits = p.forageEnabledBits();
        this.forageUnlockedBits = p.forageUnlockedBits();
        this.hunterPreyOff = new java.util.HashSet<>(p.hunterPreyOffIds());
        java.util.List<net.minecraft.world.item.ItemStack> cache = new java.util.ArrayList<>();
        for (int i = 0; i < p.seedCacheItemIds().size(); i++) {
            int count = i < p.seedCacheCounts().size() ? p.seedCacheCounts().get(i) : 1;
            cache.add(new net.minecraft.world.item.ItemStack(
                net.minecraft.core.registries.BuiltInRegistries.ITEM.byId(p.seedCacheItemIds().get(i)), count));
        }
        this.seedCache = cache;
        this.jobStateReceived = true;
        boolean nowEmployed = !this.jobTypeId.isEmpty();
        if (activeTab == Tab.JOB
                && (prevEmployed != nowEmployed || prevCanManage != jobCanManage || !prevReceived)) {
            jobOverlay = JobOverlay.NONE;
            rebuildWidgets();
        }
    }

    /** Y of the "Back" button shown while an overlay is open (just above the persistent Cancel). */
    private int jobBackBtnY(int panelY) { return panelY + panelHeight() - 52; }

    private void openJobOverlay(JobOverlay overlay) {
        if (overlay == JobOverlay.TOOL_PICK) buildAxeSlots();
        if (overlay == JobOverlay.LOG_PICK && validLogs.isEmpty()) buildValidLogList();
        if (overlay == JobOverlay.PREY_PICK) buildPreyList();
        if (overlay == JobOverlay.SETTINGS) buildSettingsOptions();
        jobOverlay = overlay;
        jobOverlayScroll = 0;
        rebuildWidgets();
    }

    /** Rebuilds the prey-picker rows from the huntable entity-type tag (synced to the client with
     *  every other registry tag), sorted by id so the row order is stable across opens. */
    private void buildPreyList() {
        preyList.clear();
        net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE
            .getTag(com.bannerbound.core.entity.HunterWorkGoal.HUNTABLE_TAG)
            .ifPresent(set -> set.forEach(h -> preyList.add(h.value())));
        preyList.sort(java.util.Comparator.comparing(t ->
            net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(t).toString()));
    }

    /** Opens the tool picker for the primary slot ({@code pickaxe == false}) or the quarryworker's
     *  pickaxe slot ({@code true}), filtering the list to that slot's allowed items. */
    private void openToolPick(boolean pickaxe) {
        toolPickPickaxe = pickaxe;
        openJobOverlay(JobOverlay.TOOL_PICK);
    }

    private void closeJobOverlay() {
        jobOverlay = JobOverlay.NONE;
        rebuildWidgets();
    }

    private int addJobActionButton(int panelX, int startY, int btnWidth, int index,
                                   Component label, Button.OnPress onPress) {
        return addJobActionButton(panelX, startY, btnWidth, index, label, onPress, null, true);
    }

    private int addJobActionButton(int panelX, int startY, int btnWidth, int index,
                                   Component label, Button.OnPress onPress,
                                   Component tooltip, boolean active) {
        int colW = (btnWidth - 4) / 2;
        int x = panelX + 12 + (index % 2) * (colW + 4);
        int y = startY - (index / 2) * 24;
        PolishButton.Builder builder = PolishButton.polished(label, onPress).bounds(x, y, colW, 20);
        if (tooltip != null) {
            builder.tooltip(net.minecraft.client.gui.components.Tooltip.create(tooltip));
        }
        Button button = builder.build();
        button.active = active;
        this.addRenderableWidget(button);
        return index + 1;
    }

    private void buildJobTab(int panelX, int panelY, int btnWidth) {
        final int sy = panelY + JOB_CONTENT_Y;
        // Main-view content is drawn only when no overlay is open; the overlay list draws itself in
        // render() so it cleanly covers the area instead of overlapping the main controls.
        this.addRenderableOnly((graphics, mouseX, mouseY, partialTick) ->
            renderJobMain(graphics, panelX, panelY, sy));

        if (!jobStateReceived || !jobCanManage) return;

        // Overlay open → the only control is a Back button (Cancel still persists below it).
        if (jobOverlay != JobOverlay.NONE) {
            this.addRenderableWidget(PolishButton.polished(
                    Component.translatable("bannerbound.citizen.job.back"),
                    btn -> closeJobOverlay())
                .bounds(panelX + 12, jobBackBtnY(panelY), btnWidth, 20)
                .build());
            return;
        }

        // Main view buttons. Unemployed → just the assign picker.
        if (jobTypeId.isEmpty()) {
            this.addRenderableWidget(PolishButton.polished(
                    Component.translatable("bannerbound.citizen.job.assign"),
                    btn -> openJobOverlay(JobOverlay.JOB_PICK))
                .bounds(panelX + 12, sy + 28, btnWidth, 20)
                .build());
            return;
        }

        // Employed: the main view carries only the everyday controls — the primary "Change Job"
        // (one-click reassign/unassign via the picker), the job's most-used control (drop-off, or a
        // core target picker), and a "⚙ Options" gate for the set-and-forget actions. All the rare
        // configure-once actions live in the SETTINGS overlay (buildSettingsOptions) to keep this
        // panel un-bloated.
        int primaryY = panelY + panelHeight() - 52;
        int actionStartY = primaryY - 24;
        int actionIndex = 0;
        addPrimaryJobButton(panelX, panelY, btnWidth, primaryY);
        buildSettingsOptions();

        if (com.bannerbound.core.entity.CrafterWorkGoal.isWorkshopJob(jobTypeId)) {
            // Crafter: everything routes through the bound workshop — Open / Choose Workshop is the
            // one core control. No tool, no drop-off, no Options.
            final String workshopId = jobWorkshopId;
            if (workshopId.isEmpty()) {
                actionIndex = addJobActionButton(panelX, actionStartY, btnWidth, actionIndex,
                    Component.translatable("bannerbound.workshop.picker_title"),
                    btn -> net.neoforged.neoforge.network.PacketDistributor.sendToServer(
                        new com.bannerbound.core.network.AssignCitizenJobPayload(entityId,
                            jobTypeId)));
            } else {
                actionIndex = addJobActionButton(panelX, actionStartY, btnWidth, actionIndex,
                    Component.translatable("bannerbound.citizen.job.open_workshop"),
                    btn -> net.neoforged.neoforge.network.PacketDistributor.sendToServer(
                        new com.bannerbound.core.network.OpenWorkshopMenuRequestPayload(workshopId)));
            }
            if (!settingsOptions.isEmpty()) {
                actionIndex = addJobActionButton(panelX, actionStartY, btnWidth, actionIndex,
                    Component.translatable("bannerbound.citizen.job.options"),
                    btn -> openJobOverlay(JobOverlay.SETTINGS));
            }
            return;
        }
        if (com.bannerbound.core.entity.StockerWorkGoal.JOB_TYPE_ID.equals(jobTypeId)) {
            // Stocker: fully auto from the task board — plus the trade-courier opt-in: a Trading
            // stocker may be adopted to physically walk a deal's goods to the partner settlement.
            if (jobCanManage) {
                final boolean on = jobTradingCourier;
                Button trading = PolishButton.polished(
                        Component.translatable(on ? "bannerbound.citizen.job.trading_on"
                            : "bannerbound.citizen.job.trading_off"),
                        btn -> {
                            net.neoforged.neoforge.network.PacketDistributor.sendToServer(
                                new com.bannerbound.core.network.SetCitizenTradingPayload(entityId, !on));
                            jobTradingCourier = !on;
                            this.rebuildWidgets();
                        })
                    .bounds(panelX + 12, actionStartY, btnWidth, 20)
                    .tooltip(net.minecraft.client.gui.components.Tooltip.create(
                        Component.translatable("bannerbound.citizen.job.trading_tooltip")))
                    .build();
                this.addRenderableWidget(trading);
            }
            return;
        }

        // Storage is no longer marked per worker — every settlement worker deposits into / takes from
        // the shared storage pool (open stockpiles + loose baskets), governed by per-stockpile
        // toggles. Only the forager/hunter core target pickers remain on the action row.
        if (FORAGER_TYPE.equals(jobTypeId)) {
            actionIndex = addJobActionButton(panelX, actionStartY, btnWidth, actionIndex,
                Component.translatable("bannerbound.forager.picker.title"),
                btn -> openJobOverlay(JobOverlay.FORAGE_PICK));
        }
        if (HUNTER_TYPE.equals(jobTypeId)) {
            actionIndex = addJobActionButton(panelX, actionStartY, btnWidth, actionIndex,
                Component.translatable("bannerbound.hunter.picker.title"),
                btn -> openJobOverlay(JobOverlay.PREY_PICK));
        }

        // ⚙ Options — only when this job actually has set-and-forget actions to fold away.
        if (!settingsOptions.isEmpty()) {
            actionIndex = addJobActionButton(panelX, actionStartY, btnWidth, actionIndex,
                Component.translatable("bannerbound.citizen.job.options"),
                btn -> openJobOverlay(JobOverlay.SETTINGS));
        }
    }

    /** The primary employed-view button: "Change Job" under a government (opens the picker, which
     *  leads with "— Make idle —" so reassign and unassign are one click), or the compliance-gated
     *  "Request switch" in anarchy. */
    private void addPrimaryJobButton(int panelX, int panelY, int btnWidth, int primaryY) {
        if (jobAnarchy) {
            Button reqSwitch = PolishButton.polished(
                    Component.translatable("bannerbound.citizen.job.request_switch"),
                    btn -> openJobOverlay(JobOverlay.JOB_PICK))
                .bounds(panelX + 12, primaryY, btnWidth, 20)
                .tooltip(net.minecraft.client.gui.components.Tooltip.create(
                    Component.translatable(jobSwitchRefused
                        ? "bannerbound.citizen.job.switch_refused_tooltip"
                        : "bannerbound.citizen.job.anarchy_hint")))
                .build();
            // Greyed while a recent switch request is still being refused (NO_WORK_AS_JOB active).
            reqSwitch.active = !jobSwitchRefused;
            this.addRenderableWidget(reqSwitch);
        } else {
            this.addRenderableWidget(PolishButton.polished(
                    Component.translatable("bannerbound.citizen.job.change_job"),
                    btn -> openJobOverlay(JobOverlay.JOB_PICK))
                .bounds(panelX + 12, primaryY, btnWidth, 20)
                .build());
        }
    }

    /** Builds the ⚙ Options rows for the current job — the set-and-forget actions folded off the
     *  main view. Each row fires the same payload its old main-view button did. */
    private void buildSettingsOptions() {
        settingsOptions.clear();
        if (jobTypeId.isEmpty()) return;
        boolean hasRod = playerHasForemanRod();
        Component noRod = Component.translatable("bannerbound.citizen.job.no_rod_tooltip");
        // No tool-depot / drop-off / seed marking anymore — workers source tools, seeds, and a
        // deposit target from the settlement storage pool. Only the rod-area + forester options remain.
        if (FORESTER_TYPE.equals(jobTypeId)) {
            boolean keep = jobForesterKeepExtras;
            settingsOptions.add(new JobOption(
                Component.translatable(keep
                    ? "bannerbound.citizen.job.extras_on" : "bannerbound.citizen.job.extras_off"),
                () -> net.neoforged.neoforge.network.PacketDistributor.sendToServer(
                    new com.bannerbound.core.network.SetForesterKeepExtrasPayload(entityId, !keep)),
                true, Component.translatable("bannerbound.citizen.job.extras_tooltip")));
            // Tree plantation area — research-gated (Silviculture), needs a rod in hand.
            if (jobPlantationUnlocked) {
                settingsOptions.add(new JobOption(
                    Component.translatable("bannerbound.citizen.job.select_plantation_area"),
                    () -> net.neoforged.neoforge.network.PacketDistributor.sendToServer(
                        new com.bannerbound.core.network.BindForemanToCitizenPayload(entityId)),
                    hasRod, hasRod ? null : noRod));
            }
        }
        if (DIGGER_TYPE.equals(jobTypeId) || FARMER_TYPE.equals(jobTypeId)
                || HERDER_TYPE.equals(jobTypeId)) {
            settingsOptions.add(new JobOption(
                Component.translatable("bannerbound.citizen.job.select_work_area"),
                () -> net.neoforged.neoforge.network.PacketDistributor.sendToServer(
                    new com.bannerbound.core.network.BindForemanToCitizenPayload(entityId)),
                hasRod, hasRod ? null : noRod));
        }
    }

    /** Draws the Job tab's main view (skipped while an overlay is open). */
    private void renderJobMain(GuiGraphics graphics, int panelX, int panelY, int sy) {
        if (jobOverlay != JobOverlay.NONE) return;
        int x = panelX + 14;
        if (!jobStateReceived) {
            graphics.drawString(this.font, Component.translatable("bannerbound.citizen.job.loading")
                .withStyle(ChatFormatting.DARK_GRAY), x, sy, 0xFF888888, false);
            return;
        }
        if (jobTypeId.isEmpty()) {
            graphics.drawString(this.font,
                Component.translatable("bannerbound.job.unemployed").withStyle(ChatFormatting.GRAY),
                x, sy, 0xFFCCCCCC, false);
        } else {
            // Job title + icon (the settlement's current tool-age tool for this job). The item may
            // be undiscovered, so bypass the unknown-item "?" swap — it's a UI glyph, not loot.
            UnknownItemHelper.setBypassUnknownSwap(true);
            graphics.renderItem(itemFromId(jobIconItemId), x, sy - 3);
            UnknownItemHelper.setBypassUnknownSwap(false);
            graphics.drawString(this.font, jobTitle(jobTypeId).withStyle(ChatFormatting.WHITE),
                x + 20, sy + 1, 0xFFFFFFFF, false);
            // Status headline — the glanceable "is this worker working, and if not why" verdict.
            // Colour by category: green = working, amber = idle/waiting, red = hard blocker.
            graphics.drawString(this.font, statusLine(), x + 20, sy + 11, statusColor(), false);
            boolean workshopJob = com.bannerbound.core.entity.CrafterWorkGoal.isWorkshopJob(jobTypeId);
            if (workshopJob) {
                // Workshop row: the bound workshop's name (custom or derived type), or a red hint.
                Component workshopName = jobWorkshopId.isEmpty()
                    ? Component.translatable("bannerbound.citizen.job.no_workshop")
                        .withStyle(ChatFormatting.RED)
                    : (jobWorkshopName.isEmpty()
                        ? Component.translatable(com.bannerbound.core.api.workshop.WorkBlockRegistry
                            .displayKey(jobWorkshopTypeId))
                        : Component.literal(jobWorkshopName))
                        .copy().withStyle(ChatFormatting.AQUA);
                graphics.drawString(this.font,
                    Component.translatable("bannerbound.citizen.job.workshop")
                        .withStyle(ChatFormatting.GRAY)
                        .append(" ").append(workshopName),
                    x, sy + 28, 0xFFFFFFFF, false);
                // Skill line + villager-style XP bar: tier name, then progress through the current
                // band toward the next promotion. Live -- jobSkillXp refreshes on the 1 Hz poll.
                if (!jobWorkshopId.isEmpty()) {
                    Component tier = Component.translatable("bannerbound.skill."
                            + com.bannerbound.core.api.quality.QualityMath.skillTierKey(jobSkillXp))
                        .withStyle(ChatFormatting.GOLD);
                    graphics.drawString(this.font,
                        Component.translatable("bannerbound.citizen.job.skill", tier),
                        x, sy + 44, 0xFFFFFFFF, false);
                    int barW = 140;
                    int barY = sy + 56;
                    float progress = com.bannerbound.core.api.quality.QualityMath
                        .skillProgress(jobSkillXp);
                    // The villager trade-XP palette: near-black track, thin lime fill.
                    graphics.fill(x - 1, barY - 1, x + barW + 1, barY + 4, 0xFF000000);
                    graphics.fill(x, barY, x + barW, barY + 3, 0xFF2B2B2B);
                    graphics.fill(x, barY, x + (int) (barW * progress), barY + 3, 0xFF80FF20);
                }
            }
            if (!workshopJob) {
                drawSkillLine(graphics, x, sy + 28);
            }
            if (com.bannerbound.core.entity.StockerWorkGoal.JOB_TYPE_ID.equals(jobTypeId)) {
                // "Active tasks" — the settlement haul board in queue order. This citizen's own
                // haul glows lime, other stockers' claims gray out, open orders stay white.
                graphics.drawString(this.font,
                    Component.translatable("bannerbound.citizen.job.tasks_header")
                        .withStyle(ChatFormatting.GRAY),
                    x, sy + 50, 0xFFAAAAAA, false);
                if (stockerTaskItems.isEmpty()) {
                    PolishedScreen.drawWrapped(graphics, this.font,
                        Component.translatable("bannerbound.citizen.job.no_tasks")
                            .withStyle(ChatFormatting.DARK_GRAY),
                        x, sy + 64, PANEL_WIDTH - 40, 0xFF888888);
                } else {
                    int rowY = sy + 64;
                    int shown = Math.min(stockerTaskItems.size(), 7);
                    for (int i = 0; i < shown; i++) {
                        UnknownItemHelper.setBypassUnknownSwap(true);
                        graphics.renderItem(itemFromId(stockerTaskItems.get(i)), x, rowY - 4);
                        UnknownItemHelper.setBypassUnknownSwap(false);
                        int state = i < stockerTaskStates.size() ? stockerTaskStates.get(i) : 0;
                        String destName = i < stockerTaskDests.size() ? stockerTaskDests.get(i) : "";
                        Component dest = destName.isEmpty()
                            ? Component.translatable("bannerbound.citizen.job.task_stockpile")
                            : Component.literal(destName);
                        Component row = Component.literal("×" + stockerTaskCounts.get(i) + " → ")
                            .append(dest);
                        int color = state == 2 ? 0xFF80FF20 : state == 1 ? 0xFF888888 : 0xFFFFFFFF;
                        graphics.drawString(this.font, row, x + 20, rowY + 1, color, false);
                        rowY += 16;
                    }
                    if (stockerTaskItems.size() > shown) {
                        graphics.drawString(this.font,
                            Component.literal("+" + (stockerTaskItems.size() - shown) + "…")
                                .withStyle(ChatFormatting.DARK_GRAY),
                            x, rowY, 0xFF888888, false);
                    }
                }
            }
            if (jobHasToolSlot(jobTypeId)) {
                int toolY = workshopJob ? sy + 72 : sy + 50;
                int toolSlotY = toolY - 4;
                // Tool row (label left, slot right) — shared by forester (axe) + digger (shovel).
                graphics.drawString(this.font,
                    Component.translatable("bannerbound.citizen.job.tool").withStyle(ChatFormatting.GRAY),
                    x, toolY, 0xFFCCCCCC, false);
                int tsx = jobToolSlotX(panelX);
                drawSlotBg(graphics, tsx, toolSlotY);
                if (jobHasTool && !jobToolIcon.isEmpty()) {
                    graphics.renderItem(jobToolIcon, tsx, toolSlotY);
                }
                int statusY = toolY + 26;
                if (FORESTER_TYPE.equals(jobTypeId)) {
                    // Preferred-wood row (forester only) — one row below the tool row.
                    graphics.drawString(this.font,
                        Component.translatable("bannerbound.citizen.job.preferred_wood").withStyle(ChatFormatting.GRAY),
                        x, toolY + 26, 0xFFCCCCCC, false);
                    int lbx = jobLogBtnX(panelX);
                    int lby = toolY + 21;
                    graphics.fill(lbx, lby, lbx + JOB_LOG_BTN, lby + JOB_LOG_BTN, 0xFF3A3A3A);
                    graphics.renderOutline(lbx, lby, JOB_LOG_BTN, JOB_LOG_BTN, 0xFF8B8B8B);
                    graphics.renderItem(new net.minecraft.world.item.ItemStack(jobPreferredLog), lbx + 4, lby + 4);
                    statusY = toolY + 54;
                } else if (DIGGER_TYPE.equals(jobTypeId) && jobPickaxeUnlocked) {
                    // Pickaxe row (quarryworker, after the Quarry research) — second tool slot.
                    graphics.drawString(this.font,
                        Component.translatable("bannerbound.citizen.job.pickaxe").withStyle(ChatFormatting.GRAY),
                        x, toolY + 24, 0xFFCCCCCC, false);
                    int psx = jobToolSlotX(panelX);
                    drawSlotBg(graphics, psx, toolY + 20);
                    if (jobHasPickaxe && !jobPickaxeIcon.isEmpty()) {
                        graphics.renderItem(jobPickaxeIcon, psx, toolY + 20);
                    }
                    statusY = toolY + 50;
                }
                if (!workshopJob) {
                    // Storage status: whether this worker has anywhere in the settlement storage pool
                    // to bank its yield (outpost workers use their own outpost chest instead).
                    graphics.drawString(this.font, storageStatus(jobDropOffSet), x, statusY, 0xFFFFFFFF, false);
                    // Farmer also shows whether seeds are reachable + the seed cache (overflow seeds it
                    // saved when storage was full; replanted before pulling fresh seeds from the pool).
                    if (FARMER_TYPE.equals(jobTypeId)) {
                        graphics.drawString(this.font, seedStatus(jobSeedSourceSet), x, statusY + 14, 0xFFFFFFFF, false);
                        int cacheLabelY = statusY + 30;
                        graphics.drawString(this.font,
                            Component.translatable("bannerbound.citizen.job.seed_cache").withStyle(ChatFormatting.GRAY),
                            x, cacheLabelY, 0xFFCCCCCC, false);
                        int slotY = cacheLabelY + 11;
                        for (int i = 0; i < SEED_CACHE_SLOTS; i++) {
                            int slx = x + i * 18;
                            drawSlotBg(graphics, slx, slotY);
                            if (i < seedCache.size() && !seedCache.get(i).isEmpty()) {
                                graphics.renderItem(seedCache.get(i), slx, slotY);
                                graphics.renderItemDecorations(this.font, seedCache.get(i), slx, slotY);
                            }
                        }
                    }
                }
            } else if (FORAGER_TYPE.equals(jobTypeId)) {
                // No tool slot — storage-pool status + gather-picker hint, below the skill line/bar.
                graphics.drawString(this.font, storageStatus(jobDropOffSet), x, sy + 50, 0xFFFFFFFF, false);
                graphics.drawString(this.font,
                    Component.translatable("bannerbound.forager.picker.hint").withStyle(ChatFormatting.DARK_GRAY),
                    x, sy + 66, 0xFF888888, false);
            }
        }
        if (!jobCanManage) {
            graphics.drawString(this.font,
                Component.translatable("bannerbound.citizen.job.no_permission").withStyle(ChatFormatting.DARK_RED),
                x, panelY + panelHeight() - 44, 0xFFAA4444, false);
        }
    }

    /** Storage-pool status line for a worker: green when it has somewhere to bank its yield (an
     *  outpost chest, or any deposit-open stockpile/basket in the pool), red when nothing is open. */
    private Component storageStatus(boolean hasStorage) {
        if (jobOutpostManaged) {
            return Component.translatable("bannerbound.citizen.job.outpost_storage").withStyle(ChatFormatting.GREEN);
        }
        return hasStorage
            ? Component.translatable("bannerbound.citizen.job.storage_ok").withStyle(ChatFormatting.GREEN)
            : Component.translatable("bannerbound.citizen.job.storage_none").withStyle(ChatFormatting.RED);
    }

    /** Seed-availability status line for the farmer: green when seeds are reachable (outpost chest or
     *  any take-open pooled storage holding seeds), red when none are. */
    private Component seedStatus(boolean hasSeeds) {
        if (jobOutpostManaged) {
            return Component.translatable("bannerbound.citizen.job.outpost_storage").withStyle(ChatFormatting.GREEN);
        }
        return hasSeeds
            ? Component.translatable("bannerbound.citizen.job.seeds_ok").withStyle(ChatFormatting.GREEN)
            : Component.translatable("bannerbound.citizen.job.seeds_none").withStyle(ChatFormatting.RED);
    }

    private static net.minecraft.world.item.ItemStack itemFromId(int id) {
        if (id == 0) return net.minecraft.world.item.ItemStack.EMPTY;
        return new net.minecraft.world.item.ItemStack(
            net.minecraft.core.registries.BuiltInRegistries.ITEM.byId(id));
    }

    private int jobToolSlotX(int panelX) { return panelX + PANEL_WIDTH - 14 - JOB_TOOL_SLOT; }
    private int jobLogBtnX(int panelX) { return panelX + PANEL_WIDTH - 14 - JOB_LOG_BTN; }

    /** The status-headline label, from {@code bannerbound.citizen.job.status.<name>}. */
    private Component statusLine() {
        return Component.translatable(
            "bannerbound.citizen.job.status." + jobWorkStatus.name().toLowerCase(java.util.Locale.ROOT));
    }

    /** Headline colour bucketed by {@link com.bannerbound.core.entity.CitizenWorkStatus.Category}. */
    private void drawSkillLine(GuiGraphics graphics, int x, int y) {
        Component tier = Component.translatable("bannerbound.skill."
                + com.bannerbound.core.api.quality.QualityMath.skillTierKey(jobSkillXp))
            .withStyle(ChatFormatting.GOLD);
        graphics.drawString(this.font,
            Component.translatable("bannerbound.citizen.job.skill", tier),
            x, y, 0xFFFFFFFF, false);
        int barW = 140;
        int barY = y + 12;
        float progress = com.bannerbound.core.api.quality.QualityMath.skillProgress(jobSkillXp);
        graphics.fill(x - 1, barY - 1, x + barW + 1, barY + 4, 0xFF000000);
        graphics.fill(x, barY, x + barW, barY + 3, 0xFF2B2B2B);
        graphics.fill(x, barY, x + (int) (barW * progress), barY + 3, 0xFF80FF20);
    }

    /** Headline colour bucketed by {@link com.bannerbound.core.entity.CitizenWorkStatus.Category}. */
    private int statusColor() {
        return switch (jobWorkStatus.category()) {
            case GOOD -> 0xFF55DD55;
            case NEUTRAL -> 0xFFE9D24A;
            case BLOCKED -> 0xFFE57761;
        };
    }

    private MutableComponent jobTitle(String typeId) {
        Component custom = ClientLanguageState.jobName(typeId, DIGGER_TYPE.equals(typeId) && jobPickaxeUnlocked);
        if (custom instanceof MutableComponent mutable) {
            return mutable;
        }
        if (custom != null) {
            return Component.empty().append(custom);
        }
        // The digger is a "Digger" until the Quarry research, then a "Quarryworker".
        if (DIGGER_TYPE.equals(typeId)) {
            return Component.translatable(jobPickaxeUnlocked
                ? "bannerbound.job.diggers_slab" : "bannerbound.job.diggers_slab.base");
        }
        return Component.translatable("bannerbound.job." + (typeId == null || typeId.isEmpty() ? "unemployed" : typeId));
    }

    /** Resolves a block id string to a Block; oak log on any failure. */
    private static net.minecraft.world.level.block.Block resolveLog(String id) {
        if (id != null && !id.isEmpty()) {
            ResourceLocation rl = ResourceLocation.tryParse(id);
            if (rl != null && net.minecraft.core.registries.BuiltInRegistries.BLOCK.containsKey(rl)) {
                return net.minecraft.core.registries.BuiltInRegistries.BLOCK.get(rl);
            }
        }
        return net.minecraft.world.level.block.Blocks.OAK_LOG;
    }

    /** Builds the discoverable {@code *_log}/{@code *_stem} list for the preferred-wood picker —
     *  mirrors the old Forester's Log screen's filter. */
    private void buildValidLogList() {
        validLogs.clear();
        for (net.minecraft.world.level.block.Block block : net.minecraft.core.registries.BuiltInRegistries.BLOCK) {
            if (!block.defaultBlockState().is(net.minecraft.tags.BlockTags.LOGS)) continue;
            ResourceLocation id = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(block);
            String path = id.getPath();
            if (path.endsWith("_log") || path.endsWith("_stem")) {
                if (!UnknownItemHelper.isKnown(block.asItem())) continue;
                validLogs.add(block);
            }
        }
        validLogs.sort((a, b) -> net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(a)
            .compareTo(net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(b)));
    }

    /** Player-inventory slots holding a tool this job is allowed to use — i.e. whose item id is in
     *  {@link #allowedToolItemIds} (the job's role tool at the settlement's current tool age or
     *  lower: forester axes, digger shovels). */
    private void buildAxeSlots() {
        axeSlots.clear();
        if (this.minecraft == null || this.minecraft.player == null) return;
        // The slot being filled decides which allow-list applies (primary tool vs the pickaxe slot).
        java.util.Set<Integer> allowed = toolPickPickaxe ? allowedPickaxeItemIds : allowedToolItemIds;
        net.minecraft.world.entity.player.Inventory inv = this.minecraft.player.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            int id = net.minecraft.core.registries.BuiltInRegistries.ITEM.getId(inv.getItem(i).getItem());
            if (!inv.getItem(i).isEmpty() && allowed.contains(id)) {
                axeSlots.add(i);
            }
        }
    }

    private static void drawSlotBg(GuiGraphics graphics, int x, int y) {
        graphics.fill(x - 1, y - 1, x + 17, y + 17, 0xFF8B8B8B);
        graphics.renderOutline(x - 1, y - 1, 18, 18, 0xFF373737);
        graphics.fill(x, y, x + 16, y + 16, 0xFF2A2A2A);
    }

    /** Mirror of the TownHallScreen "statuses" tab layout — same row height, same golden-on-gray
     *  text scheme, same time-left bar. Differences from the settlement statuses: the value column
     *  is the signed happiness modifier (rendered with sign and the heart-colour matching the
     *  direction) and infinite thoughts (UNEMPLOYED) render no time-bar. */
    /** Satisfaction (0–100) with one pillar, computed client-side from the synced thought rows —
     *  mirrors {@code Thoughts.categorySatisfaction} (BASE + Σ modifiers in that category, clamped). */
    private int categorySatisfaction(com.bannerbound.core.social.HappinessCategory cat) {
        int sum = com.bannerbound.core.social.Thoughts.BASE_HAPPINESS;
        for (ThoughtEntry t : thoughts) {
            if (t.categoryEnum() == cat) sum += t.modifier();
        }
        return Math.max(0, Math.min(100, sum));
    }

    private void buildThoughtsTab(int panelX, int panelY) {
        this.addRenderableOnly((graphics, mouseX, mouseY, partialTick) -> {
            int listX = panelX + 14;
            int listW = PANEL_WIDTH - 28;

            // ── Four happiness pillar gauges across the top ──
            com.bannerbound.core.social.HappinessCategory[] cats =
                com.bannerbound.core.social.HappinessCategory.values();
            int ringTop = panelY + 50;
            int ringR = 22;
            int ringThick = 4;
            int slotW = listW / cats.length;
            int ringCy = ringTop + ringR;
            int hovered = -1;
            for (int i = 0; i < cats.length; i++) {
                com.bannerbound.core.social.HappinessCategory cat = cats[i];
                int cx = listX + slotW * i + slotW / 2;
                int sat = categorySatisfaction(cat);
                // Track ring (dim) then the satisfaction fill, from the top, clockwise.
                Arcs.drawRing(graphics, cx, ringCy, ringR, ringThick, -Math.PI / 2, Math.PI * 2, 1f, 0xFF2A2A2A);
                Arcs.drawRing(graphics, cx, ringCy, ringR, ringThick, -Math.PI / 2, Math.PI * 2, sat / 100f, cat.color);
                graphics.drawCenteredString(this.font, sat + "%", cx, ringCy - 4, 0xFFFFFFFF);
                graphics.drawCenteredString(this.font, Component.translatable(cat.labelKey),
                    cx, ringCy + ringR + 4, cat.color);
                if (Math.abs(mouseX - cx) <= ringR && Math.abs(mouseY - ringCy) <= ringR) hovered = i;
            }

            int listY = ringCy + ringR + 18;
            int listH = panelHeight() - (listY - panelY) - 36;
            graphics.enableScissor(listX, listY, listX + listW, listY + listH);
            if (thoughts.isEmpty()) {
                Component empty = Component.translatable("bannerbound.citizen.thoughts.empty")
                    .withStyle(ChatFormatting.DARK_GRAY);
                graphics.drawCenteredString(this.font, empty,
                    panelX + PANEL_WIDTH / 2, listY + listH / 2 - 4, 0xFF888888);
            } else {
                // Row heights are variable: a long label (e.g. "Had a great conversation with
                // <name>") wraps onto extra lines instead of being clipped by the scissor, so each
                // row is as tall as its wrapped label needs. Sum them for the scroll extent.
                int totalH = 0;
                for (ThoughtEntry t : thoughts) totalH += thoughtRowHeight(t, listW);
                int maxScroll = Math.max(0, totalH - listH);
                if (thoughtScroll > maxScroll) thoughtScroll = maxScroll;
                if (thoughtScroll < 0) thoughtScroll = 0;
                // Live world tick — passed into each row so the time bar shrinks in real time.
                long nowGameTime = (this.minecraft != null && this.minecraft.level != null)
                    ? this.minecraft.level.getGameTime() : 0L;
                int y = listY - thoughtScroll;
                for (ThoughtEntry t : thoughts) {
                    int rowH = thoughtRowHeight(t, listW);
                    if (y + rowH >= listY && y <= listY + listH) {
                        drawThoughtRow(graphics, listX, y, listW, t, nowGameTime);
                    }
                    y += rowH;
                }
            }
            graphics.disableScissor();

            // Hovering a pillar gauge → that pillar's thoughts (grouped), drawn over the scissor.
            if (hovered >= 0) {
                com.bannerbound.core.social.HappinessCategory hc = cats[hovered];
                java.util.List<Component> tip = new java.util.ArrayList<>();
                tip.add(Component.translatable(hc.labelKey)
                    .append(Component.literal("  " + categorySatisfaction(hc) + "%").withStyle(ChatFormatting.GRAY)));
                boolean any = false;
                for (ThoughtEntry t : thoughts) {
                    if (t.categoryEnum() != hc) continue;
                    any = true;
                    int m = t.modifier();
                    tip.add(Component.literal((m > 0 ? "+" : "") + m + " ")
                        .withStyle(m > 0 ? ChatFormatting.GREEN : m < 0 ? ChatFormatting.RED : ChatFormatting.GRAY)
                        .append(t.label().copy().withStyle(ChatFormatting.GRAY)));
                }
                if (!any) {
                    tip.add(Component.translatable("bannerbound.citizen.thoughts.empty")
                        .withStyle(ChatFormatting.DARK_GRAY));
                }
                graphics.renderComponentTooltip(this.font, tip, mouseX, mouseY);
            }
        });
    }

    /** One row in the Thoughts tab. Top line shows the signed modifier in green/red plus the
     *  thought label; bottom line shows a thin time-left bar (omitted for infinite thoughts).
     *  {@code nowGameTime} is the live world tick used to compute remaining duration — passing
     *  it in (rather than capturing it once at open time) is what makes the bar shrink in real
     *  time while the screen stays open. */
    private void drawThoughtRow(GuiGraphics graphics, int x, int y, int width,
                                 ThoughtEntry entry, long nowGameTime) {
        // Sign-aware colour: positive modifiers in soft green, negative in soft red, zero in gray.
        int signColor = entry.modifier() > 0 ? 0xFF7BCB6F
                      : entry.modifier() < 0 ? 0xFFE57761
                      : 0xFFCCCCCC;
        Component prefixComp = Component.literal(thoughtPrefix(entry));
        graphics.drawString(this.font, prefixComp, x, y, signColor, false);
        int labelX = x + this.font.width(prefixComp) + 6;
        // Label sits to the right of the modifier value, light-gray (matches TownHall statuses).
        // Wrap to the remaining width so a long label (a conversation partner's name + glyphs)
        // flows onto extra lines instead of being clipped by the list's scissor. Continuation
        // lines align under the first label line, not under the modifier.
        List<FormattedCharSequence> lines = this.font.split(entry.label(),
            Math.max(1, width - (labelX - x)));
        int ly = y;
        for (FormattedCharSequence line : lines) {
            graphics.drawString(this.font, line, labelX, ly, 0xFFE0E0E0, false);
            ly += this.font.lineHeight;
        }
        // Time-left bar sits below the (possibly multi-line) label: empty/black background,
        // golden fill. Infinite thoughts render a faint outline only — no "remaining" to show.
        int barY = y + Math.max(1, lines.size()) * this.font.lineHeight + 5;
        int barW = width;
        graphics.fill(x, barY, x + barW, barY + 3, 0xFF1A1A1A);
        graphics.renderOutline(x - 1, barY - 1, barW + 2, 5, 0xFF353535);
        if (entry.totalDurationTicks() > 0 && entry.expireGameTime() >= 0) {
            long remaining = Math.max(0L, entry.expireGameTime() - nowGameTime);
            int fill = (int) (barW * remaining / (long) entry.totalDurationTicks());
            if (fill < 0) fill = 0;
            if (fill > barW) fill = barW;
            graphics.fill(x, barY, x + fill, barY + 3, 0xFFE2C065);
        }
    }

    /** The signed-modifier prefix shown at the start of a thought row (e.g. {@code "+5"}). */
    private static String thoughtPrefix(ThoughtEntry entry) {
        return entry.modifier() > 0 ? "+" + entry.modifier() : String.valueOf(entry.modifier());
    }

    /** Height of a thought row, accounting for label wrapping. Mirrors the layout in
     *  {@link #drawThoughtRow}: N wrapped label lines, a 5px gap, then the 3px+outline time bar
     *  and bottom padding. A single-line label yields {@link #THOUGHT_ROW_HEIGHT} (the old fixed
     *  value), so short thoughts look exactly as before. */
    private int thoughtRowHeight(ThoughtEntry entry, int width) {
        int labelX = this.font.width(thoughtPrefix(entry)) + 6;
        int lines = Math.max(1, this.font.split(entry.label(), Math.max(1, width - labelX)).size());
        return lines * this.font.lineHeight + (THOUGHT_ROW_HEIGHT - this.font.lineHeight);
    }

    private void buildRelationshipsTab(int panelX, int panelY) {
        this.addRenderableOnly((graphics, mouseX, mouseY, partialTick) -> {
            int listX = panelX + 14;
            int listY = panelY + 56;
            int listW = PANEL_WIDTH - 28;
            int listH = panelHeight() - 56 - 36; // leave room for the cancel button strip
            // Clip the rows so a long list doesn't paint over the cancel button.
            graphics.enableScissor(listX, listY, listX + listW, listY + listH);
            if (relationships.isEmpty()) {
                Component empty = Component.translatable("bannerbound.citizen.relationships.empty")
                    .withStyle(ChatFormatting.DARK_GRAY);
                graphics.drawCenteredString(this.font, empty,
                    panelX + PANEL_WIDTH / 2, listY + listH / 2 - 4, 0xFF888888);
            } else {
                int rowH = 36;
                int totalH = relationships.size() * rowH;
                int maxScroll = Math.max(0, totalH - listH);
                if (relScroll > maxScroll) relScroll = maxScroll;
                if (relScroll < 0) relScroll = 0;
                int y = listY - relScroll;
                for (RelationshipEntry e : relationships) {
                    drawRelationshipRow(graphics, listX, y, listW, e);
                    y += rowH;
                }
            }
            graphics.disableScissor();
        });
    }

    /**
     * One row in the Relationships tab: name on the left, score bar on the right with the tier
     * label centred above and {@code -100 / 0 / 100} ticks under it. Layout proportions match the
     * user's sketch (name takes ~40% of the row width, bar takes the remainder).
     */
    private void drawRelationshipRow(GuiGraphics graphics, int x, int y, int width, RelationshipEntry entry) {
        int nameW = Math.min(90, width / 2);
        int barX = x + nameW + 6;
        int barW = width - nameW - 12;
        int barY = y + 14;
        int barH = 5;

        // Name on the left, vertically centred against the bar row — clipped to its column so a
        // long name never paints into the score bar (substrByWidth keeps the glyph/tint styles).
        graphics.drawString(this.font,
            net.minecraft.locale.Language.getInstance().getVisualOrder(
                this.font.substrByWidth(entry.name(), nameW)),
            x, y + 8, 0xFFFFFFFF, false);

        // Family is a special tier — no score bar, just a centred "Family" label in a warm
        // colour. The data side still ships score=100, but rendering a bar implies a movable
        // value, which Family doesn't have.
        if (entry.isFamily()) {
            Component familyLabel = Component.literal(RelationshipTier.FAMILY.displayLabel())
                .withStyle(ChatFormatting.LIGHT_PURPLE);
            graphics.drawCenteredString(this.font, familyLabel,
                barX + barW / 2, y + 8, 0xFFFFB1FF);
            return;
        }

        // Tier label centred above the bar.
        RelationshipTier tier = RelationshipTier.of(entry.score());
        Component tierLabel = Component.literal(tier.displayLabel()).withStyle(ChatFormatting.GRAY);
        graphics.drawCenteredString(this.font, tierLabel,
            barX + barW / 2, y, 0xFFBBBBBB);

        // Two-tone bar: red on the negative half, green on the positive half.
        int zeroX = barX + barW / 2;
        graphics.fill(barX, barY, zeroX, barY + barH, BAR_RED);
        graphics.fill(zeroX, barY, barX + barW, barY + barH, BAR_GREEN);
        // Faint outline so the bar reads on a dark panel.
        graphics.renderOutline(barX - 1, barY - 1, barW + 2, barH + 2, 0xFF000000);

        // White marker dot at the score position. Clamp to [-100, 100] so a stray over-clamped
        // value still draws inside the bar.
        int clamped = Math.max(-100, Math.min(100, entry.score()));
        int dotX = barX + (int) Math.round((clamped + 100) / 200.0 * barW);
        int dotR = 2;
        graphics.fill(dotX - dotR, barY - 1, dotX + dotR, barY + barH + 1, BAR_DOT);

        // Tick labels under the bar.
        int tickY = barY + barH + 2;
        graphics.drawString(this.font, "-100", barX - 2, tickY, 0xFF888888, false);
        String zero = "0";
        graphics.drawString(this.font, zero, zeroX - this.font.width(zero) / 2, tickY, 0xFF888888, false);
        String hundred = "100";
        graphics.drawString(this.font, hundred,
            barX + barW - this.font.width(hundred) + 2, tickY, 0xFF888888, false);
    }

    /**
     * "Health: ♥♥♥♡ (cur/max)" — each heart represents 2 HP, mirroring the vanilla HUD scale.
     */
    private void drawHeartRow(GuiGraphics graphics, int x, int y, String labelKey,
                               float current, float max) {
        MutableComponent label = Component.translatable(labelKey).withStyle(ChatFormatting.GRAY);
        graphics.drawString(this.font, label, x, y, 0xFFCCCCCC, false);

        int iconsX = x + this.font.width(label) + 6;
        int iconsY = y - 1;
        int iconCount = Math.max(1, (int) Math.ceil(max / 2.0));
        for (int i = 0; i < iconCount; i++) {
            float threshold = (i + 1) * 2.0f;
            ResourceLocation sprite = current >= threshold ? HEART_FULL_SPRITE
                : current >= threshold - 1 ? HEART_HALF_SPRITE
                : HEART_EMPTY_SPRITE;
            graphics.blitSprite(sprite, iconsX + i * 9, iconsY, 9, 9);
        }

        String countText = String.format("(%s/%s)", formatInt(current), formatInt(max));
        graphics.drawString(this.font, countText,
            iconsX + iconCount * 9 + 6, y, 0xFFFFFFFF, false);
    }

    /**
     * Centered "Happiness" title + a tri-colour bar (red / yellow / green) with the happiness
     * face glyph sitting on the bar at the current value's position. The bar's segment widths
     * are 0–40 % (red), 40–70 % (yellow), 70–100 % (green) — matching the thresholds
     * {@link Icons#happiness} uses to pick the face, so the icon's expression always aligns
     * with the colour band underneath it.
     *
     * <p>The face glyph IS the indicator (no separate dot) — its colour shows the mood, its
     * x-position shows the score. Mirrors the user's sketch: title above, bar in the middle
     * with the icon-as-indicator, "0" / "100" labels under the bar ends.
     *
     * @param centerX horizontal centre of the panel (bar centred here, title centred here)
     * @param y       top of the block (title baseline approximately {@code y + font.lineHeight})
     * @param width   bar width
     */
    private Component drawHappinessBlock(GuiGraphics graphics, int centerX, int y, int width,
                                     int current, int max, int mouseX, int mouseY) {
        Component title = Component.translatable("bannerbound.citizen.happiness")
            .withStyle(ChatFormatting.WHITE);
        graphics.drawCenteredString(this.font, title, centerX, y, 0xFFFFFFFF);

        int barH = HAPPINESS_BAR_HEIGHT;
        int barX = centerX - width / 2;
        int barY = y + this.font.lineHeight + 4;
        int redEnd    = barX + Math.round(width * HAPPINESS_MID_RATIO);
        int yellowEnd = barX + Math.round(width * HAPPINESS_HIGH_RATIO);
        int barEnd    = barX + width;
        // Three segments, painted flat — the visual interest is the icon-indicator, not bar gloss.
        graphics.fill(barX,      barY, redEnd,    barY + barH, HAPPINESS_RED);
        graphics.fill(redEnd,    barY, yellowEnd, barY + barH, HAPPINESS_YELLOW);
        graphics.fill(yellowEnd, barY, barEnd,    barY + barH, HAPPINESS_GREEN);

        // "0" / "100" tick labels under the bar ends.
        int tickY = barY + barH + 2;
        graphics.drawString(this.font, "0", barX - 2, tickY, 0xFFCCCCCC, false);
        String hundred = "100";
        graphics.drawString(this.font, hundred,
            barEnd - this.font.width(hundred) + 2, tickY, 0xFFCCCCCC, false);

        // Happiness icon as the indicator — colour reflects mood, x-position reflects score.
        // Centre the glyph on the bar's vertical midline so it overlaps the bar like the dot
        // in the sketch.
        double ratio = max > 0 ? (double) current / (double) max : 0.5;
        if (ratio < 0) ratio = 0; else if (ratio > 1) ratio = 1;
        int iconCenterX = barX + (int) Math.round(width * ratio);
        MutableComponent icon = Icons.happiness(current, max);
        int iconW = this.font.width(icon);
        int iconY = barY + barH / 2 - this.font.lineHeight / 2;
        graphics.drawString(this.font, icon,
            iconCenterX - iconW / 2, iconY, 0xFFFFFFFF, false);

        // Hover anywhere over the title-through-tick-labels band → return the explainer tooltip
        // (exact score + the mood effects) for the caller to draw last, above the other rows.
        boolean hover = mouseX >= barX - 2 && mouseX <= barEnd + 2 && mouseY >= y && mouseY <= tickY + this.font.lineHeight;
        return hover ? happinessTooltip(current, max) : null;
    }

    /** Builds the happiness hover tooltip: the exact score, then the active mood band's effects.
     *  Bands match {@link com.bannerbound.core.entity.CitizenEntity#happinessBand} so the promised
     *  numbers are exactly what the citizen experiences. */
    private Component happinessTooltip(int current, int max) {
        MutableComponent tip = Component.translatable("bannerbound.citizen.happiness.tooltip.value",
            current, max).withStyle(ChatFormatting.WHITE);
        int band = com.bannerbound.core.entity.CitizenEntity.happinessBand(current, max);
        String key = band > 0 ? "green" : band < 0 ? "red" : "yellow";
        ChatFormatting titleColor = band > 0 ? ChatFormatting.GREEN
            : band < 0 ? ChatFormatting.RED : ChatFormatting.YELLOW;
        tip.append("\n\n").append(Component.translatable(
            "bannerbound.citizen.happiness." + key + ".title").withStyle(titleColor));
        if (band == 0) {
            tip.append("\n").append(Component.translatable(
                "bannerbound.citizen.happiness.yellow.none").withStyle(ChatFormatting.GRAY));
        } else {
            ChatFormatting effColor = band > 0 ? ChatFormatting.GREEN : ChatFormatting.RED;
            for (String eff : new String[] {"speed", "work", "xp"}) {
                tip.append("\n").append(Component.translatable(
                    "bannerbound.citizen.happiness." + key + "." + eff).withStyle(effColor));
            }
        }
        return tip;
    }

    /**
     * "Stamina: [▓▓▓▓░░░░░░] (cur/max)" — a thin horizontal bar with a 1px frame; fill color
     * shifts green → yellow → red as the citizen depletes. Numeric count follows.
     */
    /** Step 9 polish: stat row with an INVERTED severity palette — green at zero, yellow
     *  in the middle, red as the value climbs. Same shape as {@link #drawStaminaRow} but
     *  the colour gradient flips because high resentment is BAD (unlike high stamina).
     *  Used for the viewer-only resentment bar. */
    private void drawInvertedBar(GuiGraphics graphics, int x, int y, String labelKey,
                                  int current, int max) {
        MutableComponent label = Component.translatable(labelKey).withStyle(ChatFormatting.GRAY);
        graphics.drawString(this.font, label, x, y, 0xFFCCCCCC, false);
        int barX = x + this.font.width(label) + 6;
        int barY = y + 1;
        int barW = 80;
        int barH = 6;
        graphics.fill(barX - 1, barY - 1, barX + barW + 1, barY + barH + 1, 0xFF202020);
        graphics.fill(barX, barY, barX + barW, barY + barH, 0xFF000000);
        if (max > 0 && current > 0) {
            double ratio = (double) current / max;
            int fillW = Math.max(1, (int) Math.round(barW * ratio));
            int color = ratio >= 0.5 ? 0xFFCC3322  // red — hates you
                      : ratio >= 0.25 ? 0xFFCCAA22 // yellow — wary
                      : 0xFF44CC44;                 // green — at peace
            graphics.fill(barX, barY, barX + fillW, barY + barH, color);
        }
        String countText = String.format("(%s/%s)", current, max);
        graphics.drawString(this.font, countText, barX + barW + 6, y, 0xFFFFFFFF, false);
    }

    private void drawStaminaRow(GuiGraphics graphics, int x, int y, String labelKey,
                                 int current, int max) {
        MutableComponent label = Component.translatable(labelKey).withStyle(ChatFormatting.GRAY);
        graphics.drawString(this.font, label, x, y, 0xFFCCCCCC, false);

        int barX = x + this.font.width(label) + 6;
        int barY = y + 1;
        int barW = 80;
        int barH = 6;

        // Frame + empty fill.
        graphics.fill(barX - 1, barY - 1, barX + barW + 1, barY + barH + 1, 0xFF202020);
        graphics.fill(barX, barY, barX + barW, barY + barH, 0xFF000000);

        if (max > 0 && current > 0) {
            double ratio = (double) current / max;
            int fillW = Math.max(1, (int) Math.round(barW * ratio));
            int color = ratio >= 0.5 ? 0xFF44CC44   // green
                      : ratio >= 0.25 ? 0xFFCCAA22 // yellow
                      : 0xFFCC3322;                 // red
            graphics.fill(barX, barY, barX + fillW, barY + barH, color);
        }

        String countText = String.format("(%s/%s)", current, max);
        graphics.drawString(this.font, countText, barX + barW + 6, y, 0xFFFFFFFF, false);
    }

    private static String formatInt(float v) {
        if (Math.abs(v - Math.round(v)) < 0.05f) {
            return String.valueOf(Math.round(v));
        }
        return String.format("%.1f", v);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
        if (activeTab == Tab.RELATIONSHIPS) {
            relScroll -= (int) (scrollY * 12);
            return true;
        }
        if (activeTab == Tab.THOUGHTS) {
            thoughtScroll -= (int) (scrollY * THOUGHT_ROW_HEIGHT);
            return true;
        }
        if (activeTab == Tab.JOB && jobOverlay != JobOverlay.NONE) {
            jobOverlayScroll -= (int) (scrollY * JOB_OVERLAY_ROW_H);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (activeTab == Tab.JOB && button == 0 && jobCanManage) {
            final int panelX = (this.width - PANEL_WIDTH) / 2;
            final int panelY = panelTop();
            if (jobOverlay != JobOverlay.NONE) {
                // Overlay open: a click inside the list selects a row. Clicks elsewhere fall through
                // to super so the Back / Cancel buttons work (no accidental close on stray clicks).
                int ox = overlayX();
                int oy = overlayY();
                if (mouseX >= ox && mouseX < ox + overlayW() && mouseY >= oy && mouseY < oy + overlayH()) {
                    int idx = (int) ((mouseY - oy - 1 + jobOverlayScroll) / JOB_OVERLAY_ROW_H);
                    if (idx >= 0 && idx < jobOverlayRowCount()) {
                        onJobOverlayPick(idx);
                    }
                    return true;
                }
            } else if (jobHasToolSlot(jobTypeId)) {
                final int sy = panelY + JOB_CONTENT_Y;
                int tsx = jobToolSlotX(panelX);
                boolean workshopJob = com.bannerbound.core.entity.CrafterWorkGoal.isWorkshopJob(jobTypeId);
                int toolY = workshopJob ? sy + 72 : sy + 50;
                int tsy = toolY - 4;
                if (mouseX >= tsx && mouseX < tsx + JOB_TOOL_SLOT && mouseY >= tsy && mouseY < tsy + JOB_TOOL_SLOT) {
                    if (jobHasTool) {
                        net.neoforged.neoforge.network.PacketDistributor.sendToServer(
                            new com.bannerbound.core.network.ClearCitizenToolPayload(entityId, false));
                    } else {
                        openToolPick(false);
                    }
                    return true;
                }
                // Quarryworker pickaxe (second) slot.
                if (DIGGER_TYPE.equals(jobTypeId) && jobPickaxeUnlocked) {
                    int psy = toolY + 20;
                    if (mouseX >= tsx && mouseX < tsx + JOB_TOOL_SLOT && mouseY >= psy && mouseY < psy + JOB_TOOL_SLOT) {
                        if (jobHasPickaxe) {
                            net.neoforged.neoforge.network.PacketDistributor.sendToServer(
                                new com.bannerbound.core.network.ClearCitizenToolPayload(entityId, true));
                        } else {
                            openToolPick(true);
                        }
                        return true;
                    }
                }
                if (FORESTER_TYPE.equals(jobTypeId)) {
                    int lbx = jobLogBtnX(panelX);
                    int lby = toolY + 21;
                    if (mouseX >= lbx && mouseX < lbx + JOB_LOG_BTN && mouseY >= lby && mouseY < lby + JOB_LOG_BTN) {
                        openJobOverlay(JobOverlay.LOG_PICK);
                        return true;
                    }
                }
            }
        }
        return super.mouseClicked(mouseX, mouseY, button);
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        // Escape closes an open Job overlay (the slot/log/job picker) instead of the whole screen.
        if (keyCode == org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE
                && activeTab == Tab.JOB && jobOverlay != JobOverlay.NONE) {
            closeJobOverlay();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    /** Handles a click on row {@code idx} of the currently-open Job overlay, then closes it. */
    private void onJobOverlayPick(int idx) {
        switch (jobOverlay) {
            case JOB_PICK -> {
                int offset = jobPickOffset();
                // Leading "— Make idle —" row → unassign (empty job).
                String type = (offset == 1 && idx == 0) ? "" : jobUnlocked.get(idx - offset);
                net.neoforged.neoforge.network.PacketDistributor.sendToServer(
                    new com.bannerbound.core.network.AssignCitizenJobPayload(entityId, type));
            }
            case LOG_PICK -> {
                net.minecraft.world.level.block.Block b = validLogs.get(idx);
                this.jobPreferredLog = b;
                net.neoforged.neoforge.network.PacketDistributor.sendToServer(
                    new com.bannerbound.core.network.SetCitizenPreferredLogPayload(entityId,
                        net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(b).toString()));
            }
            case TOOL_PICK -> {
                int slot = axeSlots.get(idx);
                net.neoforged.neoforge.network.PacketDistributor.sendToServer(
                    new com.bannerbound.core.network.SetCitizenToolPayload(entityId, slot, toolPickPickaxe));
            }
            case FORAGE_PICK -> {
                // Multi-toggle, not select-and-close: flip this category and leave the picker open.
                com.bannerbound.core.api.forager.ForageCategory cat =
                    com.bannerbound.core.api.forager.ForageCategory.byOrdinal(idx);
                if (cat == null || (forageUnlockedBits & cat.bit()) == 0) return;   // locked → ignore
                boolean nowEnabled = (forageEnabledBits & cat.bit()) == 0;
                forageEnabledBits = nowEnabled                                       // optimistic local flip
                    ? (forageEnabledBits | cat.bit()) : (forageEnabledBits & ~cat.bit());
                net.neoforged.neoforge.network.PacketDistributor.sendToServer(
                    new com.bannerbound.core.network.SetForageTargetPayload(entityId, cat.ordinal(), nowEnabled));
                return;   // keep the picker open
            }
            case PREY_PICK -> {
                // Same multi-toggle pattern as the forager picker.
                if (idx < 0 || idx >= preyList.size()) return;
                String id = net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE
                    .getKey(preyList.get(idx)).toString();
                boolean nowEnabled = hunterPreyOff.contains(id);
                if (nowEnabled) hunterPreyOff.remove(id); else hunterPreyOff.add(id);   // optimistic flip
                net.neoforged.neoforge.network.PacketDistributor.sendToServer(
                    new com.bannerbound.core.network.SetHunterPreyPayload(entityId, id, nowEnabled));
                return;   // keep the picker open
            }
            case SETTINGS -> {
                if (idx < 0 || idx >= settingsOptions.size()) return;
                JobOption opt = settingsOptions.get(idx);
                if (!opt.enabled()) return;   // greyed (e.g. no rod) → keep the overlay open
                opt.action().run();
            }
            case NONE -> { return; }
        }
        closeJobOverlay();
    }

    @Override
    protected void renderPolishedBackdrop(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // Pull live stats from the entity before the renderable callbacks fire so the bars reflect
        // current state (stamina ticking down mid-chop, health changing on damage, etc.).
        refreshFromEntity();
    }

    @Override
    protected void renderPolishedExtras(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        if (activeTab == Tab.JOB && jobOverlay != JobOverlay.NONE) {
            renderJobOverlay(graphics, mouseX, mouseY);
        }
    }

    private int overlayX() { return (this.width - PANEL_WIDTH) / 2 + 12; }
    private int overlayY() { return panelTop() + 50; }
    private int overlayW() { return PANEL_WIDTH - 24; }
    /** Leaves room below the list for the Back button (jobBackBtnY = panelHeight()-52) + a gap. */
    private int overlayH() { return panelHeight() - 50 - 56; }

    /** The job picker leads with a "— Make idle —" row when the citizen is currently employed under
     *  a government (one-click unassign as part of "Change job"). Anarchy has no free unassign, and
     *  an already-unemployed citizen has nothing to clear, so the row is hidden in both cases. */
    private boolean showMakeIdleRow() {
        return jobOverlay == JobOverlay.JOB_PICK && jobCanManage && !jobAnarchy && !jobTypeId.isEmpty();
    }
    private int jobPickOffset() { return showMakeIdleRow() ? 1 : 0; }

    private int jobOverlayRowCount() {
        return switch (jobOverlay) {
            case JOB_PICK -> jobUnlocked.size() + jobPickOffset();
            case LOG_PICK -> validLogs.size();
            case TOOL_PICK -> axeSlots.size();
            case FORAGE_PICK -> com.bannerbound.core.api.forager.ForageCategory.count();
            case PREY_PICK -> preyList.size();
            case SETTINGS -> settingsOptions.size();
            case NONE -> 0;
        };
    }

    private void renderJobOverlay(GuiGraphics graphics, int mouseX, int mouseY) {
        int x = overlayX();
        int y = overlayY();
        int w = overlayW();
        int h = overlayH();
        graphics.fill(x, y, x + w, y + h, 0xFF181818);
        graphics.renderOutline(x, y, w, h, 0xFF8B8B8B);
        int rows = jobOverlayRowCount();
        if (rows == 0) {
            // The tool picker's "empty" reads differently from the job/log pickers — call it out.
            String key = jobOverlay == JobOverlay.TOOL_PICK
                ? "bannerbound.citizen.job.no_axes" : "bannerbound.citizen.job.picker_empty";
            graphics.drawCenteredString(this.font,
                Component.translatable(key).withStyle(ChatFormatting.DARK_GRAY),
                x + w / 2, y + h / 2 - 4, 0xFF888888);
            return;
        }
        int contentH = rows * JOB_OVERLAY_ROW_H;
        int maxScroll = Math.max(0, contentH - h + 2);
        if (jobOverlayScroll < 0) jobOverlayScroll = 0;
        if (jobOverlayScroll > maxScroll) jobOverlayScroll = maxScroll;
        Component hoverDesc = null;
        int offset = jobOverlay == JobOverlay.JOB_PICK ? jobPickOffset() : 0;
        graphics.enableScissor(x + 1, y + 1, x + w - 1, y + h - 1);
        for (int i = 0; i < rows; i++) {
            int rowY = y + 1 + i * JOB_OVERLAY_ROW_H - jobOverlayScroll;
            if (rowY + JOB_OVERLAY_ROW_H < y || rowY > y + h) continue;
            boolean hover = mouseX >= x && mouseX < x + w - 4 && mouseY >= rowY && mouseY < rowY + JOB_OVERLAY_ROW_H;
            if (hover) graphics.fill(x + 1, rowY, x + w - 1, rowY + JOB_OVERLAY_ROW_H, 0xFF333333);
            if (jobOverlay == JobOverlay.FORAGE_PICK) {
                renderForageRow(graphics, i, x, rowY, w);
                continue;
            }
            if (jobOverlay == JobOverlay.PREY_PICK) {
                renderPreyRow(graphics, i, x, rowY, w);
                continue;
            }
            if (jobOverlay == JobOverlay.SETTINGS) {
                JobOption opt = settingsOptions.get(i);
                graphics.drawString(this.font, opt.label(), x + 8, rowY + 6,
                    opt.enabled() ? 0xFFE0E0E0 : 0xFF777777, false);
                if (hover && opt.tooltip() != null) hoverDesc = opt.tooltip();
                continue;
            }
            // The leading "— Make idle —" row of the job picker (one-click unassign). No icon.
            if (jobOverlay == JobOverlay.JOB_PICK && offset == 1 && i == 0) {
                graphics.drawString(this.font,
                    Component.translatable("bannerbound.citizen.job.make_idle")
                        .withStyle(ChatFormatting.GRAY),
                    x + 23, rowY + 6, 0xFFAAAAAA, false);
                continue;
            }
            net.minecraft.world.item.ItemStack icon;
            Component label;
            switch (jobOverlay) {
                case JOB_PICK -> {
                    String type = jobUnlocked.get(i - offset);
                    // Icon is the settlement's current tool-age tool for the job (server-resolved).
                    icon = itemFromId((i - offset) < jobUnlockedIcons.size()
                        ? jobUnlockedIcons.get(i - offset) : 0);
                    label = jobTitle(type);
                    if (hover) hoverDesc = Component.translatable("bannerbound.job." + type + ".desc");
                }
                case LOG_PICK -> {
                    net.minecraft.world.level.block.Block b = validLogs.get(i);
                    icon = new net.minecraft.world.item.ItemStack(b);
                    label = Component.translatable(b.getDescriptionId());
                }
                case TOOL_PICK -> {
                    int slot = axeSlots.get(i);
                    icon = this.minecraft != null && this.minecraft.player != null
                        ? this.minecraft.player.getInventory().getItem(slot)
                        : net.minecraft.world.item.ItemStack.EMPTY;
                    label = icon.getHoverName();
                }
                default -> { icon = net.minecraft.world.item.ItemStack.EMPTY; label = Component.empty(); }
            }
            // Job icons are representative tools that may be undiscovered → bypass the "?" swap.
            // Log / tool rows render real (known) items, so the bypass is a harmless no-op there.
            boolean bypass = jobOverlay == JobOverlay.JOB_PICK;
            if (bypass) UnknownItemHelper.setBypassUnknownSwap(true);
            graphics.renderItem(icon, x + 3, rowY + 2);
            if (bypass) UnknownItemHelper.setBypassUnknownSwap(false);
            graphics.drawString(this.font, label, x + 23, rowY + 6, 0xFFE0E0E0, false);
        }
        graphics.disableScissor();
        if (maxScroll > 0) {
            int trackX = x + w - 4;
            int thumbH = Math.max(8, h * h / contentH);
            int thumbY = y + 1 + (h - thumbH - 2) * jobOverlayScroll / maxScroll;
            graphics.fill(trackX, y + 1, trackX + 3, y + h - 1, 0xFF0A0A0A);
            graphics.fill(trackX, thumbY, trackX + 3, thumbY + thumbH, 0xFF606060);
        }
        // Job description on hover — drawn last so it sits above the overlay box. Wrapped so a
        // one-liner doesn't run off the panel.
        if (hoverDesc != null) {
            graphics.renderTooltip(this.font,
                this.font.split(hoverDesc, 180), mouseX, mouseY);
        }
    }

    /** One row of the forager's gather-targets picker: a category icon, the label in
     *  green (ON) / red (OFF) / grey (LOCKED), and the matching state word right-aligned. */
    private void renderForageRow(GuiGraphics graphics, int idx, int x, int rowY, int w) {
        com.bannerbound.core.api.forager.ForageCategory cat =
            com.bannerbound.core.api.forager.ForageCategory.byOrdinal(idx);
        if (cat == null) return;
        boolean unlocked = (forageUnlockedBits & cat.bit()) != 0;
        boolean enabled = unlocked && (forageEnabledBits & cat.bit()) != 0;
        int color = !unlocked ? 0xFF777777 : enabled ? 0xFF55DD55 : 0xFFDD5555;
        // Category items may be undiscovered — the icon is a UI glyph, so bypass the unknown "?" swap.
        UnknownItemHelper.setBypassUnknownSwap(true);
        graphics.renderItem(forageIcon(cat), x + 3, rowY + 2);
        UnknownItemHelper.setBypassUnknownSwap(false);
        graphics.drawString(this.font, Component.translatable(cat.langKey()), x + 23, rowY + 6, color, false);
        Component state = Component.translatable(
            !unlocked ? "bannerbound.forager.state.locked"
                      : enabled ? "bannerbound.forager.state.on" : "bannerbound.forager.state.off");
        graphics.drawString(this.font, state, x + w - 8 - this.font.width(state), rowY + 6, color, false);
    }

    /** One row of the hunter's prey picker: the species' spawn-egg icon, its name in green (ON) /
     *  red (OFF), and the matching state word right-aligned. Every tagged species defaults ON. */
    private void renderPreyRow(GuiGraphics graphics, int idx, int x, int rowY, int w) {
        if (idx < 0 || idx >= preyList.size()) return;
        net.minecraft.world.entity.EntityType<?> type = preyList.get(idx);
        String id = net.minecraft.core.registries.BuiltInRegistries.ENTITY_TYPE.getKey(type).toString();
        boolean enabled = !hunterPreyOff.contains(id);
        int color = enabled ? 0xFF55DD55 : 0xFFDD5555;
        net.minecraft.world.item.Item egg = net.minecraft.world.item.SpawnEggItem.byId(type);
        net.minecraft.world.item.ItemStack icon = new net.minecraft.world.item.ItemStack(
            egg != null ? egg : net.minecraft.world.item.Items.BONE);
        UnknownItemHelper.setBypassUnknownSwap(true);
        graphics.renderItem(icon, x + 3, rowY + 2);
        UnknownItemHelper.setBypassUnknownSwap(false);
        Component preyName = ClientLanguageState.entityName(type);
        graphics.drawString(this.font, preyName == null ? type.getDescription() : preyName,
            x + 23, rowY + 6, color, false);
        Component state = Component.translatable(
            enabled ? "bannerbound.forager.state.on" : "bannerbound.forager.state.off");
        graphics.drawString(this.font, state, x + w - 8 - this.font.width(state), rowY + 6, color, false);
    }

    /** A representative item icon for each forage category. */
    private static net.minecraft.world.item.ItemStack forageIcon(
            com.bannerbound.core.api.forager.ForageCategory cat) {
        net.minecraft.world.item.Item item = switch (cat) {
            case BERRIES -> net.minecraft.world.item.Items.SWEET_BERRIES;
            case SMALL_FLOWERS -> net.minecraft.world.item.Items.POPPY;
            case TALL_FLOWERS -> net.minecraft.world.item.Items.ROSE_BUSH;
            case MUSHROOMS -> net.minecraft.world.item.Items.RED_MUSHROOM;
            case VINES -> net.minecraft.world.item.Items.VINE;
            case GRASS -> net.minecraft.world.item.Items.SHORT_GRASS;
            case LEAVES -> net.minecraft.world.item.Items.OAK_LEAVES;
            case STICKS_FIBERS -> net.minecraft.world.item.Items.STICK;
            case WILD_CROPS -> net.minecraft.world.item.Items.WHEAT;
        };
        return new net.minecraft.world.item.ItemStack(item);
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }
}
