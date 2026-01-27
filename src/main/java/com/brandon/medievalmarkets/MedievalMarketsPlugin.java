package com.brandon.medievalmarkets;

import org.bukkit.Bukkit;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;

/**
 * MedievalMarkets runs standalone by default.
 * If MPCBridge is present and healthy, we hook it via Bukkit ServicesManager.
 *
 * IMPORTANT: This class intentionally avoids compile-time references to MPCBridge classes
 * (and even MarketService) to prevent NoClassDefFoundError when the bridge is absent or disabled.
 */
public final class MedievalMarketsPlugin extends JavaPlugin {

    /** MPCBridge provider instance (type unknown at compile time). */
    private Object mpc;

    /** Market service instance (type unknown at compile time). */
    private Object market;

    @Override
    public void onEnable() {
        saveDefaultConfig(); // safe even in standalone mode

        // Try to hook bridge (optional)
        this.mpc = tryResolveMpcEconomy();

        // If bridge exists but isn't ready, we still continue standalone
        boolean mpcReady = isMpcReady(this.mpc);

        if (mpcReady) {
            // Create MarketService(this, mpc) reflectively and register full commands
            if (tryEnableFullMarketWithMpc()) {
                getLogger().info("MedievalMarkets enabled (MPCBridge hooked).");
                return;
            }

            // If anything about full MPC mode fails, fall back to standalone mode
            this.mpc = null;
            this.market = null;
            getLogger().warning("MPCBridge hook failed during MarketService init; continuing in standalone mode.");
        } else {
            if (this.mpc != null) {
                getLogger().warning("MPCBridge economy service found but not ready; continuing in standalone mode.");
            } else {
                getLogger().info("MPCBridge economy service not found; running in standalone mode.");
            }
        }

        // Standalone mode: register a lightweight /market handler (so MM still runs)
        enableStandaloneCommands();
        getLogger().info("MedievalMarkets enabled (standalone).");
    }

    /**
     * Optional bridge hook:
     * - If MPCBridge isn't installed or is disabled, returns null.
     * - If installed, returns provider instance (as Object).
     */
    private Object tryResolveMpcEconomy() {
        try {
            Class<?> mpcEconomyClass = Class.forName("com.brandon.mpcbridge.api.MpcEconomy");
            RegisteredServiceProvider<?> rsp = Bukkit.getServicesManager().getRegistration((Class) mpcEconomyClass);
            return (rsp != null) ? rsp.getProvider() : null;
        } catch (ClassNotFoundException ignored) {
            return null;
        } catch (Throwable t) {
            getLogger().warning("MPCBridge resolve failed (will continue standalone): "
                    + t.getClass().getSimpleName() + ": " + t.getMessage());
            return null;
        }
    }

    private boolean isMpcReady(Object mpcProvider) {
        if (mpcProvider == null) return false;
        try {
            Method isReady = mpcProvider.getClass().getMethod("isReady");
            Object res = isReady.invoke(mpcProvider);
            return (res instanceof Boolean) && (Boolean) res;
        } catch (NoSuchMethodException e) {
            // If the method doesn't exist, treat as not-ready (but don't fail MM)
            return false;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Attempts to enable full MarketService + MarketCommand with MPCBridge.
     * Returns true on success, false if we should fall back to standalone mode.
     */
    private boolean tryEnableFullMarketWithMpc() {
        try {
            // Load the API class (guaranteed if we got here)
            Class<?> mpcEconomyClass = Class.forName("com.brandon.mpcbridge.api.MpcEconomy");

            // MarketService(this, mpc)
            Class<?> marketServiceClass = Class.forName("com.brandon.medievalmarkets.market.MarketService");
            Constructor<?> msCtor = marketServiceClass.getConstructor(JavaPlugin.class, mpcEconomyClass);
            this.market = msCtor.newInstance(this, this.mpc);

            // market.loadDefaults()
            Method loadDefaults = marketServiceClass.getMethod("loadDefaults");
            loadDefaults.invoke(this.market);

            // Register /market command executor: new MarketCommand(market)
            Class<?> marketCommandClass = Class.forName("com.brandon.medievalmarkets.market.commands.MarketCommand");
            Constructor<?> mcCtor = marketCommandClass.getConstructor(marketServiceClass);
            Object marketCmdExecutor = mcCtor.newInstance(this.market);

            var cmd = getCommand("market");
            if (cmd != null) cmd.setExecutor((org.bukkit.command.CommandExecutor) marketCmdExecutor);

            return true;
        } catch (Throwable t) {
            getLogger().warning("Full MPC mode init failed: "
                    + t.getClass().getSimpleName() + ": " + t.getMessage());
            return false;
        }
    }

    /**
     * Standalone /market handler: keeps MM up and gives clear feedback.
     * You can later expand this to a real internal economy/stock system.
     */
    private void enableStandaloneCommands() {
        var cmd = getCommand("market");
        if (cmd == null) return;

        cmd.setExecutor((sender, command, label, args) -> {
            sender.sendMessage("§6[MedievalMarkets]§r Running in standalone mode (MPCBridge not available).");
            sender.sendMessage("§7Trading is limited until MPCBridge is installed and healthy.");
            return true;
        });
    }
}
