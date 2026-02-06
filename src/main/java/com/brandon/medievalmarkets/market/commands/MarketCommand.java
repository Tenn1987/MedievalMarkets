package com.brandon.medievalmarkets.market.commands;

import com.brandon.medievalmarkets.market.MarketService;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.*;
import java.util.AbstractMap.SimpleEntry;

import static net.kyori.adventure.text.Component.text;
import static net.kyori.adventure.text.format.NamedTextColor.*;

public final class MarketCommand implements CommandExecutor {

    private final MarketService market;
    private static final int HOT_COLD_COUNT = 7;

    public MarketCommand(MarketService market) {
        this.market = market;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {

        if (!(sender instanceof Player p)) {
            sender.sendMessage("Players only.");
            return true;
        }

        if (args.length == 0) {
            p.sendMessage(text("=== Medieval Markets ===", GOLD));
            p.sendMessage(text("Commands:", GRAY));
            p.sendMessage(text(" • /market list", YELLOW));
            p.sendMessage(text(" • /market hot [currency]", YELLOW));
            p.sendMessage(text(" • /market cold [currency]", YELLOW));
            p.sendMessage(text(" • /market price <commodity> [currency]", YELLOW));
            p.sendMessage(text(" • /market buy <commodity> <qty> [currency]", YELLOW));
            p.sendMessage(text(" • /market sell <commodity> <qty> [currency]", YELLOW));
            return true;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);

        switch (sub) {

            case "list" -> {
                p.sendMessage(text("Commodities:", GOLD));
                market.commodities().values().forEach(c -> {
                    p.sendMessage(
                            text("- ", GRAY)
                                    .append(text(c.id(), WHITE))
                                    .append(text(" (", DARK_GRAY))
                                    .append(text(c.material().name(), GRAY))
                                    .append(text(")", DARK_GRAY))
                    );
                });
                return true;
            }

            case "hot" -> {
                return showHotCold(p, true, args);
            }

            case "cold" -> {
                return showHotCold(p, false, args);
            }

            case "price" -> {
                if (args.length < 2) return usage(p, "/market price <commodity> [currency]");

                UUID townId = market.townId(p);
                if (townId == null) {
                    p.sendMessage(text("No wilderness markets.", RED));
                    p.sendMessage(text("Stand inside a burg to view local pricing.", GRAY));
                    return true;
                }

                String burgName = market.burgName(p);
                if (burgName == null) burgName = "Unknown Burg";

                String id = args[1].toLowerCase(Locale.ROOT);
                String cur = (args.length >= 3) ? args[2].toUpperCase(Locale.ROOT) : market.defaultCurrency(p);

                double raw = market.priceEach(townId, id, cur);
                long buyEach = (long) Math.ceil(raw);
                long sellEach = (long) Math.floor(raw);

                p.sendMessage(
                        text(burgName + ": ", GOLD)
                                .append(text(id, YELLOW))
                                .append(text(" @ ", GRAY))
                                .append(text("BUY ", GRAY))
                                .append(text(buyEach, GREEN))
                                .append(text(" / ", DARK_GRAY))
                                .append(text("SELL ", GRAY))
                                .append(text(sellEach, AQUA))
                                .append(text(" ", GRAY))
                                .append(text(cur, GOLD))
                                .append(text("  (raw: " + fmt(raw) + ")", DARK_GRAY))
                );

                if (sellEach <= 0) {
                    p.sendMessage(text("This item is currently worth < 1 coin here in " + cur + " (sell floors to 0).", RED));
                    p.sendMessage(text("Try selling more at once, or use a stronger currency.", GRAY));
                }

                return true;
            }

            case "buy" -> {
                if (args.length < 3) return usage(p, "/market buy <commodity> <qty> [currency]");

                if (!market.isInMarketZone(p)) {
                    p.sendMessage(text("No wilderness markets.", RED));
                    p.sendMessage(text("Trade inside a burg, or trade directly with players (NPCs later).", GRAY));
                    return true;
                }

                String id = args[1].toLowerCase(Locale.ROOT);
                int qty = parseInt(args[2], 1);
                String cur = (args.length >= 4) ? args[3].toUpperCase(Locale.ROOT) : market.defaultCurrency(p);

                boolean ok = market.buy(p, id, qty, cur);

                p.sendMessage(ok
                        ? text("Bought.", GREEN)
                        : text("Buy failed (funds? invalid commodity? inventory full?).", RED)
                );
                return true;
            }

            case "sell" -> {
                if (args.length < 3) return usage(p, "/market sell <commodity> <qty> [currency]");

                if (!market.isInMarketZone(p)) {
                    p.sendMessage(text("No wilderness markets.", RED));
                    p.sendMessage(text("Trade inside a burg, or trade directly with players (NPCs later).", GRAY));
                    return true;
                }

                String id = args[1].toLowerCase(Locale.ROOT);
                int qty = parseInt(args[2], 1);
                String cur = (args.length >= 4) ? args[3].toUpperCase(Locale.ROOT) : market.defaultCurrency(p);

                boolean ok = market.sell(p, id, qty, cur);

                p.sendMessage(ok
                        ? text("Sold.", GREEN)
                        : text("Sell failed (not enough items? invalid commodity? treasury broke? sell price is 0?).", RED)
                );
                return true;
            }

            default -> {
                return usage(p, "/market");
            }
        }
    }

    private boolean showHotCold(Player p, boolean hot, String[] args) {
        UUID townId = market.townId(p);
        if (townId == null) {
            p.sendMessage(text("No wilderness markets.", RED));
            p.sendMessage(text("Stand inside a burg to view local pricing.", GRAY));
            return true;
        }

        String burgName = market.burgName(p);
        if (burgName == null) burgName = "Unknown Burg";

        String cur = (args.length >= 2) ? args[1].toUpperCase(Locale.ROOT) : market.defaultCurrency(p);

        ArrayList<SimpleEntry<String, Double>> raws = new ArrayList<>();
        market.commodities().forEach((id, c) -> {
            double raw = market.priceEach(townId, id, cur);
            if (!(raw > 0.0) || Double.isNaN(raw) || Double.isInfinite(raw)) return;
            raws.add(new SimpleEntry<>(id, raw));
        });

        raws.sort((a, b) -> {
            long ab = (long) Math.ceil(a.getValue());
            long bb = (long) Math.ceil(b.getValue());
            int primary = Long.compare(ab, bb);
            if (primary == 0) primary = Double.compare(a.getValue(), b.getValue());
            return hot ? -primary : primary;
        });

        p.sendMessage(
                text(burgName + " — ", GOLD)
                        .append(text(hot ? "Hottest (Top " + HOT_COLD_COUNT + ")" : "Coldest (Top " + HOT_COLD_COUNT + ")", GOLD))
                        .append(text(" — ", DARK_GRAY))
                        .append(text(cur, GOLD))
        );

        int shown = 0;
        for (SimpleEntry<String, Double> e : raws) {
            if (shown >= HOT_COLD_COUNT) break;

            String id = e.getKey();
            double raw = e.getValue();

            long buyEach = (long) Math.ceil(raw);
            long sellEach = (long) Math.floor(raw);

            p.sendMessage(
                    text((shown + 1) + ". ", GRAY)
                            .append(text(id, YELLOW))
                            .append(text("  BUY ", DARK_GRAY))
                            .append(text(buyEach, GREEN))
                            .append(text(" / ", DARK_GRAY))
                            .append(text("SELL ", DARK_GRAY))
                            .append(text(sellEach, AQUA))
                            .append(text(" ", DARK_GRAY))
                            .append(text(cur, GOLD))
                            .append(text("  (raw: " + fmt(raw) + ")", DARK_GRAY))
            );

            shown++;
        }

        if (shown == 0) {
            p.sendMessage(text("No priced commodities available here yet.", GRAY));
        }

        return true;
    }

    private boolean usage(Player p, String u) {
        p.sendMessage(text("Usage: ", RED).append(text(u, YELLOW)));
        return true;
    }

    private int parseInt(String s, int def) {
        try {
            return Math.max(1, Integer.parseInt(s));
        } catch (Exception e) {
            return def;
        }
    }

    private String fmt(double v) {
        return String.format(Locale.US, "%.4f", v);
    }
}
