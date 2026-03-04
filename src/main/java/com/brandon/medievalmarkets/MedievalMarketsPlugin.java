package com.brandon.medievalmarkets;

import com.brandon.medievalmarkets.hooks.BabBurgHook;
import com.brandon.medievalmarkets.market.MarketService;
import com.brandon.medievalmarkets.market.commands.MarketCommand;
import com.brandon.medievalmarkets.market.gui.MarketBarrelSignListener;
import com.brandon.medievalmarkets.market.gui.MarketGUI;
import com.brandon.medievalmarkets.market.gui.MarketGUIListener;
import com.brandon.mpcbridge.api.MpcEconomy;
import org.bukkit.Bukkit;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

public final class MedievalMarketsPlugin extends JavaPlugin {

    private BabBurgHook babHook;
    private MpcEconomy economy;
    private MarketService marketService;

    private BukkitTask ledgerAutosaveTask;

    @Override
    public void onEnable() {

        getLogger().info("MedievalMarkets enabling...");

        // CONFIG MUST LOAD FIRST
        saveDefaultConfig();
        reloadConfig();

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
               MarketService
               ========================= */
            this.marketService = new MarketService(this, economy);
            marketService.setWildernessDefaultCurrency(
                    getConfig().getString("economy.default-currency", "SHEKEL")
            );

            // INIT loads defaults + ledger.yml and builds the PriceEngine
            marketService.init();

            getLogger().info("MarketService loaded with "
                    + marketService.commodities().size() + " commodities.");

            // Register as service
            Bukkit.getServicesManager().register(
                    MarketService.class,
                    marketService,
                    this,
                    ServicePriority.Normal
            );

            /* =========================
               GUI (AFTER INIT)
               ========================= */
            MarketGUI marketGUI = new MarketGUI(this, marketService);

            getServer().getPluginManager().registerEvents(
                    new MarketBarrelSignListener(this, marketGUI), this
            );
            getServer().getPluginManager().registerEvents(
                    new MarketGUIListener(marketGUI), this
            );

            /* =========================
               Commands
               ========================= */
            if (getCommand("market") != null) {
                getCommand("market").setExecutor(new MarketCommand(marketService));
            }
            if (getCommand("markets") != null) {
                getCommand("markets").setExecutor(new MarketCommand(marketService));
            }

            /* =========================
               Autosave ledger.yml
               ========================= */
            // every 5 minutes (async): minimizes loss on power-off
            ledgerAutosaveTask = Bukkit.getScheduler().runTaskTimerAsynchronously(this, () -> {
                try {
                    if (marketService != null) marketService.saveLedger();
                } catch (Throwable t) {
                    getLogger().warning("Ledger autosave failed: " + t.getMessage());
                }
            }, 20L * 300, 20L * 300);

            getLogger().info("MedievalMarkets hooks + services ready.");
        });

        getLogger().info("MedievalMarkets enabled (hooks pending).");
    }

    @Override
    public void onDisable() {
        // Cancel autosave task cleanly
        if (ledgerAutosaveTask != null) {
            ledgerAutosaveTask.cancel();
            ledgerAutosaveTask = null;
        }

        // Save ledger one last time
        try {
            if (marketService != null) {
                marketService.saveLedger();
            }
        } catch (Throwable t) {
            getLogger().warning("Final ledger save failed: " + t.getMessage());
        }
    }

    public BabBurgHook babHook() { return babHook; }
    public MarketService marketService() { return marketService; }
    public MpcEconomy economy() { return economy; }
}