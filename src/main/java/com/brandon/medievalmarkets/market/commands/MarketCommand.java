package com.brandon.medievalmarkets.market.commands;

import com.brandon.medievalmarkets.market.MarketService;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Locale;

import static net.kyori.adventure.text.Component.text;
import static net.kyori.adventure.text.format.NamedTextColor.*;

public final class MarketCommand implements CommandExecutor {

    private final MarketService market;

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

            case "price" -> {
                if (args.length < 2) return usage(p, "/market price <commodity> [currency]");
                String id = args[1].toLowerCase(Locale.ROOT);

                String cur = (args.length >= 3)
                        ? args[2].toUpperCase(Locale.ROOT)
                        : market.defaultCurrency(p);

                double each = market.priceEach(id, cur);

                p.sendMessage(
                        text(id, YELLOW)
                                .append(text(" @ ", GRAY))
                                .append(text(trimDouble(each), AQUA))
                                .append(text(" ", GRAY))
                                .append(text(cur, GOLD))
                                .append(text(" each", GRAY))
                );
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
                        : text("Buy failed (funds? invalid commodity? inventory full? burg treasury broke?).", RED)
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
                        : text("Sell failed (not enough items? invalid commodity? burg treasury broke?).", RED)
                );
                return true;
            }

            default -> {
                return usage(p, "/market");
            }
        }
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

    private String trimDouble(double v) {
        if (Math.abs(v - Math.rint(v)) < 1e-9) return String.valueOf((long) Math.rint(v));
        return String.format(Locale.US, "%.2f", v);
    }
}
