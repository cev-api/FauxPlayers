package com.cevapi.fauxplayers.core;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;

/** Shared count, collision, and entry-selection rules for both platforms. */
public final class PresentationMath {
    private PresentationMath() { }

    public static List<FauxPlayerEntry> merge(PluginConfig config,
                                               Collection<FauxPlayerEntry> local,
                                               Collection<FauxPlayerEntry> remote) {
        return merge(config, local, remote, List.of());
    }

    public static List<FauxPlayerEntry> merge(PluginConfig config,
                                               Collection<FauxPlayerEntry> local,
                                               Collection<FauxPlayerEntry> remote,
                                               Collection<FauxPlayerEntry> replay) {
        Map<String, FauxPlayerEntry> byName = new LinkedHashMap<>();
        for (FauxPlayerEntry entry : local) {
            byName.putIfAbsent(entry.name().toLowerCase(Locale.ROOT), entry);
        }
        for (FauxPlayerEntry entry : remote) {
            String key = entry.name().toLowerCase(Locale.ROOT);
            if ("KEEP_REMOTE".equals(config.collision)) byName.put(key, entry);
            else byName.putIfAbsent(key, entry);
        }
        for (FauxPlayerEntry entry : replay) {
            byName.putIfAbsent(entry.name().toLowerCase(Locale.ROOT), entry);
        }
        return List.copyOf(byName.values());
    }

    public static List<FauxPlayerEntry> statusEntries(PluginConfig config, PlayerSnapshot remote) {
        return statusEntries(config, remote, List.of());
    }

    public static List<FauxPlayerEntry> statusEntries(PluginConfig config, PlayerSnapshot remote,
                                                      Collection<FauxPlayerEntry> replay) {
        return merge(config,
                config.statusFakes ? config.statics : List.of(),
                config.statusRemote ? remote.players() : List.of(),
                config.statusReplay ? replay : List.of());
    }

    public static List<FauxPlayerEntry> tabEntries(PluginConfig config, PlayerSnapshot remote) {
        return tabEntries(config, remote, List.of());
    }

    public static List<FauxPlayerEntry> tabEntries(PluginConfig config, PlayerSnapshot remote,
                                                   Collection<FauxPlayerEntry> replay) {
        return merge(config,
                config.tabFakes ? config.statics : List.of(),
                config.tabRemote ? remote.players() : List.of(),
                config.tabReplay ? replay : List.of());
    }

    /**
     * Drops any entry that would duplicate a real player. The real player keeps their own entry, so
     * a fake one with the same name is left out rather than listed beside them.
     */
    public static List<FauxPlayerEntry> withoutRealPlayers(Collection<FauxPlayerEntry> entries,
                                                           Collection<String> realNames) {
        if (realNames == null || realNames.isEmpty()) return List.copyOf(entries);
        Set<String> real = new HashSet<>();
        for (String name : realNames) {
            if (name != null && !name.isBlank()) real.add(name.toLowerCase(Locale.ROOT));
        }
        if (real.isEmpty()) return List.copyOf(entries);
        List<FauxPlayerEntry> kept = new ArrayList<>();
        for (FauxPlayerEntry entry : entries) {
            if (!real.contains(entry.name().toLowerCase(Locale.ROOT))) kept.add(entry);
        }
        return List.copyOf(kept);
    }

    public static int statusCount(PluginConfig config, PlayerSnapshot remote,
                                  int realPlayers, int fakePlayers) {
        return switch (config.countMode) {
            case "FIXED" -> config.fixed;
            case "ADDITIONAL" -> realPlayers + config.additional;
            case "RANDOM" -> ThreadLocalRandom.current().nextInt(
                    config.minimum, Math.max(config.minimum, config.maximum) + 1);
            case "REMOTE" -> remote.reportedOnline();
            default -> realPlayers + fakePlayers + config.additional;
        };
    }

    public static List<FauxPlayerEntry> sample(PluginConfig config,
                                                Collection<FauxPlayerEntry> real,
                                                Collection<FauxPlayerEntry> faux) {
        List<FauxPlayerEntry> result = new ArrayList<>();
        if (!"REMOTE".equals(config.sampleMode)) result.addAll(real);
        if (!"REAL".equals(config.sampleMode)) result.addAll(faux);
        if (config.shuffle) java.util.Collections.shuffle(result);
        if (config.sampleLimit >= 0 && result.size() > config.sampleLimit) {
            result.subList(config.sampleLimit, result.size()).clear();
        }
        return result;
    }
}
