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
                if (burgName == null || burgName.isBlank()) burgName = "Unknown";

                String id = args[1].toLowerCase(Locale.ROOT);
                String cur = (args.length >= 3) ? args[2].toUpperCase(Locale.ROOT) : market.defaultCurrency(p);

                MarketService.Quote q = market.quote(townId, id, cur);

                p.sendMessage(
                        text(burgName + ": ", GOLD)
                                .append(text(id, YELLOW))
                                .append(text(" @ ", GRAY))
                                .append(text("BUY ", GRAY))
                                .append(text(q.buyEach(), GREEN))
                                .append(text(" / ", DARK_GRAY))
                                .append(text("SELL ", GRAY))
                                .append(text(q.sellEach(), AQUA))
                                .append(text(" ", GRAY))
                                .append(text(cur, GOLD))
                                .append(text(" (raw: " + fmt(q.raw()) + ")", DARK_GRAY))
                );

                if (q.sellEach() <= 0) {
                    p.sendMessage(text("This commodity currently has no sell value here.", RED));
                }

                return true;
            }

            case "buy" -> {
                if (args.length < 3) return usage(p, "/market buy <commodity> <qty> [currency]");

                UUID townId = market.townId(p);
                if (townId == null) {
                    p.sendMessage(text("No wilderness markets.", RED));
                    p.sendMessage(text("Trade inside a burg.", GRAY));
                    return true;
                }

                String id = args[1].toLowerCase(Locale.ROOT);
                int qty = parseInt(args[2], 0);
                if (qty <= 0) return usage(p, "/market buy <commodity> <qty> [currency]");

                String cur = (args.length >= 4) ? args[3].toUpperCase(Locale.ROOT) : market.defaultCurrency(p);

                boolean ok = market.buy(p, id, qty, cur);
                p.sendMessage(ok ? text("Bought.", GREEN) : text("Buy failed.", RED));
                return true;
            }

            case "sell" -> {
                if (args.length < 3) return usage(p, "/market sell <commodity> <qty> [currency]");

                UUID townId = market.townId(p);
                if (townId == null) {
                    p.sendMessage(text("No wilderness markets.", RED));
                    p.sendMessage(text("Trade inside a burg.", GRAY));
                    return true;
                }

                String id = args[1].toLowerCase(Locale.ROOT);
                int qty = parseInt(args[2], 0);
                if (qty <= 0) return usage(p, "/market sell <commodity> <qty> [currency]");

                String cur = (args.length >= 4) ? args[3].toUpperCase(Locale.ROOT) : market.defaultCurrency(p);

                boolean ok = market.sell(p, id, qty, cur);
                p.sendMessage(ok ? text("Sold.", GREEN) : text("Sell failed.", RED));
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
        if (burgName == null || burgName.isBlank()) burgName = "Unknown";

        String cur = (args.length >= 2) ? args[1].toUpperCase(Locale.ROOT) : market.defaultCurrency(p);

        ArrayList<SimpleEntry<String, MarketService.Quote>> list = new ArrayList<>();
        market.commodities().forEach((id, c) -> {
            MarketService.Quote q = market.quote(townId, id, cur);
            if (!(q.buyUnit() > 0.0) || Double.isNaN(q.buyUnit()) || Double.isInfinite(q.buyUnit())) return;
            list.add(new SimpleEntry<>(id, q));
        });

        // Sort by BUY each (defended), tie-break by raw
        list.sort((a, b) -> {
            long ab = a.getValue().buyEach();
            long bb = b.getValue().buyEach();
            int primary = Long.compare(ab, bb);
            if (primary == 0) primary = Double.compare(a.getValue().raw(), b.getValue().raw());
            return hot ? -primary : primary;
        });

        p.sendMessage(
                text(burgName + ": ", GOLD)
                        .append(text(hot ? "Hottest (Top " + HOT_COLD_COUNT + ")" : "Coldest (Top " + HOT_COLD_COUNT + ")", GOLD))
                        .append(text(" — ", DARK_GRAY))
                        .append(text(cur, GOLD))
        );

        int shown = 0;
        for (SimpleEntry<String, MarketService.Quote> e : list) {
            if (shown >= HOT_COLD_COUNT) break;

            String id = e.getKey();
            MarketService.Quote q = e.getValue();

            p.sendMessage(
                    text((shown + 1) + ". ", GRAY)
                            .append(text(id, YELLOW))
                            .append(text("  BUY ", DARK_GRAY))
                            .append(text(q.buyEach(), GREEN))
                            .append(text(" / ", DARK_GRAY))
                            .append(text("SELL ", DARK_GRAY))
                            .append(text(q.sellEach(), AQUA))
                            .append(text(" ", DARK_GRAY))
                            .append(text(cur, GOLD))
                            .append(text(" (raw: " + fmt(q.raw()) + ")", DARK_GRAY))
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

    private static int parseInt(String s, int fallback) {
        try {
            return Integer.parseInt(s);
        } catch (Exception ignored) {
            return fallback;
        }
    }

    private static String fmt(double v) {
        return String.format(Locale.US, "%.2f", v);
    }
}
