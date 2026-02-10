package com.brandon.medievalmarkets.market.gui;

import org.bukkit.ChatColor;
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

import java.util.Locale;

public final class MarketBarrelSignListener implements Listener {

    private final MarketGUI gui;

    public MarketBarrelSignListener(MarketGUI gui) {
        this.gui = gui;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onRightClickBarrel(PlayerInteractEvent e) {
        // Main hand only (prevents double-open)
        if (e.getHand() != EquipmentSlot.HAND) return;

        if (e.getAction() != Action.RIGHT_CLICK_BLOCK) return;

        Block barrel = e.getClickedBlock();
        if (barrel == null || barrel.getType() != Material.BARREL) return;

        Player p = e.getPlayer();

        // Only open GUI if there is a correctly-labeled sign attached to this barrel
        if (!hasMarketSign(barrel)) return;

        e.setCancelled(true);
        gui.openMain(p);
    }

    private boolean hasMarketSign(Block barrel) {
        // 1) Wall signs attached to the barrel (on any side)
        for (BlockFace face : new BlockFace[]{BlockFace.NORTH, BlockFace.SOUTH, BlockFace.EAST, BlockFace.WEST}) {
            Block maybeSign = barrel.getRelative(face);
            if (isWallSign(maybeSign) && isWallSignAttachedTo(maybeSign, barrel) && signSaysMarket(maybeSign)) {
                return true;
            }
        }

        // 2) Standing sign on top of the barrel (rare but valid for "barrel with a sign")
        Block above = barrel.getRelative(BlockFace.UP);
        if (isStandingSign(above) && signSaysMarket(above)) {
            return true;
        }

        return false;
    }

    private boolean isWallSign(Block b) {
        Material t = b.getType();
        // Covers OAK_WALL_SIGN, SPRUCE_WALL_SIGN, etc.
        return t.name().endsWith("_WALL_SIGN");
    }

    private boolean isStandingSign(Block b) {
        Material t = b.getType();
        // Covers OAK_SIGN, SPRUCE_SIGN, etc. (not wall sign)
        return t.name().endsWith("_SIGN") && !t.name().endsWith("_WALL_SIGN");
    }

    private boolean isWallSignAttachedTo(Block signBlock, Block barrel) {
        // A wall sign is "attached" to the block behind it (opposite its facing)
        if (!(signBlock.getBlockData() instanceof Directional dir)) return false;

        BlockFace facing = dir.getFacing();
        BlockFace attachedFace = facing.getOppositeFace();
        Block attachedTo = signBlock.getRelative(attachedFace);

        return attachedTo.equals(barrel);
    }

    private boolean signSaysMarket(Block signBlock) {
        if (!(signBlock.getState() instanceof Sign sign)) return false;

        // Legacy API (works fine on Paper/Spigot 1.19+)
        String line0 = sign.getLine(0);
        if (line0 == null) return false;

        String cleaned = ChatColor.stripColor(line0);
        if (cleaned == null) cleaned = line0;

        cleaned = cleaned.trim().toLowerCase(Locale.ROOT);

        return cleaned.equals("[market]");
    }
}
