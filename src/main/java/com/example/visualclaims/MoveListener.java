package com.example.visualclaims;

import org.bukkit.Chunk;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.*;

public class MoveListener implements Listener {
    private final VisualClaims plugin;
    private final TownManager townManager;
    private final Map<UUID, Boolean> autoclaim = new HashMap<>();
    private final Map<UUID, String> lastTownAt = new HashMap<>();
    private final Map<UUID, String> lastChunkId = new HashMap<>();

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
        if (e.isCancelled()) return;
        if (e.getFrom() == null || e.getTo() == null) return;
        Chunk from = e.getFrom().getChunk();
        Chunk to = e.getTo().getChunk();
        if (from.equals(to)) return;

        handleChunkChange(e.getPlayer(), to);
    }

    @EventHandler
    public void onTeleport(PlayerTeleportEvent e) {
        if (e.isCancelled()) return;
        if (e.getFrom() == null || e.getTo() == null) return;
        Chunk from = e.getFrom().getChunk();
        Chunk to = e.getTo().getChunk();
        if (from.equals(to)) return;

        handleChunkChange(e.getPlayer(), to);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        autoclaim.remove(id);
        lastTownAt.remove(id);
        lastChunkId.remove(id);
    }

    private void handleChunkChange(Player p, Chunk to) {
        String id = new ChunkPos(to.getWorld().getName(), to.getX(), to.getZ()).id();
        if (id.equals(lastChunkId.get(p.getUniqueId()))) return;

        lastChunkId.put(p.getUniqueId(), id);
        Optional<Town> atTown = townManager.getTownAt(to);
        updateTownPresence(p, atTown);
        handleAutoclaim(p, to, atTown);
    }

    private void updateTownPresence(Player p, Optional<Town> atTown) {
        UUID uuid = p.getUniqueId();
        String nowTown = atTown.map(Town::getName).orElse(null);
        String prevTown = lastTownAt.get(uuid);

        if (!Objects.equals(prevTown, nowTown)) {
            if (nowTown != null) p.sendMessage("§7Now entering §a" + nowTown);
            if (prevTown != null && nowTown == null) p.sendMessage("§7Now leaving §c" + prevTown);
            if (prevTown != null && nowTown != null && !prevTown.equals(nowTown)) p.sendMessage("§7Now leaving §c" + prevTown);
            lastTownAt.put(uuid, nowTown);
        }
    }

    private void handleAutoclaim(Player p, Chunk to, Optional<Town> atTown) {
        if (!autoclaim.getOrDefault(p.getUniqueId(), false)) return;

        Optional<Town> townOpt = townManager.getTownByOwner(p.getUniqueId());
        if (townOpt.isEmpty() || atTown.isPresent()) return;

        int max = townManager.computeMaxClaims(p.getUniqueId());
        boolean bypass = p.hasPermission("visclaims.admin");
        boolean ok = townManager.claimChunk(p.getUniqueId(), to, bypass);
        if (ok) {
            p.sendMessage("§aAuto-claimed chunk (" + to.getX() + ", " + to.getZ() + ")");
        } else if (!bypass && townOpt.get().claimCount() >= max) {
            p.sendMessage("§cCannot auto-claim chunk: reached max of §e" + max + "§c chunks.");
        } else if (townManager.getTownAt(to).isPresent()) {
            p.sendMessage("§cCannot auto-claim chunk: already claimed by another town.");
        }
    }
}
