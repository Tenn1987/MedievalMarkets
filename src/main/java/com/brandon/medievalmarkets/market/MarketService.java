package com.brandon.medievalmarkets.market;

import com.brandon.mpcbridge.api.MpcEconomy;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class MarketService {

    private final Plugin plugin;
    private final MpcEconomy mpc;

    private final Map<String, Commodity> commodities = new HashMap<>();
    private final MarketLedger ledger = new MarketLedger();
    private PriceEngine prices;

    // Default currency for v0.1 (later: per-city/burg)
    private String defaultCurrency = "COPPER";

    public MarketService(Plugin plugin, MpcEconomy mpc) {
        this.plugin = plugin;
        this.mpc = mpc;
    }

    public void loadDefaults() {
        // Minimal starter set (expand later into full commodity registry)
        register(new Commodity("wheat", Material.WHEAT, 5.0, 0.65));
        register(new Commodity("bread", Material.BREAD, 7.0, 0.55));
        register(new Commodity("log_oak", Material.OAK_LOG, 6.0, 0.60));
        register(new Commodity("cobblestone", Material.COBBLESTONE, 2.0, 0.80));
        register(new Commodity("iron_ingot", Material.IRON_INGOT, 40.0, 0.75));
        register(new Commodity("gold_ingot", Material.GOLD_INGOT, 120.0, 0.50));

        this.prices = new PriceEngine(ledger, commodities);

        // Seed supply so scarcity starts sane (avoid crazy spikes at boot)
        for (String id : commodities.keySet()) {
            ledger.recordSupply(id, 1000);
            ledger.recordDemand(id, 1000);
        }
    }

    private void register(Commodity c) {
        commodities.put(c.id(), c);
    }

    public Map<String, Commodity> commodities() {
        return commodities;
    }

    public String defaultCurrency() {
        return defaultCurrency;
    }

    public void setDefaultCurrency(String code) {
        this.defaultCurrency = code;
    }

    public double priceEach(String commodityId, String currencyCode) {
        double v = prices.commodityValue(commodityId);
        double r = mpc.rate(currencyCode);
        return v * r;
    }

    public boolean buy(Player buyer, String commodityId, int qty, String currencyCode) {
        Commodity c = commodities.get(commodityId);
        if (c == null || qty <= 0) return false;

        double unit = priceEach(commodityId, currencyCode);
        double total = unit * qty;

        UUID id = buyer.getUniqueId();
        if (!mpc.withdraw(id, currencyCode, total)) return false;

        // Give items
        ItemStack stack = new ItemStack(c.material(), qty);
        var leftovers = buyer.getInventory().addItem(stack);
        if (!leftovers.isEmpty()) {
            // Refund if we couldn't fit items
            int notGiven = leftovers.values().stream().mapToInt(ItemStack::getAmount).sum();
            double refund = unit * notGiven;
            if (refund > 0) mpc.deposit(id, currencyCode, refund);
        }

        // Record demand (buying consumes supply in a real market, but here we track demand pressure)
        ledger.recordDemand(commodityId, qty);

        // Optional: nudge MPC pressure (if supported)
        mpc.recordPressure(currencyCode, +0.001 * qty);

        return true;
    }

    public boolean sell(Player seller, String commodityId, int qty, String currencyCode) {
        Commodity c = commodities.get(commodityId);
        if (c == null || qty <= 0) return false;

        // Remove items from player
        int removed = removeMaterial(seller, c.material(), qty);
        if (removed <= 0) return false;

        double unit = priceEach(commodityId, currencyCode);
        double payout = unit * removed;

        mpc.deposit(seller.getUniqueId(), currencyCode, payout);

        // Record supply
        ledger.recordSupply(commodityId, removed);

        // Optional pressure opposite direction
        mpc.recordPressure(currencyCode, -0.001 * removed);

        return true;
    }

    private int removeMaterial(Player p, Material mat, int qty) {
        int remaining = qty;

        for (int slot = 0; slot < p.getInventory().getSize(); slot++) {
            ItemStack it = p.getInventory().getItem(slot);
            if (it == null || it.getType() != mat) continue;

            int take = Math.min(remaining, it.getAmount());
            it.setAmount(it.getAmount() - take);
            if (it.getAmount() <= 0) p.getInventory().setItem(slot, null);

            remaining -= take;
            if (remaining <= 0) break;
        }

        return qty - remaining;
    }
}
