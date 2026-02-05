package com.brandon.medievalmarkets.market;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public final class MarketLedger {

    // townId -> (commodityId -> count)
    private final Map<UUID, Map<String, Integer>> supply = new HashMap<>();
    private final Map<UUID, Map<String, Integer>> demand = new HashMap<>();

    private static final int BASELINE = 1000;

    /* =========================
       Recording
       ========================= */

    public void recordSupply(UUID townId, String commodityId, int qty) {
        if (townId == null || commodityId == null || qty <= 0) return;
        supply
                .computeIfAbsent(townId, k -> new HashMap<>())
                .merge(commodityId, qty, Integer::sum);
    }

    public void recordDemand(UUID townId, String commodityId, int qty) {
        if (townId == null || commodityId == null || qty <= 0) return;
        demand
                .computeIfAbsent(townId, k -> new HashMap<>())
                .merge(commodityId, qty, Integer::sum);
    }

    /* =========================
       Reading
       ========================= */

    public int supply(UUID townId, String commodityId) {
        return supply
                .getOrDefault(townId, Map.of())
                .getOrDefault(commodityId, BASELINE);
    }

    public int demand(UUID townId, String commodityId) {
        return demand
                .getOrDefault(townId, Map.of())
                .getOrDefault(commodityId, BASELINE);
    }

    /* =========================
       Optional decay (future)
       ========================= */
    // You can later add a scheduled task that slowly nudges
    // supply/demand back toward BASELINE.
}
