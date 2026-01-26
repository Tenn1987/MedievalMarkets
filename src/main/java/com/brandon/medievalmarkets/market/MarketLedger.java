package com.brandon.medievalmarkets.market;

import java.util.HashMap;
import java.util.Map;

public final class MarketLedger {

    private final Map<String, Long> supply = new HashMap<>();
    private final Map<String, Long> demand = new HashMap<>();

    public void recordSupply(String commodityId, long amount) {
        if (amount <= 0) return;
        supply.merge(commodityId, amount, Long::sum);
    }

    public void recordDemand(String commodityId, long amount) {
        if (amount <= 0) return;
        demand.merge(commodityId, amount, Long::sum);
    }

    public double scarcityIndex(String commodityId) {
        long s = Math.max(1L, supply.getOrDefault(commodityId, 1L));
        long d = Math.max(1L, demand.getOrDefault(commodityId, 1L));
        return (double) d / (double) s;
    }
}
