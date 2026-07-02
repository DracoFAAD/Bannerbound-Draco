package com.bannerbound.core.api.settlement.food;

import java.util.ArrayList;
import java.util.List;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.BiPredicate;
import java.util.function.ToDoubleBiFunction;

import com.bannerbound.core.api.settlement.Settlement;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.neoforged.neoforge.items.IItemHandler;

/**
 * The seam between Core's stored-food scan and expansions that mutate or reject food stacks. Core
 * itself counts any item with a positive food value; Antiquity registers rules here to spoil old food
 * and exclude poisoned food (COOKING_PLAN.md). With no expansion loaded, every food counts.
 *
 * <p>Processors run first, then exclusion rules. Register once during mod setup.
 */
public final class LarderHooks {
    private LarderHooks() {}

    private static final List<BiFunction<ItemStack, Level, ItemStack>> PROCESSORS = new ArrayList<>();
    private static final List<BiPredicate<ItemStack, Level>> EXCLUSIONS = new ArrayList<>();
    private static final List<ToDoubleBiFunction<ItemStack, Level>> VALUE_CONTRIBUTORS = new ArrayList<>();
    private static final List<ToDoubleBiFunction<ItemStack, Level>> VALUE_MULTIPLIERS = new ArrayList<>();
    private static final List<BiConsumer<IItemHandler, Level>> NORMALIZERS = new ArrayList<>();
    private static final List<BiFunction<ServerLevel, Settlement, List<FoodStore>>> STORE_PROVIDERS = new ArrayList<>();

    /** A non-item food source the larder draws from like stored items — e.g. a placed cooking pot
     *  holding stew servings. Lets blocks contribute to (and be consumed from) the settlement reserve
     *  without pretending to be an item in a container. */
    public interface FoodStore {
        /** Remaining drainable food value held by this store. */
        double availableFoodValue();
        /** Remove up to {@code maxValue} food value; returns how much was actually drained. */
        double drainFoodValue(double maxValue);
    }

    /** Register a provider that lists the block-based food stores in a settlement's claimed chunks. */
    public static void provideStoresWith(BiFunction<ServerLevel, Settlement, List<FoodStore>> provider) {
        STORE_PROVIDERS.add(provider);
    }

    /** Every block-based food store in the settlement right now, across all registered providers. */
    public static List<FoodStore> stores(ServerLevel level, Settlement s) {
        if (STORE_PROVIDERS.isEmpty()) return List.of();
        List<FoodStore> out = new ArrayList<>();
        for (BiFunction<ServerLevel, Settlement, List<FoodStore>> provider : STORE_PROVIDERS) {
            List<FoodStore> list = provider.apply(level, s);
            if (list != null) out.addAll(list);
        }
        return out;
    }

    /** Register a processor that may stamp, replace, or otherwise normalize a storage food stack. */
    public static void processWith(BiFunction<ItemStack, Level, ItemStack> processor) {
        PROCESSORS.add(processor);
    }

    /** Register a normalizer that runs once over a whole storage container after every slot has been
     *  processed — e.g. to re-merge food stacks a per-slot processor fragmented (spoiled food, refreshed
     *  perishables). Core itself does nothing here; expansions own the policy. */
    public static void normalizeWith(BiConsumer<IItemHandler, Level> normalizer) {
        NORMALIZERS.add(normalizer);
    }

    /** Runs every registered normalizer over one storage container. */
    public static void normalize(IItemHandler handler, Level level) {
        for (BiConsumer<IItemHandler, Level> normalizer : NORMALIZERS) {
            normalizer.accept(handler, level);
        }
    }

    /** Register a contributor that supplies a per-item food value for stacks Core's data tables don't
     *  cover — e.g. food carried in a data component, like a filled grog vessel whose nutrition lives
     *  on its {@code GrogContents}. Returns {@code 0} (or negative) when it does not apply. */
    public static void contributeValueWith(ToDoubleBiFunction<ItemStack, Level> contributor) {
        VALUE_CONTRIBUTORS.add(contributor);
    }

    /** The highest per-item food value any registered contributor assigns to {@code stack} (0 if none).
     *  Lets component-carried food count in the larder without being in a culture style's table. */
    public static double extraValue(ItemStack stack, Level level) {
        double best = 0.0;
        for (ToDoubleBiFunction<ItemStack, Level> contributor : VALUE_CONTRIBUTORS) {
            best = Math.max(best, contributor.applyAsDouble(stack, level));
        }
        return best;
    }

    /** Register a per-stack food-value multiplier — e.g. Antiquity halving the value of bland food.
     *  Should return {@code 1.0} when it does not apply; all registered multipliers are multiplied. */
    public static void multiplyValueWith(ToDoubleBiFunction<ItemStack, Level> multiplier) {
        VALUE_MULTIPLIERS.add(multiplier);
    }

    /** Combined food-value multiplier for {@code stack} from every registered multiplier ({@code 1.0}
     *  when none apply). Applied to a stack's per-item value before it feeds the larder reserve. */
    public static double valueMultiplier(ItemStack stack, Level level) {
        double m = 1.0;
        for (ToDoubleBiFunction<ItemStack, Level> multiplier : VALUE_MULTIPLIERS) {
            m *= multiplier.applyAsDouble(stack, level);
        }
        return m;
    }

    /** Register a rule that returns {@code true} for stacks that must NOT feed the larder. */
    public static void excludeWhen(BiPredicate<ItemStack, Level> rejectIf) {
        EXCLUSIONS.add(rejectIf);
    }

    /** Applies registered processors in order. Processors may return a replacement stack. */
    public static ItemStack process(ItemStack stack, Level level) {
        ItemStack current = stack;
        for (BiFunction<ItemStack, Level, ItemStack> processor : PROCESSORS) {
            if (current.isEmpty()) break;
            ItemStack next = processor.apply(current, level);
            current = next == null ? ItemStack.EMPTY : next;
        }
        return current;
    }

    /** Whether {@code stack} may feed the larder after processing. */
    public static boolean counts(ItemStack stack, Level level) {
        for (BiPredicate<ItemStack, Level> reject : EXCLUSIONS) {
            if (reject.test(stack, level)) return false;
        }
        return true;
    }
}
