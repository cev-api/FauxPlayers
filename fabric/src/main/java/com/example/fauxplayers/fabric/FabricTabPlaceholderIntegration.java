package com.example.fauxplayers.fabric;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.function.Supplier;

/** Optional integration with TAB Fabric's internal %online% placeholder. */
public final class FabricTabPlaceholderIntegration {
    private final FabricEntrypoint plugin;
    private Object placeholderManager;
    private Method registerServerPlaceholder;
    private Supplier<String> valueSupplier;
    private volatile String value = "0";
    private int checkTicks;
    private boolean unavailable;
    private boolean logged;
    private boolean loggedFailure;

    public FabricTabPlaceholderIntegration(FabricEntrypoint plugin) {
        this.plugin = plugin;
    }

    public void enable() {
        updateValue();
        register();
    }

    public void tick() {
        updateValue();
        if (!unavailable && ++checkTicks >= 100) {
            checkTicks = 0;
            register();
        }
    }

    public void close() {
        placeholderManager = null;
        registerServerPlaceholder = null;
        unavailable = false;
        logged = false;
        loggedFailure = false;
    }

    private void updateValue() {
        value = Integer.toString(plugin.displayedOnlineCount());
    }

    private String value() {
        return value;
    }

    private void register() {
        try {
            Class<?> api = Class.forName("me.neznamy.tab.api.TabAPI");
            Object tabApi = api.getMethod("getInstance").invoke(null);
            if (tabApi == null) return;
            Object currentManager = tabApi.getClass().getMethod("getPlaceholderManager").invoke(tabApi);
            if (currentManager == null) return;
            if (placeholderManager != currentManager) {
                placeholderManager = currentManager;
                registerServerPlaceholder = null;
            }
            if (registerServerPlaceholder == null) {
                Class<?> managerApi = Class.forName("me.neznamy.tab.api.placeholder.PlaceholderManager");
                registerServerPlaceholder = managerApi.getMethod(
                        "registerServerPlaceholder", String.class, int.class, Supplier.class);
            }
            if (valueSupplier == null) valueSupplier = this::value;
            registerServerPlaceholder.invoke(placeholderManager, "%online%", 1000, valueSupplier);
            loggedFailure = false;
            if (!logged) {
                plugin.log("TAB Fabric %online% overridden; faux and relayed players are included.");
                logged = true;
            }
        } catch (ClassNotFoundException error) {
            unavailable = true;
        } catch (Throwable error) {
            placeholderManager = null;
            registerServerPlaceholder = null;
            Throwable cause = error instanceof InvocationTargetException invocation
                    && invocation.getCause() != null ? invocation.getCause() : error;
            if (!loggedFailure) {
                loggedFailure = true;
                plugin.warn("Unable to override TAB Fabric %online% placeholder: "
                        + cause.getClass().getSimpleName() + ": " + cause.getMessage());
            }
        }
    }
}
