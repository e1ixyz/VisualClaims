package com.example.visualclaims;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.bukkit.Bukkit;
import org.bukkit.Chunk;
import org.bukkit.OfflinePlayer;
import org.bukkit.Statistic;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.util.*;

public class TownManager {
    private final VisualClaims plugin;
    private final DynmapHook dynmap;
    private final File townsDir;
    private final Gson gson;

    // ownerUUID -> Town
    private final Map<UUID, Town> townsByOwner = new HashMap<>();
    // chunkId -> Town
    private final Map<String, Town> townsByChunkId = new HashMap<>();

    public TownManager(VisualClaims plugin, DynmapHook dynmap) {
        this.plugin = plugin;
        this.dynmap = dynmap;
        this.townsDir = new File(plugin.getDataFolder(), "towns");
        if (!townsDir.exists()) townsDir.mkdirs();
        this.gson = new GsonBuilder().setPrettyPrinting().create();
    }

    public Optional<Town> getTownByOwner(UUID owner) { return Optional.ofNullable(townsByOwner.get(owner)); }
    public Optional<Town> getTownAt(Chunk c) {
        return Optional.ofNullable(townsByChunkId.get(new ChunkPos(c.getWorld().getName(), c.getX(), c.getZ()).id()));
    }

    public boolean createTown(UUID owner, String name, VanillaColor color, String world) {
        if (townsByOwner.containsKey(owner)) return false;
        Town t = new Town(owner, name, world, color.name());
        townsByOwner.put(owner, t);
        saveTown(t);
        return true;
    }

    public boolean deleteTown(UUID owner) {
        Town t = townsByOwner.remove(owner);
        if (t == null) return false;
        for (ChunkPos pos : t.getClaims()) {
            townsByChunkId.remove(pos.id());
            if (dynmap != null) dynmap.removeAreaMarker(pos);
        }
        File f = new File(townsDir, owner.toString() + ".json");
        if (f.exists()) f.delete();
        return true;
    }

    public boolean claimChunk(UUID owner, Chunk chunk, boolean bypass) {
        Town t = townsByOwner.get(owner);
        if (t == null) return false;
        if (!bypass && t.claimCount() >= computeMaxClaims(owner)) return false;
        ChunkPos pos = ChunkPos.of(chunk);
        if (townsByChunkId.containsKey(pos.id())) return false;
        boolean ok = t.addClaim(pos);
        if (!ok) return false;
        townsByChunkId.put(pos.id(), t);
        saveTown(t);
        if (dynmap != null) dynmap.addOrUpdateChunkArea(t, pos);
        return true;
    }

    public boolean unclaimChunk(UUID owner, ChunkPos pos) {
        Town t = townsByOwner.get(owner);
        if (t == null) return false;
        if (!t.ownsChunk(pos)) return false;

        t.removeClaim(pos);
        townsByChunkId.remove(pos.id());
        saveTown(t);
        if (dynmap != null) dynmap.removeAreaMarker(pos);
        return true;
    }

    // ✅ Admin-only force unclaim
    public boolean forceUnclaim(ChunkPos pos) {
        Town t = townsByChunkId.remove(pos.id());
        if (t != null) {
            t.removeClaim(pos);
            saveTown(t);
            if (dynmap != null) dynmap.removeAreaMarker(pos);
            return true;
        }
        return false;
    }

    public int computeMaxClaims(UUID owner) {
        int baseMax = plugin.getConfig().getInt("max-claims-per-player", 64);
        Town t = townsByOwner.get(owner);
        int bonus = (t != null) ? t.getBonusChunks() : 0;

        if (!plugin.getConfig().getBoolean("use-playtime-scaling", false)) {
            return Math.max(baseMax + bonus, t != null ? t.claimCount() : 0);
        }

        int chunksPerHour = Math.max(1, plugin.getConfig().getInt("chunks-per-hour", 2));
        int hours = getPlaytimeHours(owner);
        int dynamic = hours * chunksPerHour;

        int limit = Math.max(baseMax, dynamic) + bonus;
        int currentClaims = (t != null) ? t.claimCount() : 0;
        return Math.max(limit, currentClaims);
    }

    public int getPlaytimeHours(UUID owner) {
        OfflinePlayer op = Bukkit.getOfflinePlayer(owner);
        int ticks = 0;
        try {
            ticks = op.getStatistic(Statistic.PLAY_ONE_MINUTE);
        } catch (IllegalArgumentException ignored) {
            // Offline player without data; leave at zero.
        }
        long totalSeconds = (long) ticks / 20L;
        return (int) (totalSeconds / 3600L);
    }

    public boolean adjustBonus(UUID owner, int delta) {
        Town t = townsByOwner.get(owner);
        if (t == null) return false;
        t.addBonusChunks(delta);
        saveTown(t);
        return true;
    }

    public void saveTown(Town t) {
        try {
            File out = new File(townsDir, t.getOwner().toString() + ".json");
            try (FileWriter w = new FileWriter(out)) {
                gson.toJson(t, w);
            }
        } catch (Exception ex) {
            plugin.getLogger().severe("Failed to save town " + t.getName() + ": " + ex.getMessage());
        }
    }

    public void saveAll() {
        for (Town t : townsByOwner.values()) saveTown(t);
    }

    public void loadAll() {
        townsByOwner.clear();
        townsByChunkId.clear();
        File[] files = townsDir.listFiles((d, name) -> name.endsWith(".json"));
        if (files == null) return;
        for (File f : files) {
            try (FileReader r = new FileReader(f)) {
                Town t = gson.fromJson(r, Town.class);
                if (t != null && t.getOwner() != null) {
                    townsByOwner.put(t.getOwner(), t);
                    for (ChunkPos pos : t.getClaims()) {
                        townsByChunkId.put(pos.id(), t);
                    }
                    if (dynmap != null) dynmap.refreshTownAreas(t);
                }
            } catch (Exception ex) {
                plugin.getLogger().warning("Failed to load town file " + f.getName() + ": " + ex.getMessage());
            }
        }
    }

    public Collection<Town> allTowns() { return townsByOwner.values(); }
}
