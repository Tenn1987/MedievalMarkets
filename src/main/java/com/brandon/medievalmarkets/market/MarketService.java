package com.brandon.medievalmarkets.market;

import com.brandon.medievalmarkets.hooks.BabBurgHook;
import com.brandon.mpcbridge.api.MpcEconomy;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

public final class MarketService {

    private final Plugin plugin;
    private final MpcEconomy mpc;
    private final BabBurgHook bab; // optional hook into BAB

    private final Map<String, Commodity> commodities = new HashMap<>();
    private final MarketLedger ledger = new MarketLedger();
    private PriceEngine prices;

    // Wilderness default for price display only (no wilderness trades)
    private String wildernessDefaultCurrency = "SHEKEL";

    public MarketService(Plugin plugin, MpcEconomy mpc) {
        this.plugin = plugin;
        this.mpc = mpc;
        this.bab = new BabBurgHook(plugin);
    }

    public void loadDefaults() {
        // Load from config.yml (commodities section)
        FileConfiguration cfg = plugin.getConfig();
        ConfigurationSection sec = cfg.getConfigurationSection("commodities");
        if (sec == null) {
            plugin.getLogger().warning("[MedievalMarkets] No 'commodities:' section found in config.yml");
            return;
        }

        int seedSupply = cfg.getInt("market.seed-supply", 1000);
        int seedDemand = cfg.getInt("market.seed-demand", 1000);

        for (String key : sec.getKeys(false)) {
            ConfigurationSection csec = sec.getConfigurationSection(key);
            if (csec == null) continue;

            String matName = csec.getString("material", "");
            Material mat;
            try {
                mat = Material.valueOf(matName.toUpperCase(Locale.ROOT));
            } catch (Exception ex) {
                plugin.getLogger().warning("[MedievalMarkets] Invalid material for commodity '" + key + "': " + matName);
                continue;
            }

            double baseValue = csec.getDouble("base-value", 1.0);
            double elasticity = csec.getDouble("elasticity", 0.4);

            // Key is already lowercase from config, but enforce anyway:
            String id = key.toLowerCase(Locale.ROOT);

            register(new Commodity(id, mat, baseValue, elasticity));

            // Seed the ledger so scarcityIndex isn't weird at boot
            ledger.recordSupply(id, seedSupply);
            ledger.recordDemand(id, seedDemand);
        }

        plugin.getLogger().info("[MedievalMarkets] Loaded " + commodities.size() + " commodities from config.");
    }


    /* =========================
       Queries / Accessors
       ========================= */

    public Map<String, Commodity> commodities() {
        return commodities;
    }

    /** True only when player is inside a claimed burg chunk. */
    public boolean isInMarketZone(Player p) {
        return p != null && bab.isInBurg(p.getLocation());
    }

    /** Default currency = burg adopted currency (e.g., Rome -> DEN); wilderness falls back to SHEKEL for display only. */
    public String defaultCurrency(Player p) {
        if (p == null) return wildernessDefaultCurrency;

        String cur = bab.currencyAt(p.getLocation());
        if (cur != null && !cur.isBlank()) return cur.toUpperCase(Locale.ROOT);

        return wildernessDefaultCurrency;
    }

    /** For commands that don't have a player context (rare), use wilderness default. */
    public String defaultCurrency() {
        return wildernessDefaultCurrency;
    }

    public void setWildernessDefaultCurrency(String code) {
        this.wildernessDefaultCurrency = (code == null || code.isBlank())
                ? "SHEKEL"
                : code.toUpperCase(Locale.ROOT);
    }

    public double priceEach(String commodityId, String currencyCode) {
        double v = prices.commodityValue(commodityId);
        double r = mpc.rate(currencyCode);
        return v * r;
    }

    /* =========================
       Trades
       ========================= */

    /** BUY: player pays -> burg treasury receives. Disabled in wilderness. */
    public boolean buy(Player buyer, String commodityId, int qty, String currencyCode) {
        if (buyer == null) return false;

        UUID treasuryId = bab.treasuryIdAt(buyer.getLocation());
        if (treasuryId == null) return false; // wilderness not allowed

        Commodity c = commodities.get(commodityId);
        if (c == null || qty <= 0) return false;

        double unit = priceEach(commodityId, currencyCode);
        double total = unit * qty;

        UUID playerId = buyer.getUniqueId();

        // Withdraw from player
        if (!mpc.withdraw(playerId, currencyCode, total)) return false;

        // Deposit into town treasury
        mpc.deposit(treasuryId, currencyCode, total);

        // Give items
        ItemStack stack = new ItemStack(c.material(), qty);
        var leftovers = buyer.getInventory().addItem(stack);
        if (!leftovers.isEmpty()) {
            int notGiven = leftovers.values().stream().mapToInt(ItemStack::getAmount).sum();
            double refund = unit * notGiven;
            if (refund > 0) {
                // Refund: pull back from treasury then return to player (best-effort)
                if (mpc.withdraw(treasuryId, currencyCode, refund)) {
                    mpc.deposit(playerId, currencyCode, refund);
                } else {
                    mpc.deposit(playerId, currencyCode, refund);
                }
            }
        }

        ledger.recordDemand(commodityId, qty);
        mpc.recordPressure(currencyCode, +0.001 * qty);
        return true;
    }

    /** SELL: burg treasury pays -> player receives. Disabled in wilderness. */
    public boolean sell(Player seller, String commodityId, int qty, String currencyCode) {
        if (seller == null) return false;

        UUID treasuryId = bab.treasuryIdAt(seller.getLocation());
        if (treasuryId == null) return false; // wilderness not allowed

        Commodity c = commodities.get(commodityId);
        if (c == null || qty <= 0) return false;

        // Remove items first (restore if treasury can't pay)
        int removed = removeMaterial(seller, c.material(), qty);
        if (removed <= 0) return false;

        double unit = priceEach(commodityId, currencyCode);
        double payout = unit * removed;

        // Treasury must be able to pay
        if (!mpc.withdraw(treasuryId, currencyCode, payout)) {
            seller.getInventory().addItem(new ItemStack(c.material(), removed));
            return false;
        }

        // Pay player
        mpc.deposit(seller.getUniqueId(), currencyCode, payout);

        ledger.recordSupply(commodityId, removed);
        mpc.recordPressure(currencyCode, -0.001 * removed);
        return true;
    }

    /* =========================
       Helpers
       ========================= */

    public void register(Commodity c) {
        commodities.put(c.id(), c);
    }

    public void init() {
        loadDefaults();
        setPriceEngine(new PriceEngine(ledger, commodities));
    }

    public void setPriceEngine(PriceEngine engine) {
        this.prices = engine;
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
