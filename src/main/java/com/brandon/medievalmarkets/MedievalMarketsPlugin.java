package com.brandon.medievalmarkets;

import com.brandon.medievalmarkets.hooks.BabBurgHook;
import com.brandon.medievalmarkets.market.MarketService;
import com.brandon.medievalmarkets.market.commands.MarketCommand;
import com.brandon.mpcbridge.api.MpcEconomy;
import org.bukkit.Bukkit;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

public final class MedievalMarketsPlugin extends JavaPlugin {

    private BabBurgHook babHook;
    private MpcEconomy economy;
    private MarketService marketService;

    @Override
    public void onEnable() {

        getLogger().info("MedievalMarkets enabling...");

        // Delay everything that depends on other plugins
        Bukkit.getScheduler().runTask(this, () -> {

            /* =========================
               BAB Hook
               ========================= */
            this.babHook = new BabBurgHook(this);
            if (babHook.isReady()) {
                getLogger().info("BAB hook attached.");
            } else {
                getLogger().warning("BAB hook unavailable; wilderness rules active.");
            }

            /* =========================
               MPCBridge Economy
               ========================= */
            RegisteredServiceProvider<MpcEconomy> rsp =
                    Bukkit.getServicesManager().getRegistration(MpcEconomy.class);

            if (rsp != null && rsp.getProvider() != null && rsp.getProvider().isReady()) {
                this.economy = rsp.getProvider();
                getLogger().info("Hooked into MPCBridge economy.");
            } else {
                this.economy = null;
                getLogger().warning("MPCBridge economy service not found; running in standalone mode.");
            }

            /* =========================
               MarketService (NOW we have deps)
               ========================= */
            this.marketService = new MarketService(this, economy);

            // Register commands AFTER service exists
            if (getCommand("market") != null) {
                getCommand("market").setExecutor(new MarketCommand(marketService));
            }
            if (getCommand("markets") != null) {
                getCommand("markets").setExecutor(new MarketCommand(marketService));
            }
        });

        getLogger().info("MedievalMarkets enabled (hooks pending).");
    }

    public BabBurgHook getBabHook() {
        return babHook;
    }

    public MpcEconomy getEconomy() {
        return economy;
    }
}