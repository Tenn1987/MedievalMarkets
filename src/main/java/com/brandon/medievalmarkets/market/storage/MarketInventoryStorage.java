package com.brandon.medievalmarkets.market.storage;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

public final class MarketInventoryStorage {

    private final File file;

    public MarketInventoryStorage(JavaPlugin plugin) {
        this.file = new File(plugin.getDataFolder(), "market_inventory.yml");
    }

    public Map<String, Map<String, Integer>> load() {
        Map<String, Map<String, Integer>> result = new HashMap<>();

        if (!file.exists()) return result;

        YamlConfiguration yml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection markets = yml.getConfigurationSection("markets");
        if (markets == null) return result;

        for (String marketId : markets.getKeys(false)) {
            ConfigurationSection sec = markets.getConfigurationSection(marketId);
            if (sec == null) continue;

            Map<String, Integer> stock = new HashMap<>();
            for (String itemKey : sec.getKeys(false)) {
                stock.put(itemKey, Math.max(0, sec.getInt(itemKey)));
            }
            result.put(marketId, stock);
        }
        return result;
    }

    public void save(Map<String, Map<String, Integer>> data) {
        YamlConfiguration yml = new YamlConfiguration();
        ConfigurationSection markets = yml.createSection("markets");

        for (var marketEntry : data.entrySet()) {
            ConfigurationSection sec = markets.createSection(marketEntry.getKey());
            for (var itemEntry : marketEntry.getValue().entrySet()) {
                sec.set(itemEntry.getKey(), Math.max(0, itemEntry.getValue()));
            }
        }

        try {
            yml.save(file);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}