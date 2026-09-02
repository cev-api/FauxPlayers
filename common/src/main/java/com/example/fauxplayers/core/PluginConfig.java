package com.example.fauxplayers.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Reads the shared configuration without depending on Paper or Fabric. */
public final class PluginConfig {
    public interface Source {
        Object get(String path);

        default boolean bool(String path, boolean fallback) {
            Object value = get(path);
            if (value instanceof Boolean b) return b;
            return value == null ? fallback : Boolean.parseBoolean(String.valueOf(value));
        }

        default int integer(String path, int fallback) {
            Object value = get(path);
            if (value instanceof Number n) return n.intValue();
            if (value != null) {
                try { return Integer.parseInt(String.valueOf(value)); }
                catch (NumberFormatException ignored) { }
            }
            return fallback;
        }

        default String text(String path, String fallback) {
            Object value = get(path);
            return value == null ? fallback : String.valueOf(value);
        }

        @SuppressWarnings("unchecked")
        default List<Map<String, Object>> maps(String path) {
            Object value = get(path);
            if (!(value instanceof List<?> list)) return List.of();
            List<Map<String, Object>> result = new ArrayList<>();
            for (Object item : list) {
                if (!(item instanceof Map<?, ?> map)) continue;
                Map<String, Object> converted = new java.util.LinkedHashMap<>();
                map.forEach((key, entry) -> converted.put(String.valueOf(key), entry));
                result.add(converted);
            }
            return result;
        }
    }

    public final boolean enabled, relayEnabled, messageEnabled, statusEnabled,
            includeReal, statusFakes, statusRemote, tabEnabled, tabFakes, tabRemote;
    public final String countMode, sampleMode, collision, relaySource, defaultMode;
    public final int fixed, additional, minimum, maximum, fixedMax, defaultLatency,
            pingMinimum, pingMaximum, pingStandardDeviation, pingRefreshSeconds,
            refreshSeconds, connectTimeout, readTimeout, protocolVersion, maximumStaleSeconds;
    public final boolean shuffle, keepCache, useCount, useMax, useSample, randomPing;
    public final int sampleLimit;
    public final List<FauxPlayerEntry> statics;
    public final String relayHost, httpUrl;
    public final int relayPort;

    private PluginConfig(Source source) {
        enabled = source.bool("enabled", true);
        relayEnabled = source.bool("relay.enabled", false);
        messageEnabled = source.bool("messages.enabled", true);
        statusEnabled = source.bool("status.enabled", true);
        includeReal = source.bool("status.include-real-players", true);
        statusFakes = source.bool("status.include-static-fakes", true);
        statusRemote = source.bool("status.include-relayed-players", true);
        tabEnabled = source.bool("tab.enabled", true);
        tabFakes = source.bool("tab.include-static-fakes", true);
        tabRemote = source.bool("tab.include-relayed-players", true);

        countMode = upper(source.text("count.mode", "COMBINED"));
        sampleMode = upper(source.text("sample.mode", "COMBINED"));
        collision = upper(source.text("relay.names.collision-behaviour", "REMOVE_DUPLICATE_REMOTE"));
        relaySource = upper(source.text("relay.source", "STATUS"));
        defaultMode = gameMode(source.text("tab.default-gamemode", "SURVIVAL"));

        fixed = source.integer("count.fixed", 20);
        additional = source.integer("count.additional", 0);
        minimum = source.integer("count.minimum", 0);
        maximum = source.integer("count.maximum", minimum);
        fixedMax = source.integer("max-players.fixed", 100);
        shuffle = source.bool("sample.shuffle", false);
        sampleLimit = source.integer("sample.maximum-entries", -1);
        defaultLatency = Math.max(0, source.integer("tab.default-latency", 50));
        randomPing = source.bool("tab.random-ping.enabled", true);
        pingMinimum = source.integer("tab.random-ping.minimum", 20);
        pingMaximum = source.integer("tab.random-ping.maximum", 100);
        pingStandardDeviation = source.integer("tab.random-ping.standard-deviation", 25);
        pingRefreshSeconds = source.integer("tab.random-ping.refresh-seconds", 30);
        refreshSeconds = Math.max(1, source.integer("relay.refresh-seconds", 10));
        connectTimeout = Math.max(1, source.integer("relay.connect-timeout-ms", 3000));
        readTimeout = Math.max(1, source.integer("relay.read-timeout-ms", 3000));
        keepCache = source.bool("relay.keep-last-successful-result", true);
        maximumStaleSeconds = Math.max(0, source.integer("relay.maximum-stale-seconds", 300));
        useCount = source.bool("relay.use-for.online-count", true);
        useMax = source.bool("relay.use-for.max-players", false);
        useSample = source.bool("relay.use-for.status-sample", true);
        relayHost = source.text("relay.status.host", "127.0.0.1");
        relayPort = source.integer("relay.status.port", -1);
        protocolVersion = source.integer("relay.status.protocol-version", 776);
        httpUrl = source.text("relay.http.url", "");

        List<FauxPlayerEntry> entries = new ArrayList<>();
        for (Map<String, Object> map : source.maps("static-players")) {
            String name = String.valueOf(map.getOrDefault("name", ""));
            if (name.isBlank()) continue;
            String displayName = String.valueOf(map.getOrDefault("display-name", name));
            int latency = number(map.get("latency"), defaultLatency);
            entries.add(new FauxPlayerEntry(name, FauxPlayerEntry.stable("static", name),
                    displayName, Math.max(0, latency), defaultMode, false));
        }
        statics = List.copyOf(entries);
    }

    public static PluginConfig load(Source source) {
        return new PluginConfig(source);
    }

    private static int number(Object value, int fallback) {
        if (value instanceof Number n) return n.intValue();
        if (value != null) {
            try { return Integer.parseInt(String.valueOf(value)); }
            catch (NumberFormatException ignored) { }
        }
        return fallback;
    }

    private static String upper(String value) {
        return value == null ? "" : value.toUpperCase(Locale.ROOT);
    }

    private static String gameMode(String value) {
        String mode = upper(value);
        return switch (mode) {
            case "SURVIVAL", "CREATIVE", "ADVENTURE", "SPECTATOR" -> mode;
            default -> "SURVIVAL";
        };
    }
}
