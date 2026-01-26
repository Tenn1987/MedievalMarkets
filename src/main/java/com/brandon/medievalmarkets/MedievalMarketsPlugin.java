package com.brandon.medievalmarkets;

import com.brandon.medievalmarkets.market.MarketService;
import com.brandon.medievalmarkets.market.commands.MarketCommand;
import com.brandon.mpcbridge.api.MpcEconomy;
import org.bukkit.Bukkit;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;

public final class MedievalMarketsPlugin extends JavaPlugin {

    private MpcEconomy mpc;
    private MarketService market;

    @Override
    public void onEnable() {
        this.mpc = resolveMpcEconomy();
        if (mpc == null || !mpc.isReady()) {
            getLogger().severe("MPCBridge economy service not available. Disabling MedievalMarkets.");
            Bukkit.getPluginManager().disablePlugin(this);
            return;
        }

        saveDefaultConfig(); // optional
        this.market = new MarketService(this, mpc);
        this.market.loadDefaults();

        var cmd = getCommand("market");
        if (cmd != null) cmd.setExecutor(new MarketCommand(market));

        getLogger().info("MedievalMarkets enabled.");
    }

    private MpcEconomy resolveMpcEconomy() {
        RegisteredServiceProvider<MpcEconomy> rsp = Bukkit.getServicesManager().getRegistration(MpcEconomy.class);
        return (rsp != null) ? rsp.getProvider() : null;
    }
}
