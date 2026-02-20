package com.brandon.medievalmarkets.market.gui;

import com.brandon.medievalmarkets.market.Commodity;
import com.brandon.medievalmarkets.market.MarketService;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.*;

public final class MarketGUI {

    private static final int SIZE = 54;
    private static final int GRID_START = 9;
    private static final int GRID_END = 45;
    private static final int PER_PAGE = GRID_END - GRID_START;

    private static final int SLOT_INFO = 4;
    private static final int SLOT_REFRESH = 8;
    private static final int SLOT_PREV = 45;
    private static final int SLOT_PAGE = 49;
    private static final int SLOT_NEXT = 50;
    private static final int SLOT_CLOSE = 53;

    private final JavaPlugin plugin;
    private final MarketService market;

    public final NamespacedKey KEY_COMMODITY;
    public final NamespacedKey KEY_QTY;
    public final NamespacedKey KEY_SIDE;

    public MarketGUI(JavaPlugin plugin, MarketService market) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.market = Objects.requireNonNull(market, "market");

        this.KEY_COMMODITY = new NamespacedKey(plugin, "mm_commodity");
        this.KEY_QTY = new NamespacedKey(plugin, "mm_qty");
        this.KEY_SIDE = new NamespacedKey(plugin, "mm_side");
    }

    public MarketService market() { return market; }

    public void openMain(Player p) { openMain(p, 0); }

    public void openMain(Player p, int page) {
        if (p == null) return;

        if (!market.isInMarketZone(p)) {
            p.sendMessage(Component.text("No wilderness markets.", NamedTextColor.RED));
            p.sendMessage(Component.text("Trade inside a burg.", NamedTextColor.GRAY));
            return;
        }

        MarketSession s = new MarketSession(market, p);
        if (s.townId() == null) return;

        List<Commodity> list = new ArrayList<>(market.commodities().values());
        list.sort(Comparator.comparing(Commodity::id));

        int pages = Math.max(1, (int) Math.ceil(list.size() / (double) PER_PAGE));
        int cur = Math.max(0, Math.min(page, pages - 1));

        Inventory inv = Bukkit.createInventory(
                new Holder(View.MAIN, s, null, cur),
                SIZE,
                Component.text("Market: ", NamedTextColor.DARK_GREEN)
                        .append(Component.text(s.burgName(), NamedTextColor.GREEN))
        );

        frame(inv);
        inv.setItem(SLOT_INFO, infoItem(s));
        inv.setItem(SLOT_REFRESH, button(Material.SUNFLOWER, Component.text("Refresh", NamedTextColor.YELLOW)));
        inv.setItem(SLOT_PREV, button(Material.ARROW, Component.text("Previous", NamedTextColor.AQUA)));
        inv.setItem(SLOT_PAGE, button(Material.MAP, Component.text("Page " + (cur + 1) + "/" + pages, NamedTextColor.GOLD)));
        inv.setItem(SLOT_NEXT, button(Material.ARROW, Component.text("Next", NamedTextColor.AQUA)));
        inv.setItem(SLOT_CLOSE, button(Material.BARRIER, Component.text("Close", NamedTextColor.RED)));

        int idx = cur * PER_PAGE;
        for (int slot = GRID_START; slot < GRID_END && idx < list.size(); slot++) {
            inv.setItem(slot, commodityButton(s, list.get(idx++)));
        }

        p.openInventory(inv);
    }

    public void openTrade(Player p, String commodityId) {
        if (p == null) return;

        if (!market.isInMarketZone(p)) {
            p.sendMessage(Component.text("No wilderness markets.", NamedTextColor.RED));
            return;
        }

        MarketSession s = new MarketSession(market, p);
        if (s.townId() == null) return;

        Commodity c = market.commodities().get(commodityId.toLowerCase(Locale.ROOT));
        if (c == null) return;

        int stock = market.stock(s.townId(), c.id());
        MarketService.Quote q = market.quote(s.townId(), c.id(), s.currency());

        Inventory inv = Bukkit.createInventory(
                new Holder(View.TRADE, s, c.id(), 0),
                SIZE,
                Component.text("Trade: ", NamedTextColor.DARK_AQUA)
                        .append(Component.text(c.id(), NamedTextColor.AQUA))
        );

        frame(inv);

        inv.setItem(0, button(Material.ARROW, Component.text("Back", NamedTextColor.AQUA)));
        inv.setItem(SLOT_CLOSE, button(Material.BARRIER, Component.text("Close", NamedTextColor.RED)));
        inv.setItem(SLOT_INFO, infoItem(s));

        inv.setItem(13, button(c.material(),
                Component.text(c.id(), NamedTextColor.YELLOW),
                List.of(
                        Component.text("Stock: " + stock, stock > 0 ? NamedTextColor.WHITE : NamedTextColor.RED),
                        Component.text("Buy: " + q.buyEach() + " " + s.currency(), NamedTextColor.GRAY),
                        Component.text("Sell: " + q.sellEach() + " " + s.currency(), NamedTextColor.GRAY),
                        Component.text("Tax: " + s.taxPercentString(), NamedTextColor.GRAY)
                )
        ));

        // Buy side (show qty buttons only if stock > 0)
        inv.setItem(28, stock > 0
                ? button(Material.GREEN_CONCRETE, Component.text("BUY", NamedTextColor.GREEN))
                : button(Material.GRAY_CONCRETE, Component.text("Out of stock", NamedTextColor.RED)));

        if (stock > 0) {
            qty(inv, "buy", 29, 1, stock);
            qty(inv, "buy", 30, 8, stock);
            qty(inv, "buy", 31, 16, stock);
            qty(inv, "buy", 32, 64, stock);
        }

        // Sell side
        inv.setItem(38, button(Material.LIGHT_BLUE_CONCRETE, Component.text("SELL", NamedTextColor.AQUA)));
        qty(inv, "sell", 33, 1, Integer.MAX_VALUE);
        qty(inv, "sell", 34, 8, Integer.MAX_VALUE);
        qty(inv, "sell", 35, 16, Integer.MAX_VALUE);
        qty(inv, "sell", 36, 64, Integer.MAX_VALUE);

        p.openInventory(inv);
    }

    // ---------- Buttons / Items ----------

    private ItemStack commodityButton(MarketSession s, Commodity c) {
        MarketService.Quote q = market.quote(s.townId(), c.id(), s.currency());
        int stock = market.stock(s.townId(), c.id());

        NamedTextColor nameColor = stock > 0 ? NamedTextColor.YELLOW : NamedTextColor.RED;

        ItemStack it = button(c.material(),
                Component.text(c.id(), nameColor),
                List.of(
                        Component.text("Stock: " + stock, stock > 0 ? NamedTextColor.WHITE : NamedTextColor.RED),
                        Component.text("Buy: " + q.buyEach() + " " + s.currency(), NamedTextColor.GRAY),
                        Component.text("Sell: " + q.sellEach() + " " + s.currency(), NamedTextColor.GRAY),
                        Component.text("Tax: " + s.taxPercentString(), NamedTextColor.DARK_GRAY),
                        Component.text("Click to trade", NamedTextColor.YELLOW)
                )
        );

        ItemMeta meta = it.getItemMeta();
        meta.getPersistentDataContainer().set(KEY_COMMODITY, PersistentDataType.STRING, c.id());
        it.setItemMeta(meta);

        return it;
    }

    private ItemStack infoItem(MarketSession s) {
        return button(Material.PAPER,
                Component.text("Market Info", NamedTextColor.GOLD),
                List.of(
                        Component.text("Burg: " + s.burgName(), NamedTextColor.GRAY),
                        Component.text("Currency: " + s.currency(), NamedTextColor.GRAY),
                        Component.text("Tax: " + s.taxPercentString(), NamedTextColor.GRAY),
                        Component.text("Applies to buy & sell.", NamedTextColor.DARK_GRAY)
                )
        );
    }

    private void qty(Inventory inv, String side, int slot, int qty, int maxAllowed) {
        if (qty > maxAllowed) return;

        Material mat = side.equals("buy") ? Material.LIME_DYE : Material.LIGHT_BLUE_DYE;
        NamedTextColor color = side.equals("buy") ? NamedTextColor.GREEN : NamedTextColor.AQUA;

        ItemStack it = button(mat, Component.text((side.equals("buy") ? "Buy " : "Sell ") + qty, color));
        ItemMeta meta = it.getItemMeta();
        meta.getPersistentDataContainer().set(KEY_SIDE, PersistentDataType.STRING, side);
        meta.getPersistentDataContainer().set(KEY_QTY, PersistentDataType.INTEGER, qty);
        it.setItemMeta(meta);

        inv.setItem(slot, it);
    }

    private void frame(Inventory inv) {
        ItemStack glass = button(Material.BLACK_STAINED_GLASS_PANE, Component.text(" "));
        for (int i = 0; i < SIZE; i++) {
            if (i < 9 || i >= 45) inv.setItem(i, glass);
        }
    }

    private ItemStack button(Material mat, Component name) {
        return button(mat, name, List.of());
    }

    private ItemStack button(Material mat, Component name, List<Component> lore) {
        ItemStack it = new ItemStack(mat);
        ItemMeta meta = it.getItemMeta();
        meta.displayName(name);
        if (lore != null && !lore.isEmpty()) meta.lore(lore);
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES, ItemFlag.HIDE_ENCHANTS);
        it.setItemMeta(meta);
        return it;
    }

    // ---------- Holder / View ----------

    public enum View { MAIN, TRADE }


    public static final class Holder implements InventoryHolder {
        private final View view;
        private final MarketSession session;
        private final String commodityId;
        private final int page;

        public Holder(View view, MarketSession session, String commodityId, int page) {
            this.view = view;
            this.session = session;
            this.commodityId = commodityId;
            this.page = page;
        }

        public View view() { return view; }
        public MarketSession session() { return session; }
        public String commodityId() { return commodityId; }
        public int page() { return page; }

        @Override public Inventory getInventory() { return null; }
    }
}
