package com.bannerbound.antiquity.block.entity;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.annotation.Nullable;

import org.jetbrains.annotations.ApiStatus;

import com.bannerbound.antiquity.BannerboundAntiquity;
import com.bannerbound.antiquity.block.StoneCookingPotBlock;
import com.bannerbound.antiquity.item.StewContents;
import com.bannerbound.antiquity.recipe.StewRecipe;
import com.bannerbound.antiquity.recipe.StewRecipeManager;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtOps;
import net.minecraft.nbt.Tag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.CampfireBlock;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;

import com.bannerbound.core.api.settlement.Settlement;
import com.bannerbound.core.api.settlement.SettlementData;
import com.bannerbound.core.api.settlement.data.FoodValueLoader;

/**
 * Block entity for the stone cooking pot. Lives on a single block (not a multiblock). It holds water,
 * accepts raw food ingredients, and — while sitting on a lit campfire that is NOT the settlement's town
 * hall — cooks them over time into a {@link StewContents}. A finished stew is a finite, rot-proof food
 * store: the settlement larder draws its value down like stored items ({@code LarderHooks.FoodStore}),
 * and the player can right-click to eat a serving. When the value runs out the pot empties to plain
 * stone. See COOKING_PLAN.md / the food-economy overhaul.
 */
@ApiStatus.Internal
public class StoneCookingPotBlockEntity extends BlockEntity {
    /** Cooked value = the food value of all ingredients × this (the cook's payoff over eating them raw);
     *  a named recipe may set its own bonus. */
    public static final double DEFAULT_COOK_BONUS = 1.25;
    public static final int DEFAULT_SERVINGS = 6;
    public static final int DEFAULT_COOK_TICKS = 400;
    public static final int MAX_INGREDIENTS = 8;
    /** How far the pot is rendered below its block when it sits on a campfire ({@code ON_FIRE}) so it
     *  rests on the fire instead of floating above — the placed model, BER liquid/items, and particles
     *  all drop by this. (The campfire flame itself is hidden by {@code CampfireFireHideMixin}.) */
    public static final double VISUAL_DROP = 8.0 / 16.0;
    private static final int GENERIC_TINT = 0xB5651D;

    private boolean hasWater = false;
    private final List<ItemStack> rawIngredients = new ArrayList<>();
    private int cookProgress = 0;
    @Nullable private StewContents stew = null;
    private double remainingFoodValue = 0.0;

    public StoneCookingPotBlockEntity(BlockPos pos, BlockState state) {
        super(BannerboundAntiquity.STONE_COOKING_POT_BE.get(), pos, state);
    }

    // ─── State queries ───────────────────────────────────────────────────────────────────────────

    public boolean hasWater() { return hasWater; }
    public boolean hasStew() { return stew != null && remainingFoodValue > 0.0; }
    /** Filled = holds water or a stew (drives the FILLED model + tint). */
    public boolean isFilled() { return hasWater || hasStew(); }
    @Nullable public StewContents stew() { return stew; }
    public double remainingFoodValue() { return remainingFoodValue; }
    public int ingredientCount() { return rawIngredients.size(); }

    /** Raw ingredients currently floating in the pot (read-only). Used by the renderer to draw them. */
    public List<ItemStack> ingredients() { return rawIngredients; }

    /** Holds water + raw ingredients, no finished stew yet → it's simmering toward a stew. */
    public boolean isCooking() {
        return hasWater && stew == null && !rawIngredients.isEmpty();
    }

    /** How far the current cook has progressed, 0..1 (drives the gradual ripening of the liquid tint). */
    public float cookFraction() {
        int need = cookTicksNeeded();
        if (need <= 0) return 0.0F;
        float f = (float) cookProgress / need;
        return f < 0.0F ? 0.0F : (f > 1.0F ? 1.0F : f);
    }

    /** The colour this brew WILL become, from the ingredients now simmering — for the cook-time tint. */
    public int previewTint() {
        StewRecipe r = matchRecipe();
        return r != null ? r.tint() : GENERIC_TINT;
    }

    /** Lang key of the stew the current ingredients will become (the matched recipe, or generic). */
    public String previewName() {
        StewRecipe r = matchRecipe();
        return r != null ? r.name() : "stew.generic";
    }

    /** Tint for the liquid layer: the stew colour when cooked, plain water (no tint) otherwise. */
    public int liquidTint() {
        return stew != null ? stew.tint() : 0xFFFFFF;
    }

    /** How full the liquid sits, 0..1 — water/cooking stay full; a stew falls as its servings drain. */
    public float fillFraction() {
        if (hasStew()) {
            double total = stew.totalFoodValue();
            if (total <= 0.0) return 1.0F;
            float f = (float) (remainingFoodValue / total);
            return f < 0.0F ? 0.0F : (f > 1.0F ? 1.0F : f);
        }
        return hasWater ? 1.0F : 0.0F;
    }

    /** The on-fire visual drop — {@link #VISUAL_DROP} only when sitting on a campfire, else 0. Particles
     *  use this so they track the lowered, on-fire liquid. */
    private double visualDrop() {
        BlockState st = getBlockState();
        return st.getBlock() instanceof StoneCookingPotBlock && st.getValue(StoneCookingPotBlock.ON_FIRE)
            ? VISUAL_DROP : 0.0;
    }


    public void setWater(boolean water) {
        this.hasWater = water;
        syncFilledState();
        setChanged();
    }

    // ─── Ingredients ─────────────────────────────────────────────────────────────────────────────

    /** Try to add one unit of {@code held} as an ingredient. Returns true if accepted (caller shrinks). */
    public boolean addIngredient(ItemStack held) {
        if (!hasWater || stew != null || rawIngredients.size() >= MAX_INGREDIENTS) return false;
        // Accept real food, OR an item that's an ingredient in some stew recipe even if it has no
        // standalone food value (e.g. mushrooms → mushroom stew). Otherwise such recipes are unreachable.
        if (FoodValueLoader.base(held.getItem()) <= 0.0
                && !StewRecipeManager.isStewIngredient(held.getItem())) return false;
        ItemStack one = held.copy();
        one.setCount(1);
        rawIngredients.add(one);
        cookProgress = 0;
        if (level != null) {
            level.playSound(null, getBlockPos(), SoundEvents.GENERIC_SPLASH, SoundSource.BLOCKS, 0.5F, 1.4F);
        }
        splashParticles(10);   // the food plops into the water
        setChanged();
        return true;
    }

    // ─── Eating (player) ─────────────────────────────────────────────────────────────────────────

    /** Right-click with an empty hand → eat one serving. Returns true if a serving was served. */
    public boolean eatServing(Player player) {
        if (!hasStew()) return false;
        double per = stew.foodPerServing();
        int nutrition = Math.max(1, (int) Math.round(per * 2.0)); // haunch → hunger points
        player.getFoodData().eat(nutrition, 0.3F);
        if (stew.poisoned()) {
            player.addEffect(new MobEffectInstance(MobEffects.POISON, 200, 0));
        }
        for (MobEffectInstance e : stew.effects()) {
            player.addEffect(new MobEffectInstance(e));
        }
        splashParticles(5);    // a scoop ripples the surface (spawned before the drain lowers it)
        drainValue(per);
        if (level != null) {
            level.playSound(null, getBlockPos(), SoundEvents.GENERIC_EAT, SoundSource.PLAYERS, 0.7F, 1.0F);
        }
        return true;
    }

    /** A citizen scoops one serving (drains its food value); the StewEatGoal handles its own eat sound.
     *  Returns true if a serving was taken. */
    public boolean takeServing() {
        if (!hasStew()) return false;
        splashParticles(5);
        drainValue(stew.foodPerServing());
        return true;
    }

    /** Remove up to {@code maxValue} food value from the stew; returns how much was actually removed. */
    public double drainValue(double maxValue) {
        if (stew == null || remainingFoodValue <= 0.0 || maxValue <= 0.0) return 0.0;
        double drained = Math.min(maxValue, remainingFoodValue);
        remainingFoodValue -= drained;
        if (remainingFoodValue <= 1.0e-4) emptyPot();
        setChanged();
        return drained;
    }

    private void emptyPot() {
        stew = null;
        remainingFoodValue = 0.0;
        hasWater = false;
        cookProgress = 0;
        rawIngredients.clear();
        syncFilledState();
    }

    // ─── Cooking ─────────────────────────────────────────────────────────────────────────────────

    /** Block below is a lit campfire that is NOT this settlement's town-hall campfire. */
    public boolean isHeated() {
        if (!(level instanceof ServerLevel server)) {
            BlockState below = level.getBlockState(getBlockPos().below());
            return below.getBlock() instanceof CampfireBlock && below.getValue(CampfireBlock.LIT);
        }
        BlockPos firePos = getBlockPos().below();
        BlockState below = server.getBlockState(firePos);
        if (!(below.getBlock() instanceof CampfireBlock) || !below.getValue(CampfireBlock.LIT)) return false;
        Settlement s = SettlementData.get(server).getByChunk(new net.minecraft.world.level.ChunkPos(firePos).toLong());
        return s == null || s.townHallPos() == null || !s.townHallPos().equals(firePos);
    }

    public int cookTicksNeeded() {
        StewRecipe r = matchRecipe();
        return r != null ? r.cookTicks() : DEFAULT_COOK_TICKS;
    }

    @Nullable
    private StewRecipe matchRecipe() {
        if (rawIngredients.isEmpty()) return null;
        // Identity is the SET of ingredient TYPES present — counts don't change which stew it is.
        Set<net.minecraft.world.item.Item> types = new HashSet<>();
        for (ItemStack s : rawIngredients) types.add(s.getItem());
        return StewRecipeManager.findMatch(types);
    }

    /** The cooking food value of one {@code item}: the matched recipe's per-ingredient override if it
     *  supplies one, otherwise the item's own registered food value. */
    private static double ingredientValue(@Nullable StewRecipe recipe, net.minecraft.world.item.Item item) {
        if (recipe != null) {
            double v = recipe.valueFor(item);
            if (v >= 0.0) return v;
        }
        return FoodValueLoader.base(item);
    }

    private void finishCooking() {
        StewRecipe recipe = matchRecipe();
        double bonus = recipe != null ? recipe.bonus() : DEFAULT_COOK_BONUS;
        int servings = recipe != null ? recipe.servings() : DEFAULT_SERVINGS;
        // Cooked value = the food value of everything added (each ingredient's recipe override or its
        // own) × the cook bonus — so MORE, and richer, ingredients make a heartier (more filling) stew.
        double valueSum = 0.0;
        boolean poisoned = false;
        for (ItemStack ing : rawIngredients) {
            valueSum += ingredientValue(recipe, ing.getItem()) * ing.getCount();
            if (ing.get(BannerboundAntiquity.POISONED_FOOD.get()) != null) poisoned = true;
        }
        double total = valueSum * bonus;
        // Never finish into a worthless 0-value pot (a mix of food-less ingredients with no recipe value):
        // a bland mystery stew is at least edible.
        if (total <= 0.0) total = Math.max(1.0, rawIngredients.size());
        double perServing = total / Math.max(1, servings);
        String name = recipe != null ? recipe.name() : "stew.generic";
        int tint = recipe != null ? recipe.tint() : GENERIC_TINT;
        List<MobEffectInstance> effects = recipe != null ? recipe.effects() : List.of();

        stew = new StewContents(name, tint, perServing, servings, effects, poisoned);
        remainingFoodValue = total;
        rawIngredients.clear();
        cookProgress = 0;
        syncFilledState();
        if (level instanceof ServerLevel server) {
            server.playSound(null, getBlockPos(), SoundEvents.BREWING_STAND_BREW, SoundSource.BLOCKS, 0.8F, 1.1F);
            server.sendParticles(ParticleTypes.HAPPY_VILLAGER,
                getBlockPos().getX() + 0.5, getBlockPos().getY() + 0.7 - visualDrop(), getBlockPos().getZ() + 0.5,
                10, 0.25, 0.15, 0.25, 0.02);
        }
        setChanged();
    }

    // ─── Ticking ───────────────────────────────────────────────────────────────────────────────

    public static void tick(Level level, BlockPos pos, BlockState state, StoneCookingPotBlockEntity be) {
        if (level.isClientSide) {
            if (be.isFilled() && be.isHeated()) be.spawnSimmerParticles(level, pos);
            return;
        }
        // Swap a vanilla campfire below for the flame-less variant (covers pots already placed in a
        // loaded world, not just freshly-placed ones); no-op once already swapped.
        StoneCookingPotBlock.hideCampfireFlame(level, pos);
        // Don't cook (or decay progress) in a DORMANT settlement's claimed chunk: this tick fires
        // whenever the chunk is loaded (force-loaded claims / a nearby outsider), so it would keep
        // running for an offline tribe. Mirrors FoodSpoilageEvents' dormancy guard.
        if (level instanceof ServerLevel sl) {
            Settlement owner = SettlementData.get(sl).getByChunk(new ChunkPos(pos).toLong());
            if (owner != null && owner.isDormant()) return;
        }
        boolean cooking = be.hasWater && be.stew == null && !be.rawIngredients.isEmpty();
        if (cooking && be.isHeated()) {
            be.cookProgress++;
            if (be.cookProgress >= be.cookTicksNeeded()) {
                be.finishCooking();
            } else if (be.cookProgress % 20 == 0) {
                be.setChanged();
            }
        } else if (be.cookProgress > 0 && !be.isHeated()) {
            // Fire's out — progress slowly slips back, like the kiln.
            be.cookProgress = Math.max(0, be.cookProgress - 1);
        }
    }

    private void spawnSimmerParticles(Level level, BlockPos pos) {
        // Bubble + smoke at the actual liquid surface (which lowers as a stew drains). Visual only —
        // no looping ambient sound; sounds play on placing food in / scooping a serving out.
        double surfaceY = pos.getY() + (2.0 + fillFraction() * 7.0) / 16.0 - visualDrop() + 0.02;
        if (level.random.nextInt(3) == 0) {
            level.addParticle(stew != null ? ParticleTypes.BUBBLE_POP : ParticleTypes.SPLASH,
                pos.getX() + 0.3 + level.random.nextDouble() * 0.4, surfaceY,
                pos.getZ() + 0.3 + level.random.nextDouble() * 0.4,
                0.0, 0.01, 0.0);
        }
        if (level.random.nextInt(6) == 0) {
            level.addParticle(ParticleTypes.SMOKE,
                pos.getX() + 0.5, surfaceY + 0.05, pos.getZ() + 0.5, 0.0, 0.02, 0.0);
        }
    }

    /** A burst of water-splash particles at the liquid surface — food dropped in, a serving scooped out. */
    private void splashParticles(int count) {
        if (!(level instanceof ServerLevel sl)) return;
        BlockPos pos = getBlockPos();
        double surfaceY = pos.getY() + (2.0 + fillFraction() * 7.0) / 16.0 - visualDrop() + 0.05;
        sl.sendParticles(ParticleTypes.SPLASH, pos.getX() + 0.5, surfaceY, pos.getZ() + 0.5,
            count, 0.18, 0.04, 0.18, 0.0);
    }

    /** Keep the FILLED blockstate in step with whether the pot holds water/stew. */
    private void syncFilledState() {
        if (level == null || level.isClientSide) return;
        BlockState st = getBlockState();
        boolean filled = isFilled();
        if (st.getBlock() instanceof StoneCookingPotBlock && st.getValue(StoneCookingPotBlock.FILLED) != filled) {
            level.setBlock(getBlockPos(), st.setValue(StoneCookingPotBlock.FILLED, filled), Block.UPDATE_ALL);
        }
    }

    @Override
    public void setChanged() {
        super.setChanged();
        if (level != null && !level.isClientSide) {
            level.sendBlockUpdated(getBlockPos(), getBlockState(), getBlockState(), Block.UPDATE_CLIENTS);
        }
    }

    // ─── NBT + client sync ─────────────────────────────────────────────────────────────────────

    @Override
    protected void saveAdditional(CompoundTag tag, HolderLookup.Provider provider) {
        super.saveAdditional(tag, provider);
        tag.putBoolean("HasWater", hasWater);
        tag.putInt("CookProgress", cookProgress);
        tag.putDouble("RemainingFood", remainingFoodValue);
        if (!rawIngredients.isEmpty()) {
            ListTag list = new ListTag();
            for (ItemStack s : rawIngredients) list.add(s.save(provider));
            tag.put("Ingredients", list);
        }
        if (stew != null) {
            Tag t = StewContents.CODEC.encodeStart(NbtOps.INSTANCE, stew).result().orElse(null);
            if (t != null) tag.put("Stew", t);
        }
    }

    @Override
    protected void loadAdditional(CompoundTag tag, HolderLookup.Provider provider) {
        super.loadAdditional(tag, provider);
        hasWater = tag.getBoolean("HasWater");
        cookProgress = tag.getInt("CookProgress");
        remainingFoodValue = tag.getDouble("RemainingFood");
        rawIngredients.clear();
        if (tag.contains("Ingredients")) {
            ListTag list = tag.getList("Ingredients", Tag.TAG_COMPOUND);
            for (int i = 0; i < list.size(); i++) {
                ItemStack.parse(provider, list.getCompound(i)).ifPresent(rawIngredients::add);
            }
        }
        stew = tag.contains("Stew")
            ? StewContents.CODEC.parse(NbtOps.INSTANCE, tag.get("Stew")).result().orElse(null)
            : null;
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
