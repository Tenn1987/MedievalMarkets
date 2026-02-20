package com.brandon.medievalmarkets.market.gui;

import com.brandon.medievalmarkets.market.MarketService;
import org.bukkit.entity.Player;

import java.util.Locale;
import java.util.UUID;

public final class MarketSession {

    private final UUID townId;
    private final String burgName;
    private final String currency;
    private final double taxRate; // 0..1

    public MarketSession(MarketService market, Player p) {
        this.townId = market.townId(p);

        String bn = market.burgName(p);
        if (bn == null || bn.isBlank()) bn = "Unknown";
        this.burgName = bn;

        String cur = market.defaultCurrency(p);
        if (cur == null || cur.isBlank()) cur = "SHEKEL";
        this.currency = cur.toUpperCase(Locale.ROOT);

        this.taxRate = market.taxRate(p);
    }

    public UUID townId() { return townId; }
    public String burgName() { return burgName; }
    public String currency() { return currency; }
    public double taxRate() { return taxRate; }

    public String taxPercentString() {
        return String.format(Locale.US, "%.1f%%", taxRate * 100.0);
    }
}
