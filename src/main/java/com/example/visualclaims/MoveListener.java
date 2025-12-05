package com.example.visualclaims;

import org.bukkit.Chunk;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.util.*;

public class MoveListener implements Listener {
    private final VisualClaims plugin;
    private final TownManager townManager;
    private final Map<UUID, Boolean> autoclaim = new HashMap<>();
    private final Map<UUID, Boolean> autohistory = new HashMap<>();
    private final Map<UUID, String> lastTownAt = new HashMap<>();
    private final Map<UUID, UUID> lastTownOwner = new HashMap<>();
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

    public boolean toggleAutohistory(UUID uuid) {
        boolean now = !autohistory.getOrDefault(uuid, false);
        autohistory.put(uuid, now);
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

        // Disable auto modes on teleport
        UUID id = e.getPlayer().getUniqueId();
        if (autoclaim.remove(id) != null) e.getPlayer().sendMessage("§cAutoclaim disabled (teleport).");
        if (autohistory.remove(id) != null) e.getPlayer().sendMessage("§cAutohistory disabled (teleport).");

        handleChunkChange(e.getPlayer(), to);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        autoclaim.remove(id);
        autohistory.remove(id);
        lastTownAt.remove(id);
        lastTownOwner.remove(id);
        lastChunkId.remove(id);
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        townManager.applyScoreboard(e.getPlayer());
    }

    private void handleChunkChange(Player p, Chunk to) {
        ChunkPos pos = new ChunkPos(to.getWorld().getName(), to.getX(), to.getZ());
        String id = pos.id();
        if (id.equals(lastChunkId.get(p.getUniqueId()))) return;

        lastChunkId.put(p.getUniqueId(), id);
        Optional<Town> atTown = townManager.getTownAt(to);
        updateTownPresence(p, atTown);
        handleAutoclaim(p, to, atTown);
        handleAutohistory(p, pos);
    }

    private void updateTownPresence(Player p, Optional<Town> atTown) {
        UUID uuid = p.getUniqueId();
        Town currentTown = atTown.orElse(null);
        UUID currentOwner = currentTown != null ? currentTown.getOwner() : null;
        UUID prevOwner = lastTownOwner.get(uuid);
        String prevTownName = lastTownAt.get(uuid);
        Town prevTown = prevOwner == null ? null : townManager.getTownByOwner(prevOwner).orElse(null);
        if (prevTown == null && prevTownName != null) {
            prevTown = townManager.findTown(prevTownName).orElse(null);
        }

        if (!Objects.equals(prevOwner, currentOwner)) {
            if (prevTown != null || prevTownName != null) {
                String label = townManager.coloredTownName(prevOwner, prevTownName);
                p.sendMessage("§7Now leaving " + label);
            }
            if (currentTown != null) {
                p.sendMessage("§7Now entering " + townManager.coloredTownName(currentTown));
                notifyTownEntry(p, currentTown);
                lastTownAt.put(uuid, currentTown.getName());
                lastTownOwner.put(uuid, currentOwner);
            } else {
                lastTownAt.remove(uuid);
                lastTownOwner.remove(uuid);
            }
        }
    }

    private void handleAutoclaim(Player p, Chunk to, Optional<Town> atTown) {
        if (!autoclaim.getOrDefault(p.getUniqueId(), false)) return;

        Optional<Town> townOpt = townManager.getTownOf(p.getUniqueId());
        if (townOpt.isEmpty() || atTown.isPresent()) return;

        Town t = townOpt.get();
        int max = townManager.computeMaxClaims(t.getOwner());
        boolean bypass = p.hasPermission("visclaims.admin");
        boolean ok = townManager.claimChunk(t, to, bypass);
        if (ok) {
            p.sendMessage("§aAuto-claimed chunk (" + to.getX() + ", " + to.getZ() + ")");
        } else if (!bypass && townOpt.get().claimCount() >= max) {
            p.sendMessage("§cCannot auto-claim chunk: reached max of §e" + max + "§c chunks.");
        } else if (townManager.getTownAt(to).isPresent()) {
            p.sendMessage("§cCannot auto-claim chunk: already claimed by another town.");
        }
    }

    private void handleAutohistory(Player p, ChunkPos pos) {
        if (!autohistory.getOrDefault(p.getUniqueId(), false)) return;
        List<ChunkHistoryEntry> entries = townManager.getHistoryFor(pos);
        if (entries.isEmpty()) {
            p.sendMessage("§7History: none for this chunk.");
            return;
        }
        int shown = Math.min(2, entries.size());
        StringBuilder sb = new StringBuilder("§7History: ");
        for (int i = 0; i < shown; i++) {
            ChunkHistoryEntry e = entries.get(i);
            if (i > 0) sb.append(" §8| ");
            String townLabel = townManager.coloredTownName(e.getTownOwner(), e.getTownName());
            sb.append(e.getAction()).append(" ").append(townLabel).append(" (").append(formatAgo(e.getTimestamp())).append(")");
        }
        p.sendMessage(sb.toString());
    }

    private void notifyTownEntry(Player entrant, Town town) {
        Optional<Town> entrantTown = townManager.getTownOf(entrant.getUniqueId());
        if (entrantTown.isPresent() && entrantTown.get().getOwner().equals(town.getOwner())) return;
        String msg = "§e" + entrant.getName() + " §7entered " + townManager.coloredTownName(town) + "§7 territory.";
        townManager.messageTown(town, msg);
    }

    private String formatAgo(long timestamp) {
        long diff = Math.max(0, System.currentTimeMillis() - timestamp);
        long minutes = diff / 60000;
        if (minutes < 1) return "just now";
        if (minutes < 60) return minutes + "m ago";
        long hours = minutes / 60;
        if (hours < 24) return hours + "h ago";
        long days = hours / 24;
        return days + "d ago";
    }
}
