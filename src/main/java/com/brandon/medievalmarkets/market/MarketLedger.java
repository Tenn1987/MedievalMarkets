package com.brandon.medievalmarkets.market;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.Plugin;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class MarketLedger {

    // townId -> (commodityId -> count)
    private final Map<UUID, Map<String, Integer>> supply = new HashMap<>();
    private final Map<UUID, Map<String, Integer>> demand = new HashMap<>();

    private static final int BASELINE = 1000;

    // Safety clamp so bad data can't explode your exponent math
    private static final int MIN_COUNT = 1;
    private static final int MAX_COUNT = 50_000_000;

    /* =========================
       Recording
       ========================= */

    public synchronized void recordSupply(UUID townId, String commodityId, int qty) {
        if (townId == null || commodityId == null || qty <= 0) return;
        supply
                .computeIfAbsent(townId, k -> new HashMap<>())
                .merge(commodityId, qty, Integer::sum);
    }

    public synchronized void recordDemand(UUID townId, String commodityId, int qty) {
        if (townId == null || commodityId == null || qty <= 0) return;
        demand
                .computeIfAbsent(townId, k -> new HashMap<>())
                .merge(commodityId, qty, Integer::sum);
    }

    /* =========================
       Reading (per-town)
       ========================= */

    public synchronized int supply(UUID townId, String commodityId) {
        return supply
                .getOrDefault(townId, Map.of())
                .getOrDefault(commodityId, BASELINE);
    }

    public synchronized int demand(UUID townId, String commodityId) {
        return demand
                .getOrDefault(townId, Map.of())
                .getOrDefault(commodityId, BASELINE);
    }

    /* =========================
       Reading (GLOBAL)
       ========================= */

    public synchronized int globalSupply(String commodityId) {
        if (commodityId == null) return BASELINE;

        long total = 0L;
        boolean any = false;

        for (Map<String, Integer> m : supply.values()) {
            Integer v = m.get(commodityId);
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
        if (commodityId == null) return BASELINE;

        long total = 0L;
        boolean any = false;

        for (Map<String, Integer> m : demand.values()) {
            Integer v = m.get(commodityId);
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
    }

    public synchronized void clearTown(UUID townId) {
        if (townId == null) return;
        supply.remove(townId);
        demand.remove(townId);
    }

    public synchronized void loadFromFile(Plugin plugin, File file) {
        if (plugin == null || file == null) return;

        supply.clear();
        demand.clear();

        if (!file.exists()) {
            plugin.getLogger().info("[MedievalMarkets] No ledger file found (fresh start): " + file.getName());
            return;
        }

        YamlConfiguration yml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection towns = yml.getConfigurationSection("towns");
        if (towns == null) {
            plugin.getLogger().warning("[MedievalMarkets] Ledger file missing 'towns:' root: " + file.getName());
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

            ConfigurationSection sSec = townSec.getConfigurationSection("supply");
            if (sSec != null) {
                Map<String, Integer> map = new HashMap<>();
                for (String cid : sSec.getKeys(false)) {
                    int v = clampCount(sSec.getInt(cid, BASELINE));
                    map.put(cid.toLowerCase(), v);
                }
                supply.put(townId, map);
            }

            ConfigurationSection dSec = townSec.getConfigurationSection("demand");
            if (dSec != null) {
                Map<String, Integer> map = new HashMap<>();
                for (String cid : dSec.getKeys(false)) {
                    int v = clampCount(dSec.getInt(cid, BASELINE));
                    map.put(cid.toLowerCase(), v);
                }
                demand.put(townId, map);
            }
        }

        plugin.getLogger().info("[MedievalMarkets] Loaded market ledger: " + supply.size() + " towns.");
    }

    public synchronized void saveToFile(Plugin plugin, File file) {
        if (plugin == null || file == null) return;

        YamlConfiguration yml = new YamlConfiguration();

        for (UUID townId : unionTownIds()) {
            String base = "towns." + townId;

            Map<String, Integer> sMap = supply.get(townId);
            if (sMap != null && !sMap.isEmpty()) {
                for (Map.Entry<String, Integer> e : sMap.entrySet()) {
                    yml.set(base + ".supply." + e.getKey().toLowerCase(), clampCount(e.getValue()));
                }
            }

            Map<String, Integer> dMap = demand.get(townId);
            if (dMap != null && !dMap.isEmpty()) {
                for (Map.Entry<String, Integer> e : dMap.entrySet()) {
                    yml.set(base + ".demand." + e.getKey().toLowerCase(), clampCount(e.getValue()));
                }
            }
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

    private int clampCount(int v) {
        if (v < MIN_COUNT) return MIN_COUNT;
        if (v > MAX_COUNT) return MAX_COUNT;
        return v;
    }

    private java.util.Set<UUID> unionTownIds() {
        java.util.Set<UUID> ids = new java.util.HashSet<>(supply.keySet());
        ids.addAll(demand.keySet());
        return ids;
    }
}
