package com.bannerbound.core.citystate;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceLocation;

/**
 * One AI city-state — a discovered vanilla village repurposed as a diplomatic actor (see the
 * CITY_STATES plan). Mutable record-style class persisted inside {@link CityStateData}, mirroring the
 * {@code BarbarianCamp} save/load pattern.
 *
 * <p>We DO NOT touch the village's villagers or buildings (mod compatibility). The only block we add
 * is a faction banner beside the village centre — the future capture objective. The city-state's
 * economy is an abstract, grounded <b>trade-stock ledger</b> ({@link #ledger}); it never fills
 * physical chests off-screen (CITY_STATES §1D anti-cheat).
 *
 * <p>{@link #realized} is transient and forced false on load (no entities are saved).
 */
public final class CityState {
    public final UUID id;
    public BlockPos center;            // village meeting-point (bell) — the identity anchor
    public BlockPos bannerPos;         // our added faction banner (the raze/capture target)
    public ResourceLocation biome;     // resolved once at detection
    public long languageSeed;          // deterministic per-village tongue seed
    public String name = "";           // the city-state's name in its own tongue
    public CityStateDifficulty difficulty = CityStateDifficulty.MEDIUM;

    // Economy / evolution (abstract — see CITY_STATES plan Phase 3, the "living economy").
    public final Map<String, Integer> ledger = new LinkedHashMap<>(); // item id → tradeable surplus
    public final Set<String> knownTech = new HashSet<>();             // research the city-state has adopted
    public double techProgress;        // 0..1 toward adopting the next missing tech
    public int believedPop = BASE_POP; // countedHomes + popDrift, clamped — drives production + caps
    public long lastEconomyTick;       // off-screen clock anchor

    // Living-economy state (Phase 3). Grounding scans persist so the economy runs entirely
    // off-screen; prosperity breathes population/tech/garrison around the bed-counted baseline.
    public int countedHomes = BASE_POP;   // beds counted near the bell (the grounded pop baseline)
    public int popDrift;                  // prosperity-driven daily drift around countedHomes
    public double prosperity = 0.5;       // P ∈ [0,2] — fed + trading thrives, isolated/starving stagnates
    public double fedRatio = 1.0;         // smoothed eaten/needed food ratio
    public double tradeVolume;            // decaying sum of completed-deal value with players (P4 feeds it)
    public long dayIndex;                 // last in-game day the daily pass ran
    public final Map<String, Integer> jobPois = new LinkedHashMap<>();       // job-site POI id → count
    public final Map<String, Integer> resourceChunks = new LinkedHashMap<>(); // ChunkResource name → count
    public final Set<Long> scannedChunks = new HashSet<>();                  // claimed chunks already classified
    public final Map<String, Integer> imports = new LinkedHashMap<>();       // delivered non-food, drained daily
    public final java.util.List<Demand> demands = new java.util.ArrayList<>(); // active demand slots (≤3)
    public final Map<String, Long> demandCooldowns = new LinkedHashMap<>();  // item id → day it may reroll

    /** One active demand slot: the city-state pays a premium for {@code item} until filled/expired. */
    public static final class Demand {
        public String item;
        public int qtyRemaining;
        public long createdDay;

        public Demand(String item, int qtyRemaining, long createdDay) {
            this.item = item;
            this.qtyRemaining = qtyRemaining;
            this.createdDay = createdDay;
        }
    }

    // Transient economy caches (never saved; rebuilt on demand).
    public transient CityStateEconomy.ActiveGoods activeGoodsCache;
    public transient Map<String, Double> prodRemainder = new HashMap<>(); // fractional item carry-over
    public transient double eatenValueToday;       // food value consumed since the last daily pass
    public transient double neededValueToday;      // food value wanted since the last daily pass
    public transient double foodDebt;              // sub-item food-value owed (items are integers)
    public transient double wantDebt;              // sub-item wants-drain owed

    public boolean bannerStamped;      // banner placed at least once
    public boolean bannerRazed;        // banner broken (capture/defeat groundwork — P2)
    public boolean atWar;              // legacy flag (war state now lives in #wars; kept for save compat)
    public UUID vassalOf;              // settlement this city-state is a vassal of (captured → vassal), or null

    // The city-state's claimed territory (packed chunk longs), computed at detection. Players can't
    // found a settlement on/beside it, nor establish an outpost INSIDE it.
    public final Set<Long> claimedChunks = new HashSet<>();

    public transient boolean realized; // banner stamped + (later) worker NPCs present for a nearby player

    // Per player-settlement relationship (war/trade gating arrives in P2/P4).
    public final Map<UUID, Integer> relScore = new HashMap<>();
    public final Set<UUID> discoveredBy = new HashSet<>();

    // War state, keyed by the attacking settlement (only players declare — §2). A city-state can be
    // warred by more than one settlement at once; capture is by whoever scores the banner first.
    public final Map<UUID, CityStateWar> wars = new HashMap<>();

    /** One settlement's war against this city-state. Mirrors the SettlementData war lifecycle. */
    public static final class CityStateWar {
        public int pendingTicks;     // >0 = warning countdown; war goes active at 0
        public boolean active;       // declared war is live (mercenaries defend)
        public long startedAt;
        public boolean peaceOffered; // the settlement has offered peace
        public long redeclareAfter;  // tick before this pair may re-declare
        public long capturedAt;      // >0 = this settlement has captured the standard, awaiting resolution
    }

    // Carryable standard (break the banner during war → carry it to YOUR town hall to capture). One
    // per city-state; capture credits whoever scores it. Mirrors SettlementData.StolenStandard.
    public boolean standardInPlay;   // the banner item exists in the world / a carrier's pack
    public UUID standardCarrier;     // current carrier, or null if dropped
    public BlockPos standardDroppedPos;
    public long standardDroppedAt;
    public long standardAutoReturnAt;

    public CityStateWar warWith(UUID settlementId) {
        return settlementId == null ? null : wars.get(settlementId);
    }

    public CityStateWar getOrCreateWar(UUID settlementId) {
        return wars.computeIfAbsent(settlementId, k -> new CityStateWar());
    }

    /** Mercenaries attack members of a settlement whose war is ACTIVE and not yet resolved by capture. */
    public boolean isActiveEnemy(UUID settlementId) {
        CityStateWar w = warWith(settlementId);
        return w != null && w.active && w.capturedAt == 0;
    }

    /** True while any war is pending, active, or captured — freezes evolution + accrual (§1E). */
    public boolean isFrozen() {
        for (CityStateWar w : wars.values()) {
            if (w.pendingTicks > 0 || w.active || w.capturedAt > 0) return true;
        }
        return false;
    }

    /** True while the banner is broken/carried or already captured — the banner must NOT auto-rebuild
     *  (the broken standard is the objective); a returned standard clears this and the banner re-raises. */
    public boolean standardInPlayOrCaptured() {
        if (standardInPlay) return true;
        for (CityStateWar w : wars.values()) {
            if (w.capturedAt > 0) return true;
        }
        return false;
    }

    /** The settlement that has captured this city-state's banner (awaiting resolution), or null. */
    public UUID capturedBySettlement() {
        for (Map.Entry<UUID, CityStateWar> e : wars.entrySet()) {
            if (e.getValue().capturedAt > 0) return e.getKey();
        }
        return null;
    }

    /** Believed population assumed for a freshly detected village until its size is counted. */
    public static final int BASE_POP = 6;

    /** Economy save-format version. Saves below 2 (the pre-living-economy placeholder) get their
     *  ledger cleared on load — the seed-at-half-cap rule repopulates it from the new catalog on the
     *  next economy tick, so old worlds converge in one tick with no stale sand/stick stock. */
    public static final int ECON_VERSION = 2;

    public CityState(UUID id, BlockPos center, ResourceLocation biome) {
        this.id = id;
        this.center = center;
        this.bannerPos = center;
        this.biome = biome;
    }

    private CityState(UUID id) {
        this.id = id;
    }

    CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putUUID("Id", id);
        tag.putLong("Center", center.asLong());
        if (bannerPos != null) tag.putLong("BannerPos", bannerPos.asLong());
        if (biome != null) tag.putString("Biome", biome.toString());
        tag.putLong("LanguageSeed", languageSeed);
        tag.putString("Name", name);
        tag.putString("Difficulty", difficulty.name());
        tag.putDouble("TechProgress", techProgress);
        tag.putInt("BelievedPop", believedPop);
        tag.putLong("LastEconomyTick", lastEconomyTick);
        tag.putBoolean("BannerStamped", bannerStamped);
        tag.putBoolean("BannerRazed", bannerRazed);
        tag.putBoolean("AtWar", atWar);
        if (vassalOf != null) tag.putUUID("VassalOf", vassalOf);

        long[] claims = new long[claimedChunks.size()];
        int ci = 0;
        for (long c : claimedChunks) claims[ci++] = c;
        tag.putLongArray("ClaimedChunks", claims);

        CompoundTag led = new CompoundTag();
        for (Map.Entry<String, Integer> e : ledger.entrySet()) led.putInt(e.getKey(), e.getValue());
        tag.put("Ledger", led);

        ListTag tech = new ListTag();
        for (String t : knownTech) {
            CompoundTag c = new CompoundTag();
            c.putString("T", t);
            tech.add(c);
        }
        tag.put("KnownTech", tech);

        ListTag rel = new ListTag();
        for (Map.Entry<UUID, Integer> e : relScore.entrySet()) {
            CompoundTag c = new CompoundTag();
            c.putUUID("S", e.getKey());
            c.putInt("V", e.getValue());
            rel.add(c);
        }
        tag.put("Relations", rel);

        ListTag disc = new ListTag();
        for (UUID u : discoveredBy) {
            CompoundTag c = new CompoundTag();
            c.putUUID("S", u);
            disc.add(c);
        }
        tag.put("DiscoveredBy", disc);

        ListTag warList = new ListTag();
        for (Map.Entry<UUID, CityStateWar> e : wars.entrySet()) {
            CityStateWar w = e.getValue();
            CompoundTag c = new CompoundTag();
            c.putUUID("S", e.getKey());
            c.putInt("Pending", w.pendingTicks);
            c.putBoolean("Active", w.active);
            c.putLong("Started", w.startedAt);
            c.putBoolean("Peace", w.peaceOffered);
            c.putLong("Redeclare", w.redeclareAfter);
            c.putLong("Captured", w.capturedAt);
            warList.add(c);
        }
        tag.put("Wars", warList);

        tag.putBoolean("StdInPlay", standardInPlay);
        if (standardCarrier != null) tag.putUUID("StdCarrier", standardCarrier);
        if (standardDroppedPos != null) tag.putLong("StdDroppedPos", standardDroppedPos.asLong());
        tag.putLong("StdDroppedAt", standardDroppedAt);
        tag.putLong("StdAutoReturn", standardAutoReturnAt);

        // Living-economy state (Phase 3). EconVersion gates the one-time ledger migration on load.
        tag.putInt("EconVersion", ECON_VERSION);
        tag.putInt("CountedHomes", countedHomes);
        tag.putInt("PopDrift", popDrift);
        tag.putDouble("Prosperity", prosperity);
        tag.putDouble("FedRatio", fedRatio);
        tag.putDouble("TradeVolume", tradeVolume);
        tag.putLong("DayIndex", dayIndex);
        tag.put("JobPois", saveStringIntMap(jobPois));
        tag.put("ResourceChunks", saveStringIntMap(resourceChunks));
        tag.put("Imports", saveStringIntMap(imports));
        long[] scanned = new long[scannedChunks.size()];
        int si = 0;
        for (long c : scannedChunks) scanned[si++] = c;
        tag.putLongArray("ScannedChunks", scanned);
        ListTag demandList = new ListTag();
        for (Demand d : demands) {
            CompoundTag c = new CompoundTag();
            c.putString("Item", d.item);
            c.putInt("Qty", d.qtyRemaining);
            c.putLong("Day", d.createdDay);
            demandList.add(c);
        }
        tag.put("Demands", demandList);
        CompoundTag cds = new CompoundTag();
        for (Map.Entry<String, Long> e : demandCooldowns.entrySet()) cds.putLong(e.getKey(), e.getValue());
        tag.put("DemandCooldowns", cds);
        return tag;
    }

    private static CompoundTag saveStringIntMap(Map<String, Integer> map) {
        CompoundTag out = new CompoundTag();
        for (Map.Entry<String, Integer> e : map.entrySet()) out.putInt(e.getKey(), e.getValue());
        return out;
    }

    private static void loadStringIntMap(CompoundTag tag, Map<String, Integer> into) {
        for (String key : tag.getAllKeys()) into.put(key, tag.getInt(key));
    }

    static CityState load(CompoundTag tag) {
        if (!tag.hasUUID("Id")) return null;
        CityState cs = new CityState(tag.getUUID("Id"));
        cs.center = BlockPos.of(tag.getLong("Center"));
        cs.bannerPos = tag.contains("BannerPos") ? BlockPos.of(tag.getLong("BannerPos")) : cs.center;
        if (tag.contains("Biome")) cs.biome = ResourceLocation.tryParse(tag.getString("Biome"));
        cs.languageSeed = tag.getLong("LanguageSeed");
        cs.name = tag.getString("Name");
        if (cs.name.isBlank()) cs.name = CityStateNames.generate(cs.languageSeed);
        cs.difficulty = CityStateDifficulty.fromName(tag.getString("Difficulty"));
        cs.techProgress = tag.getDouble("TechProgress");
        cs.believedPop = tag.contains("BelievedPop") ? tag.getInt("BelievedPop") : BASE_POP;
        cs.lastEconomyTick = tag.getLong("LastEconomyTick");
        cs.bannerStamped = tag.getBoolean("BannerStamped");
        cs.bannerRazed = tag.getBoolean("BannerRazed");
        cs.atWar = tag.getBoolean("AtWar");
        if (tag.hasUUID("VassalOf")) cs.vassalOf = tag.getUUID("VassalOf");
        for (long c : tag.getLongArray("ClaimedChunks")) cs.claimedChunks.add(c);
        cs.realized = false;

        CompoundTag led = tag.getCompound("Ledger");
        for (String key : led.getAllKeys()) cs.ledger.put(key, led.getInt(key));

        ListTag tech = tag.getList("KnownTech", Tag.TAG_COMPOUND);
        for (int i = 0; i < tech.size(); i++) cs.knownTech.add(tech.getCompound(i).getString("T"));

        ListTag rel = tag.getList("Relations", Tag.TAG_COMPOUND);
        for (int i = 0; i < rel.size(); i++) {
            CompoundTag c = rel.getCompound(i);
            if (c.hasUUID("S")) cs.relScore.put(c.getUUID("S"), c.getInt("V"));
        }
        ListTag disc = tag.getList("DiscoveredBy", Tag.TAG_COMPOUND);
        for (int i = 0; i < disc.size(); i++) {
            CompoundTag c = disc.getCompound(i);
            if (c.hasUUID("S")) cs.discoveredBy.add(c.getUUID("S"));
        }
        ListTag warList = tag.getList("Wars", Tag.TAG_COMPOUND);
        for (int i = 0; i < warList.size(); i++) {
            CompoundTag c = warList.getCompound(i);
            if (!c.hasUUID("S")) continue;
            CityStateWar w = new CityStateWar();
            w.pendingTicks = c.getInt("Pending");
            w.active = c.getBoolean("Active");
            w.startedAt = c.getLong("Started");
            w.peaceOffered = c.getBoolean("Peace");
            w.redeclareAfter = c.getLong("Redeclare");
            w.capturedAt = c.getLong("Captured");
            cs.wars.put(c.getUUID("S"), w);
        }
        cs.standardInPlay = tag.getBoolean("StdInPlay");
        if (tag.hasUUID("StdCarrier")) cs.standardCarrier = tag.getUUID("StdCarrier");
        if (tag.contains("StdDroppedPos")) cs.standardDroppedPos = BlockPos.of(tag.getLong("StdDroppedPos"));
        cs.standardDroppedAt = tag.getLong("StdDroppedAt");
        cs.standardAutoReturnAt = tag.getLong("StdAutoReturn");

        // Living-economy state (Phase 3) — all optional-with-defaults for old saves.
        if (tag.getInt("EconVersion") < ECON_VERSION) {
            cs.ledger.clear(); // pre-catalog placeholder stock (sand/stick/cobble) — reseeds next tick
        }
        cs.countedHomes = tag.contains("CountedHomes") ? tag.getInt("CountedHomes") : cs.believedPop;
        cs.popDrift = tag.getInt("PopDrift");
        cs.prosperity = tag.contains("Prosperity") ? tag.getDouble("Prosperity") : 0.5;
        cs.fedRatio = tag.contains("FedRatio") ? tag.getDouble("FedRatio") : 1.0;
        cs.tradeVolume = tag.getDouble("TradeVolume");
        cs.dayIndex = tag.getLong("DayIndex");
        loadStringIntMap(tag.getCompound("JobPois"), cs.jobPois);
        loadStringIntMap(tag.getCompound("ResourceChunks"), cs.resourceChunks);
        loadStringIntMap(tag.getCompound("Imports"), cs.imports);
        for (long c : tag.getLongArray("ScannedChunks")) cs.scannedChunks.add(c);
        ListTag demandList = tag.getList("Demands", Tag.TAG_COMPOUND);
        for (int i = 0; i < demandList.size(); i++) {
            CompoundTag c = demandList.getCompound(i);
            cs.demands.add(new Demand(c.getString("Item"), c.getInt("Qty"), c.getLong("Day")));
        }
        CompoundTag cds = tag.getCompound("DemandCooldowns");
        for (String key : cds.getAllKeys()) cs.demandCooldowns.put(key, cds.getLong(key));
        return cs;
    }
}
