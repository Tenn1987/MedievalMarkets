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

    /* =========================
       Liquidity defense tuning
       =========================
       As treasury runs low, widen spread to protect itself:
       - BUY becomes more expensive (burg earns more)
       - SELL becomes cheaper (burg pays less)
     */

    /** Config key: market.treasury-target */
    private static final double DEFAULT_TREASURY_TARGET = 10_000.0;

    /** At max stress (treasury empty), buy multiplier is 1.0 + BUY_SPREAD_A. */
    private static final double BUY_SPREAD_A = 1.00; // max 2x at stress=1

    /** At max stress (treasury empty), sell multiplier is 1.0 - SELL_SPREAD_B (clamped). */
    private static final double SELL_SPREAD_B = 0.80; // down to 0.20x at stress=1 (before clamp)

    /** Never allow sell multiplier to go below this (prevents perpetual 0 offers). */
    private static final double MIN_SELL_MULT = 0.10;

    /** Reflection cache for MPC balance lookup (API differs across versions). */
    private transient java.lang.reflect.Method mpcBalanceMethod;

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

    public String burgName(Player p) {
        if (p == null) return null;
        return bab.burgNameAt(p.getLocation());
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

    /**
     * Base market price EACH in the requested currency (no liquidity defense spread).
     * Uses the town's local supply/demand ledger and converts from SHEKEL by forex rate.
     */
    public double priceEach(UUID townId, String commodityId, String currencyCode) {
        if (prices == null) return 0.0;

        double v = prices.commodityValue(townId, commodityId); // in SHEKEL
        double r = mpc.rate(currencyCode);
        if (!(r > 0.0) || Double.isNaN(r) || Double.isInfinite(r)) return 0.0;

        // SHEKEL -> target currency
        return v / r;
    }

    /** Quote object: base raw (market) plus treasury-defense BUY/SELL unit prices and integer each. */
    public static final class Quote {
        public final double raw;       // base market price each (in requested currency)
        public final double buyUnit;   // liquidity-defended buy unit price (float)
        public final double sellUnit;  // liquidity-defended sell unit price (float)
        public final long buyEach;     // ceil(buyUnit)
        public final long sellEach;    // floor(sellUnit)
        public final double stress;    // 0..1

        private Quote(double raw, double buyUnit, double sellUnit, long buyEach, long sellEach, double stress) {
            this.raw = raw;
            this.buyUnit = buyUnit;
            this.sellUnit = sellUnit;
            this.buyEach = buyEach;
            this.sellEach = sellEach;
            this.stress = stress;
        }
    }

    /** Compute a local quote with treasury defense applied. */
    public Quote quote(UUID townId, String commodityId, String currencyCode) {
        String cur = currencyCode.toUpperCase(Locale.ROOT);

        double raw = priceEach(townId, commodityId, cur);
        if (!(raw > 0.0) || Double.isNaN(raw) || Double.isInfinite(raw)) {
            return new Quote(0.0, 0.0, 0.0, 0L, 0L, 0.0);
        }

        double stress = treasuryStress01(townId, cur);

        double buyMult = 1.0 + (BUY_SPREAD_A * stress);
        double sellMult = Math.max(MIN_SELL_MULT, 1.0 - (SELL_SPREAD_B * stress));

        double buyUnit = raw * buyMult;
        double sellUnit = raw * sellMult;

        long buyEach = safeCeilToLong(buyUnit);
        long sellEach = safeFloorToLong(sellUnit);

        return new Quote(raw, buyUnit, sellUnit, buyEach, sellEach, stress);
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
        if (!(r > 0.0) || Double.isNaN(r) || Double.isInfinite(r)) return 0.0;
        return v / r;
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

        Quote q = quote(townId, commodityId, cur);
        if (!(q.buyUnit > 0.0) || Double.isNaN(q.buyUnit) || Double.isInfinite(q.buyUnit)) return false;

        // BUY rounds UP on TOTAL
        long costCoins = safeCeilToLong(q.buyUnit * (double) qty);
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

            if (notGiven > 0) {
                // Refund for items not delivered (using same defended buyUnit)
                long newCostCoins = safeCeilToLong(q.buyUnit * (double) given);
                long newTaxCoins = salesTax(newCostCoins, taxRate);

                long newGrand;
                try {
                    newGrand = Math.addExact(newCostCoins, newTaxCoins);
                } catch (ArithmeticException ex) {
                    newGrand = grandCoins; // no refund; extreme edge-case
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

        Quote q = quote(townId, commodityId, cur);
        if (!(q.sellUnit > 0.0) || Double.isNaN(q.sellUnit) || Double.isInfinite(q.sellUnit)) {
            seller.getInventory().addItem(new ItemStack(c.material(), removed));
            return false;
        }

        // SELL rounds DOWN on TOTAL
        long payoutCoins = safeFloorToLong(q.sellUnit * (double) removed);
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
       Liquidity helpers
       ========================= */

    private double treasuryTarget() {
        try {
            return plugin.getConfig().getDouble("market.treasury-target", DEFAULT_TREASURY_TARGET);
        } catch (Exception ignored) {
            return DEFAULT_TREASURY_TARGET;
        }
    }

    /** Returns stress in [0..1]. 0 = healthy, 1 = broke. */
    private double treasuryStress01(UUID townId, String currencyCode) {
        double target = Math.max(1.0, treasuryTarget());
        double bal = mpcBalanceSafe(townId, currencyCode);

        // If we can't read balance (API mismatch), assume healthy (no artificial spread).
        if (!Double.isFinite(bal)) return 0.0;

        double t = bal / target;
        if (t < 0.0) t = 0.0;
        if (t > 1.0) t = 1.0;

        return 1.0 - t;
    }

    /**
     * Balance lookup via reflection so we don't hard-lock on an MPCBridge method name.
     * Tries: balance(UUID,String), getBalance(UUID,String)
     */
    private double mpcBalanceSafe(UUID wallet, String cur) {
        try {
            if (mpcBalanceMethod == null) {
                // Try common names
                try {
                    mpcBalanceMethod = mpc.getClass().getMethod("balance", UUID.class, String.class);
                } catch (NoSuchMethodException ignored) {
                    mpcBalanceMethod = mpc.getClass().getMethod("getBalance", UUID.class, String.class);
                }
            }

            Object v = mpcBalanceMethod.invoke(mpc, wallet, cur);
            if (v instanceof Number n) return n.doubleValue();

            // Sometimes APIs return primitive double boxed as Double
            if (v instanceof Double d) return d;

            return Double.NaN;
        } catch (Throwable t) {
            return Double.NaN;
        }
    }

    /* =========================
       General helpers
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
