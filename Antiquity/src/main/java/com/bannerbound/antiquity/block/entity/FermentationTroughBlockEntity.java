package com.bannerbound.antiquity.block.entity;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.antiquity.BannerboundAntiquity;
import com.bannerbound.antiquity.block.FermentationTroughBlock;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Block entity for a Fermentation Trough. Each cell stores its own <em>share</em> of liquid in units
 * (one bucket = {@link #UNITS_PER_CELL} units = 100% of one cell; a hand-scoop adds 1 unit = 20%).
 * Connected troughs form one shared pool — the displayed fill is the run's total ÷ capacity
 * ({@code FermentationTroughBlock.runFraction}).
 *
 * <p>Fermentation (GROG_PLAN.md Phase 2): charging the pool with a fermentable stamps a grog recipe id
 * + a start time; the grog is <em>ready</em> once enough game-time has passed (readiness is lazy —
 * computed from game-time on read). A light server tick fires the one-shot "ready" cue (sound + puff)
 * the moment it finishes. The ferment fields are kept identical across a pool's cells (the block
 * consolidates them on every structural change), so any cell answers for the whole pool. The recipe id
 * resolves the liquid's tint/identity via {@code GrogRecipeManager} (Phase 3 pours it into mugs/horns).
 */
@ApiStatus.Internal
public class FermentationTroughBlockEntity extends BlockEntity {
    /** Units per cell — one bucket, drawn as a full (100%) cell. A hand-scoop adds one unit (20%). */
    public static final int UNITS_PER_CELL = 5;

    /** This cell's share of the run's liquid, 0..{@link #UNITS_PER_CELL}. */
    private int units = 0;
    /** The fermenting/finished grog's recipe id, or {@code ""} for plain water. */
    private String grogRecipeId = "";
    /** Game-time the ferment started; ready once {@code gameTime - start >= fermentTicks}. */
    private long fermentStart = 0L;
    /** Snapshotted ferment duration (already warmth-adjusted at charge time). */
    private int fermentTicks = 0;
    /** Whether the "grog ready" cue has already fired (so it plays once, not every tick). */
    private boolean notified = false;

    public FermentationTroughBlockEntity(BlockPos pos, BlockState state) {
        super(BannerboundAntiquity.FERMENTATION_TROUGH_BE.get(), pos, state);
    }

    // ─── Liquid (per-cell share) ─────────────────────────────────────────────────────────────────

    public int units() {
        return units;
    }

    public int spaceLeft() {
        return UNITS_PER_CELL - units;
    }

    /** Add up to {@code n} units to this cell; returns how many were added (caller spills the rest). */
    public int addUnits(int n) {
        int added = Math.min(Math.max(0, n), spaceLeft());
        if (added > 0) {
            units += added;
            setChanged();
        }
        return added;
    }

    /** Remove one unit from this cell (a drained serving); returns false if the cell is empty. */
    public boolean removeUnit() {
        if (units <= 0) return false;
        units--;
        setChanged();
        return true;
    }

    // ─── Fermentation ──────────────────────────────────────────────────────────────────────────

    public boolean isCharged() {
        return !grogRecipeId.isEmpty();
    }

    public String grogRecipeId() {
        return grogRecipeId;
    }

    public long fermentStart() {
        return fermentStart;
    }

    public int fermentTicks() {
        return fermentTicks;
    }

    /** Still bubbling away (charged, not yet ready) — drives the rendered bubble layer + ambient FX. */
    public boolean fermenting(long gameTime) {
        return isCharged() && gameTime - fermentStart < fermentTicks;
    }

    /** Finished — the liquid is now grog (charged and past its ferment time). */
    public boolean grogReady(long gameTime) {
        return isCharged() && gameTime - fermentStart >= fermentTicks;
    }

    /** Ferment progress 0..1 (drives the liquid's colour ripening); 1 once charged with no duration. */
    public float fermentProgress(long gameTime) {
        if (!isCharged()) return 0.0F;
        if (fermentTicks <= 0) return 1.0F;
        return Math.min(1.0F, Math.max(0.0F, (gameTime - fermentStart) / (float) fermentTicks));
    }

    /** Begin fermenting this cell into {@code recipeId}, finishing {@code ticks} after {@code start}. */
    public void charge(String recipeId, long start, int ticks) {
        setFerment(recipeId, start, ticks);
    }

    /** Set the ferment fields verbatim (used to keep a pool's cells in lockstep); syncs only on change.
     *  Any change re-arms the one-shot "ready" cue. */
    public void setFerment(String recipeId, long start, int ticks) {
        if (grogRecipeId.equals(recipeId) && fermentStart == start && fermentTicks == ticks) return;
        grogRecipeId = recipeId;
        fermentStart = start;
        fermentTicks = ticks;
        notified = false;
        setChanged();
    }

    /** Light server tick: the moment the grog finishes, fire the one-shot ready cue (once per pool —
     *  only the pool-start cell, the one not open on its counter-clockwise side, emits). Cheap: a few
     *  comparisons that early-out until done. */
    public static void serverTick(Level level, BlockPos pos, BlockState state,
                                  FermentationTroughBlockEntity be) {
        if (!be.isCharged() || be.notified || !be.grogReady(level.getGameTime())) return;
        be.notified = true;
        be.setChanged();
        boolean poolStart = !state.hasProperty(FermentationTroughBlock.RIGHT)
            || !state.getValue(FermentationTroughBlock.RIGHT);
        if (poolStart && level instanceof ServerLevel sl) {
            sl.playSound(null, pos, SoundEvents.COMPOSTER_READY, SoundSource.BLOCKS, 0.8F, 1.0F);
            sl.sendParticles(ParticleTypes.POOF, pos.getX() + 0.5, pos.getY() + 0.65, pos.getZ() + 0.5,
                10, 0.3, 0.05, 0.3, 0.01);
        }
    }

    // ─── NBT + client sync ───────────────────────────────────────────────────────────────────────

    @Override
    public void setChanged() {
        super.setChanged();
        if (level != null && !level.isClientSide) {
            level.sendBlockUpdated(getBlockPos(), getBlockState(), getBlockState(), Block.UPDATE_CLIENTS);
        }
    }

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider provider) {
        super.saveAdditional(tag, provider);
        tag.putInt("Units", units);
        tag.putString("Grog", grogRecipeId);
        tag.putLong("FermentStart", fermentStart);
        tag.putInt("FermentTicks", fermentTicks);
        tag.putBoolean("Notified", notified);
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider provider) {
        super.loadAdditional(tag, provider);
        units = tag.getInt("Units");
        grogRecipeId = tag.getString("Grog");
        fermentStart = tag.getLong("FermentStart");
        fermentTicks = tag.getInt("FermentTicks");
        notified = tag.getBoolean("Notified");
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider provider) {
        CompoundTag tag = new CompoundTag();
        saveAdditional(tag, provider);
        return tag;
    }

    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }
}
