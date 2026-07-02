package com.bannerbound.antiquity.block.entity;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.antiquity.BannerboundAntiquity;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Block entity for the Tanning Rack — holds ONE hide at a time and walks the scrape → cure → dry
 * pipeline (one rack = one hide in progress, like the pottery slab). Phases:
 * <ul>
 *   <li>{@link Phase#EMPTY} — nothing on the rack.</li>
 *   <li>{@link Phase#RAW} — a quality-tagged raw hide is laid out; right-clicking with a knife opens
 *       the scrape minigame, which consumes it for {@code scraped_hide × quality}.</li>
 *   <li>{@link Phase#DRYING} — a cured hide is drying; a server timer counts down.</li>
 *   <li>{@link Phase#DRY} — drying finished; right-click empty-handed to take the leather.</li>
 * </ul>
 */
@ApiStatus.Internal
public class TanningRackBlockEntity extends BlockEntity {
    /** Ticks a cured hide takes to dry into leather (~60s). */
    public static final int DRY_TICKS = 1200;

    public enum Phase { EMPTY, RAW, DRYING, DRY }

    private Phase phase = Phase.EMPTY;
    /** The laid-out hide: the raw hide (RAW) so we keep its quality; empty otherwise. */
    private ItemStack held = ItemStack.EMPTY;
    private int dryTicks = 0;

    public TanningRackBlockEntity(BlockPos pos, BlockState state) {
        super(BannerboundAntiquity.TANNING_RACK_BE.get(), pos, state);
    }

    public Phase getPhase() {
        return phase;
    }

    /** The item currently shown on the rack (raw hide / cured hide / finished leather), for the renderer. */
    public ItemStack getDisplayStack() {
        return switch (phase) {
            case RAW -> held;
            case DRYING -> new ItemStack(BannerboundAntiquity.CURED_HIDE.get());
            case DRY -> new ItemStack(net.minecraft.world.item.Items.LEATHER);
            case EMPTY -> ItemStack.EMPTY;
        };
    }

    public ItemStack getRawHide() {
        return held;
    }

    // ─── Phase transitions (called from TanningRackBlock / Tannery) ───────────────────────────────

    /** Lay a raw hide on an empty rack. */
    public boolean placeRaw(ItemStack rawHide) {
        if (phase != Phase.EMPTY) return false;
        held = rawHide.copyWithCount(1);
        phase = Phase.RAW;
        setChanged();
        return true;
    }

    /** Lay a cured hide on an empty rack and start the drying timer. */
    public boolean placeCured() {
        if (phase != Phase.EMPTY) return false;
        phase = Phase.DRYING;
        dryTicks = DRY_TICKS;
        held = ItemStack.EMPTY;
        setChanged();
        return true;
    }

    /** Take whatever the rack currently holds back into hand, resetting to empty. */
    public ItemStack retrieve() {
        ItemStack out = switch (phase) {
            case RAW -> held;
            case DRYING -> new ItemStack(BannerboundAntiquity.CURED_HIDE.get());
            case DRY -> new ItemStack(net.minecraft.world.item.Items.LEATHER);
            case EMPTY -> ItemStack.EMPTY;
        };
        clear();
        return out;
    }

    /** Consume the raw hide after a finished scrape; the caller emits the scraped hides. */
    public void clear() {
        phase = Phase.EMPTY;
        held = ItemStack.EMPTY;
        dryTicks = 0;
        setChanged();
    }

    public boolean isRaw() {
        return phase == Phase.RAW;
    }

    public boolean isDry() {
        return phase == Phase.DRY;
    }

    /** Drying progress 0..1 (0 = just placed, 1 = dry) — drives the cured→leather render cross-fade. */
    public float dryProgress() {
        if (phase == Phase.DRY) return 1.0F;
        if (phase != Phase.DRYING) return 0.0F;
        return 1.0F - dryTicks / (float) DRY_TICKS;
    }

    // ─── Ticking (drying timer + ambient particles) ───────────────────────────────────────────────

    public static void tick(Level level, BlockPos pos, BlockState state, TanningRackBlockEntity be) {
        if (be.phase != Phase.DRYING) return;
        // Both sides count down so the client fade stays smooth without per-tick network sync; the
        // server is authoritative for the DRYING → DRY transition (and re-syncs at the end).
        if (be.dryTicks > 0) be.dryTicks--;
        if (level.isClientSide) {
            if (level.random.nextInt(5) == 0) {
                level.addParticle(net.minecraft.core.particles.ParticleTypes.SMOKE,
                    pos.getX() + 0.5 + (level.random.nextDouble() - 0.5) * 1.6,
                    pos.getY() + 1.1 + level.random.nextDouble() * 0.7,
                    pos.getZ() + 0.5 + (level.random.nextDouble() - 0.5) * 1.6,
                    0.0, 0.015, 0.0);
            }
        } else if (be.dryTicks <= 0) {
            be.phase = Phase.DRY;
            be.setChanged();
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
        tag.putString("Phase", phase.name());
        tag.putInt("DryTicks", dryTicks);
        if (!held.isEmpty()) {
            tag.put("Held", held.save(provider));
        }
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider provider) {
        super.loadAdditional(tag, provider);
        try {
            phase = Phase.valueOf(tag.getString("Phase"));
        } catch (IllegalArgumentException e) {
            phase = Phase.EMPTY;
        }
        dryTicks = tag.getInt("DryTicks");
        held = tag.contains("Held")
            ? ItemStack.parse(provider, tag.getCompound("Held")).orElse(ItemStack.EMPTY)
            : ItemStack.EMPTY;
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
