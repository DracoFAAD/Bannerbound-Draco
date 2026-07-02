package com.bannerbound.core.menu;

import java.util.ArrayList;
import java.util.List;

import org.jetbrains.annotations.ApiStatus;
import org.jetbrains.annotations.Nullable;

import com.bannerbound.core.BannerboundCore;
import com.bannerbound.core.api.settlement.Settlement;
import com.bannerbound.core.api.settlement.SettlementData;
import com.bannerbound.core.api.settlement.Stockpile;
import com.bannerbound.core.entity.DropOffContainers;
import com.bannerbound.core.network.StockpileContentsPayload;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.network.PacketDistributor;

/**
 * Container menu for the Stockpile terminal. The storage side is <b>virtual</b> — a summed, sorted
 * list of everything across the stockpile's enclosed containers, synced to the client via
 * {@link StockpileContentsPayload} (totals can exceed a stack, which real {@link Slot}s can't hold).
 * Only the player's own inventory uses real slots. Shift-clicking a player item deposits it into the
 * stockpile (native {@link #quickMoveStack}); clicking a virtual cell withdraws via a payload that
 * calls {@link #withdraw}.
 */
@ApiStatus.Internal
public class StockpileMenu extends AbstractContainerMenu {
    public static final int PLAYER_INV_X = 8;
    // Pushed down to leave a toggle row between the storage grid and the player inventory.
    public static final int PLAYER_INV_Y = 166;

    private final int menuId;
    private final BlockPos pos;
    private final Level level;
    @Nullable
    private final ServerPlayer serverPlayer; // server only — null on the client
    /** Client: the latest synced summed list. Server: recomputed on demand. */
    private List<StockEntry> contents = List.of();
    /** Client: synced enclosure status ordinal + container count, for the header. */
    private int statusOrdinal;
    private int containerCount;
    /** Client: synced occupied / total slot counts across the enclosed containers, for the
     *  "N slots free" capacity readout. */
    private int usedSlots;
    private int totalSlots;
    /** Client: synced worker-access toggles (default open) driving the terminal's two buttons. */
    private boolean allowDeposit = true;
    private boolean allowTake = true;
    /** Client: synced show-for-trading flag (default closed) — the trade toggle button state. */
    private boolean showTrade = false;

    /** Client factory — reads the block pos from the open-screen buffer. */
    public StockpileMenu(int id, Inventory inv, RegistryFriendlyByteBuf buf) {
        this(id, inv, buf.readBlockPos(), null);
    }

    /** Server constructor (via the block's {@code SimpleMenuProvider}). */
    public StockpileMenu(int id, Inventory inv, BlockPos pos, @Nullable ServerPlayer player) {
        super(BannerboundCore.STOCKPILE_MENU.get(), id);
        this.menuId = id;
        this.pos = pos.immutable();
        this.level = inv.player.level();
        this.serverPlayer = player;
        addPlayerInventory(inv);
    }

    private void addPlayerInventory(Inventory inv) {
        for (int row = 0; row < 3; row++) {
            for (int col = 0; col < 9; col++) {
                addSlot(new Slot(inv, col + row * 9 + 9,
                    PLAYER_INV_X + col * 18, PLAYER_INV_Y + row * 18));
            }
        }
        for (int col = 0; col < 9; col++) {
            addSlot(new Slot(inv, col, PLAYER_INV_X + col * 18, PLAYER_INV_Y + 58));
        }
    }

    public int menuId() { return menuId; }
    public BlockPos pos() { return pos; }
    public List<StockEntry> contents() { return contents; }
    public int statusOrdinal() { return statusOrdinal; }
    public int containerCount() { return containerCount; }
    public int usedSlots() { return usedSlots; }
    public int totalSlots() { return totalSlots; }
    public int freeSlots() { return Math.max(0, totalSlots - usedSlots); }
    public boolean allowDeposit() { return allowDeposit; }
    public boolean allowTake() { return allowTake; }
    public boolean showTrade() { return showTrade; }

    /** Client: apply a synced snapshot from the server. */
    public void setSnapshot(int status, int count, int used, int total,
                            boolean allowDeposit, boolean allowTake, boolean showTrade,
                            List<StockEntry> entries) {
        this.statusOrdinal = status;
        this.containerCount = count;
        this.usedSlots = used;
        this.totalSlots = total;
        this.allowDeposit = allowDeposit;
        this.allowTake = allowTake;
        this.showTrade = showTrade;
        this.contents = entries;
    }

    /** Server: flip a toggle on this stockpile's record and re-sync. {@code toggle} selects which
     *  flag ({@code StockpileTogglePayload.TOGGLE_*}). No-op without access or a record. */
    public void setWorkerAccess(ServerPlayer player, int toggle, boolean value) {
        if (!com.bannerbound.core.api.settlement.DiplomacyManager.canAccessStockpile(player, pos)) return;
        Stockpile sp = record();
        if (sp == null) return;
        switch (toggle) {
            case com.bannerbound.core.network.StockpileTogglePayload.TOGGLE_DEPOSIT ->
                sp.setAllowWorkerDeposit(value);
            case com.bannerbound.core.network.StockpileTogglePayload.TOGGLE_TAKE ->
                sp.setAllowWorkerTake(value);
            case com.bannerbound.core.network.StockpileTogglePayload.TOGGLE_TRADE ->
                sp.setShowForTrading(value);
            default -> { return; }
        }
        // Push the change to autonomous workers right away (don't wait out the pool-cache TTL), and
        // persist it.
        if (level instanceof ServerLevel sl) {
            Settlement owner = SettlementData.get(sl.getServer().overworld())
                .getByChunk(new net.minecraft.world.level.ChunkPos(pos).toLong());
            if (owner != null) {
                com.bannerbound.core.entity.SettlementStorage.invalidate(owner.id());
                SettlementData.get(sl.getServer().overworld()).setDirty();
            }
        }
        broadcastChanges();
    }

    /** Server: {occupied, total} slot counts across the enclosed containers (0,0 if no aggregate). */
    private int[] slotCounts() {
        Container c = aggregate();
        if (c == null) return new int[]{0, 0};
        int size = c.getContainerSize();
        int used = 0;
        for (int i = 0; i < size; i++) {
            if (!c.getItem(i).isEmpty()) used++;
        }
        return new int[]{used, size};
    }

    /** The stockpile's aggregate inventory (server only), or null. */
    @Nullable
    private Container aggregate() {
        return level instanceof ServerLevel sl ? DropOffContainers.resolveDropOff(sl, pos) : null;
    }

    /** The stockpile record for this block (server only), or null. */
    @Nullable
    private Stockpile record() {
        if (!(level instanceof ServerLevel sl)) return null;
        net.minecraft.server.MinecraftServer server = sl.getServer();
        if (server == null) return null;
        Settlement owner = SettlementData.get(server.overworld())
            .getByChunk(new net.minecraft.world.level.ChunkPos(pos).toLong());
        return owner != null ? owner.getStockpile(pos) : null;
    }

    /** Sums the enclosed containers into display entries (count-1 stack + total), sorted by total. */
    public List<StockEntry> computeSummed() {
        Container c = aggregate();
        if (c == null) return List.of();
        List<StockEntry> out = new ArrayList<>();
        int size = c.getContainerSize();
        for (int i = 0; i < size; i++) {
            ItemStack s = c.getItem(i);
            if (s.isEmpty()) continue;
            boolean merged = false;
            for (int j = 0; j < out.size(); j++) {
                StockEntry e = out.get(j);
                if (ItemStack.isSameItemSameComponents(e.display(), s)) {
                    out.set(j, new StockEntry(e.display(), e.total() + s.getCount()));
                    merged = true;
                    break;
                }
            }
            if (!merged) {
                ItemStack one = s.copy();
                one.setCount(1);
                out.add(new StockEntry(one, s.getCount()));
            }
        }
        out.sort((a, b) -> Integer.compare(b.total(), a.total()));
        return out;
    }

    /** Withdraws up to a stack (or half) of {@code template} from the stockpile into the player. */
    public void withdraw(ServerPlayer player, ItemStack template, boolean half) {
        if (!com.bannerbound.core.api.settlement.DiplomacyManager.canAccessStockpile(player, pos)) return;
        Container c = aggregate();
        if (c == null || template.isEmpty()) return;
        int want = half ? Math.max(1, template.getMaxStackSize() / 2) : template.getMaxStackSize();
        ItemStack pulled = ItemStack.EMPTY;
        int size = c.getContainerSize();
        for (int i = 0; i < size && (pulled.isEmpty() || pulled.getCount() < want); i++) {
            ItemStack s = c.getItem(i);
            if (s.isEmpty() || !ItemStack.isSameItemSameComponents(s, template)) continue;
            int take = Math.min(s.getCount(), want - (pulled.isEmpty() ? 0 : pulled.getCount()));
            if (take <= 0) continue;
            if (pulled.isEmpty()) {
                pulled = s.copy();
                pulled.setCount(take);
            } else {
                pulled.grow(take);
            }
            s.shrink(take);
            c.setItem(i, s.isEmpty() ? ItemStack.EMPTY : s);
        }
        if (pulled.isEmpty()) return;
        c.setChanged();
        if (!player.getInventory().add(pulled)) {
            player.drop(pulled, false);
        }
        broadcastChanges();
    }

    /** Deposits the cursor (carried) stack into the stockpile — all of it, or a single item. */
    public void deposit(ServerPlayer player, boolean single) {
        if (!com.bannerbound.core.api.settlement.DiplomacyManager.canAccessStockpile(player, pos)) return;
        Container c = aggregate();
        if (c == null) return;
        ItemStack carried = getCarried();
        if (carried.isEmpty()) return;
        ItemStack toInsert = carried.copy();
        if (single) toInsert.setCount(1);
        ItemStack leftover = DropOffContainers.insert(c, toInsert);
        int moved = toInsert.getCount() - leftover.getCount();
        if (moved > 0) {
            ItemStack newCarried = carried.copy();
            newCarried.shrink(moved);
            setCarried(newCarried.isEmpty() ? ItemStack.EMPTY : newCarried);
            broadcastChanges();
        }
    }

    @Override
    public ItemStack quickMoveStack(Player player, int index) {
        // Every slot is player inventory; shift-click deposits into the stockpile.
        Slot slot = slots.get(index);
        if (slot == null || !slot.hasItem()) return ItemStack.EMPTY;
        if (level instanceof ServerLevel) {
            if (player instanceof ServerPlayer sp
                    && !com.bannerbound.core.api.settlement.DiplomacyManager.canAccessStockpile(sp, pos)) {
                return ItemStack.EMPTY;
            }
            Container c = aggregate();
            if (c != null) {
                ItemStack stack = slot.getItem();
                ItemStack leftover = DropOffContainers.insert(c, stack.copy());
                int moved = stack.getCount() - leftover.getCount();
                if (moved > 0) {
                    stack.shrink(moved);
                    slot.setChanged();
                    broadcastChanges();
                }
            }
        }
        return ItemStack.EMPTY; // stop the shift-click loop; deposit isn't slot-to-slot
    }

    @Override
    public void broadcastChanges() {
        super.broadcastChanges();
        if (serverPlayer != null) {
            Stockpile sp = record();
            int status = sp != null ? sp.status().ordinal() : Stockpile.Status.UNMARKED.ordinal();
            int count = sp != null ? sp.containerCount() : 0;
            boolean allowDep = sp == null || sp.allowWorkerDeposit();
            boolean allowTk = sp == null || sp.allowWorkerTake();
            boolean trade = sp != null && sp.showForTrading();
            int[] slots = slotCounts();
            PacketDistributor.sendToPlayer(serverPlayer,
                new StockpileContentsPayload(menuId, status, count, slots[0], slots[1],
                    allowDep, allowTk, trade, computeSummed()));
        }
    }

    @Override
    public boolean stillValid(Player player) {
        return level.getBlockState(pos).is(BannerboundCore.STOCKPILE.get())
            && player.distanceToSqr(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5) <= 64.0
            && (!(player instanceof ServerPlayer sp)
                || com.bannerbound.core.api.settlement.DiplomacyManager.canAccessStockpile(sp, pos));
    }
}
