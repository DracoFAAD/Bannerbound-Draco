package com.bannerbound.antiquity.workshop;

import java.util.List;
import java.util.Set;

import javax.annotation.Nullable;

import com.bannerbound.antiquity.BannerboundAntiquity;
import com.bannerbound.antiquity.block.entity.ClayTankBlockEntity;
import com.bannerbound.antiquity.block.entity.TanningRackBlockEntity;
import com.bannerbound.core.api.settlement.Settlement;
import com.bannerbound.core.api.settlement.Workshop;
import com.bannerbound.core.api.workshop.WorkExecutor;
import com.bannerbound.core.api.workshop.WorkshopStorage;
import com.bannerbound.core.api.workshop.Workshops;
import com.bannerbound.core.entity.CitizenEntity;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/**
 * NPC driver for the Tannery workshop (the generic Crafter staffs it). It walks the leather chain
 * FORWARD whenever leather is wanted (an order or a min-stock deficit): a cured hide dries to
 * leather, else a scraped hide cures (consuming a curing bucket from the clay tank), else a raw
 * hide is scraped. The player minigame realizes hide quality as scraped quantity; the NPC bulk-path
 * scrapes at a fixed standard yield (no per-stack quality tracking). The clay tank cooperation
 * mirrors {@code PotterExecutor}'s rack↔kiln pattern.
 */
public class TanneryExecutor implements WorkExecutor {
    private static final int BEATS = 3;
    /** Scraped hides the NPC gets per raw hide (quality→quantity is the player-minigame reward). */
    private static final int NPC_SCRAPE_YIELD = 2;

    // Per-step base durations (ticks, before the goal's skill-speed scaling). Each step is its own
    // craft with its own walk + delay, so the tanner visibly works the chain instead of teleporting
    // a tank to "done": scoop water → pour it → add lime → cure a hide → dry it on the rack.
    private static final int FILL_TICKS = 40;   // scooping water into the bucket at the source
    private static final int POUR_TICKS = 40;   // emptying the bucket into the tank
    private static final int LIME_TICKS = 60;   // working quicklime through the water
    private static final int CURE_TICKS = 60;   // dunking + soaking a hide
    private static final int SCRAPE_TICKS = 60; // scraping a raw hide down at the rack
    // Laying a cured hide on the rack / lifting the dried leather off it — quick handling. The drying
    // itself is the rack block entity's own ~60s timer (the SAME one the player's drying uses), so the
    // tanner lays the hide and walks off to other work, then returns for the leather once it's dry.
    private static final int RACK_HANDLE_TICKS = 30;

    @Nullable
    @Override
    public Craft chooseCraft(ServerLevel sl, Settlement settlement, Workshop workshop, BlockPos workBlock) {
        TanningRackBlockEntity rack = sl.getBlockEntity(workBlock) instanceof TanningRackBlockEntity r ? r : null;
        TanningRackBlockEntity.Phase rackPhase = rack == null ? null : rack.getPhase();
        // DRY-TAKE: a hide on THIS rack finished drying → lift the leather off it. UNGATED by demand
        // (rule 1 of the waiting-stage contract, see Workshops.wantsAnother): the leather is already
        // made, and leaving it on the rack jams the slot.
        if (rack != null && rack.isDry()) {
            return new Craft(List.of(), new ItemStack(Items.LEATHER), RACK_HANDLE_TICKS, 1);
        }
        // Everything below STARTS a new leather unit, so it's gated on NET demand: outstanding orders
        // / min-stock deficit MINUS leather already in flight (hides drying on any rack). Without the
        // subtraction a single order lays a second hide to dry while one is mid-dry — overproduction.
        ItemStack leather = new ItemStack(Items.LEATHER);
        int inProgress = TanneryWorkshopRules.leatherInProgress(sl, workshop);
        if (!Workshops.wantsAnother(sl, settlement, workshop, leather, inProgress)) return null;
        // DRY-PLACE: a cured hide is stocked and the rack is free → lay it out to dry. The rack's own
        // timer then dries it (visible, smoking) while the tanner walks off to other steps; it comes
        // back for DRY-TAKE once dry. Empty result + a cured-hide input distinguishes it from LIME.
        if (rackPhase == TanningRackBlockEntity.Phase.EMPTY
                && WorkshopStorage.count(sl, workshop, BannerboundAntiquity.CURED_HIDE.get()) > 0) {
            return new Craft(List.of(new ItemStack(BannerboundAntiquity.CURED_HIDE.get())),
                ItemStack.EMPTY, RACK_HANDLE_TICKS, BEATS);
        }
        boolean hasScraped = WorkshopStorage.count(sl, workshop, BannerboundAntiquity.SCRAPED_HIDE.get()) > 0;
        if (hasScraped) {
            ClayTankBlockEntity tank = TanneryWorkshopRules.findTank(sl, workshop);
            ClayTankBlockEntity.LiquidType liquid = tank == null ? null : tank.getLiquid();
            // The tank charges in visible steps, gated on its liquid state + the bucket items that
            // carry "water in hand" between trips. Each is its own craft (own walk + delay):
            //   CURE  — tank holds curing liquid → dunk a scraped hide into it (→ cured hide).
            //   LIME  — tank holds water → work quicklime through it (→ curing liquid).
            //   POUR  — tank empty, a filled bucket on hand → empty it into the tank (→ water).
            //   FILL  — tank empty, no filled bucket → walk to open water and scoop the bucket full.
            // FILL/LIME only start when quicklime is stocked, so the tanner never fills a tank it
            // can't finish charging (the hide would sit in plain water forever).
            boolean hasLime = WorkshopStorage.count(sl, workshop, BannerboundAntiquity.QUICKLIME.get()) > 0;
            if (TanneryWorkshopRules.hasCuring(sl, workshop)) {
                return new Craft(List.of(new ItemStack(BannerboundAntiquity.SCRAPED_HIDE.get())),
                    new ItemStack(BannerboundAntiquity.CURED_HIDE.get()), CURE_TICKS, BEATS);
            }
            if (liquid == ClayTankBlockEntity.LiquidType.WATER && hasLime) {
                return new Craft(List.of(new ItemStack(BannerboundAntiquity.QUICKLIME.get())),
                    ItemStack.EMPTY, LIME_TICKS, BEATS);
            }
            if (liquid == ClayTankBlockEntity.LiquidType.EMPTY) {
                boolean hasFilled = WorkshopStorage.count(sl, workshop,
                    BannerboundAntiquity.CLAY_FIRED_WATER_BUCKET.get()) > 0;
                if (hasFilled) {
                    // POUR: empty the filled bucket into the tank, the empty bucket cycles back.
                    return new Craft(List.of(new ItemStack(BannerboundAntiquity.CLAY_FIRED_WATER_BUCKET.get())),
                        new ItemStack(BannerboundAntiquity.CLAY_FIRED_BUCKET.get()), POUR_TICKS, BEATS);
                }
                if (hasLime
                    && WorkshopStorage.count(sl, workshop, BannerboundAntiquity.CLAY_FIRED_BUCKET.get()) > 0
                    && tank != null && TanneryWorkshopRules.findWaterSource(sl, tank) != null) {
                    // FILL: carry the empty bucket to open water and scoop it full.
                    return new Craft(List.of(new ItemStack(BannerboundAntiquity.CLAY_FIRED_BUCKET.get())),
                        new ItemStack(BannerboundAntiquity.CLAY_FIRED_WATER_BUCKET.get()), FILL_TICKS, BEATS);
                }
            }
        }
        // SCRAPE: a raw hide → scraped hides, worked at the rack. Needs the rack free (the raw hide is
        // laid on it for the duration, see onStart/finish) — a rack mid-dry waits its turn.
        if (rackPhase == TanningRackBlockEntity.Phase.EMPTY) {
            Item rawHide = firstRawHide(sl, workshop);
            if (rawHide != null) {
                return new Craft(List.of(new ItemStack(rawHide)),
                    new ItemStack(BannerboundAntiquity.SCRAPED_HIDE.get(), NPC_SCRAPE_YIELD), SCRAPE_TICKS, BEATS);
            }
        }
        return null;
    }

    private static boolean hasInput(Craft craft, Item item) {
        for (ItemStack in : craft.inputs()) {
            if (in.is(item)) return true;
        }
        return false;
    }

    private static boolean isFill(Craft craft) {
        return craft.result().is(BannerboundAntiquity.CLAY_FIRED_WATER_BUCKET.get());
    }

    private static boolean isPour(Craft craft) {
        return craft.result().is(BannerboundAntiquity.CLAY_FIRED_BUCKET.get());
    }

    /** LIME has no item result — it converts the tank's water to curing liquid. Distinguished from
     *  DRY-PLACE (also resultless) by its quicklime input. */
    private static boolean isLime(Craft craft) {
        return craft.result().isEmpty() && hasInput(craft, BannerboundAntiquity.QUICKLIME.get());
    }

    /** DRY-PLACE has no item result either — it lays a stocked cured hide onto the rack to dry. */
    private static boolean isDryPlace(Craft craft) {
        return craft.result().isEmpty() && hasInput(craft, BannerboundAntiquity.CURED_HIDE.get());
    }

    /** DRY-TAKE lifts finished leather off the rack — leather result, no input withdrawn. */
    private static boolean isDryTake(Craft craft) {
        return craft.result().is(Items.LEATHER) && craft.inputs().isEmpty();
    }

    /** SCRAPE works a raw hide down to scraped hides at the rack (raw hide shown on it meanwhile). */
    private static boolean isScrape(Craft craft) {
        return craft.result().is(BannerboundAntiquity.SCRAPED_HIDE.get());
    }

    private static boolean isCure(Craft craft) {
        return craft.result().is(BannerboundAntiquity.CURED_HIDE.get());
    }

    /** FILL happens at a water source; POUR/LIME/CURE happen at the clay tank; SCRAPE/DRY at the rack. */
    @Override
    public BlockPos workTarget(ServerLevel sl, Settlement settlement, Workshop workshop,
                               BlockPos workBlock, Craft craft) {
        if (isPour(craft) || isLime(craft) || isCure(craft) || isFill(craft)) {
            ClayTankBlockEntity tank = TanneryWorkshopRules.findTank(sl, workshop);
            if (tank == null) return workBlock;
            if (isFill(craft)) {
                BlockPos water = TanneryWorkshopRules.findWaterSource(sl, tank);
                return water != null ? water : tank.getBlockPos();
            }
            return tank.getBlockPos();
        }
        // SCRAPE / DRY-PLACE / DRY-TAKE all happen at the rack itself.
        return workBlock;
    }

    /** SCRAPE lays the raw hide on the rack so the player sees the tanner working it. */
    @Override
    public void onStart(ServerLevel sl, CitizenEntity citizen, BlockPos workBlock, Craft craft) {
        if (isScrape(craft) && sl.getBlockEntity(workBlock) instanceof TanningRackBlockEntity rack
                && rack.getPhase() == TanningRackBlockEntity.Phase.EMPTY && !craft.inputs().isEmpty()) {
            rack.placeRaw(craft.inputs().get(0));
        }
    }

    /** A SCRAPE interrupted mid-swing must not leave its raw hide stranded on the rack render — the
     *  goal returns the withdrawn raw hide to storage, so clear the visual to match. */
    @Override
    public void onAbort(ServerLevel sl, CitizenEntity citizen, BlockPos workBlock, Craft craft) {
        if (isScrape(craft) && sl.getBlockEntity(workBlock) instanceof TanningRackBlockEntity rack
                && rack.isRaw()) {
            rack.clear();
        }
    }

    @Override
    public ItemStack finish(ServerLevel sl, CitizenEntity citizen, BlockPos workBlock, Craft craft) {
        if (isPour(craft) || isLime(craft) || isCure(craft)) {
            Settlement s = citizen.getSettlement();
            Workshop w = s == null ? null : s.getWorkshop(citizen.getAssignedWorkshopId());
            ClayTankBlockEntity tank = w == null ? null : TanneryWorkshopRules.findTank(sl, w);
            if (tank != null) {
                if (isPour(craft)) {
                    tank.fillWater();        // poured water → tank full of water
                } else if (isLime(craft)) {
                    tank.convertToCuring();  // quicklime worked in → curing liquid
                } else {
                    tank.drawCuring();       // a hide soaked → consume one bucket of curing liquid
                }
            }
        } else if (sl.getBlockEntity(workBlock) instanceof TanningRackBlockEntity rack) {
            if (isScrape(craft)) {
                rack.clear();        // raw hide scraped down → take the scraped hides (the result)
            } else if (isDryPlace(craft)) {
                rack.placeCured();   // cured hide laid out → the rack's timer dries it from here
            } else if (isDryTake(craft)) {
                rack.clear();        // dried leather lifted off → the result is the leather
            }
        }
        return craft.result().copy();
    }

    @Override
    public void onBeat(ServerLevel sl, CitizenEntity citizen, BlockPos workBlock, Craft craft, int beatIndex) {
        BlockPos at = citizen.blockPosition();
        if (isFill(craft)) {
            // Scooping the bucket full at the water source.
            sl.playSound(null, at, SoundEvents.BUCKET_FILL, SoundSource.BLOCKS, 0.6F, 1.0F);
            splash(sl, citizen, 6);
        } else if (isPour(craft)) {
            // Emptying the bucket into the tank.
            sl.playSound(null, at, SoundEvents.BUCKET_EMPTY, SoundSource.BLOCKS, 0.7F, 0.9F);
            splash(sl, citizen, 8);
        } else if (isLime(craft)) {
            // Working the quicklime through the water — fizz + a puff of pale dust.
            sl.playSound(null, at, SoundEvents.BREWING_STAND_BREW, SoundSource.BLOCKS, 0.6F, 1.1F);
            sl.sendParticles(net.minecraft.core.particles.ParticleTypes.WHITE_SMOKE,
                citizen.getX(), citizen.getY() + 1.0, citizen.getZ(), 6, 0.2, 0.1, 0.2, 0.0);
        } else if (isCure(craft)) {
            // Dunking the hide into the curing liquid.
            sl.playSound(null, at, SoundEvents.BUCKET_EMPTY, SoundSource.BLOCKS, 0.6F, 1.1F);
            splash(sl, citizen, 8);
        } else if (isScrape(craft)) {
            // Scraping the raw hide down at the rack.
            sl.playSound(null, workBlock, SoundEvents.SHEEP_SHEAR, SoundSource.BLOCKS, 0.5F, 1.0F);
        } else {
            // Laying a cured hide out to dry / lifting the finished leather — light handling.
            sl.playSound(null, workBlock, SoundEvents.LEASH_KNOT_PLACE, SoundSource.BLOCKS, 0.5F, 1.0F);
        }
    }

    private static void splash(ServerLevel sl, CitizenEntity citizen, int count) {
        sl.sendParticles(net.minecraft.core.particles.ParticleTypes.SPLASH,
            citizen.getX(), citizen.getY() + 0.8, citizen.getZ(), count, 0.25, 0.1, 0.25, 0.0);
    }

    @Override
    public List<ItemStack> possibleOutputs(ServerLevel sl, BlockPos workBlock) {
        return List.of(new ItemStack(Items.LEATHER),
            new ItemStack(BannerboundAntiquity.SCRAPED_HIDE.get()),
            new ItemStack(BannerboundAntiquity.CURED_HIDE.get()));
    }

    @Override
    public Set<Item> retainedItems(ServerLevel sl, Settlement settlement, Workshop workshop, BlockPos workBlock) {
        // Keep raw hides + quicklime + the intermediates in the workshop so the chain can run; only
        // finished leather is surplus for the stocker to haul out.
        return Set.of(
            BannerboundAntiquity.COW_HIDE.get(), BannerboundAntiquity.SHEEP_HIDE.get(),
            BannerboundAntiquity.PIG_HIDE.get(), BannerboundAntiquity.GOAT_HIDE.get(),
            BannerboundAntiquity.HORSE_HIDE.get(), BannerboundAntiquity.SCRAPED_HIDE.get(),
            BannerboundAntiquity.CURED_HIDE.get(), BannerboundAntiquity.QUICKLIME.get(),
            // The water-scooping vessel is a kept tool, never surplus — and a bucket mid-trip (filled
            // with fetched water) must not be hauled off before it's poured into the tank.
            BannerboundAntiquity.CLAY_FIRED_BUCKET.get(),
            BannerboundAntiquity.CLAY_FIRED_WATER_BUCKET.get());
    }

    /** Ask the Stocker to keep the tannery stocked with quicklime (the Mason's output) and raw hides
     *  while leather is wanted, so the NPC tanner can charge the tank and feed the chain. */
    @Override
    public List<ItemStack> missingInputs(ServerLevel sl, Settlement settlement, Workshop workshop, BlockPos workBlock) {
        List<ItemStack> out = new java.util.ArrayList<>();
        if (!leatherWanted(sl, settlement, workshop)) return out;
        addDeficit(out, sl, workshop, BannerboundAntiquity.QUICKLIME.get(), 4);
        for (Item hide : RAW_HIDES) {
            addDeficit(out, sl, workshop, hide, 4);
        }
        // One fired clay bucket per tank base, ordered from the potters (kiln-fired). The bucket is
        // conserved, not consumed — it just cycles empty↔water-filled as the tanner fetches water —
        // so a bucket that's currently filled still counts: only order more when neither variant is on
        // hand, else the stocker would deliver a fresh bucket on every fill trip.
        int tankBases = TanneryWorkshopRules.countTankBases(sl, workshop);
        if (tankBases > 0) {
            int buckets = WorkshopStorage.count(sl, workshop, BannerboundAntiquity.CLAY_FIRED_BUCKET.get())
                + WorkshopStorage.count(sl, workshop, BannerboundAntiquity.CLAY_FIRED_WATER_BUCKET.get());
            if (buckets < tankBases) {
                out.add(new ItemStack(BannerboundAntiquity.CLAY_FIRED_BUCKET.get(), tankBases - buckets));
            }
        }
        return out;
    }

    private static void addDeficit(List<ItemStack> out, ServerLevel sl, Workshop workshop, Item item, int buffer) {
        int have = WorkshopStorage.count(sl, workshop, item);
        if (have < buffer) out.add(new ItemStack(item, buffer - have));
    }

    private static final Item[] RAW_HIDES = {
        BannerboundAntiquity.COW_HIDE.get(), BannerboundAntiquity.SHEEP_HIDE.get(),
        BannerboundAntiquity.PIG_HIDE.get(), BannerboundAntiquity.GOAT_HIDE.get(),
        BannerboundAntiquity.HORSE_HIDE.get()
    };

    private static boolean leatherWanted(ServerLevel sl, Settlement settlement, Workshop workshop) {
        if (Workshops.orderedCraftCount(workshop, Items.LEATHER) > 0) return true;
        return Workshops.wantedByMinStock(sl, settlement, workshop, new ItemStack(Items.LEATHER));
    }

    @Nullable
    private static Item firstRawHide(ServerLevel sl, Workshop workshop) {
        Item[] hides = {
            BannerboundAntiquity.COW_HIDE.get(), BannerboundAntiquity.SHEEP_HIDE.get(),
            BannerboundAntiquity.PIG_HIDE.get(), BannerboundAntiquity.GOAT_HIDE.get(),
            BannerboundAntiquity.HORSE_HIDE.get()
        };
        for (Item hide : hides) {
            if (WorkshopStorage.count(sl, workshop, hide) > 0) return hide;
        }
        return null;
    }
}
