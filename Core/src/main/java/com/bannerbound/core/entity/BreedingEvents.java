package com.bannerbound.core.entity;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.core.BannerboundCore;
import com.bannerbound.core.Config;

import net.minecraft.core.BlockPos;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.tags.FluidTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.entity.animal.Animal;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import com.bannerbound.core.building.PenEnclosure;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.event.entity.living.BabyEntitySpawnEvent;

/**
 * Breeding is a <b>roll</b>, not a guarantee — for ALL animal breeding (player-fed or herder-fed). Hooks
 * {@link BabyEntitySpawnEvent}: success → the baby spawns as normal (hearts); failure → cancel it (no baby)
 * with smoke particles, while the parents still take their breeding cooldown so a failed attempt isn't free.
 *
 * <p>The chance starts at {@link Config#HERDER_BASE_BREED_CHANCE} and is adjusted by the <b>ground the pair
 * stands on</b> and <b>nearby water</b> — the same rule everywhere, so a grassy, watered spot breeds best and
 * a sandy/gravelly, dry one breeds worst. The herder pen is no longer special-cased: an infertile pen isn't
 * blocked, it just breeds poorly. Fertile/infertile ground is recognised by tag
 * ({@code #bannerbound:fertile_breeding_ground} / {@code #bannerbound:infertile_breeding_ground}) so the
 * lists are data-tunable and other mods can contribute.
 */
@EventBusSubscriber(modid = BannerboundCore.MODID)
@ApiStatus.Internal
public final class BreedingEvents {
    private static final TagKey<Block> FERTILE_GROUND = TagKey.create(Registries.BLOCK,
        ResourceLocation.fromNamespaceAndPath(BannerboundCore.MODID, "fertile_breeding_ground"));
    private static final TagKey<Block> INFERTILE_GROUND = TagKey.create(Registries.BLOCK,
        ResourceLocation.fromNamespaceAndPath(BannerboundCore.MODID, "infertile_breeding_ground"));
    /** Manure blocks (Antiquity's {@code manure}) — droppings that foul a pen. Each one near a breeding
     *  pair (or counted across a pen) shaves the breed chance, so an unmucked pen breeds badly until a
     *  herder clears it. Recognised by tag so any addon's droppings contribute and Core stays self-contained
     *  (Core ships the tag empty; Antiquity populates it). */
    public static final TagKey<Block> MANURE = TagKey.create(Registries.BLOCK,
        ResourceLocation.fromNamespaceAndPath(BannerboundCore.MODID, "manure"));

    private BreedingEvents() {
    }

    @SubscribeEvent
    public static void onBabySpawn(BabyEntitySpawnEvent event) {
        if (!(event.getParentA() instanceof Animal a) || event.getChild() == null) return;
        if (!(a.level() instanceof ServerLevel sl)) return;
        Animal b = event.getParentB() instanceof Animal pb ? pb : null;

        if (sl.getRandom().nextDouble() < breedChance(sl, a.blockPosition())) return;   // success → baby + hearts

        // Failure: no baby. Cancel, force the breeding cooldown + clear love (so they don't instantly retry),
        // and puff smoke instead of hearts.
        event.setCanceled(true);
        a.setAge(6000);
        a.resetLove();
        if (b != null) { b.setAge(6000); b.resetLove(); }
        puffSmoke(sl, a);
        if (b != null) puffSmoke(sl, b);
    }

    /** The breeding roll a pair gets at {@code breedPos}: base chance, +bonus on fertile ground / −penalty on
     *  infertile ground (the block they stand ON), +bonus if water is within the configured radius. Clamped to
     *  [0,1]. Public so the herder/UI can preview a spot's odds. */
    public static double breedChance(Level level, BlockPos breedPos) {
        double chance = Config.HERDER_BASE_BREED_CHANCE.get()
            + groundModifier(level.getBlockState(breedPos.below()));   // block they stand ON
        if (waterNear(level, breedPos, Config.BREED_WATER_RADIUS.get())) {
            chance += Config.BREED_WATER_BONUS.get();
        }
        chance -= manureCount(level, breedPos, Config.BREED_MANURE_RADIUS.get())
            * Config.BREED_MANURE_PENALTY.get();   // a fouled pen breeds poorly until it's mucked out
        return clamp(chance);
    }

    /** A representative breeding chance for a whole PEN — its average ground modifier across walkable floor
     *  cells, plus the water bonus if water sits in or near the interior. Lets the pen UI / floating marker
     *  show "is this pen good enough?" without committing to one breed spot. Works on either side (client
     *  scans the same blocks). */
    public static double penBreedQuality(Level level, PenEnclosure.Result r) {
        int land = 0;
        int manure = 0;
        double groundSum = 0.0;
        boolean water = false;
        for (BlockPos c : r.interior()) {
            BlockState s = level.getBlockState(c);
            if (s.getFluidState().is(FluidTags.WATER)) { water = true; continue; }
            if (s.blocksMotion() && !level.getBlockState(c.above()).blocksMotion()) {
                land++;
                groundSum += groundModifier(s);   // pen interior cells ARE the floor (flood sits at floor Y)
            }
            if (level.getBlockState(c.above()).is(MANURE)) manure++;   // droppings sit in the air cell above the floor
        }
        double avgGround = land > 0 ? groundSum / land : 0.0;
        if (!water) {   // no interior water → is there water just outside, within the breeding radius?
            BlockPos centre = new BlockPos((r.min().getX() + r.max().getX()) / 2, r.min().getY(),
                (r.min().getZ() + r.max().getZ()) / 2);
            water = waterNear(level, centre, Config.BREED_WATER_RADIUS.get());
        }
        return clamp(Config.HERDER_BASE_BREED_CHANCE.get() + avgGround
            + (water ? Config.BREED_WATER_BONUS.get() : 0.0)
            - manure * Config.BREED_MANURE_PENALTY.get());
    }

    /** Count manure blocks ({@link #MANURE}) within {@code radius} blocks horizontally (±2 vertically) of
     *  {@code pos} — droppings sit in the air cell above the floor, so this scans the cells the pair (and the
     *  cells around them) occupy. Chunk-guarded so it never force-loads. */
    public static int manureCount(Level level, BlockPos pos, int radius) {
        if (radius <= 0) return 0;
        int count = 0;
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                for (int dy = -2; dy <= 2; dy++) {
                    p.set(pos.getX() + dx, pos.getY() + dy, pos.getZ() + dz);
                    if (!level.hasChunkAt(p)) continue;
                    if (level.getBlockState(p).is(MANURE)) count++;
                }
            }
        }
        return count;
    }

    /** Breeding-chance delta for the floor an animal stands on: +grass bonus on fertile ground, −penalty on
     *  infertile ground, 0 otherwise (both recognised by tag — the single source of truth for both the live
     *  roll and the pen-quality preview). */
    public static double groundModifier(BlockState floor) {
        if (floor.is(FERTILE_GROUND)) return Config.BREED_GRASS_BONUS.get();
        if (floor.is(INFERTILE_GROUND)) return -Config.BREED_INFERTILE_PENALTY.get();
        return 0.0;
    }

    private static double clamp(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }

    /** True if any water (source or flowing) lies within {@code radius} blocks horizontally (±2 vertically)
     *  of {@code pos}. Early-exits on the first hit; breeding is rare so the scan cost is fine. */
    private static boolean waterNear(Level level, BlockPos pos, int radius) {
        BlockPos.MutableBlockPos p = new BlockPos.MutableBlockPos();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                for (int dy = -2; dy <= 2; dy++) {
                    p.set(pos.getX() + dx, pos.getY() + dy, pos.getZ() + dz);
                    if (!level.hasChunkAt(p)) continue;   // never force-load a chunk just to look for water
                    if (level.getFluidState(p).is(FluidTags.WATER)) return true;
                }
            }
        }
        return false;
    }

    private static void puffSmoke(ServerLevel sl, Animal animal) {
        sl.sendParticles(ParticleTypes.SMOKE,
            animal.getX(), animal.getY() + animal.getBbHeight() * 0.5, animal.getZ(),
            10, 0.3, 0.3, 0.3, 0.02);
    }
}
