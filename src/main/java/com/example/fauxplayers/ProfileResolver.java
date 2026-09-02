package com.example.fauxplayers;

import java.net.URI;
import java.net.http.*;
import java.util.concurrent.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.*;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.profile.PlayerProfile;

/** Resolves Mojang UUIDs, canonical name casing, and signed skin data without blocking the server thread. */
public final class ProfileResolver {
    private static final HttpClient HTTP=HttpClient.newBuilder().connectTimeout(java.time.Duration.ofSeconds(5)).build();
    private final JavaPlugin plugin;
    private final ConcurrentHashMap<String,CompletableFuture<PlayerProfile>> cache=new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String,CompletableFuture<String>> names=new ConcurrentHashMap<>();
    private final ConcurrentHashMap<java.util.UUID,CompletableFuture<TextureProperty>> textures=new ConcurrentHashMap<>();

    public record TextureProperty(String value,String signature){}
    public ProfileResolver(JavaPlugin plugin){this.plugin=plugin;}

    public CompletableFuture<PlayerProfile> resolve(String name){
        String key=name.toLowerCase(java.util.Locale.ROOT);
        return cache.computeIfAbsent(key,ignored->Bukkit.createPlayerProfile(null,name).update()
            .thenApply(profile->{plugin.getLogger().info("Resolved profile "+name+" -> "+profile.getUniqueId()+" skin="+(profile.getTextures().getSkin()!=null));return (PlayerProfile)profile;})
            .orTimeout(10,TimeUnit.SECONDS)
            .exceptionally(error->{plugin.getLogger().warning("Unable to resolve Mojang profile for "+name+": "+error.getMessage());return null;}));
    }

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
        return textures.computeIfAbsent(uuid,ignored->{
            HttpRequest request=HttpRequest.newBuilder(URI.create("https://sessionserver.mojang.com/session/minecraft/profile/"+uuid+"?unsigned=false"))
                .timeout(java.time.Duration.ofSeconds(5)).GET().build();
            return HTTP.sendAsync(request,HttpResponse.BodyHandlers.ofString())
                .thenApply(response->{
                    Matcher v=Pattern.compile("\\\"value\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"").matcher(response.body());
                    Matcher sig=Pattern.compile("\\\"signature\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"").matcher(response.body());
                    return v.find()?new TextureProperty(v.group(1),sig.find()?sig.group(1):null):null;
                })
                .exceptionally(error->null);
        });
    }

    public void clear(){cache.clear();names.clear();textures.clear();}
}