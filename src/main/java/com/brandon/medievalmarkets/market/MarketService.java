package com.brandon.medievalmarkets.market;

import com.brandon.medievalmarkets.hooks.BabBurgHook;
import com.brandon.mpcbridge.api.MpcEconomy;
import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import static net.kyori.adventure.text.Component.text;
import static net.kyori.adventure.text.format.NamedTextColor.*;

public final class MarketService {

    private final Plugin plugin;
    private final MpcEconomy mpc;
    private final BabBurgHook bab;

    private final Map<String, Commodity> commodities = new HashMap<>();
    private final MarketLedger ledger = new MarketLedger();
    private final File ledgerFile;
    private PriceEngine prices;

    // Wilderness default for price display only (no wilderness trades)
    private String wildernessDefaultCurrency = "SHEKEL";

    // Safety: max tax rate (BaB clamps too, but never trust reflection)
    private static final double MAX_TAX = 0.35;

    public MarketService(Plugin plugin, MpcEconomy mpc) {
        this.plugin = plugin;
        this.mpc = mpc;
        this.bab = new BabBurgHook(plugin);
        this.ledgerFile = new File(plugin.getDataFolder(), "market-ledger.yml");
    }

    /* =========================
       Init / Loading
       ========================= */

    public void init() {
        loadDefaults();
        loadLedger();
        this.prices = new PriceEngine(ledger, commodities);
    }

    public void loadDefaults() {
        commodities.clear();

        FileConfiguration cfg = plugin.getConfig();
        ConfigurationSection sec = cfg.getConfigurationSection("commodities");
        if (sec == null) {
            plugin.getLogger().warning("[MedievalMarkets] No 'commodities:' section found in config.yml");
            return;
        }

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

    public void loadLedger() {
        ledger.loadFromFile(plugin, ledgerFile);
    }

    public void saveLedger() {
        ledger.saveToFile(plugin, ledgerFile);
    }

    public void register(Commodity c) {
        commodities.put(c.id(), c);
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

    /** Town identity == burg treasury UUID (institutional MPC wallet). */
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

    public void setWildernessDefaultCurrency(String code) {
        this.wildernessDefaultCurrency = (code == null || code.isBlank())
                ? "SHEKEL"
                : code.toUpperCase(Locale.ROOT);
    }

    public double priceEach(UUID townId, String commodityId, String currencyCode) {
        if (prices == null) return 0.0;
        double v = prices.commodityValue(townId, commodityId);
        double r = mpc.rate(currencyCode);
        return v * r;
    }

    /* =========================
       GLOBAL (for MPC backing)
       ========================= */

    private String findCommodityId(Material mat) {
        if (mat == null) return null;
        for (Commodity c : commodities.values()) {
            if (c.material() == mat) return c.id();
        }
        return null;
    }

    /**
     * Reference value of a raw material in REFERENCE currency units (your base values).
     * This is GLOBAL across all towns and WILL fluctuate as ledger totals change.
     *
     * MPC should use this to value COMMODITY-backed currencies.
     */
    public double referenceValue(Material mat) {
        if (prices == null || mat == null) return 0.0;
        String id = findCommodityId(mat);
        if (id == null) return 0.0;
        return prices.globalCommodityValue(id);
    }

    /** Optional helper: global price each in a target currency code. */
    public double referenceValueEach(String commodityId, String currencyCode) {
        if (prices == null) return 0.0;
        double v = prices.globalCommodityValue(commodityId);
        double r = mpc.rate(currencyCode);
        return v * r;
    }

    /* =========================
       Trades (taxed, integer-safe)
       ========================= */

    /** BUY: player pays -> burg treasury receives. Disabled in wilderness. */
    public boolean buy(Player buyer, String commodityId, int qty, String currencyCode) {
        if (buyer == null) return false;

        UUID townId = bab.treasuryIdAt(buyer.getLocation());
        if (townId == null) return false; // wilderness not allowed

        Commodity c = commodities.get(commodityId);
        if (c == null || qty <= 0) return false;

        String cur = currencyCode.toUpperCase(Locale.ROOT);

        // price in "coins per item" (raw float)
        double unitRaw = priceEach(townId, commodityId, cur);
        if (!(unitRaw > 0.0) || Double.isNaN(unitRaw) || Double.isInfinite(unitRaw)) return false;

        // BUY rounds UP on TOTAL
        long costCoins = safeCeilToLong(unitRaw * (double) qty);
        if (costCoins <= 0) return false;

        double taxRate = clampTax(bab.salesTaxRateAt(buyer.getLocation()));
        long taxCoins = salesTax(costCoins, taxRate);

        long grandCoins;
        try {
            grandCoins = Math.addExact(costCoins, taxCoins);
        } catch (ArithmeticException ex) {
            buyer.sendMessage(text("Trade total overflow.", RED));
            return false;
        }

        UUID playerId = buyer.getUniqueId();

        plugin.getLogger().info("[MM][BUY] town=" + townId
                + " cur=" + cur
                + " unitRaw=" + unitRaw
                + " qty=" + qty
                + " cost=" + costCoins
                + " taxRate=" + taxRate
                + " tax=" + taxCoins
                + " grand=" + grandCoins);

        try {
            // Withdraw from player
            if (!mpc.withdraw(playerId, cur, (double) grandCoins)) return false;

            // Deposit into town treasury (cost + tax)
            mpc.deposit(townId, cur, (double) grandCoins);

            // Give items
            ItemStack stack = new ItemStack(c.material(), qty);
            Map<Integer, ItemStack> leftovers = buyer.getInventory().addItem(stack);

            int notGiven = leftovers.values().stream().mapToInt(ItemStack::getAmount).sum();
            int given = qty - notGiven;

            if (notGiven > 0)

                if (notGiven > 0) {
                    // Compute what the player *should* have paid for the items actually received.
                    long newCostCoins = safeCeilToLong(unitRaw * (double) given);
                    long newTaxCoins = salesTax(newCostCoins, taxRate);

                    long newGrand;
                    try {
                        newGrand = Math.addExact(newCostCoins, newTaxCoins);
                    } catch (ArithmeticException ex) {
                        newGrand = grandCoins; // no refund; ultra edge-case
                    }

                    long refundCoins = grandCoins - newGrand;
                    if (refundCoins > 0) {
                        // best-effort: reverse from town treasury then pay player
                        if (mpc.withdraw(townId, cur, (double) refundCoins)) {
                            mpc.deposit(playerId, cur, (double) refundCoins);
                        } else {
                            // If treasury withdraw fails, still refund player to avoid "paid but no item"
                            mpc.deposit(playerId, cur, (double) refundCoins);
                        }
                    }
                }

            if (given > 0) {
                ledger.recordDemand(townId, commodityId, given);
                mpc.recordPressure(cur, +0.001 * given);
            }

            return true;

        } catch (RuntimeException ex) {
            plugin.getLogger().warning("[MM][BUY] Exception: " + ex.getMessage());
            return false;
        }
    }

    /** SELL: burg treasury pays -> player receives. Disabled in wilderness. */
    public boolean sell(Player seller, String commodityId, int qty, String currencyCode) {
        if (seller == null) return false;

        UUID townId = bab.treasuryIdAt(seller.getLocation());
        if (townId == null) return false; // wilderness not allowed

        Commodity c = commodities.get(commodityId);
        if (c == null || qty <= 0) return false;

        String cur = currencyCode.toUpperCase(Locale.ROOT);

        // Remove items first (restore if something fails)
        int removed = removeMaterial(seller, c.material(), qty);
        if (removed <= 0) return false;

        double unitRaw = priceEach(townId, commodityId, cur);
        if (!(unitRaw > 0.0) || Double.isNaN(unitRaw) || Double.isInfinite(unitRaw)) {
            seller.getInventory().addItem(new ItemStack(c.material(), removed));
            return false;
        }

        // SELL rounds DOWN on TOTAL
        long payoutCoins = safeFloorToLong(unitRaw * (double) removed);
        if (payoutCoins <= 0) {
            seller.getInventory().addItem(new ItemStack(c.material(), removed));
            seller.sendMessage(text("Not worth 1 coin here in " + cur + ".", RED));
            return false;
        }

        double taxRate = clampTax(bab.salesTaxRateAt(seller.getLocation()));
        long taxCoins = salesTax(payoutCoins, taxRate);

        long netCoins = payoutCoins - taxCoins;
        if (netCoins <= 0) {
            seller.getInventory().addItem(new ItemStack(c.material(), removed));
            seller.sendMessage(text("Sale too small after tax.", RED));
            return false;
        }

        plugin.getLogger().info("[MM][SELL] town=" + townId
                + " cur=" + cur
                + " unitRaw=" + unitRaw
                + " removed=" + removed
                + " payout=" + payoutCoins
                + " taxRate=" + taxRate
                + " tax=" + taxCoins
                + " net=" + netCoins);

        try {
            // Town treasury must be able to pay the NET amount (tax stays in town)
            if (!mpc.withdraw(townId, cur, (double) netCoins)) {
                seller.getInventory().addItem(new ItemStack(c.material(), removed));
                seller.sendMessage(text("Town treasury cannot afford this purchase.", RED));
                return false;
            }

            // Pay player net
            mpc.deposit(seller.getUniqueId(), cur, (double) netCoins);

            ledger.recordSupply(townId, commodityId, removed);
            mpc.recordPressure(cur, -0.001 * removed);
            return true;

        } catch (RuntimeException ex) {
            seller.getInventory().addItem(new ItemStack(c.material(), removed));
            plugin.getLogger().warning("[MM][SELL] Exception: " + ex.getMessage());
            return false;
        }
    }

    /* =========================
       Helpers
       ========================= */

    private double clampTax(double rate) {
        if (!Double.isFinite(rate)) return 0.0;
        if (rate < 0.0) return 0.0;
        if (rate > MAX_TAX) return MAX_TAX;
        return rate;
    }

    private long salesTax(long baseCoins, double rate) {
        if (baseCoins <= 0) return 0L;
        if (!(rate > 0.0)) return 0L;
        return Math.max(0L, (long) Math.floor((double) baseCoins * rate));
    }

    private long safeCeilToLong(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v) || v <= 0.0) return 0L;
        if (v >= (double) Long.MAX_VALUE) return Long.MAX_VALUE;
        return (long) Math.ceil(v);
    }

    private long safeFloorToLong(double v) {
        if (Double.isNaN(v) || Double.isInfinite(v) || v <= 0.0) return 0L;
        if (v >= (double) Long.MAX_VALUE) return Long.MAX_VALUE;
        return (long) Math.floor(v);
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
