package com.brandon.medievalmarkets.market;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.Locale;

public final class MarketLedger {

    // townId -> (commodityId -> count)
    private final Map<UUID, Map<String, Integer>> supply = new HashMap<>();
    private final Map<UUID, Map<String, Integer>> demand = new HashMap<>();
    private final Map<UUID, Map<String, Integer>> stock  = new HashMap<>();

    /** Default liquidity baseline (prevents “dead markets”) */
    private static final int BASELINE = 1000;

    // Clamp counts so bad data doesn't break math
    private static final int MIN_COUNT = 1;
    private static final int MAX_COUNT = 50_000_000;

    // Stock can be 0
    private static final int MIN_STOCK = 0;
    private static final int MAX_STOCK = 50_000_000;

    /* =========================
       Seeding (NEW)
       ========================= */

    /**
     * Ensures a town has baseline supply/demand for all commodities so prices can form immediately.
     * Safe to call repeatedly.
     */
    public synchronized void seedTownIfMissing(UUID townId, Collection<String> commodityIds) {
        if (townId == null || commodityIds == null || commodityIds.isEmpty()) return;

        supply.computeIfAbsent(townId, k -> new HashMap<>());
        demand.computeIfAbsent(townId, k -> new HashMap<>());

        Map<String, Integer> s = supply.get(townId);
        Map<String, Integer> d = demand.get(townId);

        for (String cid : commodityIds) {
            String id = norm(cid);
            if (id.isBlank()) continue;
            s.putIfAbsent(id, BASELINE);
            d.putIfAbsent(id, BASELINE);
        }
    }

    /* =========================
       Recording
       ========================= */

    public synchronized void recordSupply(UUID townId, String commodityId, int qty) {
        if (townId == null || commodityId == null || qty <= 0) return;
        supply.computeIfAbsent(townId, k -> new HashMap<>())
                .merge(norm(commodityId), qty, Integer::sum);
        clampCountMap(townId, supply);
    }

    public synchronized void recordDemand(UUID townId, String commodityId, int qty) {
        if (townId == null || commodityId == null || qty <= 0) return;
        demand.computeIfAbsent(townId, k -> new HashMap<>())
                .merge(norm(commodityId), qty, Integer::sum);
        clampCountMap(townId, demand);
    }

    /* =========================
       Stock
       ========================= */

    public synchronized int stock(UUID townId, String commodityId) {
        if (townId == null || commodityId == null) return 0;
        return stock.getOrDefault(townId, Map.of()).getOrDefault(norm(commodityId), 0);
    }

    public synchronized void addStock(UUID townId, String commodityId, int qty) {
        if (townId == null || commodityId == null || qty <= 0) return;
        stock.computeIfAbsent(townId, k -> new HashMap<>())
                .merge(norm(commodityId), qty, Integer::sum);
        clampStockMap(townId);
    }

    public synchronized int removeStock(UUID townId, String commodityId, int qty) {
        if (townId == null || commodityId == null || qty <= 0) return 0;
        Map<String, Integer> m = stock.get(townId);
        if (m == null) return 0;

        String id = norm(commodityId);
        int have = m.getOrDefault(id, 0);
        int take = Math.min(have, qty);
        if (take <= 0) return 0;

        int left = have - take;
        if (left <= 0) m.remove(id);
        else m.put(id, left);

        return take;
    }

    private void clampStockMap(UUID townId) {
        Map<String, Integer> m = stock.get(townId);
        if (m == null) return;
        for (Map.Entry<String, Integer> e : new ArrayList<>(m.entrySet())) {
            int v = e.getValue() == null ? 0 : e.getValue();
            if (v < MIN_STOCK) v = MIN_STOCK;
            if (v > MAX_STOCK) v = MAX_STOCK;
            m.put(e.getKey(), v);
        }
    }

    private void clampCountMap(UUID townId, Map<UUID, Map<String, Integer>> target) {
        Map<String, Integer> m = target.get(townId);
        if (m == null) return;
        for (Map.Entry<String, Integer> e : new ArrayList<>(m.entrySet())) {
            int v = e.getValue() == null ? BASELINE : e.getValue();
            m.put(e.getKey(), clampCount(v));
        }
    }

    /* =========================
       Reading (per-town)
       ========================= */

    public synchronized int supply(UUID townId, String commodityId) {
        if (townId == null || commodityId == null) return BASELINE;
        return supply.getOrDefault(townId, Map.of()).getOrDefault(norm(commodityId), BASELINE);
    }

    public synchronized int demand(UUID townId, String commodityId) {
        if (townId == null || commodityId == null) return BASELINE;
        return demand.getOrDefault(townId, Map.of()).getOrDefault(norm(commodityId), BASELINE);
    }

    /* =========================
       Reading (GLOBAL)
       ========================= */

    public synchronized int globalSupply(String commodityId) {
        String id = norm(commodityId);
        long total = 0L;
        boolean any = false;

        for (Map<String, Integer> m : supply.values()) {
            Integer v = m.get(id);
            if (v != null) {
                any = true;
                total += v;
            }
        }

        if (!any) return BASELINE;
        if (total > Integer.MAX_VALUE) return Integer.MAX_VALUE;
        return (int) Math.max(1L, total);
    }

    public synchronized int globalDemand(String commodityId) {
        String id = norm(commodityId);
        long total = 0L;
        boolean any = false;

        for (Map<String, Integer> m : demand.values()) {
            Integer v = m.get(id);
            if (v != null) {
                any = true;
                total += v;
            }
        }

        if (!any) return BASELINE;
        if (total > Integer.MAX_VALUE) return Integer.MAX_VALUE;
        return (int) Math.max(1L, total);
    }

    /* =========================
       Persistence
       ========================= */

    public synchronized void clearAll() {
        supply.clear();
        demand.clear();
        stock.clear();
    }

    public synchronized void clearTown(UUID townId) {
        if (townId == null) return;
        supply.remove(townId);
        demand.remove(townId);
        stock.remove(townId);
    }

    public synchronized void loadFromFile(Plugin plugin, File file) {
        if (plugin == null || file == null) return;

        clearAll();

        if (!file.exists()) {
            writeEmptyLedger(plugin, file);
            return;
        }

        YamlConfiguration yml = YamlConfiguration.loadConfiguration(file);

        ConfigurationSection towns = yml.getConfigurationSection("towns");
        if (towns == null) {
            // Empty/old/bad ledger -> initialize cleanly
            writeEmptyLedger(plugin, file);
            return;
        }

        for (String townKey : towns.getKeys(false)) {
            UUID townId;
            try {
                townId = UUID.fromString(townKey);
            } catch (Exception ex) {
                plugin.getLogger().warning("[MedievalMarkets] Skipping bad town UUID in ledger: " + townKey);
                continue;
            }

            ConfigurationSection townSec = towns.getConfigurationSection(townKey);
            if (townSec == null) continue;

            readMapInto(townSec.getConfigurationSection("supply"), supply, townId, true);
            readMapInto(townSec.getConfigurationSection("demand"), demand, townId, true);
            readMapInto(townSec.getConfigurationSection("stock"),  stock,  townId, false);
        }

        plugin.getLogger().info("[MedievalMarkets] Loaded market ledger: " + unionTownIds().size() + " towns.");
    }

    private void readMapInto(ConfigurationSection sec,
                             Map<UUID, Map<String, Integer>> target,
                             UUID townId,
                             boolean clampCounts) {
        if (sec == null) return;

        Map<String, Integer> map = new HashMap<>();
        for (String cid : sec.getKeys(false)) {
            int v = sec.getInt(cid, clampCounts ? BASELINE : 0);
            v = clampCounts ? clampCount(v) : clampStock(v);
            map.put(norm(cid), v);
        }
        if (!map.isEmpty()) target.put(townId, map);
    }

    public synchronized void saveToFile(Plugin plugin, File file) {
        if (plugin == null || file == null) return;

        YamlConfiguration yml = new YamlConfiguration();
        yml.createSection("towns");

        for (UUID townId : unionTownIds()) {
            String base = "towns." + townId;

            writeMap(yml, base + ".supply", supply.get(townId), true);
            writeMap(yml, base + ".demand", demand.get(townId), true);
            writeMap(yml, base + ".stock",  stock.get(townId), false);
        }

        try {
            File parent = file.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            yml.save(file);
            plugin.getLogger().info("[MedievalMarkets] Saved market ledger: " + file.getName());
        } catch (IOException ex) {
            plugin.getLogger().severe("[MedievalMarkets] Failed saving market ledger: " + ex.getMessage());
        }
    }

    private void writeMap(YamlConfiguration yml, String path, Map<String, Integer> map, boolean clampCounts) {
        if (map == null || map.isEmpty()) return;
        for (Map.Entry<String, Integer> e : map.entrySet()) {
            int v = e.getValue() == null ? (clampCounts ? BASELINE : 0) : e.getValue();
            v = clampCounts ? clampCount(v) : clampStock(v);
            yml.set(path + "." + norm(e.getKey()), v);
        }
    }

    private void writeEmptyLedger(Plugin plugin, File file) {
        YamlConfiguration yml = new YamlConfiguration();
        yml.set("towns", new HashMap<>());
        try {
            File parent = file.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            yml.save(file);
            plugin.getLogger().info("[MedievalMarkets] Initialized empty market ledger: " + file.getName());
        } catch (IOException ex) {
            plugin.getLogger().severe("[MedievalMarkets] Failed initializing ledger: " + ex.getMessage());
        }
    }

    private int clampCount(int v) {
        if (v < MIN_COUNT) return MIN_COUNT;
        if (v > MAX_COUNT) return MAX_COUNT;
        return v;
    }

    private int clampStock(int v) {
        if (v < MIN_STOCK) return MIN_STOCK;
        if (v > MAX_STOCK) return MAX_STOCK;
        return v;
    }

    private Set<UUID> unionTownIds() {
        Set<UUID> ids = new HashSet<>(supply.keySet());
        ids.addAll(demand.keySet());
        ids.addAll(stock.keySet());
        return ids;
    }

    private String norm(String id) {
        return (id == null) ? "" : id.toLowerCase(Locale.ROOT);
    }
}
