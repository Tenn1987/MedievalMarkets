package com.brandon.medievalmarkets.market.gui;

import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.block.Sign;
import org.bukkit.block.data.Directional;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Locale;

public final class MarketBarrelSignListener implements Listener {

    private final JavaPlugin plugin;
    private final MarketGUI gui;

    public MarketBarrelSignListener(JavaPlugin plugin, MarketGUI gui) {
        this.plugin = plugin;
        this.gui = gui;
    }

    /**
     * IMPORTANT:
     * BaB plot/anti-grief may cancel the interact event before we see it.
     * So we MUST run even if cancelled, otherwise market becomes unusable.
     */
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onInteract(PlayerInteractEvent e) {

        if (e.getHand() != EquipmentSlot.HAND) return;
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK) return;

        Block clicked = e.getClickedBlock();
        if (clicked == null) return;

        Player player = e.getPlayer();

        // CASE 1: Player clicked a BARREL
        if (clicked.getType() == Material.BARREL) {
            if (!hasMarketSign(clicked)) return;

            e.setCancelled(true); // cancel vanilla barrel open
            gui.openMain(player, 0);
            return;
        }

        // CASE 2: Player clicked a SIGN
        if (!isSign(clicked.getType())) return;

        Sign sign;
        try {
            sign = (Sign) clicked.getState();
        } catch (ClassCastException ex) {
            return;
        }

        if (!isMarketSign(sign)) return;

        Block attached = getAttachedBlock(clicked);
        if (attached == null || attached.getType() != Material.BARREL) return;

        e.setCancelled(true);
        gui.openMain(player, 0);
    }

    /* ---------------- helpers ---------------- */

    private boolean isSign(Material m) {
        return m.name().endsWith("_SIGN") || m.name().endsWith("_WALL_SIGN");
    }

    private boolean isMarketSign(Sign sign) {
        for (String line : sign.getLines()) {
            if (line == null) continue;
            if (line.toLowerCase(Locale.ROOT).contains("[market]")) return true;
        }
        return false;
    }

    private boolean hasMarketSign(Block barrel) {
        for (BlockFace face : BlockFace.values()) {
            if (face == BlockFace.SELF) continue;

            Block rel = barrel.getRelative(face);
            if (!isSign(rel.getType())) continue;

            Sign sign;
            try {
                sign = (Sign) rel.getState();
            } catch (ClassCastException ex) {
                continue;
            }

            if (!isMarketSign(sign)) continue;

            Block attached = getAttachedBlock(rel);
            if (attached != null && attached.equals(barrel)) return true;
        }
        return false;
    }

    private Block getAttachedBlock(Block signBlock) {
        if (!(signBlock.getBlockData() instanceof Directional dir)) return null;
        // For wall signs, facing is the direction the sign LOOKS, attached is opposite
        BlockFace attachedFace = dir.getFacing().getOppositeFace();
        return signBlock.getRelative(attachedFace);
    }
}
