package com.bannerbound.core.api.research;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

import com.bannerbound.core.BannerboundCore;
import com.bannerbound.core.api.faith.Faith;
import com.bannerbound.core.api.faith.FaithData;
import com.bannerbound.core.api.faith.FaithManager;
import com.bannerbound.core.api.research.data.CultureTreeLoader;
import com.bannerbound.core.api.research.data.FaithTreeLoader;
import com.bannerbound.core.api.research.data.ResearchTreeLoader;
import com.bannerbound.core.api.settlement.Settlement;
import com.bannerbound.core.api.settlement.SettlementData;
import com.bannerbound.core.api.settlement.SettlementManager;

import net.minecraft.ChatFormatting;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;

/** Central index and runtime for all data-driven insights. */
public final class InsightManager {
    public enum TreeType {
        SCIENCE("science"), CULTURE("culture"), FAITH("faith");
        private final String key;
        TreeType(String key) { this.key = key; }
        public String storageKey(String nodeId) { return key + "|" + nodeId; }
    }

    private record Entry(TreeType tree, ResearchDefinition definition, InsightDefinition insight) {}
    private static volatile Map<String, List<Entry>> byType = Map.of();
    private static final Set<UUID> pendingSettlementSync = new java.util.HashSet<>();
    private static final Set<UUID> pendingFaithSync = new java.util.HashSet<>();

    private InsightManager() {}

    public static List<String> firedNodeIds(Iterable<String> keys, TreeType tree) {
        String prefix = tree.key + "|";
        List<String> out = new ArrayList<>();
        for (String key : keys) {
            if (key.startsWith(prefix)) out.add(key.substring(prefix.length()));
        }
        return out;
    }

    public static List<com.bannerbound.core.network.ResearchStateSyncPayload.ProgressEntry>
            counterEntries(Settlement settlement, TreeType tree,
                           Iterable<ResearchDefinition> definitions) {
        List<com.bannerbound.core.network.ResearchStateSyncPayload.ProgressEntry> out = new ArrayList<>();
        for (ResearchDefinition def : definitions) {
            if (def.insight() == null) continue;
            int count = settlement.insightCount(tree.storageKey(def.id()));
            if (count > 0) {
                out.add(new com.bannerbound.core.network.ResearchStateSyncPayload.ProgressEntry(def.id(), count));
            }
        }
        return out;
    }

    public static List<com.bannerbound.core.network.ResearchStateSyncPayload.ProgressEntry>
            counterEntries(Faith faith, TreeType tree, Iterable<ResearchDefinition> definitions) {
        List<com.bannerbound.core.network.ResearchStateSyncPayload.ProgressEntry> out = new ArrayList<>();
        for (ResearchDefinition def : definitions) {
            if (def.insight() == null) continue;
            int count = faith.insightCount(tree.storageKey(def.id()));
            if (count > 0) {
                out.add(new com.bannerbound.core.network.ResearchStateSyncPayload.ProgressEntry(def.id(), count));
            }
        }
        return out;
    }

    public static void rebuildIndex() {
        Map<String, List<Entry>> index = new HashMap<>();
        addAll(index, TreeType.SCIENCE, ResearchTreeLoader.getAll());
        addAll(index, TreeType.CULTURE, CultureTreeLoader.getAll());
        addAll(index, TreeType.FAITH, FaithTreeLoader.getAll());
        Map<String, List<Entry>> frozen = new HashMap<>();
        index.forEach((key, value) -> frozen.put(key, List.copyOf(value)));
        byType = Map.copyOf(frozen);
        BannerboundCore.LOGGER.info("Indexed {} insight trigger types", byType.size());
    }

    private static void addAll(Map<String, List<Entry>> index, TreeType tree,
                               Map<String, ResearchDefinition> definitions) {
        for (ResearchDefinition def : definitions.values()) {
            if (def.insight() == null) continue;
            index.computeIfAbsent(def.insight().trigger().type(), ignored -> new ArrayList<>())
                .add(new Entry(tree, def, def.insight()));
        }
    }

    /** True if any loaded insight watches this trigger type — lets hot event hooks early-out cheaply. */
    public static boolean isTracked(String triggerType) {
        return byType.containsKey(triggerType);
    }

    /**
     * Polls settlement holdings for every {@code obtain_item} insight and sets its counter to the amount
     * currently held — settlement storage (via {@link com.bannerbound.core.api.workshop.SettlementItemCensus})
     * plus the inventories of online members. LEVEL / "have ≥N now" semantics, exactly like
     * {@code reach_population}: the counter tracks the live held amount and firing is one-way (sticky).
     * <p>
     * This is tagless — nothing is stamped on items — so items stack normally, and it catches a stack
     * the moment it lands in a member's inventory however it got there (pulled out of a drying rack or
     * other custom workstation, taken from a chest, picked up off the ground). Throwing and re-collecting
     * an item can never raise the true held total, so the counter cannot be padded.
     */
    private static void pollObtain(MinecraftServer server, Settlement settlement) {
        List<Entry> entries = byType.get("obtain_item");
        if (entries == null || entries.isEmpty()) return;
        ServerLevel level = server.overworld();
        for (Entry entry : entries) {
            int held = obtainHoldings(level, server, settlement, entry.insight().trigger().target());
            if (held <= 0) continue;
            record(server, settlement, entry, held, InsightTriggerRegistry.Kind.LEVEL);
        }
    }

    /** Total of {@code target} (a flat item id or a {@code #tag}) the settlement currently holds across
     *  its storage and online members' inventories. */
    private static int obtainHoldings(ServerLevel level, MinecraftServer server, Settlement settlement,
                                      String target) {
        if (target == null || target.isEmpty()) return 0;
        if (target.startsWith("#")) {
            ResourceLocation tagId = ResourceLocation.tryParse(target.substring(1));
            if (tagId == null) return 0;
            int total = 0;
            for (net.minecraft.core.Holder<Item> holder
                    : BuiltInRegistries.ITEM.getTagOrEmpty(TagKey.create(Registries.ITEM, tagId))) {
                total += itemHoldings(level, server, settlement, holder.value());
            }
            return total;
        }
        ResourceLocation id = ResourceLocation.tryParse(target);
        if (id == null) return 0;
        Item item = BuiltInRegistries.ITEM.get(id);
        return item == net.minecraft.world.item.Items.AIR
            ? 0 : itemHoldings(level, server, settlement, item);
    }

    /** Count of one {@code item} across settlement storage + the inventories of online members. */
    private static int itemHoldings(ServerLevel level, MinecraftServer server, Settlement settlement, Item item) {
        int total = com.bannerbound.core.api.workshop.SettlementItemCensus.count(level, settlement, item);
        for (UUID memberId : settlement.members()) {
            ServerPlayer member = server.getPlayerList().getPlayer(memberId);
            if (member != null) total += member.getInventory().countItem(item);
        }
        return total;
    }

    /**
     * Public expansion hook. The predicate receives each insight's authored target and answers
     * whether this occurrence matches it.
     */
    public static void recordEvent(MinecraftServer server, Settlement settlement, String triggerType,
                                   Predicate<String> targetMatches, int amount) {
        if (server == null || settlement == null || amount <= 0) return;
        InsightTriggerRegistry.Type type = InsightTriggerRegistry.get(triggerType);
        if (type == null) return;
        for (Entry entry : byType.getOrDefault(triggerType, List.of())) {
            if (!targetMatches.test(entry.insight().trigger().target())) continue;
            record(server, settlement, entry, amount, type.kind());
        }
    }

    public static void tickLevels(MinecraftServer server) {
        if (server == null || server.getTickCount() % 20 != 0) return;
        for (Settlement settlement : SettlementData.get(server.overworld()).all()) {
            recordEvent(server, settlement, "reach_population", ignored -> true, settlement.population());
            pollObtain(server, settlement);
        }
        for (UUID settlementId : new ArrayList<>(pendingSettlementSync)) {
            Settlement settlement = SettlementData.get(server.overworld()).getById(settlementId);
            if (settlement != null) {
                ResearchManager.broadcastStateToSettlement(server, settlement);
                CultureManager.broadcastStateToSettlement(server, settlement);
            }
        }
        for (UUID faithId : new ArrayList<>(pendingFaithSync)) {
            Faith faith = FaithData.get(server.overworld()).byId(faithId);
            if (faith != null) FaithManager.broadcastTreeState(server, faith);
        }
        pendingSettlementSync.clear();
        pendingFaithSync.clear();
    }

    private static void record(MinecraftServer server, Settlement settlement, Entry entry, int amount,
                               InsightTriggerRegistry.Kind kind) {
        // Future-era nodes are deliberately unknown to this settlement. Do not let ordinary
        // actions reveal or pre-complete them through an insight before their era is reached.
        if (entry.definition().minAge().ordinal() > settlement.age().ordinal()) return;

        String key = entry.tree().storageKey(entry.definition().id());
        if (entry.tree() == TreeType.FAITH) {
            if (!settlement.hasFaith()) return;
            Faith faith = FaithData.get(server.overworld()).byId(settlement.faithId());
            if (faith == null || faith.hasFiredInsight(key)
                    || faith.completedResearches().contains(entry.definition().id())) return;
            if (entry.definition().faithPath() != null
                    && entry.definition().faithPath() != faith.path()) return;
            int next = kind == InsightTriggerRegistry.Kind.LEVEL
                ? amount : faith.insightCount(key) + amount;
            faith.setInsightCount(key, next);
            FaithData.get(server.overworld()).setDirty();
            pendingFaithSync.add(faith.id());
            if (next >= entry.insight().trigger().count()) fireFaith(server, settlement, faith, key, entry);
            return;
        }

        boolean complete = entry.tree() == TreeType.SCIENCE
            ? settlement.hasCompletedResearch(entry.definition().id())
            : settlement.hasCompletedCultureResearch(entry.definition().id());
        if (complete || settlement.hasFiredInsight(key)) return;
        int next = kind == InsightTriggerRegistry.Kind.LEVEL
            ? amount : settlement.insightCount(key) + amount;
        settlement.setInsightCount(key, next);
        SettlementData.get(server.overworld()).setDirty();
        pendingSettlementSync.add(settlement.id());
        if (next >= entry.insight().trigger().count()) fireSettlement(server, settlement, key, entry);
    }

    private static void fireSettlement(MinecraftServer server, Settlement settlement,
                                       String key, Entry entry) {
        double points = points(entry);
        boolean completed = entry.tree() == TreeType.SCIENCE
            ? ResearchManager.addInsightProgress(server, settlement, entry.definition(), points)
            : CultureManager.addInsightProgress(server, settlement, entry.definition(), points);
        settlement.markInsightFired(key);
        pendingSettlementSync.remove(settlement.id());
        SettlementData.get(server.overworld()).setDirty();
        announce(server, settlement, entry, completed);
        if (entry.tree() == TreeType.SCIENCE) ResearchManager.broadcastStateToSettlement(server, settlement);
        else CultureManager.broadcastStateToSettlement(server, settlement);
    }

    private static void fireFaith(MinecraftServer server, Settlement actingSettlement, Faith faith,
                                  String key, Entry entry) {
        boolean completed = FaithManager.addInsightProgress(server, faith, entry.definition(), points(entry));
        faith.markInsightFired(key);
        pendingFaithSync.remove(faith.id());
        FaithData.get(server.overworld()).setDirty();
        for (UUID settlementId : faith.memberSettlements()) {
            Settlement member = SettlementData.get(server.overworld()).getById(settlementId);
            if (member != null && entry.definition().minAge().ordinal() <= member.age().ordinal()) {
                announce(server, member, entry, completed);
            }
        }
        FaithManager.broadcastTreeState(server, faith);
    }

    private static double points(Entry entry) {
        return entry.insight().boostPoints() > 0.0
            ? entry.insight().boostPoints()
            : entry.insight().boostFraction() * entry.definition().cost();
    }

    private static void announce(MinecraftServer server, Settlement settlement, Entry entry, boolean completed) {
        String label = entry.insight().message().isBlank()
            ? entry.definition().name() : entry.insight().message();
        Component message = Component.translatable(
            completed ? "bannerbound.insight.completed" : "bannerbound.insight.gained", label)
            .withStyle(ChatFormatting.GOLD);
        SettlementManager.broadcastToSettlement(server, settlement, message);
        for (UUID memberId : settlement.members()) {
            ServerPlayer member = server.getPlayerList().getPlayer(memberId);
            if (member != null) {
                member.playNotifySound(BannerboundCore.INSIGHT_SOUND.get(),
                    net.minecraft.sounds.SoundSource.MASTER, 0.8f, 1.0f);
            }
        }
    }

    public static Predicate<String> matcherFor(Block block) {
        return target -> matches(target, BuiltInRegistries.BLOCK.getKey(block),
            raw -> BuiltInRegistries.BLOCK.wrapAsHolder(block).is(TagKey.create(Registries.BLOCK, raw)));
    }

    public static Predicate<String> matcherFor(EntityType<?> type) {
        return target -> matches(target, BuiltInRegistries.ENTITY_TYPE.getKey(type),
            raw -> BuiltInRegistries.ENTITY_TYPE.wrapAsHolder(type).is(TagKey.create(Registries.ENTITY_TYPE, raw)));
    }

    public static Predicate<String> matcherFor(Item item) {
        return target -> matches(target, BuiltInRegistries.ITEM.getKey(item),
            raw -> BuiltInRegistries.ITEM.wrapAsHolder(item).is(TagKey.create(Registries.ITEM, raw)));
    }

    private static boolean matches(String target, ResourceLocation direct,
                                   Predicate<ResourceLocation> tagMatches) {
        if (target == null || target.isEmpty()) return true;
        if (target.startsWith("#")) {
            ResourceLocation tag = ResourceLocation.tryParse(target.substring(1));
            return tag != null && tagMatches.test(tag);
        }
        ResourceLocation wanted = ResourceLocation.tryParse(target);
        return wanted != null && wanted.equals(direct);
    }
}
