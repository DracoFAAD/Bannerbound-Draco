package com.bannerbound.core.api;

import com.bannerbound.core.api.entity.ForesterTreeRegistry;
import com.bannerbound.core.api.farmer.AwaitingSeedRegistry;
import com.bannerbound.core.api.farmer.FarmerFoodBonus;
import com.bannerbound.core.api.fisher.FisherCatchTable;
import com.bannerbound.core.api.research.OreDisguise;
import com.bannerbound.core.api.research.ResearchDefinition;
import com.bannerbound.core.api.research.ResearchManager;
import com.bannerbound.core.api.research.ToolAge;
import com.bannerbound.core.api.settlement.ChunkProtection;
import com.bannerbound.core.api.settlement.Citizen;
import com.bannerbound.core.api.settlement.Era;
import com.bannerbound.core.api.settlement.ImmigrationManager;
import com.bannerbound.core.api.settlement.Settlement;
import com.bannerbound.core.api.settlement.SettlementData;
import com.bannerbound.core.api.settlement.SettlementManager;
import com.bannerbound.core.api.settlement.Workstation;
import com.bannerbound.core.api.territory.ChunkClaimCost;
import com.bannerbound.core.api.territory.TerritoryService;
import com.bannerbound.core.api.world.BlockSelection;
import com.bannerbound.core.api.world.BlockSelectionRegistry;

import com.bannerbound.core.BannerboundCore;

/**
 * Public entry point for the Bannerbound: Core API.
 * <p>
 * Everything reachable from {@code com.bannerbound.core.api.*} is the stable surface that
 * expansion mods may depend on. Anything outside that subtree is implementation detail and
 * marked with {@link org.jetbrains.annotations.ApiStatus.Internal} — those signatures can
 * change without notice.
 *
 * <h2>What you get</h2>
 * <ul>
 *   <li>{@code api.settlement} — {@link com.bannerbound.core.api.settlement.Settlement},
 *       {@link com.bannerbound.core.api.settlement.Citizen},
 *       {@link com.bannerbound.core.api.settlement.Era},
 *       {@link com.bannerbound.core.api.settlement.Workstation},
 *       {@link com.bannerbound.core.api.settlement.SettlementManager},
 *       {@link com.bannerbound.core.api.settlement.SettlementData},
 *       {@link com.bannerbound.core.api.settlement.ImmigrationManager},
 *       {@link com.bannerbound.core.api.settlement.ChunkProtection}.</li>
 *   <li>{@code api.research} — {@link com.bannerbound.core.api.research.ResearchDefinition},
 *       {@link com.bannerbound.core.api.research.ToolAge},
 *       {@link com.bannerbound.core.api.research.OreDisguise},
 *       {@link com.bannerbound.core.api.research.ResearchManager}, plus
 *       JSON loaders under {@code api.research.data}.</li>
 *   <li>{@code api.territory} — {@link com.bannerbound.core.api.territory.ChunkClaimCost},
 *       {@link com.bannerbound.core.api.territory.TerritoryService}, plus the loader under
 *       {@code api.territory.data}.</li>
 *   <li>{@code api.world} — {@link com.bannerbound.core.api.world.BlockSelection},
 *       {@link com.bannerbound.core.api.world.BlockSelectionRegistry}.</li>
 *   <li>{@code api.entity}, {@code api.farmer}, {@code api.fisher} — workstation-side
 *       service facades ({@link com.bannerbound.core.api.entity.ForesterTreeRegistry},
 *       {@link com.bannerbound.core.api.farmer.FarmerFoodBonus},
 *       {@link com.bannerbound.core.api.farmer.AwaitingSeedRegistry},
 *       {@link com.bannerbound.core.api.fisher.FisherCatchTable}).</li>
 *   <li>Registered game objects (blocks, items, entity types, etc.) are reachable as
 *       {@code public static final} fields on {@link com.bannerbound.core.BannerboundCore}.
 *       The fields are stable; the class itself is marked internal — do not extend it.</li>
 * </ul>
 *
 * <h2>Adding data without writing code</h2>
 * Every JSON loader under {@code api.*.data} scans <em>all</em> namespaces under
 * {@code data/<namespace>/} — so an expansion can ship its own research nodes, tool ages,
 * ore disguises, starting items, or chunk-claim costs by dropping JSON into
 * {@code data/<your_mod_id>/research/}, {@code .../tool_ages/}, etc. No code required.
 *
 * <h2>Custom events</h2>
 * Bannerbound currently fires <em>no</em> custom events. If you need to react to e.g.
 * "settlement founded" or "research completed," subscribe to NeoForge lifecycle events and
 * poll the relevant {@code Manager} class. Custom event types will be added in
 * {@code api.event} when there's a concrete consumer.
 */
public final class BannerboundAPI {
    private BannerboundAPI() {}

    /** Mod ID of Bannerbound: Core — convenience re-export of {@link BannerboundCore#MODID}. */
    public static final String MODID = BannerboundCore.MODID;

    /**
     * Semantic version of the API surface (distinct from mod version). Bump the major when
     * making breaking changes to any class in {@code com.bannerbound.core.api.*}.
     */
    public static final String API_VERSION = "1.0.0";
}
