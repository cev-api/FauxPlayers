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
    private static final HttpClient HTTP=HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(5)).build();
    private static final int MAX_TEXTURE_ATTEMPTS=3;
    private static final long[] TEXTURE_RETRY_DELAYS_MILLIS={1000L,3000L};
    private static final Pattern PROFILE_ID=Pattern.compile("\\\"id\\\"\\s*:\\s*\\\"([0-9a-fA-F]{32})\\\"");
    private static final Pattern PROFILE_NAME=Pattern.compile("\\\"name\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"");
    private final JavaPlugin plugin;
    private final ConcurrentHashMap<String,CompletableFuture<PlayerProfile>> cache=new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String,CompletableFuture<String>> names=new ConcurrentHashMap<>();
    private final ConcurrentHashMap<java.util.UUID,CompletableFuture<TextureProperty>> textures=new ConcurrentHashMap<>();

    public record TextureProperty(String value,String signature){}
    public ProfileResolver(JavaPlugin plugin){this.plugin=plugin;}

    public CompletableFuture<PlayerProfile> resolve(String name){
        String key=name.toLowerCase(java.util.Locale.ROOT);
        return cache.computeIfAbsent(key,ignored->lookupProfile(name)
            .thenApply(identity->{
                if(identity==null)return null;
                // Do not call PlayerProfile#update here. Paper delegates that call
                // to authlib, which performs a second session-server skin lookup
                // and can emit a full stack trace when Mojang is slow or offline.
                PlayerProfile profile=Bukkit.createPlayerProfile(identity.uuid(),identity.name());
                plugin.getLogger().info("Resolved profile "+name+" -> "+profile.getUniqueId()+" (skin lookup deferred)");
                return profile;
            })
            .orTimeout(10,TimeUnit.SECONDS)
            .exceptionally(error->{plugin.getLogger().fine("Mojang profile lookup unavailable for "+name+": "+rootCause(error).getMessage());return null;}));
    }

    private CompletableFuture<ProfileIdentity> lookupProfile(String name){
        String encoded=java.net.URLEncoder.encode(name,StandardCharsets.UTF_8).replace("+","%20");
        HttpRequest request=HttpRequest.newBuilder(URI.create("https://api.mojang.com/users/profiles/minecraft/"+encoded))
            .timeout(java.time.Duration.ofSeconds(5)).GET().build();
        return HTTP.sendAsync(request,HttpResponse.BodyHandlers.ofString())
            .thenApply(response->{
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
                .timeout(java.time.Duration.ofSeconds(5)).GET().build();
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
        return textures.computeIfAbsent(uuid,ignored->fetchTexture(uuid,1).exceptionally(error->null));
    }

    private CompletableFuture<TextureProperty> fetchTexture(UUID uuid,int attempt){
        HttpRequest request=HttpRequest.newBuilder(URI.create("https://sessionserver.mojang.com/session/minecraft/profile/"+uuid+"?unsigned=false"))
            .timeout(java.time.Duration.ofSeconds(5)).GET().build();
        return HTTP.sendAsync(request,HttpResponse.BodyHandlers.ofString()).<CompletableFuture<TextureProperty>>handle((response,error)->{
            if(error!=null){
                if(retryable(rootCause(error))&&attempt<MAX_TEXTURE_ATTEMPTS)return retryTexture(uuid,attempt);
                return CompletableFuture.<TextureProperty>completedFuture(null);
            }
            if(response.statusCode()==200)return CompletableFuture.completedFuture(parseTexture(response.body()));
            if(retryableStatus(response.statusCode())&&attempt<MAX_TEXTURE_ATTEMPTS)return retryTexture(uuid,attempt);
            return CompletableFuture.completedFuture(null);
        }).thenCompose(next->next);
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

    public void clear(){cache.clear();names.clear();textures.clear();}
}
