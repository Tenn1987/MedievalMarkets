package com.brandon.medievalmarkets.market.gui;

import com.brandon.medievalmarkets.market.MarketService;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.*;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;

public final class MarketGUIListener implements Listener {

    private final MarketGUI gui;

    public MarketGUIListener(MarketGUI gui) {
        this.gui = gui;
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onClick(InventoryClickEvent e) {
        Inventory top = e.getView().getTopInventory();
        InventoryHolder h = top.getHolder();
        if (!(h instanceof MarketGUI.Holder holder)) return;

        // Cancel any interaction with the TOP inventory (prevents removing items)
        if (e.getClickedInventory() != null && e.getClickedInventory().equals(top)) {
            e.setCancelled(true);
        }

        // Also block shift-moves into/out of the GUI
        if (e.getAction() == InventoryAction.MOVE_TO_OTHER_INVENTORY) {
            e.setCancelled(true);
            return;
        }

        // Only handle clicks in the TOP inventory
        if (e.getClickedInventory() == null || !e.getClickedInventory().equals(top)) return;

        Player p = (Player) e.getWhoClicked();
        int slot = e.getSlot();

        // Common buttons
        if (slot == 53) { // Close
            p.closeInventory();
            return;
        }

        // View routing
        if (holder.view() == MarketGUI.View.MAIN) {
            handleMainClick(p, holder, slot, e.getCurrentItem());
        } else {
            handleTradeClick(p, holder, slot, e.getCurrentItem());
        }
    }

    private void handleMainClick(Player p, MarketGUI.Holder holder, int slot, ItemStack clicked) {

        // Top bar buttons (match MarketGUI slots)
        if (slot == 8) { // Refresh
            gui.openMain(p, holder.page());
            return;
        }
        if (slot == 45) { // Prev
            gui.openMain(p, Math.max(0, holder.page() - 1));
            return;
        }
        if (slot == 50) { // Next
            gui.openMain(p, holder.page() + 1);
            return;
        }

        if (clicked == null || clicked.getType().isAir()) return;

        // Commodity click -> open trade screen
        ItemMeta meta = clicked.getItemMeta();
        if (meta == null) return;

        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        String commodityId = pdc.get(gui.KEY_COMMODITY, org.bukkit.persistence.PersistentDataType.STRING);
        if (commodityId == null || commodityId.isBlank()) return;

        gui.openTrade(p, commodityId);
    }

    private void handleTradeClick(Player p, MarketGUI.Holder holder, int slot, ItemStack clicked) {

        // Back button
        if (slot == 0) {
            gui.openMain(p, 0);
            return;
        }

        if (clicked == null || clicked.getType().isAir()) return;

        // Qty buttons use PDC: mm_side + mm_qty
        ItemMeta meta = clicked.getItemMeta();
        if (meta == null) return;

        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        String side = pdc.get(gui.KEY_SIDE, org.bukkit.persistence.PersistentDataType.STRING);
        Integer qty = pdc.get(gui.KEY_QTY, org.bukkit.persistence.PersistentDataType.INTEGER);

        if (side == null || qty == null) return;

        String commodityId = holder.commodityId();
        if (commodityId == null || commodityId.isBlank()) return;

        MarketService market = gui.market();
        String currency = holder.session().currency();

        boolean ok;
        if (side.equalsIgnoreCase("buy")) {
            ok = market.buy(p, commodityId, qty, currency);
        }

         else if (side.equalsIgnoreCase("sell")) {
            ok = market.sell(p, commodityId, qty, currency);
        } else {
            return;
        }

        // Refresh trade screen (keeps it feeling responsive)
        if (ok) {
            gui.openTrade(p, commodityId);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)
    public void onDrag(InventoryDragEvent e) {
        Inventory top = e.getView().getTopInventory();
        InventoryHolder h = top.getHolder();
        if (!(h instanceof MarketGUI.Holder)) return;

        // If any dragged slot is in the TOP inventory, cancel
        for (int rawSlot : e.getRawSlots()) {
            if (rawSlot < top.getSize()) {
                e.setCancelled(true);
                return;
            }
        }
    }
}
