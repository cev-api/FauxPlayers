package com.example.fauxplayers.fabric;

import com.mojang.authlib.GameProfile;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.server.MinecraftServer;

/** Uses the server's authenticated Mojang services, including signed textures. */
final class FabricProfileResolver {
    private final MinecraftServer server;
    private final ConcurrentHashMap<String, CompletableFuture<GameProfile>> profiles = new ConcurrentHashMap<>();

    FabricProfileResolver(MinecraftServer server) {
        this.server = server;
    }

    CompletableFuture<GameProfile> resolve(String name) {
        return profiles.computeIfAbsent(name.toLowerCase(Locale.ROOT), ignored ->
                CompletableFuture.supplyAsync(() -> {
                    try {
                        var id = server.services().profileRepository().findProfileByName(name).orElse(null);
                        if (id == null) return null;
                        var result = server.services().sessionService().fetchProfile(id.id(), true);
                        return result == null ? new GameProfile(id.id(), id.name()) : result.profile();
                    } catch (Exception ignoredError) {
                        return null;
                    }
                }));
    }

    CompletableFuture<String> canonicalName(String name) {
        return resolve(name).thenApply(profile -> profile == null || profile.name() == null
                ? name : profile.name());
    }
}
