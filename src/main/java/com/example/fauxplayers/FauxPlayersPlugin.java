package com.example.fauxplayers;

import com.example.fauxplayers.core.*; import java.io.*; import java.util.*; import net.kyori.adventure.text.Component; import net.kyori.adventure.text.format.NamedTextColor; import org.bukkit.Bukkit; import org.bukkit.event.*; import org.bukkit.scheduler.BukkitTask; import org.bukkit.event.player.PlayerJoinEvent; import org.bukkit.event.player.PlayerQuitEvent; import org.bukkit.event.player.PlayerCommandSendEvent; import org.bukkit.plugin.java.JavaPlugin; import org.bukkit.configuration.file.FileConfiguration; import org.bukkit.configuration.file.YamlConfiguration;

public final class FauxPlayersPlugin extends JavaPlugin {
 private PluginConfig config; private RelayManager relay; private TabListManager tab; private TabHeaderManager header; private TabDisplayBridge displayBridge; private PlayerListObjectiveBridge playerListObjective; private TabPlaceholderIntegration tabPlaceholders; private BukkitTask syncTask; private boolean observedJoinMessage, observedLeaveMessage; private String joinMessageTemplate, leaveMessageTemplate; private File messageCacheFile; private FileConfiguration messageCache;
 @Override public void onEnable(){saveDefaultConfig();loadMessageCache();if(Bukkit.getPluginManager().getPlugin("ProtocolLib")!=null){displayBridge=new TabDisplayBridge(this);displayBridge.enable();}if(Bukkit.getPluginManager().getPlugin("TAB")!=null){playerListObjective=new PlayerListObjectiveBridge(this);playerListObjective.enable();}reloadLocal();if(Bukkit.getPluginManager().getPlugin("ProtocolLib")!=null){header=new TabHeaderManager(this);header.enable();}if(Bukkit.getPluginManager().getPlugin("TAB")!=null){tabPlaceholders=new TabPlaceholderIntegration(this);tabPlaceholders.enable();}getCommand("fauxplayers").setExecutor(new FauxPlayersCommand(this));getServer().getPluginManager().registerEvents(new StatusPingListener(this),this);getServer().getPluginManager().registerEvents(new Listener(){
  @EventHandler(priority=EventPriority.MONITOR) public void join(PlayerJoinEvent e){observeServerMessage(e.getJoinMessage(),e.getPlayer().getName(),true);tab.sendTo(e.getPlayer(),tabEntries());}
  @EventHandler(priority=EventPriority.HIGHEST) public void commandSend(PlayerCommandSendEvent e){if(!e.getPlayer().isOp())e.getCommands().removeIf(name->name.equalsIgnoreCase("fauxplayers")||name.equalsIgnoreCase("fp")||name.equalsIgnoreCase("fakeplayers"));}
  @EventHandler(priority=EventPriority.MONITOR) public void quit(PlayerQuitEvent e){observeServerMessage(e.getQuitMessage(),e.getPlayer().getName(),false);}
 },this);getLogger().info("FauxPlayers enabled; presentation-only entries are never server players.");}
 private void reloadLocal(){
  config=PluginConfig.load(new PluginConfig.Source(){public Object get(String path){return getConfig().get(path);}});
  if(relay==null)relay=new RelayManager(new RelayManager.Host(){public void fine(String message){getLogger().fine(message);}public void warning(String message){getLogger().warning(message);}public void remoteChanged(PlayerSnapshot oldSnapshot,PlayerSnapshot newSnapshot){getServer().getScheduler().runTask(FauxPlayersPlugin.this,()->FauxPlayersPlugin.this.remoteChanged(oldSnapshot,newSnapshot));}});else relay.stop();
  if(tab==null)tab=new TabListManager(this);
  if(config.enabled)relay.start(config);
  if(!config.enabled||!config.tabEnabled)tab.clear();else {tab.sync(config,tabEntries());tab.reassertPings();}
  if(syncTask==null)syncTask=getServer().getScheduler().runTaskTimer(this,()->{
   if(!config.enabled||!config.tabEnabled)tab.clear();else {tab.sync(config,tabEntries());tab.reassertPings();}
  },1L,5L);
 } public void reloadPlugin(){reloadConfig();reloadLocal();}
 public PluginConfig config(){return config;} public RelayManager relay(){return relay;} public TabListManager tab(){return tab;} public TabDisplayBridge displayBridge(){return displayBridge;} public PlayerListObjectiveBridge playerListObjective(){return playerListObjective;}
 public int displayedOnlineCount(){return Bukkit.getOnlinePlayers().size()+((config!=null&&config.enabled&&config.tabEnabled)?tabEntries().size():0);}
 public List<FauxPlayerEntry> remoteEntries(){return config!=null&&config.relayEnabled&&relay!=null?relay.snapshot().players():List.of();}
 public List<FauxPlayerEntry> statusEntries(){return PresentationMath.statusEntries(config,relay.snapshot());}
 public List<FauxPlayerEntry> mergeEntries(Collection<FauxPlayerEntry> local, Collection<FauxPlayerEntry> remote){return PresentationMath.merge(config,local,remote);}
 public List<FauxPlayerEntry> tabEntries(){return PresentationMath.tabEntries(config,relay.snapshot());}
 public void refresh(){relay.refreshAsync(config);}
 public boolean isFauxName(String name){return tabEntries().stream().anyMatch(e->e.name().equalsIgnoreCase(name));}
 public void sendFauxChat(String name,String message){FauxPlayerEntry match=tabEntries().stream().filter(e->e.name().equalsIgnoreCase(name)).findFirst().orElse(null);if(match==null)return;tab.canonicalName(match.name()).thenAccept(canonical->getServer().getScheduler().runTask(this,()->Bukkit.broadcastMessage("§7<"+canonical+"> §f"+message)));}
 private void loadMessageCache(){
  if(!getDataFolder().exists())getDataFolder().mkdirs();
  messageCacheFile=new File(getDataFolder(),"message-format.yml");
  messageCache=YamlConfiguration.loadConfiguration(messageCacheFile);
  observedJoinMessage=messageCache.getBoolean("join.observed",false);
  observedLeaveMessage=messageCache.getBoolean("leave.observed",false);
  joinMessageTemplate=messageCache.getString("join.template");
  leaveMessageTemplate=messageCache.getString("leave.template");
 }
 private void saveMessageCache(){
  if(messageCache==null||messageCacheFile==null)return;
  messageCache.set("join.observed",observedJoinMessage);
  messageCache.set("join.template",joinMessageTemplate);
  messageCache.set("leave.observed",observedLeaveMessage);
  messageCache.set("leave.template",leaveMessageTemplate);
  try{messageCache.save(messageCacheFile);}catch(IOException ex){getLogger().log(java.util.logging.Level.WARNING,"Unable to save message-format.yml",ex);}
 }
 public void observeServerMessage(String message,String realName,boolean join){
  String template=message==null?null:message.replace(realName,"{name}");
  if(join){observedJoinMessage=true;joinMessageTemplate=template;}else{observedLeaveMessage=true;leaveMessageTemplate=template;}
  saveMessageCache();
 }
 public void fakeMessage(String name, boolean join){
  if(!config.messageEnabled)return;
  boolean observed=join?observedJoinMessage:observedLeaveMessage;
  String captured=join?joinMessageTemplate:leaveMessageTemplate;
  if(observed){
   if(captured==null||captured.isEmpty())return;
   Bukkit.broadcastMessage(captured.replace("{name}",name));
   return;
  }
  String key=join?"multiplayer.player.joined":"multiplayer.player.left";
  Component vanilla=Component.translatable(key,Component.text(name)).color(NamedTextColor.YELLOW);
  Bukkit.broadcast(vanilla);
 } public void remoteChanged(PlayerSnapshot oldSnapshot, PlayerSnapshot newSnapshot){if(oldSnapshot.refreshedAt()==null)return;Set<String> oldNames=new HashSet<>();for(FauxPlayerEntry e:oldSnapshot.players())oldNames.add(e.name().toLowerCase(Locale.ROOT));Set<String> newNames=new HashSet<>();for(FauxPlayerEntry e:newSnapshot.players())newNames.add(e.name().toLowerCase(Locale.ROOT));for(FauxPlayerEntry e:newSnapshot.players())if(!oldNames.contains(e.name().toLowerCase(Locale.ROOT)))fakeMessage(e.name(),true);for(FauxPlayerEntry e:oldSnapshot.players())if(!newNames.contains(e.name().toLowerCase(Locale.ROOT)))fakeMessage(e.name(),false);}
 @Override public void onDisable(){if(syncTask!=null)syncTask.cancel();if(tabPlaceholders!=null)tabPlaceholders.close();if(header!=null)header.close();if(displayBridge!=null)displayBridge.close();if(tab!=null)tab.clear();if(playerListObjective!=null)playerListObjective.close();if(relay!=null)relay.stop();}
}
