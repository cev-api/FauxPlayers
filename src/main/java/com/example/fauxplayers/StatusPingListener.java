package com.example.fauxplayers;

import com.example.fauxplayers.core.*; import com.destroystokyo.paper.event.server.PaperServerListPingEvent; import java.util.*; import org.bukkit.Bukkit; import org.bukkit.event.*; import org.bukkit.event.server.ServerListPingEvent; import org.bukkit.plugin.java.JavaPlugin;

public final class StatusPingListener implements Listener {
 private final FauxPlayersPlugin plugin; StatusPingListener(FauxPlayersPlugin p){plugin=p;}
 @EventHandler public void ping(PaperServerListPingEvent e){PluginConfig c=plugin.config();if(!c.enabled||!c.statusEnabled)return;PlayerSnapshot r=plugin.relay().snapshot();List<FauxPlayerEntry> f=plugin.statusEntries();int real=Bukkit.getOnlinePlayers().size();e.setNumPlayers(PresentationMath.statusCount(c,r,real,f.size()));if(c.useMax&&r.reportedMax()>0)e.setMaxPlayers(r.reportedMax());else if(c.fixedMax>0)e.setMaxPlayers(c.fixedMax);var list=e.getListedPlayers();list.clear();List<FauxPlayerEntry> realEntries=Bukkit.getOnlinePlayers().stream().map(p->new FauxPlayerEntry(p.getName(),p.getUniqueId(),p.getName(),0,"SURVIVAL",false)).toList();for(var x:PresentationMath.sample(c,realEntries,f))list.add(new PaperServerListPingEvent.ListedPlayerInfo(x.name(),x.uuid()));}
}
