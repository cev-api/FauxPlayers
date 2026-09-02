package com.example.fauxplayers.core;

import java.util.List;

/** Shared command names and value suggestions used by both platform adapters. */
public final class CommandCatalog {
    public static final List<String> ROOT = List.of(
            "reload", "status", "info", "refresh", "list", "add", "remove",
            "say", "chat", "ping", "get", "set", "relay");
    public static final List<String> RELAY_OPTIONS = List.of(
            "enable", "enabled", "disable", "host", "port", "source", "refresh-seconds", "refresh");
    public static final List<String> SETTINGS = List.of(
            "enabled", "status.enabled", "status.include-real-players", "status.include-static-fakes",
            "status.include-relayed-players", "count.mode", "count.fixed", "count.additional",
            "count.minimum", "count.maximum", "max-players.fixed", "sample.mode", "sample.shuffle",
            "sample.maximum-entries", "tab.enabled", "tab.include-static-fakes", "tab.include-relayed-players",
            "tab.default-latency", "tab.default-gamemode", "tab.random-ping.enabled", "tab.random-ping.minimum",
            "tab.random-ping.maximum", "tab.random-ping.standard-deviation", "tab.random-ping.refresh-seconds",
            "relay.enabled", "relay.source", "relay.status.host", "relay.status.port",
            "relay.status.protocol-version", "relay.http.url", "relay.refresh-seconds",
            "relay.connect-timeout-ms", "relay.read-timeout-ms", "relay.keep-last-successful-result",
            "relay.maximum-stale-seconds");

    private CommandCatalog() {
    }

    public static List<String> valuesFor(String setting) {
        return switch (setting) {
            case "enabled", "status.enabled", "status.include-real-players", "status.include-static-fakes",
                    "status.include-relayed-players", "sample.shuffle", "tab.enabled",
                    "tab.include-static-fakes", "tab.include-relayed-players", "tab.random-ping.enabled",
                    "relay.enabled", "relay.keep-last-successful-result" -> List.of("true", "false");
            case "count.mode" -> List.of("COMBINED", "FIXED", "ADDITIONAL", "RANDOM", "REMOTE");
            case "sample.mode" -> List.of("REAL", "REMOTE", "COMBINED");
            case "tab.default-gamemode" -> List.of("SURVIVAL", "CREATIVE", "ADVENTURE", "SPECTATOR");
            case "relay.source" -> List.of("STATUS", "HTTP");
            default -> List.of();
        };
    }
}
