package com.cevapi.fauxplayers.fabric;

import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.minecraft.server.MinecraftServer;

/** Resolves UUIDs through the server and fetches signed textures without authlib logging stack traces. */
final class FabricProfileResolver {
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(java.time.Duration.ofSeconds(5)).build();
    private static final int MAX_TEXTURE_ATTEMPTS = 3;
    private static final long[] TEXTURE_RETRY_DELAYS_MILLIS = {1000L, 3000L};
    private static final Pattern TEXTURE_VALUE = Pattern.compile("\\\"value\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"");
    private static final Pattern TEXTURE_SIGNATURE = Pattern.compile("\\\"signature\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"");
    private final MinecraftServer server;
    private final ConcurrentHashMap<String, CompletableFuture<GameProfile>> profiles = new ConcurrentHashMap<>();

    FabricProfileResolver(MinecraftServer server) {
        this.server = server;
    }

    CompletableFuture<GameProfile> resolve(String name) {
        return profiles.computeIfAbsent(name.toLowerCase(Locale.ROOT), ignored ->
                CompletableFuture.supplyAsync(() -> {
                    return server.services().profileRepository().findProfileByName(name).orElse(null);
                }).thenCompose(id -> {
                    if (id == null) return CompletableFuture.completedFuture(null);
                    return fetchTexture(id.id(), 1).thenApply(texture -> {
                        GameProfile profile = new GameProfile(id.id(), id.name());
                        if (texture != null) {
                            Property property = texture.signature() == null
                                    ? new Property("textures", texture.value())
                                    : new Property("textures", texture.value(), texture.signature());
                            profile.properties().put("textures", property);
                        }
                        return profile;
                    });
                }).exceptionally(ignoredError -> null));
    }

    private CompletableFuture<TextureProperty> fetchTexture(UUID uuid, int attempt) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(
                        "https://sessionserver.mojang.com/session/minecraft/profile/" + uuid + "?unsigned=false"))
                .timeout(java.time.Duration.ofSeconds(5)).GET().build();
        return HTTP.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .<CompletableFuture<TextureProperty>>handle((response, error) -> {
                    if (error != null) {
                        return attempt < MAX_TEXTURE_ATTEMPTS
                                ? retryTexture(uuid, attempt)
                                : CompletableFuture.completedFuture(null);
                    }
                    if (response.statusCode() == 200) {
                        return CompletableFuture.completedFuture(parseTexture(response.body()));
                    }
                    if (retryableStatus(response.statusCode()) && attempt < MAX_TEXTURE_ATTEMPTS) {
                        return retryTexture(uuid, attempt);
                    }
                    return CompletableFuture.completedFuture(null);
                }).thenCompose(next -> next);
    }

    private CompletableFuture<TextureProperty> retryTexture(UUID uuid, int attempt) {
        long delay = TEXTURE_RETRY_DELAYS_MILLIS[Math.min(attempt - 1, TEXTURE_RETRY_DELAYS_MILLIS.length - 1)];
        return CompletableFuture.runAsync(() -> { },
                        CompletableFuture.delayedExecutor(delay, TimeUnit.MILLISECONDS))
                .thenCompose(ignored -> fetchTexture(uuid, attempt + 1));
    }

    private static TextureProperty parseTexture(String body) {
        Matcher value = TEXTURE_VALUE.matcher(body);
        if (!value.find()) return null;
        Matcher signature = TEXTURE_SIGNATURE.matcher(body);
        return new TextureProperty(value.group(1), signature.find() ? signature.group(1) : null);
    }

    private static boolean retryableStatus(int status) {
        return status == 408 || status == 429 || status >= 500;
    }

    private record TextureProperty(String value, String signature) { }

    CompletableFuture<String> canonicalName(String name) {
        return resolve(name).thenApply(profile -> profile == null || profile.name() == null
                ? name : profile.name());
    }
}
