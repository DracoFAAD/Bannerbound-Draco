package com.bannerbound.core.item;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import javax.annotation.Nullable;

import com.bannerbound.core.BannerboundCore;
import com.bannerbound.core.api.settlement.Homes;
import com.bannerbound.core.api.settlement.Settlement;
import com.bannerbound.core.api.settlement.SettlementData;
import com.bannerbound.core.api.settlement.Workshop;
import com.bannerbound.core.api.workshop.WorkBlockRegistry;
import com.bannerbound.core.api.workshop.Workshops;
import com.bannerbound.core.api.world.BlockSelection;
import com.bannerbound.core.api.world.BlockSelectionRegistry;
import com.bannerbound.core.network.WorkshopMenu;
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
 * Workshop Orders rod — the Home Marker Rod's crafter-workshop sibling (see CRAFTER_PLAN.md).
 * Key difference from homes: workshops have NO anchor block, so the rod binds to a workshop
 * <em>id</em> and the FIRST committed box <b>creates</b> the workshop (it must contain a
 * registered work block so we know the building is meant to be one).
 *
 * <p>Interaction map:
 * <ul>
 *   <li><b>Right-click a block (unbound)</b> — A→B cycle; committing the first box creates a new
 *       workshop, binds the rod to it, and opens nothing (keep drawing boxes to grow it).</li>
 *   <li><b>Right-click a block (bound)</b> — A→B cycle; each box ADDS to the bound workshop's
 *       union (must stay connected — no skipping blocks).</li>
 *   <li><b>Shift-right-click a block inside an existing workshop</b> — bind the rod to it and
 *       open its menu.</li>
 *   <li><b>Shift-right-click in air</b> — unbind (clears the in-progress A too).</li>
 *   <li><b>Shift-left-click a block inside a workshop box</b> — remove that box (the smallest
 *       one containing the click when boxes overlap). Removing the last box deletes the
 *       workshop and unassigns its workers.</li>
 * </ul>
 *
 * Caps mirror the Home Marker Rod: per-axis box cap {@link #MAX_BOX_DIM}, per-workshop box count
 * {@link #MAX_BOXES_PER_WORKSHOP}, and the whole union must fit in {@link #MAX_UNION_SPAN} per
 * axis (workshops have no anchor block to anchor a radius to).
 */
public class WorkshopRodItem extends Item {
    public static final int MAX_BOX_DIM = 32;
    public static final int MAX_BOXES_PER_WORKSHOP = 128;
    /** Max per-axis span of the whole box union — the no-anchor stand-in for the home rod's
     *  radius-from-House-Block cap. */
    public static final int MAX_UNION_SPAN = 48;

    public WorkshopRodItem(Properties properties) {
        super(properties);
    }

    // ─── Shift-right-click in air → unbind ──────────────────────────────────────────────────

    @Override
    public InteractionResultHolder<ItemStack> use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        if (level.isClientSide || !(player instanceof ServerPlayer sp)) {
            return InteractionResultHolder.success(stack);
        }
        if (player.isShiftKeyDown()) {
            if (boundWorkshopId(stack) != null) {
                clearAll(stack);
                sp.displayClientMessage(
                    Component.translatable("bannerbound.workshop_rod.unbound")
                        .withStyle(ChatFormatting.GRAY), true);
            } else {
                sp.displayClientMessage(
                    Component.translatable("bannerbound.workshop_rod.hint")
                        .withStyle(ChatFormatting.YELLOW), true);
            }
            return InteractionResultHolder.consume(stack);
        }
        return InteractionResultHolder.pass(stack);
    }

    // ─── Right-click on a block ───────────────────────────────────────────────────────────────

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
            // Shift-right-click inside an existing workshop → bind + open its menu.
            Workshops.Hit hit = Workshops.findAt(sl, clicked);
            if (hit != null && hit.settlement().members().contains(sp.getUUID())) {
                stack.set(BannerboundCore.BOUND_WORKSHOP_ID.get(), hit.workshop().id().toString());
                stack.remove(BannerboundCore.MARKER_POINT_A.get());
                WorkshopMenu.open(sp, sl, hit);
                return InteractionResult.CONSUME;
            }
            sp.displayClientMessage(
                Component.translatable("bannerbound.workshop_rod.no_workshop_here")
                    .withStyle(ChatFormatting.YELLOW), true);
            return InteractionResult.CONSUME;
        }

        // Non-shift: A→B cycle (bound = grow that workshop; unbound = first commit creates one).
        BlockPos a = stack.get(BannerboundCore.MARKER_POINT_A.get());
        if (a == null) {
            stack.set(BannerboundCore.MARKER_POINT_A.get(), clicked.immutable());
            sp.displayClientMessage(
                Component.translatable("bannerbound.workshop_rod.point_a_set",
                    clicked.getX(), clicked.getY(), clicked.getZ())
                    .withStyle(ChatFormatting.GREEN), true);
            return InteractionResult.CONSUME;
        }
        tryCommitBox(sl, sp, stack, a, clicked);
        stack.remove(BannerboundCore.MARKER_POINT_A.get());
        return InteractionResult.CONSUME;
    }

    /** Caps + overlap + connectivity checks; creates the workshop on the first box; registers the
     *  box, broadcasts, and re-validates so the player sees the new status immediately. */
    private static void tryCommitBox(ServerLevel sl, ServerPlayer sp, ItemStack stack,
                                      BlockPos a, BlockPos b) {
        // Per-axis box cap.
        int worstAxis = Math.max(Math.abs(b.getX() - a.getX()) + 1,
            Math.max(Math.abs(b.getY() - a.getY()) + 1, Math.abs(b.getZ() - a.getZ()) + 1));
        if (worstAxis > MAX_BOX_DIM) {
            reject(sp, "bannerbound.workshop_rod.too_long", worstAxis, MAX_BOX_DIM);
            return;
        }
        // The clicked area must belong to a settlement (the workshop's owner).
        SettlementData data = SettlementData.get(sl.getServer().overworld());
        Settlement owner = data.getByChunk(new ChunkPos(b).toLong());
        if (owner == null) {
            reject(sp, "bannerbound.workshop_rod.no_settlement");
            return;
        }

        // Resolve (or mint) the workshop this box belongs to.
        UUID boundId = boundWorkshopId(stack);
        Workshop workshop = boundId == null ? null : owner.getWorkshop(boundId);
        boolean creating = workshop == null;
        UUID workshopId = creating ? UUID.randomUUID() : workshop.id();

        BlockSelectionRegistry registry = BlockSelectionRegistry.get(sl);
        if (registry.findByWorkshop(workshopId).size() >= MAX_BOXES_PER_WORKSHOP) {
            reject(sp, "bannerbound.workshop_rod.too_many_boxes", MAX_BOXES_PER_WORKSHOP);
            return;
        }
        UUID selectionId = UUID.randomUUID();
        BlockSelection candidate = BlockSelection.workshop(
            selectionId, owner.id(), owner.color().ordinal(), a, b, workshopId, sp.getUUID());
        BlockSelection conflict = registry.firstConflictingOverlap(candidate, selectionId);
        if (conflict != null) {
            reject(sp, conflict.kind() == BlockSelection.Kind.HOME
                ? "bannerbound.workshop_rod.overlap_house"
                : "bannerbound.workshop_rod.overlap_conflict");
            return;
        }

        // Prospective union: solids present, connected, within the union span cap.
        List<BlockSelection> prospective = new ArrayList<>(registry.findByWorkshop(workshopId));
        prospective.add(candidate);
        Set<BlockPos> marked = Homes.collectMarkedSolids(sl, prospective);
        if (marked.isEmpty()) {
            reject(sp, "bannerbound.workshop_rod.no_solids");
            return;
        }
        if (!Homes.isConnected(marked)) {
            reject(sp, "bannerbound.workshop_rod.disconnected");
            return;
        }
        if (unionSpanExceeds(marked, MAX_UNION_SPAN)) {
            reject(sp, "bannerbound.workshop_rod.too_big", MAX_UNION_SPAN);
            return;
        }
        // The first box must contain a registered work block — that's what makes the building a
        // workshop rather than an arbitrary marking.
        if (creating && !containsWorkBlock(sl, marked)) {
            reject(sp, "bannerbound.workshop_rod.no_work_block");
            return;
        }

        if (creating) {
            workshop = new Workshop(workshopId);
            owner.putWorkshop(workshop);
            stack.set(BannerboundCore.BOUND_WORKSHOP_ID.get(), workshopId.toString());
        }
        registry.register(candidate);
        SelectionBroadcaster.broadcast(sl.getServer());
        Workshop.Status status = Workshops.validate(sl, workshop);
        data.setDirty();

        String displayName = workshop.customName().isEmpty()
            ? Component.translatable(WorkBlockRegistry.displayKey(workshop.derivedTypeId())).getString()
            : workshop.customName();
        sp.displayClientMessage(
            Component.translatable(
                creating ? "bannerbound.workshop_rod.created" : "bannerbound.workshop_rod.box_committed",
                displayName,
                Component.translatable("bannerbound.workshop.status." + status.name().toLowerCase()))
                .withStyle(status == Workshop.Status.VALID ? ChatFormatting.GREEN : ChatFormatting.YELLOW),
            true);
    }

    // ─── Shift-left-click on a block → remove the box under the cursor ──────────────────────

    /** Routed in from {@link Events#onLeftClickBlock}: removes the workshop box containing
     *  {@code pos} (the smallest, when boxes overlap — so a sliver can be deleted without
     *  swallowing the big box around it). Removing the last box deletes the workshop record
     *  and unassigns its workers. */
    private static void removeBoxAt(ServerLevel sl, ServerPlayer sp, BlockPos pos) {
        BlockSelectionRegistry registry = BlockSelectionRegistry.get(sl);
        BlockSelection target = null;
        long targetVolume = Long.MAX_VALUE;
        for (BlockSelection s : registry.getAll()) {
            if (s.kind() != BlockSelection.Kind.WORKSHOP || !s.contains(pos)) continue;
            long volume = (long) (s.maxX() - s.minX() + 1)
                * (s.maxY() - s.minY() + 1) * (s.maxZ() - s.minZ() + 1);
            if (volume < targetVolume) {
                target = s;
                targetVolume = volume;
            }
        }
        if (target == null) {
            reject(sp, "bannerbound.workshop_rod.no_box_here");
            return;
        }
        SettlementData data = SettlementData.get(sl.getServer().overworld());
        Settlement owner = data.getById(target.settlementId());
        Workshop workshop = owner != null ? owner.getWorkshop(target.homeId()) : null;
        registry.unregister(target.rodId());

        String displayName = workshop == null ? "?"
            : workshop.customName().isEmpty()
                ? Component.translatable(WorkBlockRegistry.displayKey(workshop.derivedTypeId())).getString()
                : workshop.customName();
        if (workshop != null && registry.findByWorkshop(workshop.id()).isEmpty()) {
            // Last box gone → the workshop itself is deleted; its workers go back to unemployed
            // (same path as a menu unassign — the citizen field is the source of truth).
            for (UUID workerId : List.copyOf(workshop.workers())) {
                if (sl.getEntity(workerId) instanceof com.bannerbound.core.entity.CitizenEntity ce
                        && com.bannerbound.core.entity.CrafterWorkGoal.JOB_TYPE_ID.equals(ce.getJobType())) {
                    ce.setJobType(null);
                }
            }
            owner.removeWorkshop(workshop.id());
            sp.displayClientMessage(
                Component.translatable("bannerbound.workshop_rod.workshop_removed", displayName)
                    .withStyle(ChatFormatting.YELLOW), true);
        } else if (workshop != null) {
            Workshop.Status status = Workshops.validate(sl, workshop);
            sp.displayClientMessage(
                Component.translatable("bannerbound.workshop_rod.box_removed", displayName,
                    Component.translatable("bannerbound.workshop.status." + status.name().toLowerCase()))
                    .withStyle(status == Workshop.Status.VALID
                        ? ChatFormatting.GREEN : ChatFormatting.YELLOW), true);
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
            if (!(player.getMainHandItem().getItem() instanceof WorkshopRodItem rod)) return;
            event.setCanceled(true);
            if (!(event.getLevel() instanceof ServerLevel sl)
                    || !(player instanceof ServerPlayer sp)) return;
            // LeftClickBlock re-fires while the button is held — a short item cooldown turns the
            // gesture into one-removal-per-click instead of chewing through stacked boxes.
            if (sp.getCooldowns().isOnCooldown(rod)) return;
            sp.getCooldowns().addCooldown(rod, 8);
            removeBoxAt(sl, sp, event.getPos());
        }
    }

    private static boolean containsWorkBlock(ServerLevel sl, Set<BlockPos> marked) {
        for (BlockPos p : marked) {
            if (WorkBlockRegistry.isWorkBlock(sl.getBlockState(p))) return true;
        }
        return false;
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

    /** The rod's bound workshop id, or null when unbound / malformed. */
    @Nullable
    public static UUID boundWorkshopId(ItemStack stack) {
        String raw = stack.get(BannerboundCore.BOUND_WORKSHOP_ID.get());
        if (raw == null || raw.isEmpty()) return null;
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static void clearAll(ItemStack stack) {
        stack.remove(BannerboundCore.BOUND_WORKSHOP_ID.get());
        stack.remove(BannerboundCore.MARKER_POINT_A.get());
    }
}
