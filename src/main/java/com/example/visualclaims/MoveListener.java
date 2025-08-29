package com.example.visualclaims;

import org.bukkit.Chunk;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;

import java.util.*;

public class MoveListener implements Listener {
    private final VisualClaims plugin;
    private final TownManager townManager;
    private final Map<UUID, Boolean> autoclaim = new HashMap<>();
    private final Map<UUID, String> lastTownAt = new HashMap<>();

    public MoveListener(VisualClaims plugin, TownManager townManager) {
        this.plugin = plugin;
        this.townManager = townManager;
    }

    public boolean toggleAutoclaim(UUID uuid) {
        boolean now = !autoclaim.getOrDefault(uuid, false);
        autoclaim.put(uuid, now);
        return now;
    }

    @EventHandler
    public void onMove(PlayerMoveEvent e) {
        if (e.getFrom() == null || e.getTo() == null) return;
        if (e.getFrom().getChunk().equals(e.getTo().getChunk())) return;

        Player p = e.getPlayer();
        Chunk to = e.getTo().getChunk();
        Optional<Town> atTown = townManager.getTownAt(to);
        String nowTown = atTown.map(Town::getName).orElse(null);
        String prevTown = lastTownAt.get(p.getUniqueId());

        if (!Objects.equals(prevTown, nowTown)) {
            if (nowTown != null) p.sendMessage("§7Now entering §a" + nowTown);
            if (prevTown != null && nowTown == null) p.sendMessage("§7Now leaving §c" + prevTown);
            if (prevTown != null && nowTown != null && !prevTown.equals(nowTown)) p.sendMessage("§7Now leaving §c" + prevTown);
            lastTownAt.put(p.getUniqueId(), nowTown);
        }

        if (autoclaim.getOrDefault(p.getUniqueId(), false)) {
            Optional<Town> townOpt = townManager.getTownByOwner(p.getUniqueId());
            if (townOpt.isPresent() && atTown.isEmpty()) {
                int max = plugin.getConfig().getInt("max-claims-per-player", 64);
                boolean bypass = p.hasPermission("visclaims.admin");
                boolean ok = townManager.claimChunk(p.getUniqueId(), to, max, bypass);
                if (ok) {
                    p.sendMessage("§aAuto-claimed chunk (" + to.getX() + ", " + to.getZ() + ")");
                } else if (!bypass && townOpt.get().claimCount() >= max) {
                    p.sendMessage("§cCannot auto-claim chunk: reached max of §e" + max + "§c chunks.");
                } else if (townManager.getTownAt(to).isPresent()) {
                    p.sendMessage("§cCannot auto-claim chunk: already claimed by another town.");
                }
            }
        }

    }
}
