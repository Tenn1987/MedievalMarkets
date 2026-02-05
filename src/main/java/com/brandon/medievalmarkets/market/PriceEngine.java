package com.brandon.medievalmarkets.market;

import java.util.Map;
import java.util.UUID;

public final class PriceEngine {

    private final MarketLedger ledger;
    private final Map<String, Commodity> commodities;

    public PriceEngine(MarketLedger ledger, Map<String, Commodity> commodities) {
        this.ledger = ledger;
        this.commodities = commodities;
    }

    public double commodityValue(UUID townId, String commodityId) {
        Commodity c = commodities.get(commodityId);
        if (c == null || townId == null) return 0.0;

        double base = c.baseValue();
        double elasticity = c.elasticity();

        int supply = Math.max(1, ledger.supply(townId, commodityId));
        int demand = Math.max(1, ledger.demand(townId, commodityId));

        double ratio = (double) demand / (double) supply;

        // base * (demand/supply)^elasticity
        return base * Math.pow(ratio, elasticity);
    }
}
