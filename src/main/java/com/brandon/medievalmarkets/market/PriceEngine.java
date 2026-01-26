package com.brandon.medievalmarkets.market;

import java.util.Map;

public final class PriceEngine {

    private final MarketLedger ledger;
    private final Map<String, Commodity> commodities;

    public PriceEngine(MarketLedger ledger, Map<String, Commodity> commodities) {
        this.ledger = ledger;
        this.commodities = commodities;
    }

    public double commodityValue(String commodityId) {
        Commodity c = commodities.get(commodityId);
        if (c == null) return 0.0;

        double scarcity = ledger.scarcityIndex(commodityId);
        return c.baseValue() * Math.pow(scarcity, c.elasticity());
    }
}
