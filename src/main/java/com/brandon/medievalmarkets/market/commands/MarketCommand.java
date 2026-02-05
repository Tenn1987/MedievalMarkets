package com.brandon.medievalmarkets.market.commands;

import com.brandon.medievalmarkets.market.MarketService;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Locale;
import java.util.UUID;

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
            return usage(p);
        }

        String sub = args[0].toLowerCase(Locale.ROOT);

        switch (sub) {

            case "price" -> {
                if (args.length < 2) return usage(p, "/market price <commodity> [currency]");

                String id = args[1].toLowerCase(Locale.ROOT);
                String cur = (args.length >= 3)
                        ? args[2].toUpperCase(Locale.ROOT)
                        : market.defaultCurrency(p);

                UUID townId = market.townId(p);
                if (townId == null) {
                    p.sendMessage(text("No wilderness markets.", RED));
                    p.sendMessage(text("Trade inside a burg, or trade directly with players (NPCs later).", GRAY));
                    return true;
                }

                double each = market.priceEach(townId, id, cur);

                p.sendMessage(
                        text(id, YELLOW)
                                .append(text(" @ ", GRAY))
                                .append(text(trimDouble(each), AQUA))
                                .append(text(" ", GRAY))
                                .append(text(cur, GOLD))
                );
                return true;
            }

            // keep your existing sell/buy/list/etc cases here...
            default -> {
                return usage(p);
            }
        }
    }

    private boolean usage(Player p) {
        p.sendMessage(text("Usage:", YELLOW));
        p.sendMessage(text("/market price <commodity> [currency]", GRAY));
        p.sendMessage(text("/market buy|sell ...", GRAY));
        return true;
    }

    private boolean usage(Player p, String line) {
        p.sendMessage(text("Usage: ", YELLOW).append(text(line, GRAY)));
        return true;
    }

    private static String trimDouble(double d) {
        // small utility, keeps your output clean
        if (Double.isNaN(d) || Double.isInfinite(d)) return "0";
        String s = String.format(Locale.ROOT, "%.2f", d);
        // strip trailing .00
        if (s.endsWith(".00")) return s.substring(0, s.length() - 3);
        // strip trailing 0
        if (s.endsWith("0")) return s.substring(0, s.length() - 1);
        return s;
    }
}
