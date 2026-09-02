package com.example.fauxplayers;

import java.lang.reflect.Method;
import java.lang.reflect.InvocationTargetException;
import java.util.function.Supplier;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

/**
 * Overrides TAB's built-in %online% placeholder with the presentation count.
 * Reflection keeps TAB an optional integration and avoids bundling its API.
 */
public final class TabPlaceholderIntegration {
    private final FauxPlayersPlugin plugin;
    private Object placeholderManager;
    private Method registerServerPlaceholder;
    private Supplier<String> valueSupplier;
    private int refreshTask=-1;
    private volatile String value="0";
    private boolean unavailable;
    private boolean logged;
    private boolean loggedFailure;

    public TabPlaceholderIntegration(FauxPlayersPlugin plugin){this.plugin=plugin;}

    public void enable(){
        updateValue();
        register();
        refreshTask=Bukkit.getScheduler().runTaskTimer(plugin,()->{
            updateValue();
            if(!unavailable&&Bukkit.getCurrentTick()%100==0)register();
        },1L,20L).getTaskId();
    }

    public void close(){
        if(refreshTask>=0)Bukkit.getScheduler().cancelTask(refreshTask);
        refreshTask=-1;
        placeholderManager=null;
        registerServerPlaceholder=null;
        unavailable=false;
        logged=false;
        loggedFailure=false;
    }

    private void updateValue(){value=Integer.toString(plugin.displayedOnlineCount());}

    private String value(){return value;}

    private void register(){
        try{
            Class<?> api=Class.forName("me.neznamy.tab.api.TabAPI");
            Object tabApi=api.getMethod("getInstance").invoke(null);
            if(tabApi==null)return;
            Object currentManager=tabApi.getClass().getMethod("getPlaceholderManager").invoke(tabApi);
            if(currentManager==null)return;
            if(placeholderManager!=currentManager){
                placeholderManager=currentManager;
                registerServerPlaceholder=null;
            }
            if(registerServerPlaceholder==null){
                Class<?> managerApi=Class.forName("me.neznamy.tab.api.placeholder.PlaceholderManager");
                registerServerPlaceholder=managerApi.getMethod("registerServerPlaceholder",String.class,int.class,Supplier.class);
                if(registerServerPlaceholder==null)throw new NoSuchMethodException("TAB registerServerPlaceholder(String,int,Supplier) not found");
            }
            if(valueSupplier==null)valueSupplier=this::value;
            registerServerPlaceholder.invoke(placeholderManager,"%online%",1000,valueSupplier);
            loggedFailure=false;
            if(!logged){plugin.getLogger().info("TAB %online% overridden; faux and relayed players are included.");logged=true;}
        }catch(ClassNotFoundException error){
            unavailable=true;
            plugin.getLogger().fine("TAB API not found; TAB online-count integration is disabled.");
        }catch(Throwable error){
            placeholderManager=null;
            registerServerPlaceholder=null;
            Throwable cause=error instanceof InvocationTargetException invocation&&invocation.getCause()!=null
                    ? invocation.getCause() : error;
            if(!loggedFailure){
                loggedFailure=true;
                plugin.getLogger().warning("Unable to override TAB %online% placeholder: "+cause.getClass().getSimpleName()+": "+cause.getMessage());
            }
        }
    }
}
