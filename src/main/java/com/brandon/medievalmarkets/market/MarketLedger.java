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
       Reading (per-town)
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
       Reading (GLOBAL)
       ========================= */

    public int globalSupply(String commodityId) {
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

    public int globalDemand(String commodityId) {
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
}
