package com.example.fauxplayers;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.ProtocolManager;
import com.comphenix.protocol.events.ListenerPriority;
import com.comphenix.protocol.events.PacketAdapter;
import com.comphenix.protocol.events.PacketContainer;
import com.comphenix.protocol.events.PacketEvent;
import com.comphenix.protocol.reflect.StructureModifier;
import com.comphenix.protocol.wrappers.WrappedChatComponent;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

/**
 * Keeps server-side tab plugins' visible "Online: N" header value aligned with
 * the presentation-only entries sent by FauxPlayers.
 */
public final class TabHeaderManager {
    private static final Pattern TEXT_FIELD=Pattern.compile("(\"text\"\\s*:\\s*\")((?:\\\\.|[^\"\\\\])*)(\")");
    private static final Pattern ONLINE_INLINE=Pattern.compile("(?i)online\\s*:\\s*\\d+");
    private static final Pattern ONLINE_LABEL=Pattern.compile("(?i).*online\\s*:\\s*$");
    private static final Pattern NUMBER=Pattern.compile("\\d+");

    private final FauxPlayersPlugin plugin;
    private final Map<UUID,HeaderFooter> originals=new HashMap<>();
    private ProtocolManager protocol;
    private PacketAdapter listener;
    private int refreshTask=-1;
    private int lastCount=-1;

    public TabHeaderManager(FauxPlayersPlugin plugin){this.plugin=plugin;}

    public void enable(){
        if(Bukkit.getPluginManager().getPlugin("ProtocolLib")==null){
            plugin.getLogger().info("ProtocolLib not found; tab header online-count rewriting is disabled.");
            return;
        }
        protocol=ProtocolLibrary.getProtocolManager();
        listener=new PacketAdapter(plugin,ListenerPriority.NORMAL,PacketType.Play.Server.PLAYER_LIST_HEADER_FOOTER){
            @Override public void onPacketSending(PacketEvent event){rewriteOutgoing(event);}
        };
        protocol.addPacketListener(listener);
        refreshTask=Bukkit.getScheduler().runTaskTimer(plugin,this::refresh,20L,20L).getTaskId();
    }

    public void close(){
        if(protocol!=null&&listener!=null)protocol.removePacketListener(listener);
        if(refreshTask>=0)Bukkit.getScheduler().cancelTask(refreshTask);
        originals.clear();
        protocol=null;
        listener=null;
        refreshTask=-1;
    }

    private void rewriteOutgoing(PacketEvent event){
        try{
            StructureModifier<WrappedChatComponent> components=event.getPacket().getChatComponents();
            if(components.size()==0)return;
            String header=json(components,0);
            String footer=components.size()>1?json(components,1):null;
            originals.put(event.getPlayer().getUniqueId(),new HeaderFooter(header,footer));
            rewritePacket(event.getPacket(),displayedCount());
        }catch(Throwable error){
            plugin.getLogger().fine("Unable to rewrite tab header online count: "+error.getClass().getSimpleName());
        }
    }

    private void refresh(){
        int count=displayedCount();
        if(count==lastCount)return;
        lastCount=count;
        if(protocol==null)return;
        for(Player player:Bukkit.getOnlinePlayers()){
            HeaderFooter source=originals.get(player.getUniqueId());
            if(source==null)continue;
            try{
                PacketContainer packet=new PacketContainer(PacketType.Play.Server.PLAYER_LIST_HEADER_FOOTER);
                StructureModifier<WrappedChatComponent> components=packet.getChatComponents();
                if(source.header()!=null)components.write(0,WrappedChatComponent.fromJson(rewriteJson(source.header(),count)));
                if(source.footer()!=null&&components.size()>1)components.write(1,WrappedChatComponent.fromJson(rewriteJson(source.footer(),count)));
                protocol.sendServerPacket(player,packet);
            }catch(Throwable error){
                plugin.getLogger().fine("Unable to refresh tab header online count: "+error.getClass().getSimpleName());
            }
        }
    }

    private void rewritePacket(PacketContainer packet,int count){
        StructureModifier<WrappedChatComponent> components=packet.getChatComponents();
        for(int i=0;i<components.size();i++){
            WrappedChatComponent component=components.read(i);
            if(component==null)continue;
            String original=component.getJson();
            String rewritten=rewriteJson(original,count);
            if(!Objects.equals(original,rewritten))components.write(i,WrappedChatComponent.fromJson(rewritten));
        }
    }

    private int displayedCount(){
        if(plugin.config()==null||!plugin.config().enabled||!plugin.config().tabEnabled)return Bukkit.getOnlinePlayers().size();
        return Bukkit.getOnlinePlayers().size()+plugin.tabEntries().size();
    }

    private static String json(StructureModifier<WrappedChatComponent> components,int index){
        WrappedChatComponent component=components.read(index);
        return component==null?null:component.getJson();
    }

    private static String rewriteJson(String json,int count){
        if(json==null||json.isBlank())return json;
        Matcher fields=TEXT_FIELD.matcher(json);
        StringBuffer result=new StringBuffer();
        boolean pendingOnline=false;
        boolean changed=false;
        while(fields.find()){
            String raw=fields.group(2);
            String visible=decodeJsonText(raw);
            String replacement=visible;
            Matcher inline=ONLINE_INLINE.matcher(visible);
            if(inline.find()){
                replacement=visible.substring(0,inline.start())+"Online: "+count+visible.substring(inline.end());
            }else if(pendingOnline&&NUMBER.matcher(visible).matches()){
                replacement=Integer.toString(count);
                pendingOnline=false;
            }else if(ONLINE_LABEL.matcher(visible).matches()){
                pendingOnline=true;
            }else if(!visible.isBlank()){
                pendingOnline=false;
            }
            String encoded=replacement.equals(visible)?raw:encodeJsonText(replacement);
            if(!encoded.equals(raw))changed=true;
            fields.appendReplacement(result,Matcher.quoteReplacement(fields.group(1)+encoded+fields.group(3)));
        }
        fields.appendTail(result);
        return changed?result.toString():json;
    }

    private static String decodeJsonText(String value){
        StringBuilder out=new StringBuilder(value.length());
        for(int i=0;i<value.length();i++){
            char c=value.charAt(i);
            if(c!='\\'||i+1>=value.length()){out.append(c);continue;}
            char escaped=value.charAt(++i);
            switch(escaped){
                case '"','\\','/' -> out.append(escaped);
                case 'b' -> out.append('\b');
                case 'f' -> out.append('\f');
                case 'n' -> out.append('\n');
                case 'r' -> out.append('\r');
                case 't' -> out.append('\t');
                case 'u' -> {
                    if(i+4<value.length()){
                        try{out.append((char)Integer.parseInt(value.substring(i+1,i+5),16));i+=4;}
                        catch(NumberFormatException error){out.append('u');}
                    }else out.append('u');
                }
                default -> out.append(escaped);
            }
        }
        return out.toString();
    }

    private static String encodeJsonText(String value){
        StringBuilder out=new StringBuilder(value.length()+8);
        for(char c:value.toCharArray()){
            switch(c){
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if(c<0x20){
                        out.append("\\u");
                        String hex=Integer.toHexString(c);
                        out.append("0000",0,4-hex.length()).append(hex);
                    }else out.append(c);
                }
            }
        }
        return out.toString();
    }

    private record HeaderFooter(String header,String footer){}
}