package com.cevapi.fauxplayers;

import com.cevapi.fauxplayers.core.*; import java.io.*; import java.util.*; import net.kyori.adventure.text.Component; import net.kyori.adventure.text.format.NamedTextColor; import org.bukkit.Bukkit; import org.bukkit.event.*; import org.bukkit.scheduler.BukkitTask; import org.bukkit.event.player.PlayerJoinEvent; import org.bukkit.event.player.PlayerQuitEvent; import org.bukkit.event.player.PlayerCommandSendEvent; import org.bukkit.plugin.java.JavaPlugin; import org.bukkit.configuration.file.FileConfiguration; import org.bukkit.configuration.file.YamlConfiguration;

public final class FauxPlayersPlugin extends JavaPlugin {
 private PluginConfig config; private RelayManager relay; private ReplayManager replay; private TabListManager tab; private TabHeaderManager header; private TabDisplayBridge displayBridge; private PlayerListObjectiveBridge playerListObjective; private TabPlaceholderIntegration tabPlaceholders; private CommandAutocompleteFilter autocompleteFilter; private BukkitTask syncTask; private boolean observedJoinMessage, observedLeaveMessage; private String joinMessageTemplate, leaveMessageTemplate; private File messageCacheFile; private FileConfiguration messageCache;
 /** The fake entries currently shown, so a configuration change can announce what it adds or removes. */
 private int tickFailures;
 @Override public void onEnable(){saveDefaultConfig();loadMessageCache();if(Bukkit.getPluginManager().getPlugin("ProtocolLib")!=null){displayBridge=new TabDisplayBridge(this);displayBridge.enable();}if(Bukkit.getPluginManager().getPlugin("TAB")!=null){playerListObjective=new PlayerListObjectiveBridge(this);playerListObjective.enable();}reloadLocal();if(Bukkit.getPluginManager().getPlugin("ProtocolLib")!=null){header=new TabHeaderManager(this);header.enable();}if(Bukkit.getPluginManager().getPlugin("TAB")!=null){tabPlaceholders=new TabPlaceholderIntegration(this);tabPlaceholders.enable();}autocompleteFilter=new CommandAutocompleteFilter(this);autocompleteFilter.enable();getCommand("fauxplayers").setExecutor(new FauxPlayersCommand(this));getServer().getPluginManager().registerEvents(new StatusPingListener(this),this);getServer().getPluginManager().registerEvents(new Listener(){
  @EventHandler(priority=EventPriority.MONITOR) public void join(PlayerJoinEvent e){observeServerMessage(e.getJoinMessage(),e.getPlayer().getName(),true);tab.sendTo(e.getPlayer(),tabEntries());}
  @EventHandler(priority=EventPriority.HIGHEST) public void commandSend(PlayerCommandSendEvent e){if(!e.getPlayer().isOp())e.getCommands().removeIf(name->{String command=name.toLowerCase(Locale.ROOT);int separator=command.indexOf(':');if(separator>=0)command=command.substring(separator+1);return command.equals("fauxplayers")||command.equals("fp")||command.equals("fakeplayers");});}
  @EventHandler(priority=EventPriority.MONITOR) public void quit(PlayerQuitEvent e){observeServerMessage(e.getQuitMessage(),e.getPlayer().getName(),false);}
 },this);getLogger().info("FauxPlayers enabled; presentation-only entries are never server players.");}
 private void reloadLocal(){
  // The list from before this change, so the announcement covers only what the change did. A
  // baseline compared later would also pick up players the relay or the replay added on their own,
  // which is what made an unrelated command announce the whole relay roster as joining.
  PluginConfig previous=config;
  PlayerSnapshot snapshot=relay!=null?relay.snapshot():PlayerSnapshot.empty();
  List<FauxPlayerEntry> before=entriesFor(previous,snapshot);
  config=PluginConfig.load(new PluginConfig.Source(){public Object get(String path){return getConfig().get(path);}});
  if(relay==null)relay=new RelayManager(new RelayManager.Host(){public void fine(String message){getLogger().fine(message);}public void warning(String message){getLogger().warning(message);}public void remoteChanged(PlayerSnapshot oldSnapshot,PlayerSnapshot newSnapshot){getServer().getScheduler().runTask(FauxPlayersPlugin.this,()->FauxPlayersPlugin.this.remoteChanged(oldSnapshot,newSnapshot));}});else relay.stop();
  if(tab==null)tab=new TabListManager(this);
  if(replay==null)replay=new ReplayManager(new ReplayManager.Host(){
   public void fine(String message){getLogger().info(message);}
   public void warning(String message){getLogger().warning(message);}
   public void onReplayEvent(ReplayManager.Event event){replayEvent(event);}
  });
  replay.start(config,replayFile(config),knownReplayPlayers());
  if(config.enabled)relay.start(config);
  // Announce what this change adds or removes before the list is rebuilt, so switching a mode off
  // reads as its players leaving rather than the entries vanishing with no explanation.
  announceFauxChanges(before,entriesFor(config,snapshot));
  if(!config.enabled||!config.tabEnabled)tab.clear();else {tab.sync(config,tabEntries());tab.reassertPings();}
  if(syncTask==null)syncTask=getServer().getScheduler().runTaskTimer(this,()->{
   try{
    replay.tick();
    if(!config.enabled||!config.tabEnabled)tab.clear();else {tab.sync(config,tabEntries());tab.reassertPings();}
   }catch(Throwable error){
    // A repeating task dies for good if its body throws, which would silently freeze the replay
    // and the player list for the rest of the session. Keep the loop alive and report the cause.
    if(tickFailures++<3)getLogger().log(java.util.logging.Level.WARNING,"Replay presentation tick failed; the loop continues.",error);
   }
  },1L,5L);
 } public void reloadPlugin(){reloadConfig();reloadLocal();}
 /** The player list one configuration would produce, using a snapshot captured once for both sides. */
 private List<FauxPlayerEntry> entriesFor(PluginConfig candidate,PlayerSnapshot snapshot){
  if(candidate==null||!candidate.enabled||!candidate.tabEnabled)return List.of();
  PlayerSnapshot gated=candidate.relayEnabled?snapshot:PlayerSnapshot.empty();
  List<FauxPlayerEntry> replayed=candidate.replayEnabled&&replay!=null?replay.entries():List.of();
  return PresentationMath.withoutRealPlayers(PresentationMath.tabEntries(candidate,gated,replayed),onlineRealNames());
 }
 /**
  * Announces the difference between the list before a configuration change and the list after it.
  * A change that leaves the list alone, such as editing speed, therefore stays silent.
  */
 private void announceFauxChanges(List<FauxPlayerEntry> before,List<FauxPlayerEntry> after){
  LinkedHashMap<String,String> was=new LinkedHashMap<>();for(FauxPlayerEntry entry:before)was.put(entry.name().toLowerCase(Locale.ROOT),entry.name());
  LinkedHashMap<String,String> now=new LinkedHashMap<>();for(FauxPlayerEntry entry:after)now.put(entry.name().toLowerCase(Locale.ROOT),entry.name());
  for(Map.Entry<String,String> entry:now.entrySet())if(!was.containsKey(entry.getKey()))fakeMessage(entry.getValue(),true);
  for(Map.Entry<String,String> entry:was.entrySet())if(!now.containsKey(entry.getKey()))fakeMessage(entry.getValue(),false);
 }
 public PluginConfig config(){return config;} public RelayManager relay(){return relay;} public ReplayManager replay(){return replay;} public TabListManager tab(){return tab;} public TabDisplayBridge displayBridge(){return displayBridge;} public PlayerListObjectiveBridge playerListObjective(){return playerListObjective;}
 private java.nio.file.Path replayFile(PluginConfig current){if(current.replayFile==null||current.replayFile.isBlank())return null;java.nio.file.Path resolved=java.nio.file.Path.of(current.replayFile);return resolved.isAbsolute()?resolved:new File(getDataFolder(),current.replayFile).toPath();}
 /** Names the server already knows are in-game players, used to read relayed chat correctly. */
 public java.util.Set<String> knownReplayPlayers(){java.util.Set<String> names=new java.util.LinkedHashSet<>();if(config==null)return names;for(FauxPlayerEntry entry:config.statics)names.add(entry.name());for(FauxPlayerEntry entry:relaySnapshot().players())names.add(entry.name());return names;}
 public int displayedOnlineCount(){return Bukkit.getOnlinePlayers().size()+((config!=null&&config.enabled&&config.tabEnabled)?tabEntries().size():0);}
 public List<FauxPlayerEntry> remoteEntries(){return relaySnapshot().players();}
 /**
  * The relay roster, gated on the relay being enabled. The cache outlives a disable so it can be
  * used again on re-enable, but a disabled relay must never keep contributing players to the list.
  * Without this gate, turning the relay off stopped the refreshing and left the last roster showing.
  */
 public PlayerSnapshot relaySnapshot(){return config!=null&&config.relayEnabled&&relay!=null?relay.snapshot():PlayerSnapshot.empty();}
 public List<FauxPlayerEntry> statusEntries(){return PresentationMath.withoutRealPlayers(PresentationMath.statusEntries(config,relaySnapshot(),replayEntries()),onlineRealNames());}
 public List<FauxPlayerEntry> replayEntries(){return replay==null||config==null||!config.replayEnabled?List.of():replay.entries();}
 public List<FauxPlayerEntry> mergeEntries(Collection<FauxPlayerEntry> local, Collection<FauxPlayerEntry> remote){return PresentationMath.merge(config,local,remote);}
 public List<FauxPlayerEntry> tabEntries(){return PresentationMath.withoutRealPlayers(PresentationMath.tabEntries(config,relaySnapshot(),replayEntries()),onlineRealNames());}
 /** Names of the real players online, so a fake entry never duplicates one of them. */
 public java.util.Set<String> onlineRealNames(){java.util.Set<String> names=new java.util.LinkedHashSet<>();for(org.bukkit.entity.Player player:Bukkit.getOnlinePlayers())names.add(player.getName());return names;}
 /** True when a real player of that name is online right now. */
 public boolean isOnlineReal(String name){org.bukkit.entity.Player player=Bukkit.getPlayerExact(name);return player!=null&&player.isOnline();}
 public void refresh(){relay.refreshAsync(config);}
 public boolean isFauxName(String name){return tabEntries().stream().anyMatch(e->e.name().equalsIgnoreCase(name));}
 public void sendFauxChat(String name,String message){FauxPlayerEntry match=tabEntries().stream().filter(e->e.name().equalsIgnoreCase(name)).findFirst().orElse(null);if(match==null)return;tab.canonicalName(match.name()).thenAccept(canonical->getServer().getScheduler().runTask(this,()->Bukkit.broadcastMessage("§7<"+canonical+"> §f"+message)));}
 public void replayEvent(ReplayManager.Event event){
  // Never speak for, or list, someone who is actually on the server. Their own entry and their own
  // words win, so the replayed line is left out rather than shown beside the real player.
  if(config.replaySkipRealPlayers&&event.actor()!=null&&!event.actor().isBlank()&&isOnlineReal(event.actor()))return;
  switch(event.kind()){
   case JOIN -> fakeMessage(event.actor(),true);
   case LEAVE -> fakeMessage(event.actor(),false);
   case CHAT -> Bukkit.broadcastMessage("§7<"+event.actor()+"> §f"+event.text());
   case DISCORD -> Bukkit.broadcastMessage("§9[Discord] §7<"+event.actor()+"> §f"+event.text());
   default -> {if(event.text()!=null&&!event.text().isBlank())Bukkit.broadcastMessage("§7"+event.text());}
  }
 }
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
 @Override public void onDisable(){if(syncTask!=null)syncTask.cancel();if(replay!=null)replay.stop();if(tabPlaceholders!=null)tabPlaceholders.close();if(header!=null)header.close();if(displayBridge!=null)displayBridge.close();if(tab!=null)tab.clear();if(playerListObjective!=null)playerListObjective.close();if(autocompleteFilter!=null)autocompleteFilter.close();if(relay!=null)relay.stop();}
}
