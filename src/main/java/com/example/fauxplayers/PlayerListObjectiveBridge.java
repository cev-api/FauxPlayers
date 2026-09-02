package com.example.fauxplayers;

import com.example.fauxplayers.core.*; import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.ProtocolManager;
import com.comphenix.protocol.events.ListenerPriority;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketEvent;
import com.comphenix.protocol.events.PacketContainer;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.gson.GsonComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/**
 * Mirrors TAB's player-list scoreboard objective for presentation-only entries.
 *
 * TAB's grey "Ping: Nms" column is the TAB-PlayerList objective, not the
 * PlayerInfo display-name component. A fake player therefore needs a score
 * packet as well as its native PlayerInfo latency.
 */
public final class PlayerListObjectiveBridge {
    private static final String OBJECTIVE="TAB-PlayerList";
    private static final Pattern PING=Pattern.compile("(?i)(Ping\\s*:\\s*)\\d+(\\s*ms)");

    private final FauxPlayersPlugin plugin;
    private ProtocolManager protocol;
    private PacketAdapter listener;
    private Constructor<?> scoreConstructor;
    private Constructor<?> fixedFormatConstructor;
    private Constructor<?> resetConstructor;
    private Method getHandle;
    private Method sendMethod;
    private Class<?> componentClass;
    private volatile ScoreTemplate template;
    private boolean loggedCapture;
    private boolean loggedPacket;
    private boolean loggedWarning;
    private boolean loggedSend;

    public PlayerListObjectiveBridge(FauxPlayersPlugin plugin){this.plugin=plugin;}

    public void enable(){
        if(Bukkit.getPluginManager().getPlugin("TAB")==null)return;
        try{
            initialiseNms();
            if(Bukkit.getPluginManager().getPlugin("ProtocolLib")!=null){
                protocol=ProtocolLibrary.getProtocolManager();
                listener=new PacketAdapter(plugin,ListenerPriority.MONITOR,PacketType.Play.Server.SCOREBOARD_SCORE){
                    @Override public void onPacketSending(PacketEvent event){capture(event);}
                };
                protocol.addPacketListener(listener);
            }
            plugin.getLogger().info("TAB player-list objective bridge enabled; fake entries receive TAB-PlayerList scores.");
        }catch(Throwable error){
            warn("Unable to enable TAB player-list objective bridge: "+error.getClass().getSimpleName()+": "+error.getMessage());
        }
    }

    public void close(){
        if(protocol!=null&&listener!=null)protocol.removePacketListener(listener);
        protocol=null;listener=null;template=null;
    }

    public void add(Player viewer,FauxPlayerEntry entry){sendScore(viewer,entry.name(),entry.latency());}
    public void update(Player viewer,FauxPlayerEntry entry){sendScore(viewer,entry.name(),entry.latency());}

    public void remove(Player viewer,String name){
        if(name==null||name.isBlank()||resetConstructor==null)return;
        try{sendResetPacket(viewer,resetConstructor.newInstance(name,OBJECTIVE));}
        catch(Throwable error){warn("Unable to remove TAB player-list score: "+error.getClass().getSimpleName()+": "+error.getMessage());}
    }

    private void sendScore(Player viewer,String name,int latency){
        if(viewer==null||!viewer.isOnline()||name==null||name.isBlank()||scoreConstructor==null)return;
        try{
            Object component=displayComponent(latency);
            Optional<?> display=Optional.empty();
            Optional<?> numberFormat=Optional.empty();
            if(fixedFormatConstructor!=null)numberFormat=Optional.of(fixedFormatConstructor.newInstance(component));
            else display=Optional.of(component);
            Object packet=scoreConstructor.newInstance(name,OBJECTIVE,latency,display,numberFormat);
            sendScorePacket(viewer,packet);
            if(!loggedSend){
                loggedSend=true;
                plugin.getLogger().info("Sent TAB-PlayerList score for fake "+name+" (ping="+latency+").");
            }
        }catch(Throwable error){warn("Unable to send TAB player-list score: "+error.getClass().getSimpleName()+": "+error.getMessage());}
    }

    private void initialiseNms()throws Exception{
        ClassLoader loader=plugin.getClass().getClassLoader();
        componentClass=Class.forName("net.minecraft.network.chat.Component",false,loader);
        Class<?> score=Class.forName("net.minecraft.network.protocol.game.ClientboundSetScorePacket",false,loader);
        for(Constructor<?> constructor:score.getDeclaredConstructors()){
            Class<?>[] types=constructor.getParameterTypes();
            if(types.length==5&&types[0]==String.class&&types[1]==String.class&&types[2]==int.class&&types[3]==Optional.class&&types[4]==Optional.class){
                constructor.setAccessible(true);scoreConstructor=constructor;break;
            }
        }
        if(scoreConstructor==null)throw new IllegalStateException("ClientboundSetScorePacket constructor not found");
        try{
            Class<?> fixed=Class.forName("net.minecraft.network.chat.numbers.FixedFormat",false,loader);
            for(Constructor<?> constructor:fixed.getDeclaredConstructors())
                if(constructor.getParameterCount()==1&&constructor.getParameterTypes()[0]==componentClass){constructor.setAccessible(true);fixedFormatConstructor=constructor;break;}
        }catch(ClassNotFoundException ignored){}
        if(fixedFormatConstructor==null)plugin.getLogger().fine("Modern score number format unavailable; using legacy display component.");
        Class<?> reset=Class.forName("net.minecraft.network.protocol.game.ClientboundResetScorePacket",false,loader);
        for(Constructor<?> constructor:reset.getDeclaredConstructors()){
            Class<?>[] types=constructor.getParameterTypes();
            if(types.length==2&&types[0]==String.class&&types[1]==String.class){
                constructor.setAccessible(true);resetConstructor=constructor;break;
            }
        }
        Class<?> craft=Class.forName("org.bukkit.craftbukkit.entity.CraftPlayer",false,loader);
        getHandle=craft.getMethod("getHandle");
        Class<?> connection=Class.forName("net.minecraft.server.network.ServerGamePacketListenerImpl",false,loader);
        for(Method method:connection.getMethods())if(method.getName().equals("send")&&method.getParameterCount()==1){sendMethod=method;break;}
        if(sendMethod==null)throw new IllegalStateException("player connection send method not found");
    }

    private Object displayComponent(int latency)throws Exception{
        ScoreTemplate current=template;
        if(current!=null){
            Object parsed=fromJson(current.render(latency));
            if(parsed!=null)return parsed;
        }
        Object component=componentClass.getMethod("literal",String.class).invoke(null,"Ping: "+latency+"ms");
        try{
            Class<?> formatting=Class.forName("net.minecraft.ChatFormatting",false,componentClass.getClassLoader());
            Object gray=Enum.valueOf((Class)formatting,"GRAY");
            for(Method method:component.getClass().getMethods()){
                if(method.getName().equals("withStyle")&&method.getParameterCount()==1&&method.getParameterTypes()[0].isInstance(gray)){
                    Object styled=method.invoke(component,gray);if(styled!=null)return styled;
                }
            }
        }catch(Throwable ignored){}
        return component;
    }

    private Object fromJson(String json){
        if(json==null||json.isBlank())return null;
        try{
            Component adventure=GsonComponentSerializer.gson().deserialize(json);
            Class<?> paper=Class.forName("io.papermc.paper.adventure.PaperAdventure",false,componentClass.getClassLoader());
            for(Method method:paper.getDeclaredMethods()){
                if(!Modifier.isStatic(method.getModifiers())||!method.getName().equals("asVanilla")||method.getParameterCount()!=1||!method.getParameterTypes()[0].isInstance(adventure))continue;
                try{method.setAccessible(true);}catch(Throwable ignored){}
                Object result=method.invoke(null,adventure);
                if(result!=null&&componentClass.isInstance(result))return result;
            }
        }catch(Throwable ignored){}
        return null;
    }

    private void capture(PacketEvent event){
        try{
            Object packet=event.getPacket().getHandle();
            String objective=stringValue(read(packet,"objectiveName","getObjectiveName","objective"));
            if(!OBJECTIVE.equals(objective))return;
            String owner=stringValue(read(packet,"scoreHolderName","getScoreHolderName","owner","getOwner"));
            Integer score=integerValue(read(packet,"score","getScore"));
            if(owner==null||score==null)return;
            if(!loggedPacket){
                loggedPacket=true;
                plugin.getLogger().info("Captured TAB-PlayerList score packets; fake scores will use TAB's real display format.");
            }
            Player real=Bukkit.getPlayerExact(owner);
            if(real==null)return;
            Object display=unwrap(read(packet,"display","getDisplay"));
            if(display==null){
                Object numberFormat=unwrap(read(packet,"numberFormat","getNumberFormat"));
                display=read(numberFormat,"value","getValue");
            }
            String json=componentJson(display);
            if(json!=null&&!json.isBlank()){
                template=new ScoreTemplate(score,json);
                if(!loggedCapture){
                    loggedCapture=true;
                    plugin.getLogger().info("Captured TAB player-list display format from "+owner+"; fake Ping text will use the objective column.");
                }
            }
        }catch(Throwable error){plugin.getLogger().fine("Unable to capture TAB player-list score: "+error.getClass().getSimpleName()+": "+error.getMessage());}
    }

    private String componentJson(Object value){
        if(value==null)return null;
        try{
            Component adventure;
            if(value instanceof Component c)adventure=c;
            else{
                Class<?> paper=Class.forName("io.papermc.paper.adventure.PaperAdventure",false,componentClass.getClassLoader());
                Object converted=null;
                for(Method method:paper.getDeclaredMethods()){
                    if(!Modifier.isStatic(method.getModifiers())||!method.getName().equals("asAdventure")||method.getParameterCount()!=1||!method.getParameterTypes()[0].isInstance(value))continue;
                    try{method.setAccessible(true);}catch(Throwable ignored){}
                    converted=method.invoke(null,value);if(converted!=null)break;
                }
                if(!(converted instanceof Component c))return null;
                adventure=c;
            }
            return GsonComponentSerializer.gson().serialize(adventure);
        }catch(Throwable ignored){return null;}
    }

    private static Object unwrap(Object value){return value instanceof Optional<?> optional?optional.orElse(null):value;}

    private static Object read(Object target,String... names){
        if(target==null)return null;
        for(String name:names){
            for(Method method:target.getClass().getMethods()){
                if(method.getName().equals(name)&&method.getParameterCount()==0){
                    try{return method.invoke(target);}catch(Throwable ignored){}
                }
            }
            Class<?> type=target.getClass();
            while(type!=null){
                try{
                    Field field=type.getDeclaredField(name);
                    field.setAccessible(true);
                    return field.get(target);
                }catch(Throwable ignored){type=type.getSuperclass();}
            }
        }
        return null;
    }

    private static String stringValue(Object value){return value instanceof String s?s:null;}
    private static Integer integerValue(Object value){return value instanceof Integer i?i:null;}

    private void sendScorePacket(Player viewer,Object packet)throws Exception{
        boolean protocolSent=false;
        if(protocol!=null){
            try{
                protocol.sendServerPacket(viewer,new PacketContainer(PacketType.Play.Server.SCOREBOARD_SCORE,packet));
                protocolSent=true;
            }catch(Throwable error){
                plugin.getLogger().fine("ProtocolLib score transport failed; using direct connection: "+error.getClass().getSimpleName()+": "+error.getMessage());
            }
        }
        // ProtocolLib can accept a packet without actually forwarding it when its
        // runtime packet mapping is stale. Direct Paper delivery is idempotent and
        // guarantees the client receives the score on affected versions.
        if(!protocolSent||protocol!=null)send(viewer,packet);
    }

    private void sendResetPacket(Player viewer,Object packet)throws Exception{
        boolean protocolSent=false;
        if(protocol!=null){
            try{
                protocol.sendServerPacket(viewer,new PacketContainer(PacketType.Play.Server.RESET_SCORE,packet));
                protocolSent=true;
            }catch(Throwable error){
                plugin.getLogger().fine("ProtocolLib reset transport failed; using direct connection: "+error.getClass().getSimpleName()+": "+error.getMessage());
            }
        }
        if(!protocolSent||protocol!=null)send(viewer,packet);
    }

    private void send(Player viewer,Object packet)throws Exception{
        Object handle=getHandle.invoke(viewer);
        Field field=null;Class<?> type=handle.getClass();
        while(type!=null&&field==null){
            try{field=type.getDeclaredField("connection");}catch(NoSuchFieldException ignored){type=type.getSuperclass();}
        }
        if(field==null)throw new IllegalStateException("player connection field not found");
        field.setAccessible(true);
        sendMethod.invoke(field.get(handle),packet);
    }

    private void warn(String message){
        if(!loggedWarning){loggedWarning=true;plugin.getLogger().warning(message);}
    }

    private record ScoreTemplate(int realScore,String json){
        String render(int fakeScore){
            Matcher matcher=PING.matcher(json);
            StringBuffer out=new StringBuffer();
            while(matcher.find())matcher.appendReplacement(out,Matcher.quoteReplacement(matcher.group(1)+fakeScore+matcher.group(2)));
            matcher.appendTail(out);
            String result=out.toString();
            if(result.equals(json)&&realScore!=fakeScore)result=json.replace(Integer.toString(realScore),Integer.toString(fakeScore));
            return result;
        }
    }
}
