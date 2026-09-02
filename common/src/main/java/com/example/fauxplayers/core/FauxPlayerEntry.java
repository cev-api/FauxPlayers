package com.example.fauxplayers.core;

import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.UUID;

/** Platform-neutral presentation data. This is never a server-side player. */
public record FauxPlayerEntry(
        String name,
        UUID uuid,
        String displayName,
        int latency,
        String gameMode,
        boolean remote) {

    public static UUID stable(String source, String name) {
        return UUID.nameUUIDFromBytes(
                (source + "\u0000" + name.toLowerCase(Locale.ROOT)).getBytes(StandardCharsets.UTF_8));
    }
}
