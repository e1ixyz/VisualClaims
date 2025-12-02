package com.example.visualclaims;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Chunk;
import org.bukkit.OfflinePlayer;
import org.bukkit.Statistic;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.ScoreboardManager;

import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.lang.reflect.Type;
import java.util.*;

public class TownManager {
    private final VisualClaims plugin;
    private final DynmapHook dynmap;
    private final File townsDir;
    private final File historyFile;
    private final Gson gson;

    private static final int HISTORY_LIMIT = 10;
    private static final long INVITE_TTL_MS = 10 * 60 * 1000L;
    private static final long ALLIANCE_INVITE_TTL_MS = 10 * 60 * 1000L;

    // ownerUUID -> Town
    private final Map<UUID, Town> townsByOwner = new HashMap<>();
    // owner or member UUID -> Town
    private final Map<UUID, Town> townsByMember = new HashMap<>();
    // chunkId -> Town
    private final Map<String, Town> townsByChunkId = new HashMap<>();
    // pending invites to join towns: target -> invite
    private final Map<UUID, TownInvite> pendingInvites = new HashMap<>();
    // pending alliance invites: targetOwner -> invite
    private final Map<UUID, AllianceInvite> pendingAllianceInvites = new HashMap<>();
    // chunkId -> history entries (newest first)
    private final Map<String, List<ChunkHistoryEntry>> chunkHistory = new HashMap<>();

    private Scoreboard warBoard;
    private Objective warObjective;

    public TownManager(VisualClaims plugin, DynmapHook dynmap) {
        this.plugin = plugin;
        this.dynmap = dynmap;
        this.townsDir = new File(plugin.getDataFolder(), "towns");
        if (!townsDir.exists()) townsDir.mkdirs();
        this.historyFile = new File(plugin.getDataFolder(), "history.json");
        this.gson = new GsonBuilder().setPrettyPrinting().create();
    }

    public Optional<Town> getTownByOwner(UUID owner) { return Optional.ofNullable(townsByOwner.get(owner)); }
    public Optional<Town> getTownOf(UUID uuid) { return Optional.ofNullable(townsByMember.get(uuid)); }
    public Optional<Town> getTownAt(Chunk c) {
        return Optional.ofNullable(townsByChunkId.get(new ChunkPos(c.getWorld().getName(), c.getX(), c.getZ()).id()));
    }

    public Optional<Town> findTown(String query) {
        if (query == null || query.isBlank()) return Optional.empty();
        String q = query.trim().toLowerCase(Locale.ROOT);
        for (Town t : townsByOwner.values()) {
            if (t.getName() != null && t.getName().trim().toLowerCase(Locale.ROOT).equals(q)) return Optional.of(t);
            OfflinePlayer op = Bukkit.getOfflinePlayer(t.getOwner());
            String name = op.getName();
            if (name != null && name.trim().toLowerCase(Locale.ROOT).equals(q)) return Optional.of(t);
            if (t.getOwner().toString().equalsIgnoreCase(query)) return Optional.of(t);
        }
        return Optional.empty();
    }

    public boolean createTown(UUID owner, String name, VanillaColor color, String world) {
        if (townsByMember.containsKey(owner)) return false;
        Town t = new Town(owner, name, world, color.name());
        townsByOwner.put(owner, t);
        indexTown(t);
        saveTown(t);
        refreshWarScoreboard();
        return true;
    }

    public boolean deleteTown(UUID owner) {
        Town t = townsByOwner.remove(owner);
        if (t == null) return false;

        for (ChunkPos pos : new HashSet<>(t.getClaims())) {
            townsByChunkId.remove(pos.id());
            if (dynmap != null) dynmap.removeAreaMarker(pos);
            recordHistory(pos, "DELETE", t);
        }

        townsByMember.remove(owner);
        for (UUID m : new HashSet<>(t.getMembers())) townsByMember.remove(m);

        for (Town other : townsByOwner.values()) {
            if (other.getAllies().remove(owner) | other.getWars().remove(owner)) {
                saveTown(other);
            }
        }

        pendingInvites.entrySet().removeIf(e -> e.getValue().getTownOwner().equals(owner));
        pendingAllianceInvites.entrySet().removeIf(e -> e.getValue().getFromOwner().equals(owner) || e.getValue().getToOwner().equals(owner));

        File f = new File(townsDir, owner.toString() + ".json");
        if (f.exists()) f.delete();
        refreshWarScoreboard();
        return true;
    }

    public boolean claimChunk(Town t, Chunk chunk, boolean bypass) {
        if (t == null) return false;
        if (!bypass && t.claimCount() >= computeMaxClaims(t.getOwner())) return false;
        ChunkPos pos = ChunkPos.of(chunk);
        if (townsByChunkId.containsKey(pos.id())) return false;
        boolean ok = t.addClaim(pos);
        if (!ok) return false;
        townsByChunkId.put(pos.id(), t);
        saveTown(t);
        if (dynmap != null) dynmap.addOrUpdateChunkArea(t, pos);
        recordHistory(pos, "CLAIM", t);
        return true;
    }

    public boolean unclaimChunk(Town t, ChunkPos pos) {
        if (t == null) return false;
        if (!t.ownsChunk(pos)) return false;

        t.removeClaim(pos);
        townsByChunkId.remove(pos.id());
        saveTown(t);
        recordHistory(pos, "UNCLAIM", t);
        if (dynmap != null) dynmap.removeAreaMarker(pos);
        return true;
    }

    // Admin-only force unclaim
    public boolean forceUnclaim(ChunkPos pos) {
        Town t = townsByChunkId.remove(pos.id());
        if (t != null) {
            t.removeClaim(pos);
            saveTown(t);
            recordHistory(pos, "FORCE-UNCLAIM", t);
            if (dynmap != null) dynmap.removeAreaMarker(pos);
            return true;
        }
        return false;
    }

    public boolean invitePlayer(UUID owner, UUID target) {
        Town t = townsByOwner.get(owner);
        if (t == null) return false;
        if (townsByMember.containsKey(target)) return false;
        pendingInvites.put(target, new TownInvite(owner, System.currentTimeMillis()));
        return true;
    }

    public Optional<Town> acceptInvite(UUID player, String hint) {
        TownInvite invite = pendingInvites.get(player);
        long now = System.currentTimeMillis();
        if (invite == null) return Optional.empty();
        if (invite.isExpired(now, INVITE_TTL_MS)) {
            pendingInvites.remove(player);
            return Optional.empty();
        }
        Town t = townsByOwner.get(invite.getTownOwner());
        if (t == null) {
            pendingInvites.remove(player);
            return Optional.empty();
        }
        if (townsByMember.containsKey(player)) return Optional.empty();
        if (hint != null && !hint.isBlank()) {
            Optional<Town> match = findTown(hint);
            if (match.isPresent() && !match.get().getOwner().equals(t.getOwner())) return Optional.empty();
        }
        t.addMember(player);
        townsByMember.put(player, t);
        pendingInvites.remove(player);
        saveTown(t);
        return Optional.of(t);
    }

    public boolean removeMember(UUID owner, UUID member) {
        Town t = townsByOwner.get(owner);
        if (t == null) return false;
        boolean removed = t.removeMember(member);
        if (removed) {
            townsByMember.remove(member);
            saveTown(t);
        }
        return removed;
    }

    public boolean sendAllianceInvite(UUID owner, UUID targetOwner) {
        Town a = townsByOwner.get(owner);
        Town b = townsByOwner.get(targetOwner);
        if (a == null || b == null) return false;
        if (owner.equals(targetOwner)) return false;
        if (a.getAllies().contains(targetOwner)) return false;
        pendingAllianceInvites.put(targetOwner, new AllianceInvite(owner, targetOwner, System.currentTimeMillis()));
        return true;
    }

    public boolean acceptAlliance(UUID owner, UUID fromOwner) {
        AllianceInvite invite = pendingAllianceInvites.get(owner);
        long now = System.currentTimeMillis();
        if (invite == null || !invite.getFromOwner().equals(fromOwner)) return false;
        if (invite.isExpired(now, ALLIANCE_INVITE_TTL_MS)) {
            pendingAllianceInvites.remove(owner);
            return false;
        }
        Town a = townsByOwner.get(invite.getFromOwner());
        Town b = townsByOwner.get(invite.getToOwner());
        if (a == null || b == null) {
            pendingAllianceInvites.remove(owner);
            return false;
        }
        if (a.getAllies().contains(b.getOwner())) {
            pendingAllianceInvites.remove(owner);
            return true;
        }
        a.addAlly(b.getOwner());
        b.addAlly(a.getOwner());
        saveTown(a);
        saveTown(b);
        pendingAllianceInvites.remove(owner);
        refreshWarScoreboard();
        return true;
    }

    public boolean removeAlliance(UUID owner, UUID otherOwner) {
        Town a = townsByOwner.get(owner);
        Town b = townsByOwner.get(otherOwner);
        if (a == null || b == null) return false;
        boolean changed = a.getAllies().remove(otherOwner) | b.getAllies().remove(owner);
        if (changed) {
            saveTown(a);
            saveTown(b);
            refreshWarScoreboard();
        }
        return changed;
    }

    public boolean toggleWar(UUID owner, UUID targetOwner) {
        Town a = townsByOwner.get(owner);
        Town b = townsByOwner.get(targetOwner);
        if (a == null || b == null) return false;
        if (owner.equals(targetOwner)) return false;
        if (a.getWars().contains(targetOwner)) {
            a.removeWar(targetOwner);
            b.removeWar(owner);
        } else {
            a.addWar(targetOwner);
            b.addWar(owner);
        }
        saveTown(a);
        saveTown(b);
        refreshWarScoreboard();
        return true;
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

    private void indexTown(Town t) {
        townsByMember.put(t.getOwner(), t);
        if (t.getMembers() != null) {
            for (UUID m : t.getMembers()) townsByMember.put(m, t);
        }
        if (t.getClaims() != null) {
            for (ChunkPos pos : t.getClaims()) townsByChunkId.put(pos.id(), t);
        }
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
        saveHistory();
    }

    public void loadAll() {
        townsByOwner.clear();
        townsByChunkId.clear();
        townsByMember.clear();
        File[] files = townsDir.listFiles((d, name) -> name.endsWith(".json"));
        if (files != null) {
            for (File f : files) {
                try (FileReader r = new FileReader(f)) {
                    Town t = gson.fromJson(r, Town.class);
                    if (t != null && t.getOwner() != null) {
                        townsByOwner.put(t.getOwner(), t);
                        indexTown(t);
                        if (dynmap != null) dynmap.refreshTownAreas(t);
                    }
                } catch (Exception ex) {
                    plugin.getLogger().warning("Failed to load town file " + f.getName() + ": " + ex.getMessage());
                }
            }
        }
        loadHistory();
        refreshWarScoreboard();
    }

    private void loadHistory() {
        chunkHistory.clear();
        if (!historyFile.exists()) return;
        try (FileReader reader = new FileReader(historyFile)) {
            Type type = new TypeToken<Map<String, List<ChunkHistoryEntry>>>(){}.getType();
            Map<String, List<ChunkHistoryEntry>> data = gson.fromJson(reader, type);
            if (data != null) chunkHistory.putAll(data);
        } catch (Exception ex) {
            plugin.getLogger().warning("Failed to load history: " + ex.getMessage());
        }
    }

    public void saveHistory() {
        try (FileWriter writer = new FileWriter(historyFile)) {
            gson.toJson(chunkHistory, writer);
        } catch (Exception ex) {
            plugin.getLogger().warning("Failed to save history: " + ex.getMessage());
        }
    }

    public List<ChunkHistoryEntry> getHistoryFor(ChunkPos pos) {
        return chunkHistory.getOrDefault(pos.id(), Collections.emptyList());
    }

    private void recordHistory(ChunkPos pos, String action, Town t) {
        List<ChunkHistoryEntry> list = chunkHistory.computeIfAbsent(pos.id(), k -> new ArrayList<>());
        List<String> allies = t == null ? Collections.emptyList() : resolveNames(t.getAllies());
        List<String> wars = t == null ? Collections.emptyList() : resolveNames(t.getWars());
        list.add(0, new ChunkHistoryEntry(System.currentTimeMillis(), action, t == null ? "Unclaimed" : t.getName(), t == null ? null : t.getOwner(), allies, wars));
        if (list.size() > HISTORY_LIMIT) {
            while (list.size() > HISTORY_LIMIT) list.remove(list.size() - 1);
        }
        saveHistory();
    }

    public Collection<Town> allTowns() { return townsByOwner.values(); }

    public void messageTown(Town t, String msg) {
        if (t == null) return;
        Set<UUID> targets = new HashSet<>();
        targets.add(t.getOwner());
        targets.addAll(t.getMembers());
        for (UUID id : targets) {
            Player p = Bukkit.getPlayer(id);
            if (p != null && p.isOnline()) p.sendMessage(msg);
        }
    }

    public List<String> buildWarLines() {
        Set<String> lines = new LinkedHashSet<>();
        for (Town t : townsByOwner.values()) {
            for (UUID enemy : new HashSet<>(t.getWars())) {
                Town other = townsByOwner.get(enemy);
                if (other == null) continue;
                if (t.getOwner().toString().compareTo(enemy.toString()) > 0) continue; // avoid duplicates
                lines.add(townLabel(t) + " vs " + townLabel(other));
            }
        }
        return new ArrayList<>(lines);
    }

    public List<String> buildAllianceLines() {
        Set<String> lines = new LinkedHashSet<>();
        for (Town t : townsByOwner.values()) {
            for (UUID ally : new HashSet<>(t.getAllies())) {
                Town other = townsByOwner.get(ally);
                if (other == null) continue;
                if (t.getOwner().toString().compareTo(ally.toString()) > 0) continue;
                lines.add(townLabel(t) + " + " + townLabel(other));
            }
        }
        return new ArrayList<>(lines);
    }

    public void refreshWarScoreboard() {
        ScoreboardManager mgr = Bukkit.getScoreboardManager();
        if (mgr == null) return;

        List<String> wars = buildWarLines();
        List<String> allies = buildAllianceLines();
        if (wars.isEmpty() && allies.isEmpty()) {
            Scoreboard main = mgr.getMainScoreboard();
            for (Player p : Bukkit.getOnlinePlayers()) p.setScoreboard(main);
            warBoard = null;
            warObjective = null;
            return;
        }

        Scoreboard board = mgr.getNewScoreboard();
        Objective obj = board.registerNewObjective("vc_war", "dummy", ChatColor.RED + "" + ChatColor.BOLD + "War Mode");
        obj.setDisplaySlot(DisplaySlot.SIDEBAR);

        int score = 15;
        obj.getScore(ChatColor.GOLD + "Wars").setScore(score--);
        int idx = 1;
        for (String line : wars) {
            if (score < 1) break;
            obj.getScore(ChatColor.WHITE + "" + idx + ". " + line).setScore(score--);
            idx++;
        }
        obj.getScore(ChatColor.AQUA + "Alliances").setScore(score--);
        idx = 1;
        for (String line : allies) {
            if (score < 1) break;
            obj.getScore(ChatColor.WHITE + "" + idx + ". " + line).setScore(score--);
            idx++;
        }

        warBoard = board;
        warObjective = obj;
        for (Player p : Bukkit.getOnlinePlayers()) {
            p.setScoreboard(board);
        }
    }

    public void applyScoreboard(Player p) {
        if (warBoard != null) {
            p.setScoreboard(warBoard);
        }
    }

    private String townLabel(Town t) {
        if (t == null) return "Unknown";
        return t.getName() != null ? t.getName() : ownerName(t.getOwner());
    }

    private String ownerName(UUID id) {
        OfflinePlayer op = Bukkit.getOfflinePlayer(id);
        return op.getName() != null ? op.getName() : id.toString().substring(0, 8);
    }

    private List<String> resolveNames(Set<UUID> ids) {
        List<String> names = new ArrayList<>();
        if (ids == null) return names;
        for (UUID id : ids) {
            Town t = townsByOwner.get(id);
            names.add(t != null ? townLabel(t) : ownerName(id));
        }
        return names;
    }
}
