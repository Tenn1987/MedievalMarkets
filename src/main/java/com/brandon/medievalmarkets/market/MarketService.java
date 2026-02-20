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
import static net.kyori.adventure.text.format.NamedTextColor.RED;

public final class MarketService {

    private final Plugin plugin;
    private final MpcEconomy mpc; // can be null in standalone mode
    private final BabBurgHook bab;

    private final Map<String, Commodity> commodities = new HashMap<>();
    private final MarketLedger ledger = new MarketLedger();
    private final File ledgerFile;

    private PriceEngine prices;

    private String wildernessDefaultCurrency = "SHEKEL";

    private static final double DEFAULT_TREASURY_TARGET = 10_000.0;

    public MarketService(Plugin plugin, MpcEconomy mpc) {
        this.plugin = plugin;
        this.mpc = mpc;
        this.bab = new BabBurgHook(plugin);
        this.ledgerFile = new File(plugin.getDataFolder(), "ledger.yml");
    }

    // ✅ MUST be public because your main plugin is in a different package
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

        int loaded = 0;
        int skipped = 0;

        for (String key : sec.getKeys(false)) {
            ConfigurationSection csec = sec.getConfigurationSection(key);
            if (csec == null) {
                skipped++;
                continue;
            }

            // material
            String matRaw = csec.getString("material", "");
            Material mat = Material.matchMaterial(matRaw);
            if (mat == null) {
                plugin.getLogger().warning("[MedievalMarkets] Bad material for commodity '" + key + "': " + matRaw);
                skipped++;
                continue;
            }

            // base + elasticity
            double base = csec.getDouble("base-value", csec.getDouble("base", 1.0));
            double elasticity = csec.getDouble("elasticity", 0.25);

            if (!Double.isFinite(base) || base <= 0) {
                plugin.getLogger().warning("[MedievalMarkets] Bad base-value for commodity '" + key + "': " + base);
                skipped++;
                continue;
            }
            if (!Double.isFinite(elasticity) || elasticity < 0) {
                plugin.getLogger().warning("[MedievalMarkets] Bad elasticity for commodity '" + key + "': " + elasticity);
                skipped++;
                continue;
            }

            Commodity c = new Commodity(key.toLowerCase(Locale.ROOT), mat, base, elasticity);

            // ✅ YOU WERE MISSING THIS LINE (this is why loaded stayed 0)
            register(c);
            loaded++;
        }

        plugin.getLogger().info("[MedievalMarkets] Loaded commodities: " + loaded + " (skipped: " + skipped + ")");
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

    public boolean isInMarketZone(Player p) {
        return p != null && bab.isInBurg(p.getLocation());
    }

    public UUID townId(Player p) {
        if (p == null) return null;
        return bab.treasuryIdAt(p.getLocation());
    }

    public String burgName(Player p) {
        if (p == null) return null;
        return bab.burgNameAt(p.getLocation());
    }

    public String defaultCurrency(Player p) {
        if (p == null) return wildernessDefaultCurrency;
        String c = bab.currencyAt(p.getLocation());
        if (c == null || c.isBlank()) return wildernessDefaultCurrency;
        return c.toUpperCase(Locale.ROOT);
    }

    public void setWildernessDefaultCurrency(String code) {
        if (code != null && !code.isBlank()) this.wildernessDefaultCurrency = code.toUpperCase(Locale.ROOT);
    }

    public double taxRate(Player p) {
        if (p == null) return 0.0;
        return clampTax(bab.salesTaxRateAt(p.getLocation()));
    }

    public int stock(UUID townId, String commodityId) {
        return ledger.stock(townId, commodityId);
    }

    /* =========================
       Quotes / Pricing
       ========================= */

    public record Quote(double raw, double buyUnit, double sellUnit, long buyEach, long sellEach) {}

    public Quote quote(UUID townId, String commodityId, String currencyCode) {
        Commodity c = commodities.get(commodityId);
        if (c == null || townId == null || prices == null) return new Quote(0, 0, 0, 0, 0);

        double raw = prices.commodityValue(townId, commodityId);

        // raw can be 0 early; we can keep it 0 for discovery.
        // but for display math safety, clamp to tiny epsilon.
        double safeRaw = Math.max(0.0001, raw);

        double spread = defendedSpread(townId, currencyCode);
        double buyUnit = safeRaw * (1.0 + spread);
        double sellUnit = safeRaw * (1.0 - spread);

        long buyEach = safeCeilToLong(buyUnit);
        long sellEach = safeFloorToLong(sellUnit);

        return new Quote(raw, buyUnit, sellUnit, buyEach, sellEach);
    }

    /* =========================
       Trades
       ========================= */

    public boolean buy(Player buyer, String commodityId, int qty, String currencyCode) {
        if (buyer == null) return false;
        if (mpc == null) {
            buyer.sendMessage(text("Economy unavailable (MPCBridge not found).", RED));
            return false;
        }

        UUID townId = bab.treasuryIdAt(buyer.getLocation());
        if (townId == null) return false;

        Commodity c = commodities.get(commodityId);
        if (c == null || qty <= 0) return false;

        String cur = currencyCode.toUpperCase(Locale.ROOT);

        Quote q = quote(townId, commodityId, cur);
        if (!(q.buyUnit > 0.0) || Double.isNaN(q.buyUnit) || Double.isInfinite(q.buyUnit)) return false;

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
            if (!mpc.withdraw(playerId, cur, (double) grandCoins)) return false;
            mpc.deposit(townId, cur, (double) grandCoins);

            ItemStack stack = new ItemStack(c.material(), qty);
            Map<Integer, ItemStack> leftovers = buyer.getInventory().addItem(stack);
            int notGiven = leftovers.values().stream().mapToInt(ItemStack::getAmount).sum();
            int given = qty - notGiven;

            if (given > 0) {
                ledger.recordDemand(townId, commodityId, given);
                // OPTIONAL: stock gating could remove here if you enforce "must have stock"
            }

            return given > 0;

        } catch (RuntimeException ex) {
            plugin.getLogger().warning("[MM][BUY] Exception: " + ex.getMessage());
            return false;
        }
    }

    public boolean sell(Player seller, String commodityId, int qty, String currencyCode) {
        if (seller == null) return false;
        if (mpc == null) {
            seller.sendMessage(text("Economy unavailable (MPCBridge not found).", RED));
            return false;
        }

        UUID townId = bab.treasuryIdAt(seller.getLocation());
        if (townId == null) return false;

        Commodity c = commodities.get(commodityId);
        if (c == null || qty <= 0) return false;

        String cur = currencyCode.toUpperCase(Locale.ROOT);

        int removed = removeMaterial(seller, c.material(), qty);
        if (removed <= 0) return false;

        Quote q = quote(townId, commodityId, cur);
        if (!(q.sellUnit > 0.0) || Double.isNaN(q.sellUnit) || Double.isInfinite(q.sellUnit)) {
            seller.getInventory().addItem(new ItemStack(c.material(), removed));
            return false;
        }

        long payoutCoins = safeFloorToLong(q.sellUnit * (double) removed);

        if (payoutCoins <= 0) {
            // ✅ Conditional bootstrap: only allow a 1-coin floor if town has < 1 in stock
            int currentStock = ledger.stock(townId, commodityId);

            if (currentStock < 1) {
                payoutCoins = 1; // town will buy 1 unit to seed inventory
            } else {
                seller.getInventory().addItem(new ItemStack(c.material(), removed));
                seller.sendMessage(text("Not worth 1 coin here in " + cur + ".", RED));
                return false;
            }
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
            if (!mpc.withdraw(townId, cur, (double) netCoins)) {
                seller.getInventory().addItem(new ItemStack(c.material(), removed));
                seller.sendMessage(text("Town treasury cannot afford this purchase.", RED));
                return false;
            }

            mpc.deposit(seller.getUniqueId(), cur, (double) netCoins);

            ledger.recordSupply(townId, commodityId, removed);
            ledger.addStock(townId, commodityId, removed);

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

    private double treasuryStress01(UUID townId, String currencyCode) {
        if (mpc == null) return 0.0;

        double target = Math.max(1.0, treasuryTarget());
        double bal = mpcBalanceSafe(townId, currencyCode);
        if (!Double.isFinite(bal)) return 0.0;

        double t = bal / target;
        if (t < 0.0) t = 0.0;
        if (t > 1.0) t = 1.0;

        return 1.0 - t;
    }

    private double defendedSpread(UUID townId, String currencyCode) {
        double base = 0.08;
        double stress = treasuryStress01(townId, currencyCode);
        return base + (0.35 * stress);
    }

    private double mpcBalanceSafe(UUID walletId, String cur) {
        try {
            return mpc.balance(walletId, cur);
        } catch (Throwable ignored) {
            return Double.NaN;
        }
    }

    private double clampTax(double rate) {
        if (!Double.isFinite(rate)) return 0.0;
        if (rate < 0.0) return 0.0;
        if (rate > 0.25) return 0.25;
        return rate;
    }

    private long salesTax(long coins, double rate) {
        if (coins <= 0) return 0;
        if (!(rate > 0.0)) return 0;
        double t = (double) coins * rate;
        long out = safeCeilToLong(t);
        return Math.max(0L, out);
    }

    private long safeCeilToLong(double v) {
        if (!Double.isFinite(v) || v <= 0) return 0L;
        double c = Math.ceil(v);
        if (c > (double) Long.MAX_VALUE) return Long.MAX_VALUE;
        return (long) c;
    }

    private long safeFloorToLong(double v) {
        if (!Double.isFinite(v) || v <= 0) return 0L;
        double f = Math.floor(v);
        if (f > (double) Long.MAX_VALUE) return Long.MAX_VALUE;
        return (long) f;
    }

    private int removeMaterial(Player player, Material mat, int qty) {
        if (player == null || mat == null || qty <= 0) return 0;

        PlayerInventory inv = player.getInventory();
        int removed = 0;

        for (int slot = 0; slot < inv.getSize(); slot++) {
            ItemStack it = inv.getItem(slot);
            if (it == null || it.getType() != mat) continue;

            int take = Math.min(it.getAmount(), qty - removed);
            if (take <= 0) break;

            it.setAmount(it.getAmount() - take);
            if (it.getAmount() <= 0) inv.setItem(slot, null);

            removed += take;
            if (removed >= qty) break;
        }

        return removed;
    }
}
