package com.example.fauxplayers.core;

import java.time.Instant;
import java.util.List;

public record PlayerSnapshot(
        int reportedOnline,
        int reportedMax,
        List<FauxPlayerEntry> players,
        String motd,
        String version,
        Instant refreshedAt) {

    public PlayerSnapshot {
        players = List.copyOf(players);
    }

    public static PlayerSnapshot empty() {
        return new PlayerSnapshot(0, 0, List.of(), null, null, null);
    }
}
