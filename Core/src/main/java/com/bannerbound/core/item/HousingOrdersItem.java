package com.bannerbound.core.item;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import javax.annotation.Nullable;

import com.bannerbound.core.BannerboundCore;
import com.bannerbound.core.api.settlement.ChunkBeauty;
import com.bannerbound.core.api.settlement.Home;
import com.bannerbound.core.api.settlement.Homes;
import com.bannerbound.core.api.settlement.HousingLimits;
import com.bannerbound.core.api.settlement.Settlement;
import com.bannerbound.core.api.settlement.SettlementData;
import com.bannerbound.core.api.world.BlockSelection;
import com.bannerbound.core.api.world.BlockSelectionRegistry;
import com.bannerbound.core.entity.HousingEvictionHook;
import com.bannerbound.core.network.OpenHouseStatusPayload;
import com.bannerbound.core.world.SelectionBroadcaster;

import net.minecraft.ChatFormatting;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;

/**
 * Housing Orders rod — the home twin of the Workshop Orders rod ({@link WorkshopRodItem}). Homes
 * have NO anchor block: the rod binds to a home <em>id</em> and the FIRST committed box that
 * contains a <b>bed</b> <b>creates</b> the home (the bed is what tells us the building is meant to
 * be a residence — the home analog of the workshop's "must contain a work block" rule).
 *
 * <p>Interaction map (identical to the Workshop Orders rod, bed-keyed):
 * <ul>
 *   <li><b>Right-click a block (unbound)</b> — A→B cycle; committing the first box (which must
 *       contain a bed) creates a new home and binds the rod to it (keep drawing to grow it).</li>
 *   <li><b>Right-click a block (bound)</b> — A→B cycle; each box ADDS to the bound home's union
 *       (must stay connected).</li>
 *   <li><b>Shift-right-click a block inside an existing home</b> — bind the rod to it and open its
 *       status panel (residents / appeal / beds).</li>
 *   <li><b>Shift-right-click in air</b> — unbind.</li>
 *   <li><b>Shift-left-click a block inside a home box</b> — remove that box (the smallest, when
 *       boxes overlap). Removing the last box deletes the home and evicts its residents.</li>
 * </ul>
 */
public class HousingOrdersItem extends Item {
    public static final int MAX_BOX_DIM = 32;
    public static final int MAX_BOXES_PER_HOME = 128;
    /** Hard per-axis ceiling on the whole box union — the per-era soft cap (BROKEN_TOO_BIG) is
     *  enforced in {@link Homes#validate} from {@link HousingLimits}. */
    public static final int MAX_UNION_SPAN = 2 * HousingLimits.MAX_RADIUS + 1;

    public HousingOrdersItem(Properties properties) {
        super(properties);
    }

    // ─── Shift-right-click in air → unbind ──────────────────────────────────────────────────────

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (level.isClientSide || !(player instanceof ServerPlayer sp)) {
            return InteractionResultHolder.success(stack);
        }
        if (player.isShiftKeyDown()) {
            if (boundHomeId(stack) != null) {
                clearAll(stack);
                sp.displayClientMessage(
                    Component.translatable("bannerbound.housing_orders.unbound")
                        .withStyle(ChatFormatting.GRAY), true);
            } else {
                sp.displayClientMessage(
                    Component.translatable("bannerbound.housing_orders.hint")
                        .withStyle(ChatFormatting.YELLOW), true);
            }
            return InteractionResultHolder.consume(stack);
        }
        return InteractionResultHolder.pass(stack);
    }

    // ─── Right-click on a block ─────────────────────────────────────────────────────────────────

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Level level = context.getLevel();
        Player player = context.getPlayer();
        if (level.isClientSide || !(player instanceof ServerPlayer sp) || !(level instanceof ServerLevel sl)) {
            return InteractionResult.SUCCESS;
        }
        ItemStack stack = context.getItemInHand();
        BlockPos clicked = context.getClickedPos();

        if (player.isShiftKeyDown()) {
            // Shift-right-click inside an existing home → bind + open its status panel.
            Homes.Hit hit = Homes.findAt(sl, clicked);
            if (hit != null) {
                stack.set(BannerboundCore.BOUND_HOME_ID.get(), hit.home().id().toString());
                stack.remove(BannerboundCore.MARKER_POINT_A.get());
                openStatusPanel(sp, sl, hit);
                return InteractionResult.CONSUME;
            }
            sp.displayClientMessage(
                Component.translatable("bannerbound.housing_orders.no_home_here")
                    .withStyle(ChatFormatting.YELLOW), true);
            return InteractionResult.CONSUME;
        }

        // Non-shift: A→B cycle (bound = grow that home; unbound = first commit creates one).
        BlockPos a = stack.get(BannerboundCore.MARKER_POINT_A.get());
        if (a == null) {
            stack.set(BannerboundCore.MARKER_POINT_A.get(), clicked.immutable());
            sp.displayClientMessage(
                Component.translatable("bannerbound.housing_orders.point_a_set",
                    clicked.getX(), clicked.getY(), clicked.getZ())
                    .withStyle(ChatFormatting.GREEN), true);
            return InteractionResult.CONSUME;
        }
        tryCommitBox(sl, sp, stack, a, clicked);
        stack.remove(BannerboundCore.MARKER_POINT_A.get());
        return InteractionResult.CONSUME;
    }

    /** Caps + overlap + connectivity + bed checks; creates the home on the first box; registers the
     *  box, broadcasts, and re-validates so the player sees the new status immediately. */
    private static void tryCommitBox(ServerLevel sl, ServerPlayer sp, ItemStack stack,
                                     BlockPos a, BlockPos b) {
        int worstAxis = Math.max(Math.abs(b.getX() - a.getX()) + 1,
            Math.max(Math.abs(b.getY() - a.getY()) + 1, Math.abs(b.getZ() - a.getZ()) + 1));
        if (worstAxis > MAX_BOX_DIM) {
            reject(sp, "bannerbound.housing_orders.too_long", worstAxis, MAX_BOX_DIM);
            return;
        }
        SettlementData data = SettlementData.get(sl.getServer().overworld());
        Settlement owner = data.getByChunk(new ChunkPos(b).toLong());
        if (owner == null) {
            reject(sp, "bannerbound.housing_orders.no_settlement");
            return;
        }

        UUID boundId = boundHomeId(stack);
        Home home = boundId == null ? null : owner.getHomeById(boundId);
        boolean creating = home == null;
        UUID homeId = creating ? UUID.randomUUID() : home.id();

        BlockSelectionRegistry registry = BlockSelectionRegistry.get(sl);
        if (registry.findByHome(homeId).size() >= MAX_BOXES_PER_HOME) {
            reject(sp, "bannerbound.housing_orders.too_many_boxes", MAX_BOXES_PER_HOME);
            return;
        }
        UUID selectionId = UUID.randomUUID();
        BlockSelection candidate = BlockSelection.home(
            selectionId, owner.id(), owner.color().ordinal(), a, b, homeId,
            BlockSelection.NO_HOME_POS, sp.getUUID());
        BlockSelection conflict = registry.firstConflictingOverlap(candidate, selectionId);
        if (conflict != null) {
            reject(sp, conflict.kind() == BlockSelection.Kind.WORKSHOP
                ? "bannerbound.housing_orders.overlap_workshop"
                : "bannerbound.housing_orders.overlap_conflict");
            return;
        }

        // Prospective union: solids present, connected, within the union span cap.
        List<BlockSelection> prospective = new ArrayList<>(registry.findByHome(homeId));
        prospective.add(candidate);
        Set<BlockPos> marked = Homes.collectMarkedSolids(sl, prospective);
        if (marked.isEmpty()) {
            reject(sp, "bannerbound.housing_orders.no_solids");
            return;
        }
        if (!Homes.isConnected(marked)) {
            reject(sp, "bannerbound.housing_orders.disconnected");
            return;
        }
        if (unionSpanExceeds(marked, MAX_UNION_SPAN)) {
            reject(sp, "bannerbound.housing_orders.too_big", MAX_UNION_SPAN);
            return;
        }
        // The first box must contain a bed — that's what makes the building a home.
        if (creating && !Homes.containsBedHead(sl, marked)) {
            reject(sp, "bannerbound.housing_orders.no_bed");
            return;
        }

        if (creating) {
            home = new Home(homeId);
            owner.putHome(home);
            stack.set(BannerboundCore.BOUND_HOME_ID.get(), homeId.toString());
        }
        registry.register(candidate);
        SelectionBroadcaster.broadcast(sl.getServer());
        Home.Status status = Homes.validate(sl, home);
        data.setDirty();

        sp.displayClientMessage(
            Component.translatable(
                creating ? "bannerbound.housing_orders.created" : "bannerbound.housing_orders.box_committed",
                Component.translatable("bannerbound.house.status." + status.name().toLowerCase()))
                .withStyle(status == Home.Status.VALID ? ChatFormatting.GREEN : ChatFormatting.YELLOW),
            true);
    }

    // ─── Shift-left-click on a block → remove the box under the cursor ──────────────────────────

    /** Removes the home box containing {@code pos} (the smallest, when boxes overlap). Removing the
     *  last box deletes the home record and evicts its residents. */
    private static void removeBoxAt(ServerLevel sl, ServerPlayer sp, BlockPos pos) {
        BlockSelectionRegistry registry = BlockSelectionRegistry.get(sl);
        BlockSelection target = null;
        long targetVolume = Long.MAX_VALUE;
        for (BlockSelection s : registry.getAll()) {
            if (s.kind() != BlockSelection.Kind.HOME || !s.contains(pos)) continue;
            long volume = (long) (s.maxX() - s.minX() + 1)
                * (s.maxY() - s.minY() + 1) * (s.maxZ() - s.minZ() + 1);
            if (volume < targetVolume) {
                target = s;
                targetVolume = volume;
            }
        }
        if (target == null) {
            reject(sp, "bannerbound.housing_orders.no_box_here");
            return;
        }
        SettlementData data = SettlementData.get(sl.getServer().overworld());
        Settlement owner = data.getById(target.settlementId());
        Home home = owner != null ? owner.getHomeById(target.homeId()) : null;
        registry.unregister(target.rodId());

        if (owner != null && home != null && registry.findByHome(home.id()).isEmpty()) {
            // Last box gone → the home is deleted; its residents are evicted (same path as a
            // bed loss — *_HOME thoughts cleared, sleepers woken).
            List<UUID> residents = new ArrayList<>(home.residents());
            owner.removeHome(home.id());
            if (!residents.isEmpty()) HousingEvictionHook.onEvict(sl, residents);
            sp.displayClientMessage(
                Component.translatable("bannerbound.housing_orders.home_removed")
                    .withStyle(ChatFormatting.YELLOW), true);
        } else if (home != null) {
            Home.Status status = Homes.validate(sl, home);
            sp.displayClientMessage(
                Component.translatable("bannerbound.housing_orders.box_removed",
                    Component.translatable("bannerbound.house.status." + status.name().toLowerCase()))
                    .withStyle(status == Home.Status.VALID ? ChatFormatting.GREEN : ChatFormatting.YELLOW), true);
        }
        data.setDirty();
        SelectionBroadcaster.broadcast(sl.getServer());
    }

    /** Left-click routing for the rod: vanilla has no item hook for attack, so the gesture comes
     *  in through the event. Cancelled on both sides so the rod never mines the clicked block. */
    @net.neoforged.fml.common.EventBusSubscriber(modid = BannerboundCore.MODID)
    public static final class Events {
        private Events() {
        }

        @net.neoforged.bus.api.SubscribeEvent
        static void onLeftClickBlock(net.neoforged.neoforge.event.entity.player.PlayerInteractEvent.LeftClickBlock event) {
            Player player = event.getEntity();
            if (!player.isShiftKeyDown()) return;
            if (!(player.getMainHandItem().getItem() instanceof HousingOrdersItem rod)) return;
            event.setCanceled(true);
            if (!(event.getLevel() instanceof ServerLevel sl)
                    || !(player instanceof ServerPlayer sp)) return;
            if (sp.getCooldowns().isOnCooldown(rod)) return;
            sp.getCooldowns().addCooldown(rod, 8);
            removeBoxAt(sl, sp, event.getPos());
        }
    }

    /** Builds the home status snapshot and opens the panel on the requesting player. Mirrors the
     *  old {@code HouseBlock.useWithoutItem} body, now keyed off the home record (no block). */
    private static void openStatusPanel(ServerPlayer sp, ServerLevel sl, Homes.Hit hit) {
        Home home = hit.home();
        // Dev diagnostic in the chat log (not the action bar) so a "why broken?" case is readable.
        sp.displayClientMessage(Component.literal("[home] " + Homes.diagnose(sl, home))
            .withStyle(ChatFormatting.DARK_GRAY), false);
        net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(sp, buildStatusPayload(sl, home));
    }

    /** Re-sends the House status panel to a player who is already looking at it — used after an
     *  in-panel resident unassign so the residents list, occupancy bar, and happiness refresh in
     *  place. Skips the dev diagnostic chat line that {@link #openStatusPanel} prints on open. */
    public static void refreshStatusPanel(ServerPlayer sp, ServerLevel sl, Home home) {
        net.neoforged.neoforge.network.PacketDistributor.sendToPlayer(sp, buildStatusPayload(sl, home));
    }

    /** Snapshots a home into the panel payload: status, occupancy, appeal/beauty, crowdedness,
     *  happiness, the demand checklist, and each resident's styled name + id (parallel lists, in
     *  residency order). */
    private static OpenHouseStatusPayload buildStatusPayload(ServerLevel sl, Home home) {
        Component statusText = Component.translatable(
            "bannerbound.house.status." + home.status().name().toLowerCase());
        ChunkBeauty beauty = home.cachedBeauty() != null ? home.cachedBeauty() : ChunkBeauty.BLAND;
        List<Component> residentNames = new ArrayList<>();
        List<UUID> residentIds = new ArrayList<>();
        for (UUID rid : home.residents()) {
            net.minecraft.world.entity.Entity ent = sl.getEntity(rid);
            if (ent instanceof com.bannerbound.core.entity.CitizenEntity oc && oc.getCustomName() != null) {
                residentNames.add(oc.getCustomName());
            } else {
                residentNames.add(Component.literal("Unknown Citizen"));
            }
            residentIds.add(rid);
        }
        List<OpenHouseStatusPayload.DemandView> demands = new ArrayList<>();
        for (com.bannerbound.core.api.settlement.HomeDemand.DemandState d : home.cachedDemands()) {
            demands.add(new OpenHouseStatusPayload.DemandView(d.demand().suffix(), d.met()));
        }
        return new OpenHouseStatusPayload(home.id(), statusText, home.status().ordinal(), home.bedCount(),
            home.residents().size(), home.cachedScore(), Component.translatable(beauty.langKey()),
            beauty.tierIndex(), home.cachedInteriorVolume(),
            (int) Math.round(home.cachedHomeHappiness()), demands, residentNames, residentIds);
    }

    private static boolean unionSpanExceeds(Set<BlockPos> marked, int maxSpan) {
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (BlockPos p : marked) {
            minX = Math.min(minX, p.getX()); maxX = Math.max(maxX, p.getX());
            minY = Math.min(minY, p.getY()); maxY = Math.max(maxY, p.getY());
            minZ = Math.min(minZ, p.getZ()); maxZ = Math.max(maxZ, p.getZ());
        }
        return (maxX - minX + 1) > maxSpan || (maxY - minY + 1) > maxSpan || (maxZ - minZ + 1) > maxSpan;
    }

    private static void reject(ServerPlayer sp, String key, Object... args) {
        sp.displayClientMessage(
            Component.translatable(key, args).withStyle(ChatFormatting.RED), true);
    }

    /** The rod's bound home id, or null when unbound / malformed. */
    @Nullable
    public static UUID boundHomeId(ItemStack stack) {
        String raw = stack.get(BannerboundCore.BOUND_HOME_ID.get());
        if (raw == null || raw.isEmpty()) return null;
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static void clearAll(ItemStack stack) {
        stack.remove(BannerboundCore.BOUND_HOME_ID.get());
        stack.remove(BannerboundCore.MARKER_POINT_A.get());
    }
}
