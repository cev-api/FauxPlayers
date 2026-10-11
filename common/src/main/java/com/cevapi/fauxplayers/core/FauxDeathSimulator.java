package com.cevapi.fauxplayers.core;

import java.util.*;

/** Produces occasional textual deaths for display-only players; it never creates entities. */
public final class FauxDeathSimulator {
    private static final List<String> MOBS = List.of("Zombie", "Skeleton", "Creeper", "Drowned", "Spider", "Fall Damage");
    private final Random random = new Random();
    private long nextAt = 180_000L + random.nextLong(420_001L);
    private long elapsed;

    public String tick(long deltaMillis, List<FauxPlayerEntry> players) {
        if (players.size() < 1) return null;
        elapsed += Math.max(0, deltaMillis);
        if (elapsed < nextAt) return null;
        elapsed = 0;
        nextAt = 180_000L + random.nextLong(420_001L);
        FauxPlayerEntry victim = players.get(random.nextInt(players.size()));
        List<FauxPlayerEntry> killers = players.stream().filter(p -> !p.uuid().equals(victim.uuid())).toList();
        if (!killers.isEmpty() && random.nextInt(3) == 0) {
            FauxPlayerEntry killer = killers.get(random.nextInt(killers.size()));
            return victim.name() + " was slain by " + killer.name();
        }
        return victim.name() + " was slain by " + MOBS.get(random.nextInt(MOBS.size()));
    }
}
