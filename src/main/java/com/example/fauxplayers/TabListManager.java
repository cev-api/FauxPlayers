package com.example.fauxplayers;

import com.example.fauxplayers.core.*; import java.lang.reflect.*;
import java.util.*;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.profile.PlayerProfile;

/** Sends presentation-only player-info packets and real Mojang skin properties. */
public final class TabListManager {
    private final JavaPlugin plugin;
    private final ProfileResolver profiles;
    private final Set<UUID> sent = new HashSet<>();
    private final Map<UUID, UUID> packetIds = new HashMap<>();
    private final Map<UUID, ResolvedState> states = new HashMap<>();
    private final Map<UUID, Long> generations = new HashMap<>();
    private PluginConfig currentConfig;
    private final Map<UUID, Long> nextPingUpdateMillis = new HashMap<>();
    private final NmsBackend backend;

    public TabListManager(JavaPlugin plugin) {
        this.plugin=plugin; this.profiles=new ProfileResolver(plugin); this.backend=NmsBackend.create(plugin);
        if (backend==null) plugin.getLogger().warning("No compatible player-info packet backend was found; fake TAB entries are disabled.");
    }
    public boolean available(){return backend!=null;}
    public java.util.concurrent.CompletableFuture<String> canonicalName(String name){return profiles.canonicalName(name);}

    public void sync(PluginConfig config, Collection<FauxPlayerEntry> entries) {
        currentConfig=config;
        if(backend==null||!config.tabEnabled)return;
        Set<UUID> wanted=new HashSet<>();
        for(FauxPlayerEntry e:entries)wanted.add(e.uuid());
        for(UUID id:new ArrayList<>(sent))if(!wanted.contains(id))remove(id);
        long now=System.currentTimeMillis();
        for(FauxPlayerEntry e:entries){
            if(sent.add(e.uuid())){
                long generation=generations.merge(e.uuid(),1L,Long::sum);
                scheduleNextPing(e.uuid());
                for(Player p:Bukkit.getOnlinePlayers())dispatch(effective(e),p,generation);
            }else if(config.randomPing&&states.containsKey(e.uuid())){
                Long next=nextPingUpdateMillis.get(e.uuid());
                if(next==null||now>=next)updatePing(e.uuid(),e);
            }
        }
    }    public void sendTo(Player viewer, Collection<FauxPlayerEntry> entries){
        if(backend==null)return;
        for(FauxPlayerEntry e:entries){
            ResolvedState state=states.get(e.uuid());
            if(state!=null){
                try{backend.add(viewer,state.entry,state.profile,state.texture);if(plugin instanceof FauxPlayersPlugin faux&&faux.playerListObjective()!=null)faux.playerListObjective().add(viewer,state.entry);}
                catch(Exception ex){plugin.getLogger().log(java.util.logging.Level.WARNING,"Unable to send fake TAB entry",ex);}
            }else{
                long generation=generations.getOrDefault(e.uuid(),0L);
                if(sent.add(e.uuid())){
                    generation=generations.merge(e.uuid(),1L,Long::sum);
                    scheduleNextPing(e.uuid());
                }
                dispatch(effective(e),viewer,generation);
            }
        }
    }    public void reassertPings(){
        if(backend==null||currentConfig==null||!currentConfig.tabEnabled||states.isEmpty())return;
        for(Player viewer:Bukkit.getOnlinePlayers())for(ResolvedState state:new ArrayList<>(states.values()))try{
            backend.updateLatency(viewer,state.entry,state.profile,state.texture);if(plugin instanceof FauxPlayersPlugin faux&&faux.playerListObjective()!=null)faux.playerListObjective().update(viewer,state.entry);
        }catch(Exception ex){plugin.getLogger().fine("Unable to reassert fake TAB ping: "+ex.getClass().getSimpleName());}
    }    private FauxPlayerEntry effective(FauxPlayerEntry e){
        if(currentConfig==null||!currentConfig.randomPing)return e;
        int min=Math.max(0,currentConfig.pingMinimum),max=Math.max(min,currentConfig.pingMaximum);
        if(max==min)return new FauxPlayerEntry(e.name(),e.uuid(),e.displayName(),min,e.gameMode(),e.remote());
        double mean=(min+max)/2.0;
        double deviation=Math.max(0,currentConfig.pingStandardDeviation);
        int ping=(int)Math.round(mean+java.util.concurrent.ThreadLocalRandom.current().nextGaussian()*deviation);
        ping=Math.max(min,Math.min(max,ping));
        return new FauxPlayerEntry(e.name(),e.uuid(),e.displayName(),ping,e.gameMode(),e.remote());
    }
    private void scheduleNextPing(UUID logicalId){
        if(currentConfig==null||!currentConfig.randomPing){nextPingUpdateMillis.remove(logicalId);return;}
        long base=Math.max(1,currentConfig.pingRefreshSeconds)*1000L;
        long minimum=Math.max(1000L,base/2L);
        long maximum=Math.max(minimum,base+base/2L);
        long delay=minimum==maximum?minimum:java.util.concurrent.ThreadLocalRandom.current().nextLong(minimum,maximum+1L);
        nextPingUpdateMillis.put(logicalId,System.currentTimeMillis()+delay);
    }
    private void updatePing(UUID logical,FauxPlayerEntry original){
        ResolvedState old=states.get(logical);if(old==null)return;
        FauxPlayerEntry next=effective(old.entry);
        scheduleNextPing(logical);
        if(next.latency()==old.entry.latency())return;
        states.put(logical,new ResolvedState(next,old.profile,old.texture));
        for(Player p:Bukkit.getOnlinePlayers())try{backend.updateLatency(p,next,old.profile,old.texture);if(plugin instanceof FauxPlayersPlugin faux&&faux.playerListObjective()!=null)faux.playerListObjective().update(p,next);}
        catch(Exception ex){plugin.getLogger().log(java.util.logging.Level.WARNING,"Unable to update fake TAB ping",ex);}
    }
    private void dispatch(FauxPlayerEntry entry, Player viewer, long generation) {
        profiles.resolve(entry.name()).thenCompose(profile -> profiles.canonicalName(profile,entry.name()).thenCompose(canonical -> {
            if(profile==null)return java.util.concurrent.CompletableFuture.completedFuture(new Object[]{null,canonical,null});
            return profiles.resolveTexture(profile.getUniqueId()).thenApply(texture -> new Object[]{profile,canonical,texture});
        })).thenAccept(result -> Bukkit.getScheduler().runTask(plugin, () -> {
            if(!viewer.isOnline()||!sent.contains(entry.uuid())||generations.getOrDefault(entry.uuid(),0L)!=generation)return;
            try {
                PlayerProfile profile=(PlayerProfile)result[0];
                String canonical=(String)result[1];
                ProfileResolver.TextureProperty texture=(ProfileResolver.TextureProperty)result[2];
                FauxPlayerEntry resolved=profile==null?entry:new FauxPlayerEntry(canonical,profile.getUniqueId(),entry.displayName(),entry.latency(),entry.gameMode(),entry.remote());
                packetIds.put(entry.uuid(),resolved.uuid());
                states.put(entry.uuid(),new ResolvedState(resolved,profile,texture));
                backend.add(viewer,resolved,profile,texture);if(plugin instanceof FauxPlayersPlugin faux&&faux.playerListObjective()!=null)faux.playerListObjective().add(viewer,resolved);
            } catch(Exception ex){plugin.getLogger().log(java.util.logging.Level.WARNING,"Unable to send fake TAB entry",ex);}
        }));
    }    private void remove(UUID logicalId){
        generations.merge(logicalId,1L,Long::sum);
        UUID packetId=packetIds.getOrDefault(logicalId,logicalId);
        ResolvedState old=states.get(logicalId);
        for(Player p:Bukkit.getOnlinePlayers())try{
            backend.remove(p,packetId);
            if(old!=null&&plugin instanceof FauxPlayersPlugin faux&&faux.playerListObjective()!=null)faux.playerListObjective().remove(p,old.entry.name());
        }catch(Exception ex){plugin.getLogger().log(java.util.logging.Level.WARNING,"Unable to remove fake TAB entry",ex);}
        packetIds.remove(logicalId);states.remove(logicalId);sent.remove(logicalId);nextPingUpdateMillis.remove(logicalId);
    }
    public void clear(){
        for(UUID id:new ArrayList<>(sent))remove(id);
        sent.clear();packetIds.clear();states.clear();generations.clear();nextPingUpdateMillis.clear();profiles.clear();
    }
    private record ResolvedState(FauxPlayerEntry entry, PlayerProfile profile, ProfileResolver.TextureProperty texture) {}

    private static final class NmsBackend {
        private final JavaPlugin plugin;
        private final Constructor<?> packetConstructor,entryConstructor,removeConstructor;
        private final Method sendMethod,getHandle;
        private final Class<?> actionClass,profileClass,gameTypeClass,componentClass;
        private final Object[] actions;
        private NmsBackend(JavaPlugin owner,Constructor<?> pc,Constructor<?> ec,Constructor<?> rc,Method sm,Method gh,Class<?> ac,Class<?> gp,Class<?> gt,Class<?> cc,Object[] a){plugin=owner;packetConstructor=pc;entryConstructor=ec;removeConstructor=rc;sendMethod=sm;getHandle=gh;actionClass=ac;profileClass=gp;gameTypeClass=gt;componentClass=cc;actions=a;}
        static NmsBackend create(JavaPlugin plugin){try{
            ClassLoader l=plugin.getClass().getClassLoader();Class<?> packet=Class.forName("net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket",false,l);Class<?> entry=Class.forName("net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket$Entry",false,l);Class<?> action=Class.forName("net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket$Action",false,l);Class<?> rem=Class.forName("net.minecraft.network.protocol.game.ClientboundPlayerInfoRemovePacket",false,l);Class<?> gp=Class.forName("com.mojang.authlib.GameProfile",false,l);Class<?> gt=Class.forName("net.minecraft.world.level.GameType",false,l);Class<?> cc=Class.forName("net.minecraft.network.chat.Component",false,l);Constructor<?> pc=null;for(Constructor<?> c:packet.getDeclaredConstructors()){Class<?>[] p=c.getParameterTypes();if(p.length==2&&p[0].isAssignableFrom(EnumSet.class)&&Collection.class.isAssignableFrom(p[1])){pc=c;break;}}if(pc==null)throw new IllegalStateException("player-info constructor not found");Constructor<?> ec=Arrays.stream(entry.getDeclaredConstructors()).max(Comparator.comparingInt(Constructor::getParameterCount)).orElseThrow();Constructor<?> rc=Arrays.stream(rem.getDeclaredConstructors()).filter(c->c.getParameterCount()==1).filter(c->{Class<?> t=c.getParameterTypes()[0];return t.isAssignableFrom(List.class)||Collection.class.isAssignableFrom(t)||Iterable.class.isAssignableFrom(t);}).findFirst().orElseThrow();rc.setAccessible(true);Class<?> craft=Class.forName("org.bukkit.craftbukkit.entity.CraftPlayer",false,l);Method gh=craft.getMethod("getHandle");Method sm=Arrays.stream(Class.forName("net.minecraft.server.network.ServerGamePacketListenerImpl",false,l).getMethods()).filter(m->m.getName().equals("send")&&m.getParameterCount()==1).findFirst().orElseThrow();List<Object> aa=new ArrayList<>();for(String n:List.of("ADD_PLAYER","UPDATE_LISTED","UPDATE_LATENCY","UPDATE_GAME_MODE","UPDATE_DISPLAY_NAME"))aa.add(Enum.valueOf((Class)action,n));return new NmsBackend(plugin,pc,ec,rc,sm,gh,action,gp,gt,cc,aa.toArray());
        }catch(Throwable t){plugin.getLogger().warning("NMS TAB backend unavailable: "+t.getClass().getSimpleName()+": "+t.getMessage());return null;}}
        void add(Player viewer,FauxPlayerEntry v,PlayerProfile paperProfile,ProfileResolver.TextureProperty texture)throws Exception{send(viewer,buildPacket(v,paperProfile,texture,actions));}
        void updateLatency(Player viewer,FauxPlayerEntry v,PlayerProfile paperProfile,ProfileResolver.TextureProperty texture)throws Exception{Object latency=Enum.valueOf((Class)actionClass,"UPDATE_LATENCY");Object display=Enum.valueOf((Class)actionClass,"UPDATE_DISPLAY_NAME");send(viewer,buildPacket(v,paperProfile,texture,new Object[]{latency,display}));}
        private Object createGameProfile(FauxPlayerEntry value, PlayerProfile paperProfile, ProfileResolver.TextureProperty texture) throws Exception {
            Object base=profileClass.getConstructor(UUID.class,String.class).newInstance(value.uuid(),value.name());
            java.net.URL skin=paperProfile==null?null:paperProfile.getTextures().getSkin();
            if(skin==null && texture==null) return base;
            Class<?> propertyClass=Class.forName("com.mojang.authlib.properties.Property");
            Object property=texture!=null
                    ? (texture.signature()==null
                        ? propertyClass.getConstructor(String.class,String.class).newInstance("textures",texture.value())
                        : propertyClass.getConstructor(String.class,String.class,String.class).newInstance("textures",texture.value(),texture.signature()))
                    : propertyClass.getConstructor(String.class,String.class).newInstance("textures",Base64.getEncoder().encodeToString(("{"+(char)34+"textures"+(char)34+":{"+(char)34+"SKIN"+(char)34+":{"+(char)34+"url"+(char)34+":"+(char)34+skin+(char)34+"}}}").getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            Class<?> immutable=Class.forName("com.google.common.collect.ImmutableMultimap");
            Object builder=immutable.getMethod("builder").invoke(null);
            builder.getClass().getMethod("put",Object.class,Object.class).invoke(builder,"textures",property);
            Object map=builder.getClass().getMethod("build").invoke(builder);
            for(Method m:profileClass.getMethods())
                if(m.getName().equals("withProperties") && m.getParameterCount()==1 && m.getParameterTypes()[0].isInstance(map)) return m.invoke(base,map);
            for(Constructor<?> c:profileClass.getDeclaredConstructors()) {
                Class<?>[] t=c.getParameterTypes();
                if(t.length==3 && t[0]==UUID.class && t[1]==String.class && t[2].isInstance(map)) return c.newInstance(value.uuid(),value.name(),map);
                if(t.length==3 && t[0]==UUID.class && t[1]==String.class) {
                    try {
                        Class<?> propertyMapClass=Class.forName("com.mojang.authlib.properties.PropertyMap");
                        for(Constructor<?> pc:propertyMapClass.getDeclaredConstructors())
                            if(pc.getParameterCount()==1 && pc.getParameterTypes()[0].isInstance(map)) {
                                pc.setAccessible(true); Object propertyMap=pc.newInstance(map);
                                if(t[2].isInstance(propertyMap)) return c.newInstance(value.uuid(),value.name(),propertyMap);
                            }
                    } catch(Throwable ignored) { }
                }
            }
            return base;
        }
        private Object createDisplayComponent(FauxPlayerEntry value)throws Exception{
            Object component=null;
            if(plugin instanceof FauxPlayersPlugin faux&&faux.displayBridge()!=null)
                component=faux.displayBridge().renderComponent(componentClass,value.displayName(),value.latency());
            if(component==null)component=componentClass.getMethod("literal",String.class).invoke(null,value.displayName());
            return component;
        }        private Object buildPacket(FauxPlayerEntry v,PlayerProfile paperProfile,ProfileResolver.TextureProperty texture,Object[] selected)throws Exception{
            Object gp=createGameProfile(v,paperProfile,texture);
            Object comp=createDisplayComponent(v);
            Object gt=Enum.valueOf((Class)gameTypeClass,v.gameMode());
            Object[] args=new Object[entryConstructor.getParameterCount()];
            for(int i=0;i<args.length;i++){
                Class<?> t=entryConstructor.getParameterTypes()[i];
                if(t==UUID.class)args[i]=v.uuid();
                else if(t==profileClass)args[i]=gp;
                else if(t==boolean.class)args[i]=true;
                else if(t==int.class)args[i]=v.latency();
                else if(t==gameTypeClass)args[i]=gt;
                else if(t==componentClass)args[i]=comp;
                else args[i]=null;
            }
            Object e=entryConstructor.newInstance(args);
            EnumSet set=EnumSet.noneOf((Class)actionClass);for(Object a:selected)set.add((Enum)a);
            return packetConstructor.newInstance(set,List.of(e));
        }        void remove(Player viewer,UUID id)throws Exception{send(viewer,removeConstructor.newInstance(List.of(id)));}
        private void send(Player viewer,Object packet)throws Exception{Object handle=getHandle.invoke(viewer);Field f=null;Class<?> c=handle.getClass();while(c!=null&&f==null){try{f=c.getDeclaredField("connection");}catch(NoSuchFieldException ignored){c=c.getSuperclass();}}if(f==null)throw new IllegalStateException("player connection field not found");f.setAccessible(true);sendMethod.invoke(f.get(handle),packet);}
    }
}
