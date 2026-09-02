package com.example.fauxplayers;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.ProtocolManager;
import com.comphenix.protocol.events.ListenerPriority;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketEvent;
import com.comphenix.protocol.reflect.StructureModifier;
import com.comphenix.protocol.wrappers.PlayerInfoData;
import com.comphenix.protocol.wrappers.WrappedChatComponent;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/**
 * Gives presentation-only entries the same display component that TAB gives
 * real entries. The native latency field remains the source of the spoofed
 * ping; this bridge copies TAB's own visual formatting only.
 */
public final class TabDisplayBridge {
    private static final Pattern PING=Pattern.compile("(?i)(Ping\\s*:\\s*)\\d+(\\s*ms)");
    private final FauxPlayersPlugin plugin;
    private ProtocolManager protocol;
    private PacketAdapter listener;
    private volatile Template template;
    private boolean loggedTemplate;
    private boolean loggedPacket;
    private boolean loggedApi;
    private boolean loggedApiFailure;

    public TabDisplayBridge(FauxPlayersPlugin plugin){this.plugin=plugin;}

    public void enable(){
        if(Bukkit.getPluginManager().getPlugin("ProtocolLib")==null)return;
        try{
            protocol=ProtocolLibrary.getProtocolManager();
            // TAB can modify a packet after NORMAL. MONITOR samples the final
            // display component that is going to the client.
            listener=new PacketAdapter(plugin,ListenerPriority.MONITOR,PacketType.Play.Server.PLAYER_INFO){
                @Override public void onPacketSending(PacketEvent event){captureAndRewrite(event);}
            };
            protocol.addPacketListener(listener);
            plugin.getLogger().info("TAB display formatting bridge enabled; fake entries use TAB's real ping column.");
        }catch(Throwable error){
            plugin.getLogger().warning("Unable to enable TAB display formatting bridge: "+error.getClass().getSimpleName()+": "+error.getMessage());
            protocol=null;listener=null;
        }
    }

    public Object renderComponent(Class<?> componentClass,String fakeName,int fakeLatency){
        Template current=template;
        if(current==null)current=loadFromTabApi();
        if(current==null)return null;
        String json=current.render(fakeName,fakeLatency);
        if(json==null)return null;
        try{
            // Adventure is the API-side representation TAB and modern Paper use.
            // Convert it through CraftBukkit first so this also works when the
            // NMS JSON serializer is private or renamed on a Paper build.
            Component adventure=GsonComponentSerializer.gson().deserialize(json);
            // Paper's supported Adventure bridge is more stable than reflective
            // NMS JSON parsing across 1.21.x mappings.
            Class<?> paperAdventure=Class.forName("io.papermc.paper.adventure.PaperAdventure",false,componentClass.getClassLoader());
            for(Method method:paperAdventure.getDeclaredMethods()){
                if(!Modifier.isStatic(method.getModifiers())||!method.getName().equals("asVanilla")||method.getParameterCount()!=1||!method.getParameterTypes()[0].isInstance(adventure))continue;
                try{method.setAccessible(true);}catch(Throwable ignored){}
                try{
                    Object result=method.invoke(null,adventure);
                    if(result!=null&&componentClass.isInstance(result))return result;
                }catch(Throwable ignored){}
            }
            Class<?> craftChat=Class.forName("org.bukkit.craftbukkit.util.CraftChatMessage",false,componentClass.getClassLoader());
            for(Method method:craftChat.getDeclaredMethods()){
                if(!Modifier.isStatic(method.getModifiers())||!method.getName().equals("fromAdventure")||method.getParameterCount()!=1||!method.getParameterTypes()[0].isInstance(adventure))continue;
                try{method.setAccessible(true);}catch(Throwable ignored){}
                try{
                    Object result=method.invoke(null,adventure);
                    if(result!=null&&componentClass.isInstance(result))return result;
                }catch(Throwable ignored){}
            }
            Class<?> serializer=Class.forName("net.minecraft.network.chat.Component$Serializer",false,componentClass.getClassLoader());
            for(Method method:serializer.getDeclaredMethods()){
                if(!Modifier.isStatic(method.getModifiers())||!method.getName().equals("fromJson")||method.getParameterCount()!=1||method.getParameterTypes()[0]!=String.class)continue;
                try{method.setAccessible(true);}catch(Throwable ignored){}
                try{
                    Object result=method.invoke(null,json);
                    if(result instanceof java.util.Optional<?> optional)result=optional.orElse(null);
                    if(result!=null&&componentClass.isInstance(result))return result;
                }catch(Throwable ignored){}
            }
            // Some Paper mappings expose the JSON reader through CraftChatMessage
            // instead of a public Component.Serializer method.
            for(Method method:craftChat.getDeclaredMethods()){
                if(!Modifier.isStatic(method.getModifiers())||method.getParameterCount()!=1||method.getParameterTypes()[0]!=String.class||!method.getName().equalsIgnoreCase("fromJSON"))continue;
                try{method.setAccessible(true);}catch(Throwable ignored){}
                try{
                    Object result=method.invoke(null,json);
                    if(result instanceof Object[] array)result=array.length==0?null:array[0];
                    if(result!=null&&componentClass.isInstance(result))return result;
                }catch(Throwable ignored){}
            }
        }catch(Throwable error){plugin.getLogger().fine("Unable to create TAB display component: "+error.getClass().getSimpleName());}
        return null;
    }

    public void close(){
        if(protocol!=null&&listener!=null)protocol.removePacketListener(listener);
        protocol=null;listener=null;template=null;
    }

    private void captureAndRewrite(PacketEvent event){
        try{
            StructureModifier<List<PlayerInfoData>> modifier=event.getPacket().getPlayerInfoDataLists();
            if(modifier.size()==0)return;
            List<PlayerInfoData> source=modifier.read(0);
            if(source==null||source.isEmpty())return;
            if(!loggedPacket){
                loggedPacket=true;
                plugin.getLogger().info("TAB bridge observed PLAYER_INFO packets (entries="+source.size()+"); waiting for TAB's real display component.");
            }
            List<PlayerInfoData> rewritten=null;
            for(int index=0;index<source.size();index++){
                PlayerInfoData data=source.get(index);
                if(data==null||data.getProfileId()==null)continue;
                UUID id=data.getProfileId();
                boolean real=Bukkit.getPlayer(id)!=null;
                WrappedChatComponent display=data.getDisplayName();
                if(real&&display!=null){
                    Player player=Bukkit.getPlayer(id);
                    String profileName=data.getProfile()==null?player.getName():data.getProfile().getName();
                    if(profileName!=null&&!profileName.isBlank()){
                        template=new Template(profileName,data.getLatency(),display.getJson());
                        if(!loggedTemplate){
                            plugin.getLogger().info("Captured TAB player-info display format from "+profileName+"; fake entries will use TAB formatting.");
                            loggedTemplate=true;
                        }
                    }
                }else if(!real&&display!=null){
                    String fakeName=data.getProfile()==null?null:data.getProfile().getName();
                    Template current=template;
                    if(fakeName!=null&&current!=null){
                        String json=current.render(fakeName,data.getLatency());
                        if(json!=null&&!json.equals(display.getJson())){
                            if(rewritten==null)rewritten=new ArrayList<>(source);
                            rewritten.set(index,new PlayerInfoData(data.getProfileId(),data.getLatency(),data.isListed(),data.getGameMode(),data.getProfile(),WrappedChatComponent.fromJson(json)));
                        }
                    }
                }
            }
            if(rewritten!=null)modifier.write(0,rewritten);
        }catch(Throwable error){
            plugin.getLogger().fine("Unable to capture TAB display format: "+error.getClass().getSimpleName()+": "+error.getMessage());
        }
    }

    /**
     * PacketEvents-based TAB builds can run outside ProtocolLib's modification
     * order. The public TAB API is the second source of truth: it exposes the
     * already-replaced prefix, name and suffix for an online real player.
     */
    private Template loadFromTabApi(){
        try{
            Object tabPlugin=Bukkit.getPluginManager().getPlugin("TAB");
            if(tabPlugin==null)return null;
            Player source=Bukkit.getOnlinePlayers().stream().findFirst().orElse(null);
            if(source==null)return null;
            ClassLoader loader=tabPlugin.getClass().getClassLoader();
            Class<?> apiClass=Class.forName("me.neznamy.tab.api.TABAPI",true,loader);
            Object api=apiClass.getMethod("getInstance").invoke(null);
            Object tabPlayer=invoke(api,"getPlayer",source.getUniqueId());
            if(tabPlayer==null)tabPlayer=invoke(api,"getPlayer",source.getName());
            if(tabPlayer==null)return null;
            Object manager=invoke(api,"getTabListFormatManager");
            if(manager==null)return null;
            String prefix=formatValue(manager,tabPlayer,"getOriginalReplacedPrefix","getOriginalPrefix");
            String name=formatValue(manager,tabPlayer,"getOriginalReplacedName","getOriginalName");
            String suffix=formatValue(manager,tabPlayer,"getOriginalReplacedSuffix","getOriginalSuffix");
            if(prefix==null&&name==null&&suffix==null)return null;
            if(name==null||name.isEmpty())name=source.getName();
            String format=(prefix==null?"":prefix)+name+(suffix==null?"":suffix);
            String json=legacyJson(format);
            if(json==null)return null;
            Template discovered=new Template(source.getName(),source.getPing(),json);
            template=discovered;
            if(!loggedApi){
                loggedApi=true;
                plugin.getLogger().info("Captured TAB format through its API; fake entries will use TAB's native name/suffix layout.");
            }
            return discovered;
        }catch(Throwable error){
            if(!loggedApiFailure){
                loggedApiFailure=true;
                plugin.getLogger().warning("TAB API display format fallback unavailable: "+error.getClass().getSimpleName()+": "+error.getMessage());
            }
            return null;
        }
    }

    private static Object invoke(Object target,String name,Object... arguments)throws Exception{
        for(Method method:target.getClass().getMethods()){
            if(!method.getName().equals(name)||method.getParameterCount()!=arguments.length)continue;
            Class<?>[] types=method.getParameterTypes();
            boolean matches=true;
            for(int i=0;i<types.length;i++)if(arguments[i]!=null&&!wrap(types[i]).isInstance(arguments[i])){matches=false;break;}
            if(matches)return method.invoke(target,arguments);
        }
        throw new NoSuchMethodException(name);
    }

    private static Class<?> wrap(Class<?> type){
        if(!type.isPrimitive())return type;
        if(type==boolean.class)return Boolean.class;if(type==byte.class)return Byte.class;if(type==short.class)return Short.class;
        if(type==int.class)return Integer.class;if(type==long.class)return Long.class;if(type==float.class)return Float.class;
        if(type==double.class)return Double.class;if(type==char.class)return Character.class;return type;
    }

    private String formatValue(Object manager,Object tabPlayer,String preferred,String fallback){
        try{
            String value=stringValue(invoke(manager,preferred,tabPlayer));
            if(value!=null)return value;
        }catch(Throwable ignored){}
        try{return stringValue(invoke(manager,fallback,tabPlayer));}catch(Throwable ignored){return null;}
    }

    private static String stringValue(Object value){return value instanceof String s?s:null;}

    private static String legacyJson(String value){
        try{
            Component component;
            if(value.indexOf('§')>=0)component=LegacyComponentSerializer.legacySection().deserialize(value);
            else if(value.indexOf('&')>=0)component=LegacyComponentSerializer.legacyAmpersand().deserialize(value);
            else component=Component.text(value);
            return GsonComponentSerializer.gson().serialize(component);
        }catch(Throwable ignored){return null;}
    }

    private record Template(String realName,int latency,String json){
        String render(String fakeName,int fakeLatency){
            if(json==null||json.isBlank()||fakeName==null)return null;
            String escapedReal=escape(realName),escapedFake=escape(fakeName);
            String rendered=json.replace(escapedReal,escapedFake);
            Matcher matcher=PING.matcher(rendered);
            StringBuffer out=new StringBuffer();
            while(matcher.find())matcher.appendReplacement(out,Matcher.quoteReplacement(matcher.group(1)+fakeLatency+matcher.group(2)));
            matcher.appendTail(out);
            return replaceSplitPing(out.toString(),fakeLatency);
        }

        private static String escape(String value){
            return value==null?"":value.replace("\\","\\\\").replace("\"","\\\"");
        }

        private static String replaceSplitPing(String json,int latency){
            String lower=json.toLowerCase(Locale.ROOT);
            int from=0;
            while((from=lower.indexOf("ping",from))>=0){
                int colon=lower.indexOf(':',from+4);
                if(colon<0)break;
                int end=Math.min(json.length(),colon+160);
                for(int i=colon+1;i<end;i++){
                    if(!Character.isDigit(json.charAt(i)))continue;
                    int j=i+1;while(j<end&&Character.isDigit(json.charAt(j)))j++;
                    if(lower.substring(j,end).contains("ms"))return json.substring(0,i)+latency+json.substring(j);
                    i=j;
                }
                from=colon+1;
            }
            return json;
        }
    }
}