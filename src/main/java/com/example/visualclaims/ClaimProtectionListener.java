package com.example.visualclaims;

import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.TNTPrimed;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.BlockSpreadEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;

import java.util.Iterator;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public class ClaimProtectionListener implements Listener {
    private final VisualClaims plugin;
    private final TownManager towns;

    public ClaimProtectionListener(VisualClaims plugin, TownManager towns) {
        this.plugin = plugin;
        this.towns = towns;
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockIgnite(BlockIgniteEvent event) {
        if (!configEnabled("prevent-fire", true)) return;
        Block target = event.getBlock();
        if (target == null) return;

        Player player = event.getPlayer();
        if (player != null) {
            if (isProtected(target, player)) {
                event.setCancelled(true);
                player.sendMessage("§cYou cannot light fires in another town's land.");
            }
            return;
        }

        if (event.getCause() == BlockIgniteEvent.IgniteCause.SPREAD) return;
        if (!isClaimed(target)) return;
        Block source = event.getIgnitingBlock();
        if (source != null && sameTown(source, target)) return;
        event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockSpread(BlockSpreadEvent event) {
        if (!configEnabled("prevent-fire-spread", true)) return;
        if (event.getNewState() == null || event.getNewState().getType() != Material.FIRE) return;
        Block target = event.getBlock();
        if (target == null || !isClaimed(target)) return;
        Block source = event.getSource();
        if (source != null && sameTown(source, target)) return;
        event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onBucketEmpty(PlayerBucketEmptyEvent event) {
        if (!configEnabled("prevent-lava", true)) return;
        if (event.getBucket() != Material.LAVA_BUCKET) return;
        Block clicked = event.getBlockClicked();
        if (clicked == null) return;
        Block target = clicked.getRelative(event.getBlockFace());
        if (isProtected(target, event.getPlayer())) {
            event.setCancelled(true);
            event.getPlayer().sendMessage("§cYou cannot place lava in another town's land.");
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onLavaFlow(BlockFromToEvent event) {
        if (!configEnabled("prevent-lava-flow", true)) return;
        if (event.getBlock() == null || event.getToBlock() == null) return;
        if (event.getBlock().getType() != Material.LAVA) return;
        Block to = event.getToBlock();
        if (!isClaimed(to)) return;
        if (sameTown(event.getBlock(), to)) return;
        event.setCancelled(true);
    }

    @EventHandler(ignoreCancelled = true)
    public void onTntPlace(BlockPlaceEvent event) {
        if (!configEnabled("prevent-tnt", true)) return;
        if (event.getBlockPlaced().getType() != Material.TNT) return;
        if (isProtected(event.getBlockPlaced(), event.getPlayer())) {
            event.setCancelled(true);
            event.getPlayer().sendMessage("§cYou cannot place TNT in another town's land.");
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onTntExplode(EntityExplodeEvent event) {
        if (!configEnabled("prevent-tnt-explosions", true)) return;
        Entity entity = event.getEntity();
        if (entity == null) return;
        if (!(entity instanceof TNTPrimed)) return;
        Player source = null;
        TNTPrimed tnt = (TNTPrimed) entity;
        Entity src = tnt.getSource();
        if (src instanceof Player) source = (Player) src;
        Iterator<Block> it = event.blockList().iterator();
        while (it.hasNext()) {
            Block block = it.next();
            if (block == null) continue;
            if (isProtected(block, source)) it.remove();
        }
    }

    private boolean isClaimed(Block block) {
        return getTownAt(block).isPresent();
    }

    private Optional<Town> getTownAt(Block block) {
        if (block == null) return Optional.empty();
        return towns.getTownAt(block.getChunk());
    }

    private boolean isProtected(Block block, Player player) {
        Optional<Town> tOpt = getTownAt(block);
        if (tOpt.isEmpty()) return false;
        if (player == null) return true;
        if (player.hasPermission("visclaims.admin")) return false;
        return !tOpt.get().isMember(player.getUniqueId());
    }

    private boolean sameTown(Block a, Block b) {
        UUID aOwner = getTownAt(a).map(Town::getOwner).orElse(null);
        UUID bOwner = getTownAt(b).map(Town::getOwner).orElse(null);
        return Objects.equals(aOwner, bOwner) && aOwner != null;
    }

    private boolean configEnabled(String key, boolean def) {
        return plugin.getConfig().getBoolean("claim-protection." + key, def);
    }
}
