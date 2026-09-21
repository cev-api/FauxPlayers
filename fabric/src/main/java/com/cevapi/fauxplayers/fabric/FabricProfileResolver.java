package com.cevapi.fauxplayers.fabric;

import com.google.common.collect.ImmutableMultimap;
import com.mojang.authlib.GameProfile;
import com.mojang.authlib.properties.Property;
import com.mojang.authlib.properties.PropertyMap;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolves Mojang UUIDs and signed textures over our own HTTP client.
 * <p>
 * The name lookup deliberately does not go through the server's profile repository. That client uses a
 * short read timeout and logs a full stack trace on failure, so a slow reply left a name unread and
 * filled the console. These requests use a generous timeout and report a single line instead.
 */
final class FabricProfileResolver {
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(java.time.Duration.ofSeconds(5)).build();
    private static final String NAME_ENDPOINT = "https://api.mojang.com/users/profiles/minecraft/";
    private static final String TEXTURE_ENDPOINT =
            "https://sessionserver.mojang.com/session/minecraft/profile/";
    private static final java.time.Duration LOOKUP_TIMEOUT = java.time.Duration.ofSeconds(15);
    private static final int MAX_TEXTURE_ATTEMPTS = 3;
    private static final long[] TEXTURE_RETRY_DELAYS_MILLIS = {1000L, 3000L};
    private static final Pattern PROFILE_ID = Pattern.compile("\\\"id\\\"\\s*:\\s*\\\"([0-9a-fA-F]{32})\\\"");
    private static final Pattern PROFILE_NAME = Pattern.compile("\\\"name\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"");
    private static final Pattern TEXTURE_VALUE = Pattern.compile("\\\"value\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"");
    private static final Pattern TEXTURE_SIGNATURE = Pattern.compile("\\\"signature\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"");
    /** Space between lookups, because a whole roster is asked for at once and Mojang throttles that. */
    private static final long MINIMUM_REQUEST_INTERVAL_MILLIS = 200L;
    private static final int STATUS_OK = 200;
    private static final int STATUS_NOT_FOUND = 404;
    private static final int STATUS_NO_CONTENT = 204;
    /** Only a complete result is remembered, so a throttled lookup is retried on the next pass. */
    private final ConcurrentHashMap<String, GameProfile> resolved = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, CompletableFuture<GameProfile>> pending = new ConcurrentHashMap<>();
    /** Names Mojang reported as having no account, so they are retried far less often. */
    private final Set<String> missing = ConcurrentHashMap.newKeySet();
    private final java.util.concurrent.atomic.AtomicLong lastRequest = new java.util.concurrent.atomic.AtomicLong();
    private final java.util.concurrent.atomic.AtomicBoolean reportedFailure = new java.util.concurrent.atomic.AtomicBoolean();
    private volatile Consumer<String> warning = message -> { };

    FabricProfileResolver() {
    }

    /** Where to send the first explanation of a head that could not be read. */
    void warnWith(Consumer<String> sink) {
        this.warning = sink;
    }

    /** True when a name was confirmed to have no Mojang account, so it is retried far less often. */
    boolean isMissing(String name) {
        return missing.contains(name.toLowerCase(Locale.ROOT));
    }

    /** True when a profile carries a signed texture, which is what draws the real head. */
    static boolean hasTexture(GameProfile profile) {
        if (profile == null) return false;
        try {
            return !profile.properties().get("textures").isEmpty();
        } catch (Throwable ignored) {
            return false;
        }
    }

    CompletableFuture<GameProfile> resolve(String name) {
        String key = name.toLowerCase(Locale.ROOT);
        GameProfile complete = resolved.get(key);
        if (complete != null) return CompletableFuture.completedFuture(complete);
        // A miss is never cached, not even one Mojang reports as unknown, so a failed lookup is
        // always retried. The previous version stored the failure through computeIfAbsent, which
        // left that name without a head for the rest of the session and made no later attempt.
        return pending.computeIfAbsent(key, ignored -> lookup(name)
                .thenApply(profile -> {
                    // A profile without textures is still shown and re-added once the skin arrives,
                    // but it is not remembered, so the next pass asks again.
                    if (profile != null && hasTexture(profile)) resolved.put(key, profile);
                    return profile;
                })
                .whenComplete((profile, error) -> pending.remove(key)));
    }

    private CompletableFuture<GameProfile> lookup(String name) {
        return throttle(ignored -> lookupIdentity(name).handle((identity, error) -> {
            if (error != null) {
                // Anything unreadable is left unrecorded, so the next pass retries it at the normal
                // rate rather than being written off.
                note(name, describe(rootCause(error)));
                return null;
            }
            return identity;
        }).thenCompose(identity -> {
            if (identity == null) return CompletableFuture.completedFuture(null);
            return fetchTexture(identity.id(), 1).thenApply(texture -> {
                if (texture == null) return new GameProfile(identity.id(), identity.name());
                Property property = texture.signature() == null
                        ? new Property("textures", texture.value())
                        : new Property("textures", texture.value(), texture.signature());
                // The profile's properties cannot be added to after construction: the plain two
                // argument GameProfile is backed by an immutable multimap, so putting into it throws
                // UnsupportedOperationException and the head silently falls back to the default skin.
                // The map is therefore built up front and passed to the constructor.
                return new GameProfile(identity.id(), identity.name(),
                        new PropertyMap(ImmutableMultimap.of("textures", property)));
            });
        }));
    }

    /** Reads the UUID and the canonical name for an account. */
    private CompletableFuture<ProfileIdentity> lookupIdentity(String name) {
        String encoded = URLEncoder.encode(name, StandardCharsets.UTF_8).replace("+", "%20");
        HttpRequest request = HttpRequest.newBuilder(URI.create(NAME_ENDPOINT + encoded))
                .timeout(LOOKUP_TIMEOUT).GET().build();
        return HTTP.sendAsync(request, HttpResponse.BodyHandlers.ofString()).thenApply(response -> {
            int status = response.statusCode();
            if (status == STATUS_NOT_FOUND || status == STATUS_NO_CONTENT) {
                // A confirmed absent account is noted so it can be retried less often. It is still
                // retried, because a temporary lookup failure can look the same as a missing name.
                missing.add(name.toLowerCase(Locale.ROOT));
                return null;
            }
            if (status != STATUS_OK) throw new IllegalStateException("HTTP " + status);
            Matcher id = PROFILE_ID.matcher(response.body());
            if (!id.find()) throw new IllegalStateException("the response held no id");
            Matcher canonical = PROFILE_NAME.matcher(response.body());
            return new ProfileIdentity(uuidFromUndashed(id.group(1)),
                    canonical.find() ? canonical.group(1) : name);
        });
    }

    private static UUID uuidFromUndashed(String value) {
        return UUID.fromString(value.substring(0, 8) + "-" + value.substring(8, 12) + "-"
                + value.substring(12, 16) + "-" + value.substring(16, 20) + "-" + value.substring(20));
    }

    /** The cause behind a completion wrapper, so a timeout reads as a timeout. */
    private static Throwable rootCause(Throwable error) {
        Throwable cause = error;
        while ((cause instanceof java.util.concurrent.CompletionException
                || cause instanceof java.util.concurrent.ExecutionException) && cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause;
    }

    private record ProfileIdentity(UUID id, String name) { }

    /** Runs the supplier once this resolver is allowed to make another request. */
    private <T> CompletableFuture<T> throttle(
            java.util.function.Function<Void, CompletableFuture<T>> supplier) {
        long now = System.currentTimeMillis();
        long earliest = lastRequest.get() + MINIMUM_REQUEST_INTERVAL_MILLIS;
        long wait = Math.max(0L, earliest - now);
        lastRequest.set(Math.max(now, earliest));
        if (wait > 0L) {
            return CompletableFuture.runAsync(() -> { },
                            CompletableFuture.delayedExecutor(wait, TimeUnit.MILLISECONDS))
                    .thenCompose(ignored -> supplier.apply(null));
        }
        return supplier.apply(null);
    }

    /** Says once why a name could not be read, instead of leaving a default head unexplained. */
    private void note(String name, String reason) {
        if (reportedFailure.compareAndSet(false, true)) {
            warning.accept("Could not read the Mojang profile for " + name + " (" + reason
                    + "); that head uses the default skin and is retried later.");
        }
    }

    private static String describe(Throwable error) {
        String message = String.valueOf(error.getMessage());
        return message.length() > 120 ? message.substring(0, 120) : message;
    }

    private CompletableFuture<TextureProperty> fetchTexture(UUID uuid, int attempt) {
        return throttle(ignored -> fetchTextureNow(uuid, attempt));
    }

    private CompletableFuture<TextureProperty> fetchTextureNow(UUID uuid, int attempt) {
        HttpRequest request = HttpRequest.newBuilder(URI.create(
                        TEXTURE_ENDPOINT + uuid + "?unsigned=false"))
                .timeout(LOOKUP_TIMEOUT).GET().build();
        return HTTP.sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .<CompletableFuture<TextureProperty>>handle((response, error) -> {
                    if (error != null) {
                        if (attempt < MAX_TEXTURE_ATTEMPTS) return retryTexture(uuid, attempt);
                        note(uuid.toString(), describe(rootCause(error)));
                        return CompletableFuture.completedFuture(null);
                    }
                    if (response.statusCode() == STATUS_OK) {
                        TextureProperty property = parseTexture(response.body());
                        if (property == null) note(uuid.toString(), "the response held no texture");
                        return CompletableFuture.completedFuture(property);
                    }
                    if (retryableStatus(response.statusCode()) && attempt < MAX_TEXTURE_ATTEMPTS) {
                        return retryTexture(uuid, attempt);
                    }
                    if (response.statusCode() != STATUS_NOT_FOUND && response.statusCode() != STATUS_NO_CONTENT) {
                        note(uuid.toString(), "HTTP " + response.statusCode());
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

    void clear() {
        resolved.clear();
        pending.clear();
        missing.clear();
        reportedFailure.set(false);
    }
}
