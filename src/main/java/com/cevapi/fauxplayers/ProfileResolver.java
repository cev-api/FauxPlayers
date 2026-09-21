package com.cevapi.fauxplayers;

import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.*;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.profile.PlayerProfile;

/** Resolves Mojang UUIDs, canonical name casing, and signed skin data without blocking the server thread. */
public final class ProfileResolver {
    private static final HttpClient HTTP=HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(10)).build();
    private static final int MAX_TEXTURE_ATTEMPTS=3;
    private static final long[] TEXTURE_RETRY_DELAYS_MILLIS={1000L,3000L};
    private static final Pattern PROFILE_ID=Pattern.compile("\\\"id\\\"\\s*:\\s*\\\"([0-9a-fA-F]{32})\\\"");
    private static final Pattern PROFILE_NAME=Pattern.compile("\\\"name\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"");
    private final JavaPlugin plugin;
    /** Successful name lookups, and the ones still in flight. */
    private final ConcurrentHashMap<String,PlayerProfile> profiles=new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String,CompletableFuture<PlayerProfile>> pending=new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String,CompletableFuture<String>> names=new ConcurrentHashMap<>();
    /** Serialises name lookups: replay mode asks for a whole roster at once. */
    private final java.util.concurrent.atomic.AtomicLong lastNameRequest=new java.util.concurrent.atomic.AtomicLong();
    private static final long MINIMUM_NAME_INTERVAL_MILLIS=200L;
    private final java.util.concurrent.atomic.AtomicBoolean reportedLookupFailure=new java.util.concurrent.atomic.AtomicBoolean();
    /** Only successful textures are kept here, so a rate limit does not poison a head forever. */
    private final ConcurrentHashMap<java.util.UUID,CompletableFuture<TextureProperty>> textures=new ConcurrentHashMap<>();
    private final ConcurrentHashMap<java.util.UUID,CompletableFuture<TextureProperty>> pendingTextures=new ConcurrentHashMap<>();
    /** Serialises texture requests: replay mode asks for many at once. */
    private final java.util.concurrent.atomic.AtomicLong lastTextureRequest=new java.util.concurrent.atomic.AtomicLong();
    private static final long MINIMUM_TEXTURE_INTERVAL_MILLIS=250L;
    private final java.util.concurrent.atomic.AtomicBoolean reportedTextureFailure=new java.util.concurrent.atomic.AtomicBoolean();

    public record TextureProperty(String value,String signature){}
    public ProfileResolver(JavaPlugin plugin){this.plugin=plugin;}

    public CompletableFuture<PlayerProfile> resolve(String name){
        String key=name.toLowerCase(java.util.Locale.ROOT);
        PlayerProfile cached=profiles.get(key);
        if(cached!=null)return CompletableFuture.completedFuture(cached);
        // Only a success is remembered. A burst of lookups is rate limited by Mojang with HTTP 429,
        // and caching that failure would leave every head on the default skin for the whole session.
        return pending.computeIfAbsent(key,ignored->lookupProfile(name)
            .thenApply(identity->{
                if(identity==null)return null;
                // Do not call PlayerProfile#update here. Paper delegates that call
                // to authlib, which performs a second session-server skin lookup
                // and can emit a full stack trace when Mojang is slow or offline.
                PlayerProfile profile=Bukkit.createPlayerProfile(identity.uuid(),identity.name());
                plugin.getLogger().info("Resolved profile "+name+" -> "+profile.getUniqueId()+" (skin lookup deferred)");
                return profile;
            })
            // Generous, because Mojang can answer slowly and a short timeout would throw a
            // perfectly good lookup away.
            .orTimeout(20,TimeUnit.SECONDS)
            .exceptionally(error->{String reason=rootCause(error).getMessage();if(reason!=null&&reason.contains("no such account"))missing.add(key);noteLookupFailure(name,reason);return null;})
            .thenApply(profile->{if(profile!=null)profiles.put(key,profile);return profile;})
            .whenComplete((profile,error)->pending.remove(key)));
    }

    /** Names Mojang reported as having no account, so they are retried far less often. */
    private final java.util.Set<String> missing=ConcurrentHashMap.newKeySet();

    /** True when a name was reported as having no Mojang account. */
    public boolean isMissing(String name){return missing.contains(name.toLowerCase(java.util.Locale.ROOT));}

    /** Says once why a name could not be resolved, instead of leaving a default head unexplained. */
    private void noteLookupFailure(String name,String reason){
        if(reportedLookupFailure.compareAndSet(false,true)){
            plugin.getLogger().warning("Could not look up the Mojang profile for "+name+" ("+reason+"); heads fall back to the default skin and the lookup is retried later. Bulk lookups can be rate limited by Mojang.");
        }
    }

    private CompletableFuture<ProfileIdentity> lookupProfile(String name){
        long now=System.currentTimeMillis();
        long earliest=lastNameRequest.get()+MINIMUM_NAME_INTERVAL_MILLIS;
        long wait=Math.max(0,earliest-now);
        lastNameRequest.set(Math.max(now,earliest));
        if(wait>0){
            return CompletableFuture.runAsync(()->{},CompletableFuture.delayedExecutor(wait,TimeUnit.MILLISECONDS))
                .thenCompose(ignored->lookupProfileNow(name));
        }
        return lookupProfileNow(name);
    }

    private CompletableFuture<ProfileIdentity> lookupProfileNow(String name){
        String encoded=java.net.URLEncoder.encode(name,StandardCharsets.UTF_8).replace("+","%20");
        HttpRequest request=HttpRequest.newBuilder(URI.create("https://api.mojang.com/users/profiles/minecraft/"+encoded))
            .timeout(java.time.Duration.ofSeconds(15)).GET().build();
        return HTTP.sendAsync(request,HttpResponse.BodyHandlers.ofString())
            .thenApply(response->{
                if(response.statusCode()==204||response.statusCode()==404)
                    throw new IllegalStateException("no such account (HTTP "+response.statusCode()+")");
                if(response.statusCode()!=200)throw new IllegalStateException("HTTP "+response.statusCode());
                Matcher id=PROFILE_ID.matcher(response.body());
                if(!id.find())throw new IllegalStateException("profile UUID missing from response");
                Matcher profileName=PROFILE_NAME.matcher(response.body());
                String canonical=profileName.find()?profileName.group(1):name;
                return new ProfileIdentity(uuidFromUndashed(id.group(1)),canonical);
            });
    }

    private static UUID uuidFromUndashed(String value){
        return UUID.fromString(value.substring(0,8)+"-"+value.substring(8,12)+"-"+value.substring(12,16)+"-"+value.substring(16,20)+"-"+value.substring(20));
    }

    private static Throwable rootCause(Throwable error){
        Throwable cause=error;
        while((cause instanceof CompletionException||cause instanceof ExecutionException)&&cause.getCause()!=null)cause=cause.getCause();
        return cause;
    }

    private record ProfileIdentity(UUID uuid,String name){}

    public CompletableFuture<String> canonicalName(String name){
        String key=name.toLowerCase(java.util.Locale.ROOT);
        return names.computeIfAbsent(key,ignored->{
            HttpRequest request=HttpRequest.newBuilder(URI.create("https://api.mojang.com/users/profiles/minecraft/"+name))
                .timeout(java.time.Duration.ofSeconds(15)).GET().build();
            return HTTP.sendAsync(request,HttpResponse.BodyHandlers.ofString())
                .thenApply(response->{Matcher m=Pattern.compile("\\\"name\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"").matcher(response.body());return m.find()?m.group(1):name;})
                .exceptionally(error->name);
        });
    }

    public CompletableFuture<String> canonicalName(PlayerProfile profile,String fallback){
        String profileName=profile==null?null:profile.getName();
        if(profileName!=null&&!profileName.isBlank()&&!profileName.equals(fallback))return CompletableFuture.completedFuture(profileName);
        return canonicalName(fallback);
    }

    public CompletableFuture<TextureProperty> resolveTexture(java.util.UUID uuid){
        CompletableFuture<TextureProperty> cached=textures.get(uuid);
        if(cached!=null)return cached;
        // Only a success is remembered. A miss is retried by the next request instead of
        // leaving that head on the default skin for the rest of the session.
        return pendingTextures.computeIfAbsent(uuid,key->fetchTexture(key,1)
            .thenApply(texture->{if(texture!=null)textures.put(key,CompletableFuture.completedFuture(texture));return texture;})
            .exceptionally(error->null)
            .whenComplete((value,error)->pendingTextures.remove(key)));
    }

    private CompletableFuture<TextureProperty> fetchTexture(UUID uuid,int attempt){
        long now=System.currentTimeMillis();
        long earliest=lastTextureRequest.get()+MINIMUM_TEXTURE_INTERVAL_MILLIS;
        long wait=Math.max(0,earliest-now);
        lastTextureRequest.set(Math.max(now,earliest));
        if(wait>0){
            return CompletableFuture.runAsync(()->{},CompletableFuture.delayedExecutor(wait,TimeUnit.MILLISECONDS))
                .thenCompose(ignored->fetchTextureNow(uuid,attempt));
        }
        return fetchTextureNow(uuid,attempt);
    }

    private CompletableFuture<TextureProperty> fetchTextureNow(UUID uuid,int attempt){
        HttpRequest request=HttpRequest.newBuilder(URI.create("https://sessionserver.mojang.com/session/minecraft/profile/"+uuid+"?unsigned=false"))
            .timeout(java.time.Duration.ofSeconds(15)).GET().build();
        return HTTP.sendAsync(request,HttpResponse.BodyHandlers.ofString()).<CompletableFuture<TextureProperty>>handle((response,error)->{
            if(error!=null){
                if(retryable(rootCause(error))&&attempt<MAX_TEXTURE_ATTEMPTS)return retryTexture(uuid,attempt);
                noteTextureFailure(uuid,"network: "+rootCause(error).getMessage());
                return CompletableFuture.<TextureProperty>completedFuture(null);
            }
            if(response.statusCode()==200){
                TextureProperty property=parseTexture(response.body());
                if(property==null)noteTextureFailure(uuid,"the response held no texture");
                return CompletableFuture.completedFuture(property);
            }
            if(retryableStatus(response.statusCode())&&attempt<MAX_TEXTURE_ATTEMPTS)return retryTexture(uuid,attempt);
            if(response.statusCode()!=204)noteTextureFailure(uuid,"HTTP "+response.statusCode());
            return CompletableFuture.<TextureProperty>completedFuture(null);
        }).thenCompose(next->next);
    }

    /** Explains, once, why a head cannot use its real skin instead of quietly showing the default. */
    private void noteTextureFailure(UUID uuid,String reason){
        if(reportedTextureFailure.compareAndSet(false,true)){
            plugin.getLogger().warning("Could not read the Mojang skin for "+uuid+" ("+reason+"); heads fall back to the default skin and the lookup is retried later. A burst of lookups, such as a replay produces, can be rate limited by Mojang.");
        }
    }

    private CompletableFuture<TextureProperty> retryTexture(UUID uuid,int attempt){
        long delay=TEXTURE_RETRY_DELAYS_MILLIS[Math.min(attempt-1,TEXTURE_RETRY_DELAYS_MILLIS.length-1)];
        return CompletableFuture.runAsync(()->{},CompletableFuture.delayedExecutor(delay,TimeUnit.MILLISECONDS))
            .thenCompose(ignored->fetchTexture(uuid,attempt+1));
    }

    private static TextureProperty parseTexture(String body){
        Matcher v=Pattern.compile("\\\"value\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"").matcher(body);
        if(!v.find())return null;
        Matcher sig=Pattern.compile("\\\"signature\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"").matcher(body);
        return new TextureProperty(v.group(1),sig.find()?sig.group(1):null);
    }

    private static boolean retryableStatus(int status){return status==408||status==429||status>=500;}

    private static boolean retryable(Throwable error){return error instanceof java.io.IOException;}

    public void clear(){profiles.clear();pending.clear();missing.clear();names.clear();textures.clear();pendingTextures.clear();reportedTextureFailure.set(false);reportedLookupFailure.set(false);}
}
