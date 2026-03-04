package com.brandon.medievalmarkets.market.storage;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * Persists MarketService virtual stock (townId -> commodityId -> qty) to YAML.
 *
 * File: plugins/YourPlugin/market_stock.yml
 */
public final class MarketStockYamlStorage {

    private final JavaPlugin plugin;
    private final File file;

    public MarketStockYamlStorage(JavaPlugin plugin) {
        this.plugin = plugin;
        this.file = new File(plugin.getDataFolder(), "market_stock.yml");
    }

    public Map<String, Map<String, Integer>> load() {
        ensureParentDir();

        if (!file.exists()) {
            return new HashMap<>();
        }

        YamlConfiguration yml = YamlConfiguration.loadConfiguration(file);

        Map<String, Map<String, Integer>> out = new HashMap<>();
        ConfigurationSection towns = yml.getConfigurationSection("towns");
        if (towns == null) return out;

        for (String townId : towns.getKeys(false)) {
            ConfigurationSection townSec = towns.getConfigurationSection(townId);
            if (townSec == null) continue;

            Map<String, Integer> stock = new HashMap<>();
            for (String commodityId : townSec.getKeys(false)) {
                int qty = townSec.getInt(commodityId, 0);
                if (qty < 0) qty = 0;
                stock.put(commodityId.toLowerCase(), qty);
            }
            out.put(townId, stock);
        }

        return out;
    }

    public void save(Map<String, Map<String, Integer>> stockByTown) {
        ensureParentDir();

        YamlConfiguration yml = new YamlConfiguration();
        ConfigurationSection towns = yml.createSection("towns");

        for (Map.Entry<String, Map<String, Integer>> townEntry : stockByTown.entrySet()) {
            String townId = townEntry.getKey();
            if (townId == null || townId.isBlank()) continue;

            ConfigurationSection townSec = towns.createSection(townId);

            Map<String, Integer> stock = townEntry.getValue();
            if (stock == null) continue;

            for (Map.Entry<String, Integer> e : stock.entrySet()) {
                String commodityId = e.getKey();
                if (commodityId == null || commodityId.isBlank()) continue;

                int qty = (e.getValue() == null) ? 0 : Math.max(0, e.getValue());
                townSec.set(commodityId.toLowerCase(), qty);
            }
        }

        try {
            yml.save(file);
        } catch (IOException ex) {
            plugin.getLogger().severe("Failed to save market_stock.yml: " + ex.getMessage());
            ex.printStackTrace();
        }
    }

    private void ensureParentDir() {
        if (!plugin.getDataFolder().exists()) {
            //noinspection ResultOfMethodCallIgnored
            plugin.getDataFolder().mkdirs();
        }
    }
}