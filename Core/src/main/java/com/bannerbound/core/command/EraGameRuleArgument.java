package com.bannerbound.core.command;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import com.mojang.brigadier.StringReader;
import com.mojang.brigadier.arguments.ArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.exceptions.DynamicCommandExceptionType;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;

import com.bannerbound.core.api.settlement.Era;

import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;

/**
 * Brigadier argument that parses a Bannerbound {@link Era} by name ({@code ancient},
 * {@code classical}, …) plus the alias {@code none} (= the last era = "no cap").
 *
 * <p>Backs the value of {@code /gamerule forceMaxAge <era>} (see
 * {@link com.bannerbound.core.chat.ForceMaxAgeGameRule}) so the rule reads as friendly era presets
 * with tab-completion instead of a raw ordinal. Registered as a singleton command argument type
 * ({@link com.bannerbound.core.BannerboundCore#COMMAND_ARGUMENT_TYPES}) so the {@code /gamerule}
 * command tree — and its suggestions — sync correctly to clients.
 */
public final class EraGameRuleArgument implements ArgumentType<Era> {
    private static final Collection<String> EXAMPLES = List.of("ancient", "classical", "none");

    /** {@code none} = clear the cap (resolves to the last era); used for both parse and suggest. */
    public static final String NONE = "none";

    private static final DynamicCommandExceptionType ERROR_UNKNOWN_ERA =
        new DynamicCommandExceptionType(name ->
            Component.translatable("bannerbound.settlement.set_age.error.invalid_era", name));

    public static EraGameRuleArgument era() {
        return new EraGameRuleArgument();
    }

    /** Resolves a token to an {@link Era}: {@code none} → the last era (no cap), else by name; null if unknown. */
    public static Era resolve(String token) {
        if (token.equalsIgnoreCase(NONE)) {
            Era[] vals = Era.values();
            return vals[vals.length - 1];
        }
        return Era.fromName(token);
    }

    @Override
    public Era parse(StringReader reader) throws CommandSyntaxException {
        String word = reader.readUnquotedString();
        Era era = resolve(word);
        if (era == null) {
            throw ERROR_UNKNOWN_ERA.createWithContext(reader, word);
        }
        return era;
    }

    @Override
    public <S> CompletableFuture<Suggestions> listSuggestions(CommandContext<S> context, SuggestionsBuilder builder) {
        List<String> options = new ArrayList<>();
        options.add(NONE);
        for (Era e : Era.values()) {
            options.add(e.key());
        }
        return SharedSuggestionProvider.suggest(options, builder);
    }

    @Override
    public Collection<String> getExamples() {
        return EXAMPLES;
    }
}
