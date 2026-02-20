package com.brandon.medievalmarkets.market.gui;

import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.block.TileState;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * Right-click a marked chest/barrel to open Market GUI.
 * Marking is stored on the container using PDC so it survives restarts.
 *
 * Optional: Sneak + right-click with STICK toggles "Market" marker (admin-friendly).
 */
public final class MarketBlockListener implements Listener {

    private final MarketGUI gui;
    private final NamespacedKey keyMarket;

    public MarketBlockListener(JavaPlugin plugin, MarketGUI gui) {
        this.gui = gui;
        this.keyMarket = new NamespacedKey(plugin, "market_container");
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInteract(PlayerInteractEvent e) {
        // Only main-hand to avoid double-trigger
        if (e.getHand() != EquipmentSlot.HAND) return;

        if (e.getAction() != Action.RIGHT_CLICK_BLOCK) return;

        Block b = e.getClickedBlock();
        if (b == null) return;

        Material type = b.getType();
        boolean isContainer = (type == Material.CHEST || type == Material.BARREL);
        if (!isContainer) return;

        Player p = e.getPlayer();

        // Optional: toggle marker with sneak + stick
        if (p.isSneaking() && isHolding(p, Material.STICK)) {
            if (!(b.getState() instanceof TileState ts)) return;

            boolean nowMarked = toggleMarketMarker(ts);
            e.setCancelled(true);

            if (nowMarked) {
                p.sendMessage(ChatColor.GREEN + "Marked container as Market.");
            } else {
                p.sendMessage(ChatColor.YELLOW + "Unmarked container as Market.");
            }
            return;
        }

        // Normal click: only open GUI if marked
        if (!(b.getState() instanceof TileState ts)) return;

        if (!isMarketMarked(ts)) return; // let vanilla chest/barrel open normally

        // This is a Market container -> open GUI instead of storage
        e.setCancelled(true);
        gui.openMain(p);
    }

    private boolean isHolding(Player p, Material mat) {
        ItemStack it = p.getInventory().getItemInMainHand();
        return it != null && it.getType() == mat;
    }

    private boolean isMarketMarked(TileState ts) {
        PersistentDataContainer pdc = ts.getPersistentDataContainer();
        Byte val = pdc.get(keyMarket, PersistentDataType.BYTE);
        return val != null && val == (byte) 1;
    }

    private boolean toggleMarketMarker(TileState ts) {
        PersistentDataContainer pdc = ts.getPersistentDataContainer();
        boolean currently = isMarketMarked(ts);
        if (currently) {
            pdc.remove(keyMarket);
        } else {
            pdc.set(keyMarket, PersistentDataType.BYTE, (byte) 1);
        }
        ts.update(true);
        return !currently;
    }
}
