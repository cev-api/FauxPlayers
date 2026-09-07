package com.cevapi.fauxplayers.fabric.mixin;

import com.cevapi.fauxplayers.fabric.FabricEntrypoint;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.ParseResults;
import com.mojang.brigadier.suggestion.Suggestion;
import com.mojang.brigadier.suggestion.Suggestions;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import net.minecraft.commands.CommandSourceStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Choke-point defense at the Brigadier dispatcher itself.
 *
 * <p>Every server command-suggestion flow ends in
 * {@link CommandDispatcher#getCompletionSuggestions} - the network autocomplete
 * handler, command-block driven requests, and any other code path. The Brigadier
 * suggestion generator does not honor a node's {@code requires} predicate: it
 * asks every matching child literal for prefix completions regardless of
 * {@code canUse}, so operator-only commands appear in the raw reply of a
 * vanilla server. This mixin strips FauxPlayers aliases from the generated
 * suggestions whenever the requesting source is a non-operator player, before
 * any caller can relay them to a client. The packet-level mixin on
 * {@code handleCustomCommandSuggestions} remains as a second layer.
 */
@Mixin(CommandDispatcher.class)
public abstract class CommandDispatcherSuggestionMixin<S> {
    private static final Logger LOGGER = LoggerFactory.getLogger("FauxPlayers");

    static {
        LOGGER.info("CommandDispatcherSuggestionMixin loaded and active.");
    }

    private static boolean fauxplayers$isAlias(String text) {
        return text.equals("fauxplayers") || text.equals("fp") || text.equals("fakeplayers");
    }

    /** Lowercases a suggestion payload and keeps only its first token (root name). */
    private static String fauxplayers$firstToken(String text) {
        String line = text == null ? "" : text.trim();
        if (line.startsWith("/")) line = line.substring(1);
        int space = line.indexOf(' ');
        if (space >= 0) line = line.substring(0, space);
        int colon = line.indexOf(':');
        if (colon >= 0) line = line.substring(colon + 1);
        return line.toLowerCase(Locale.ROOT);
    }

    /**
     * The two-argument overload is the one the one-argument overload delegates
     * to, so filtering it here covers every caller.
     */
    @Inject(method = "getCompletionSuggestions(Lcom/mojang/brigadier/ParseResults;I)Ljava/util/concurrent/CompletableFuture;",
            at = @At("RETURN"), cancellable = true)
    private void fauxplayers$stripGeneratedSuggestions(ParseResults<S> parse, int cursor,
            CallbackInfoReturnable<CompletableFuture<Suggestions>> callback) {
        S source = parse.getContext().getSource();
        if (!(source instanceof CommandSourceStack stack)) return;
        if (FabricEntrypoint.admin(stack)) return;

        CompletableFuture<Suggestions> original = callback.getReturnValue();
        if (original == null) return;
        callback.setReturnValue(original.thenApply(suggestions -> {
            if (suggestions == null || suggestions.getList().isEmpty()) return suggestions;
            List<Suggestion> filtered = new ArrayList<>(suggestions.getList().size());
            for (Suggestion suggestion : suggestions.getList()) {
                if (fauxplayers$isAlias(fauxplayers$firstToken(suggestion.getText()))) continue;
                filtered.add(suggestion);
            }
            return filtered.size() == suggestions.getList().size()
                    ? suggestions
                    : new Suggestions(suggestions.getRange(), filtered);
        }));
    }
}
