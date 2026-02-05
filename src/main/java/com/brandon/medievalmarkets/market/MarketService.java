package com.brandon.medievalmarkets.market;

import com.brandon.medievalmarkets.hooks.BabBurgHook;
import com.brandon.mpcbridge.api.MpcEconomy;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
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

            String id = key.toLowerCase(Locale.ROOT);

            register(new Commodity(id, mat, baseValue, elasticity));

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

    public UUID townId(Player p) {
        if (p == null) return null;
        return bab.treasuryIdAt(p.getLocation());
    }


    /** Default currency = burg adopted currency; wilderness falls back to SHEKEL for display only. */
    public String defaultCurrency(Player p) {
        if (p == null) return wildernessDefaultCurrency;

        String cur = bab.currencyAt(p.getLocation());
        if (cur != null && !cur.isBlank()) return cur.toUpperCase(Locale.ROOT);

        return wildernessDefaultCurrency;
    }

    public String defaultCurrency() {
        return wildernessDefaultCurrency;
    }

    public void setWildernessDefaultCurrency(String code) {
        this.wildernessDefaultCurrency = (code == null || code.isBlank())
                ? "SHEKEL"
                : code.toUpperCase(Locale.ROOT);
    }

    public double priceEach(UUID townId, String commodityId, String currencyCode) {
        double v = prices.commodityValue(townId, commodityId);
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

        String cur = currencyCode.toUpperCase(Locale.ROOT);

        double unitRaw = priceEach(treasuryId, commodityId, cur);

        long unitCoins = unitPriceBuyCoins(unitRaw);
        if (unitCoins <= 0) return false;

        long totalUnits;
        try {
            totalUnits = totalCoins(unitCoins, qty);
        } catch (ArithmeticException ex) {
            buyer.sendMessage(org.bukkit.ChatColor.RED + "Trade total overflow.");
            return false;
        }

        double unit = (double) unitCoins;
        double total = (double) totalUnits;

        plugin.getLogger().info("[MM][BUY] burgTreasury=" + treasuryId
                + " cur=" + cur
                + " unit=" + unit + " qty=" + qty + " total=" + total);

        UUID playerId = buyer.getUniqueId();

        try {
            // Withdraw from player
            if (!mpc.withdraw(playerId, cur, total)) return false;

            // Deposit into town treasury
            mpc.deposit(treasuryId, cur, total);

            // Give items
            ItemStack stack = new ItemStack(c.material(), qty);
            var leftovers = buyer.getInventory().addItem(stack);
            if (!leftovers.isEmpty()) {
                int notGiven = leftovers.values().stream().mapToInt(ItemStack::getAmount).sum();

                long refundUnits;
                try {
                    refundUnits = totalCoins(unitCoins, notGiven);
                } catch (ArithmeticException ex) {
                    refundUnits = 0L;
                }

                if (refundUnits > 0) {
                    double refund = (double) refundUnits;

                    // Refund: pull back from treasury then return to player (best-effort)
                    if (mpc.withdraw(treasuryId, cur, refund)) {
                        mpc.deposit(playerId, cur, refund);
                    } else {
                        // If treasury withdrawal fails, still refund player to avoid "paid but no item"
                        mpc.deposit(playerId, cur, refund);
                    }
                }
            }

            ledger.recordDemand(treasuryId, commodityId, qty);
            mpc.recordPressure(cur, +0.001 * qty);
            return true;

        } catch (RuntimeException ex) {
            plugin.getLogger().warning("[MM][BUY] Exception: " + ex.getMessage());
            return false;
        }
    }

    /** SELL: burg treasury pays -> player receives. Disabled in wilderness. */
    public boolean sell(Player seller, String commodityId, int qty, String currencyCode) {
        if (seller == null) return false;

        UUID treasuryId = bab.treasuryIdAt(seller.getLocation());
        if (treasuryId == null) return false; // wilderness not allowed

        Commodity c = commodities.get(commodityId);
        if (c == null || qty <= 0) return false;

        String cur = currencyCode.toUpperCase(Locale.ROOT);

        // Remove items first (restore if treasury can't pay)
        int removed = removeMaterial(seller, c.material(), qty);
        if (removed <= 0) return false;

        double unitRaw = priceEach(treasuryId, commodityId, cur);

        long unitCoins = unitPriceSellCoins(unitRaw);
        if (unitCoins <= 0) {
            seller.getInventory().addItem(new ItemStack(c.material(), removed));
            return false;
        }

        long payoutUnits;
        try {
            payoutUnits = totalCoins(unitCoins, removed);
        } catch (ArithmeticException ex) {
            seller.getInventory().addItem(new ItemStack(c.material(), removed));
            seller.sendMessage(org.bukkit.ChatColor.RED + "Trade total overflow.");
            return false;
        }

        double unit = (double) unitCoins;
        double payout = (double) payoutUnits;

        plugin.getLogger().info("[MM][SELL] commodity=" + commodityId
                + " burgTreasury=" + treasuryId
                + " cur=" + cur
                + " removed=" + removed
                + " unit=" + unit
                + " payout=" + payout);

        try {
            // Treasury must be able to pay
            if (!mpc.withdraw(treasuryId, cur, payout)) {
                seller.getInventory().addItem(new ItemStack(c.material(), removed));
                return false;
            }

            // Pay player
            mpc.deposit(seller.getUniqueId(), cur, payout);

            ledger.recordSupply(treasuryId, commodityId, removed);
            mpc.recordPressure(cur, -0.001 * removed);
            return true;

        } catch (RuntimeException ex) {
            // If MPCBridge rejects for any reason, restore items
            seller.getInventory().addItem(new ItemStack(c.material(), removed));
            plugin.getLogger().warning("[MM][SELL] Exception: " + ex.getMessage());
            return false;
        }
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

    /**
     * Market rule: prices are quoted in WHOLE coin units.
     *
     * To prevent exploits:
     *  - BUY (player pays): round UP so the town never undercharges.
     *  - SELL (town pays): round DOWN so the town never overpays.
     *
     * Totals use multiplyExact to prevent long overflow corruption.
     */
    private static long unitPriceBuyCoins(double rawUnitPrice) {
        if (!Double.isFinite(rawUnitPrice) || rawUnitPrice <= 0) return 0L;
        return (long) Math.ceil(rawUnitPrice);
    }

    private static long unitPriceSellCoins(double rawUnitPrice) {
        if (!Double.isFinite(rawUnitPrice) || rawUnitPrice <= 0) return 0L;
        return (long) Math.floor(rawUnitPrice);
    }

    private static long totalCoins(long unitCoins, int qty) {
        if (unitCoins <= 0 || qty <= 0) return 0L;
        return Math.multiplyExact(unitCoins, (long) qty);
    }

    private int removeMaterial(Player p, Material mat, int amount) {
        int remaining = amount;
        PlayerInventory inv = p.getInventory();

        for (int slot = 0; slot < inv.getSize(); slot++) {
            ItemStack stack = inv.getItem(slot);
            if (stack == null) continue;
            if (stack.getType() != mat) continue;

            int stackAmt = stack.getAmount();
            int take = Math.min(stackAmt, remaining);

            remaining -= take;

            if (stackAmt - take <= 0) {
                inv.setItem(slot, null);
            } else {
                ItemStack newStack = stack.clone();
                newStack.setAmount(stackAmt - take);
                inv.setItem(slot, newStack);
            }

            if (remaining <= 0) break;
        }

        p.updateInventory();
        return amount - remaining;
    }
}
