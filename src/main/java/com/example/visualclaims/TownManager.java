package com.example.visualclaims;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Chunk;
import org.bukkit.OfflinePlayer;
import org.bukkit.Statistic;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.Player;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.ScoreboardManager;
import org.bukkit.scheduler.BukkitTask;

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
    private final File statsFile;
    private final File silentVisitFile;
    private final File contestsFile;
    private final File contestImmunityFile;
    private final Gson gson;

    private static final int HISTORY_LIMIT = 10;
    private static final long INVITE_TTL_MS = 10 * 60 * 1000L;
    private static final long ALLIANCE_INVITE_TTL_MS = 10 * 60 * 1000L;
    private static final long CONTEST_DURATION_MS = 60 * 60 * 1000L;
    private static final long CONTEST_MAX_DURATION_MS = 7 * 24 * 60 * 60 * 1000L;
    private static final long CONTEST_IMMUNITY_MS = 7 * 24 * 60 * 60 * 1000L;
    private static final long PENDING_CONTEST_TTL_MS = 15 * 1000L;
    private static final long MIN_TOWN_AGE_MS = 24 * 60 * 60 * 1000L;
    private static final long RPS_TTL_MS = 60 * 1000L;
    private static final ChatColor[] SCOREBOARD_SUFFIXES = new ChatColor[] {
            ChatColor.BLACK,
            ChatColor.DARK_BLUE,
            ChatColor.DARK_GREEN,
            ChatColor.DARK_AQUA,
            ChatColor.DARK_RED,
            ChatColor.DARK_PURPLE,
            ChatColor.GOLD,
            ChatColor.GRAY,
            ChatColor.DARK_GRAY,
            ChatColor.BLUE,
            ChatColor.GREEN,
            ChatColor.AQUA,
            ChatColor.RED,
            ChatColor.LIGHT_PURPLE,
            ChatColor.YELLOW,
            ChatColor.WHITE
    };
    enum ScoreboardMode {
        OFF,
        LEADERBOARD,
        ALLIANCES
    }

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
    // players who enabled silent visiting
    private final Set<UUID> silentVisitors = new HashSet<>();
    // per-player stats
    private final Map<String, PlayerStats> playerStats = new HashMap<>();
    // active contests by id
    private final Map<String, ContestState> contestsById = new HashMap<>();
    // chunkId -> contest
    private final Map<String, ContestState> contestsByChunkId = new HashMap<>();
    // chunkId -> immune until timestamp
    private final Map<String, Long> contestImmunityByChunkId = new HashMap<>();
    // pending contest confirmations: player -> pending
    private final Map<UUID, PendingContest> pendingContestConfirmations = new HashMap<>();
    // pending rock-paper-scissors choices by contest id
    private final Map<String, PendingRps> pendingRpsByContest = new HashMap<>();

    private final Map<UUID, Scoreboard> leaderboardBoards = new HashMap<>();
    private final Map<UUID, ScoreboardMode> scoreboardModes = new HashMap<>();
    private String scoreboardTitle;
    private String scoreboardTopKillsTitle;
    private String scoreboardTopClaimsTitle;
    private String scoreboardContestedTitle;
    private String scoreboardAlliancesTitle;
    private String scoreboardYouTitle;
    private String scoreboardNoneLine;
    private String scoreboardSeparatorLine;
    private String scoreboardHideTip;
    private String scoreboardTopEntryFormat;
    private String scoreboardAllianceEntryFormat;
    private String scoreboardYouKillsFormat;
    private String scoreboardYouDeathsFormat;
    private String scoreboardYouClaimsFormat;
    private String scoreboardContestEntryFormat;
    private String scoreboardContestPausedText;
    private BukkitTask contestTask;
    private BossBar contestBossBar;

    public TownManager(VisualClaims plugin, DynmapHook dynmap) {
        this.plugin = plugin;
        this.dynmap = dynmap;
        this.townsDir = new File(plugin.getDataFolder(), "towns");
        if (!townsDir.exists()) townsDir.mkdirs();
        this.historyFile = new File(plugin.getDataFolder(), "history.json");
        this.statsFile = new File(plugin.getDataFolder(), "player-stats.json");
        this.silentVisitFile = new File(plugin.getDataFolder(), "silent-visitors.json");
        this.contestsFile = new File(plugin.getDataFolder(), "contests.json");
        this.contestImmunityFile = new File(plugin.getDataFolder(), "contest-immunity.json");
        this.gson = new GsonBuilder().setPrettyPrinting().create();
    }

    private void loadScoreboardSettings() {
        scoreboardTitle = colorize(plugin.getConfig().getString("scoreboard.title", "&6&lLeaderboard"));
        scoreboardTopKillsTitle = colorize(plugin.getConfig().getString("scoreboard.top-kills-title", "&bTop Kills"));
        scoreboardTopClaimsTitle = colorize(plugin.getConfig().getString("scoreboard.top-claims-title", "&eTop Claims"));
        scoreboardContestedTitle = colorize(plugin.getConfig().getString("scoreboard.contested-title", "&cContested Chunks"));
        scoreboardAlliancesTitle = colorize(plugin.getConfig().getString("scoreboard.alliances-title", "&dAlliances"));
        scoreboardYouTitle = colorize(plugin.getConfig().getString("scoreboard.you-title", "&aYou"));
        scoreboardNoneLine = colorize(plugin.getConfig().getString("scoreboard.none", "&7None"));
        scoreboardSeparatorLine = colorize(plugin.getConfig().getString("scoreboard.separator", "&7----------------"));
        scoreboardHideTip = colorize(plugin.getConfig().getString("scoreboard.hide-tip", "&7Hide this with &e/lb toggle"));
        scoreboardTopEntryFormat = plugin.getConfig().getString("scoreboard.top-entry-format", "&f{index}. {town} &7({value})");
        scoreboardAllianceEntryFormat = plugin.getConfig().getString("scoreboard.alliance-entry-format", "&f{index}. {alliance}");
        scoreboardYouKillsFormat = plugin.getConfig().getString("scoreboard.you-kills-format", "&fKills: &e{value}");
        scoreboardYouDeathsFormat = plugin.getConfig().getString("scoreboard.you-deaths-format", "&fDeaths: &e{value}");
        scoreboardYouClaimsFormat = plugin.getConfig().getString("scoreboard.you-claims-format", "&fClaims: &e{value}");
        scoreboardContestEntryFormat = plugin.getConfig().getString("scoreboard.contest-entry-format", "&f{index}. {defender} &7vs {challenger} &7({chunks}) &e{time}{paused}");
        scoreboardContestPausedText = colorize(plugin.getConfig().getString("scoreboard.contest-paused-text", "&c paused"));
    }

    private String colorize(String input) {
        return ChatColor.translateAlternateColorCodes('&', input == null ? "" : input);
    }

    private String formatTemplate(String template, String... pairs) {
        String out = template == null ? "" : template;
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            out = out.replace("{" + pairs[i] + "}", pairs[i + 1] == null ? "" : pairs[i + 1]);
        }
        return colorize(out);
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
        refreshLeaderboardScoreboard();
        return true;
    }

    public boolean adminDeleteTown(Town t) {
        if (t == null) return false;
        UUID owner = t.getOwner();
        removeContestsForTown(owner);
        townsByOwner.remove(owner);
        for (ChunkPos pos : new HashSet<>(t.getClaims())) {
            townsByChunkId.remove(pos.id());
            if (dynmap != null) dynmap.removeAreaMarker(pos);
            recordHistory(pos, "ADMIN-DELETE", t);
        }
        townsByMember.remove(owner);
        for (UUID m : new HashSet<>(t.getMembers())) townsByMember.remove(m);
        for (Town other : townsByOwner.values()) {
            other.getAllies().remove(owner);
            other.getWars().remove(owner);
        }
        pendingInvites.entrySet().removeIf(e -> e.getValue().getTownOwner().equals(owner));
        pendingAllianceInvites.entrySet().removeIf(e -> e.getValue().getFromOwner().equals(owner) || e.getValue().getToOwner().equals(owner));
        File f = new File(townsDir, owner.toString() + ".json");
        if (f.exists()) f.delete();
        refreshLeaderboardScoreboard();
        return true;
    }

    public boolean deleteTown(UUID owner) {
        Town t = townsByOwner.remove(owner);
        if (t == null) return false;
        removeContestsForTown(owner);

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
        refreshLeaderboardScoreboard();
        return true;
    }

    public boolean claimChunk(Town t, Chunk chunk, boolean bypass) {
        return claimChunk(t, chunk, bypass, null);
    }

    public boolean claimChunk(Town t, Chunk chunk, boolean bypass, UUID actor) {
        if (t == null) return false;
        if (!bypass && t.claimCount() >= computeMaxClaims(t.getOwner())) return false;
        ChunkPos pos = ChunkPos.of(chunk);
        if (isOverOutpostCap(t, bypass)) return false;
        if (wouldExceedOutpostCap(t, pos, bypass)) return false;
        if (townsByChunkId.containsKey(pos.id())) return false;
        boolean ok = t.addClaim(pos);
        if (!ok) return false;
        townsByChunkId.put(pos.id(), t);
        saveTown(t);
        if (actor != null) recordPlayerClaim(actor);
        updateChunkMarker(t, pos);
        recordHistory(pos, "CLAIM", t);
        refreshLeaderboardScoreboard();
        return true;
    }

    public boolean unclaimChunk(Town t, ChunkPos pos) {
        if (t == null) return false;
        if (!t.ownsChunk(pos)) return false;
        if (isChunkContested(pos)) return false;

        t.removeClaim(pos);
        t.removeCapitalClaim(pos);
        townsByChunkId.remove(pos.id());
        saveTown(t);
        recordHistory(pos, "UNCLAIM", t);
        if (dynmap != null) dynmap.removeAreaMarker(pos);
        refreshLeaderboardScoreboard();
        return true;
    }

    // Admin-only force unclaim
    public boolean forceUnclaim(ChunkPos pos) {
        ContestState contest = contestsByChunkId.get(pos.id());
        if (contest != null) {
            resolveContest(contest, null, ContestResolution.EXPIRE);
        }
        Town t = townsByChunkId.remove(pos.id());
        if (t != null) {
            t.removeClaim(pos);
            t.removeCapitalClaim(pos);
            saveTown(t);
            recordHistory(pos, "FORCE-UNCLAIM", t);
            if (dynmap != null) dynmap.removeAreaMarker(pos);
            refreshLeaderboardScoreboard();
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

    public Optional<Town> leaveTown(UUID member) {
        Town t = townsByMember.get(member);
        if (t == null) return Optional.empty();
        if (t.getOwner().equals(member)) return Optional.empty();
        boolean removed = t.removeMember(member);
        if (!removed) return Optional.empty();
        townsByMember.remove(member);
        saveTown(t);
        return Optional.of(t);
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
        refreshLeaderboardScoreboard();
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
            refreshLeaderboardScoreboard();
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
        refreshLeaderboardScoreboard();
        return true;
    }

    public int computeMaxClaims(UUID owner) {
        int baseMax = plugin.getConfig().getInt("max-claims-per-player", 64);
        Town t = townsByOwner.get(owner);
        int bonus = (t != null) ? t.getBonusChunks() : 0;

        int theoretical = computeTheoreticalClaims(owner, baseMax, bonus);
        int currentClaims = (t != null) ? t.claimCount() : 0;
        return Math.max(theoretical, currentClaims);
    }

    private int computeTheoreticalClaims(UUID owner, int baseMax, int bonus) {
        int contestedSpent = 0;
        Town t = townsByOwner.get(owner);
        if (t != null) contestedSpent = Math.max(0, t.getContestedClaimsSpent());
        if (!plugin.getConfig().getBoolean("use-playtime-scaling", false)) {
            return Math.max(0, baseMax + bonus - contestedSpent);
        }

        int chunksPerHour = Math.max(1, plugin.getConfig().getInt("chunks-per-hour", 2));
        int hours = getPlaytimeHours(owner);
        int dynamic = hours * chunksPerHour;

        return Math.max(0, baseMax + dynamic + bonus - contestedSpent);
    }

    public int computeAllowedOutposts(UUID owner) {
        Town t = townsByOwner.get(owner);
        int baseMax = plugin.getConfig().getInt("max-claims-per-player", 64);
        int bonus = (t != null) ? t.getBonusChunks() : 0;
        int theoretical = Math.max(1, computeTheoreticalClaims(owner, baseMax, bonus));
        // Diminishing-but-unbounded growth: start at ~3 outposts near 512 claims and grow sub-linearly via a log curve.
        double ratio = Math.max(1.0d, theoretical / 512.0d);
        double baseOutposts = 3.0d;
        double growth = Math.log(ratio) / Math.log(1.3d); // generous early growth, slower later
        double allowed = baseOutposts + growth;
        return Math.max(1, (int) Math.round(allowed));
    }

    public boolean isTownOldEnough(Town t) {
        if (t == null) return false;
        long createdAt = t.getCreatedAt();
        if (createdAt <= 0L) return true;
        return System.currentTimeMillis() - createdAt >= MIN_TOWN_AGE_MS;
    }

    public long getTownAgeMs(Town t) {
        if (t == null || t.getCreatedAt() <= 0L) return 0L;
        return Math.max(0L, System.currentTimeMillis() - t.getCreatedAt());
    }

    public int computeContestCost(Town challenger, int outpostSize) {
        int base = Math.max(1, outpostSize);
        double sizeRatio = Math.min(1.0d, base / 128.0d);
        double sizeMultiplier = 1.0d + (0.5d * sizeRatio);
        double repMultiplier = reputationCostMultiplier(challenger != null ? challenger.getReputation() : 0);
        double scaled = base * sizeMultiplier * repMultiplier;
        int cost = (int) Math.ceil(scaled);
        return Math.max(base, cost);
    }

    private double reputationCostMultiplier(int reputation) {
        if (reputation <= -6) return 1.3d;
        if (reputation <= -3) return 1.2d;
        if (reputation <= -1) return 1.1d;
        if (reputation <= 2) return 1.05d;
        return 1.0d;
    }

    public int getContestedClaimsSpent(UUID owner) {
        Town t = townsByOwner.get(owner);
        return t != null ? Math.max(0, t.getContestedClaimsSpent()) : 0;
    }

    public int computeAvailableClaims(UUID owner) {
        Town t = townsByOwner.get(owner);
        if (t == null) return 0;
        int baseMax = plugin.getConfig().getInt("max-claims-per-player", 64);
        int bonus = t.getBonusChunks();
        int theoretical = computeTheoreticalClaims(owner, baseMax, bonus);
        return theoretical - t.claimCount();
    }

    private void notifyDebtCrossing(Town town, int availableBefore, int availableAfter) {
        if (town == null) return;
        if (availableBefore >= 0 && availableAfter < 0) {
            int debt = Math.max(1, -availableAfter);
            Player p = Bukkit.getPlayer(town.getOwner());
            if (p == null) return;
            p.sendMessage("§cYou are now in claim debt: §e" + debt + "§c chunks over your limit.");
            p.sendMessage("§cYou cannot claim more chunks until you are out of debt.");
            boolean scaling = plugin.getConfig().getBoolean("use-playtime-scaling", false);
            int chunksPerHour = Math.max(1, plugin.getConfig().getInt("chunks-per-hour", 2));
            if (scaling) {
                int hoursNeeded = (int) Math.ceil(debt / (double) chunksPerHour);
                p.sendMessage("§7Recover by unclaiming §e" + debt + "§7 chunks or playing about §e" + hoursNeeded + "h §7(@" + chunksPerHour + " chunks/hour).");
            } else {
                p.sendMessage("§7Recover by unclaiming §e" + debt + "§7 chunks.");
            }
        }
    }

    public boolean isAdjacentToOwnClaim(Town town, ChunkPos pos) {
        if (town == null || town.getClaims() == null || town.getClaims().isEmpty()) return false;
        int x = pos.getX();
        int z = pos.getZ();
        String world = pos.getWorld();
        int[][] dirs = { {1,0}, {-1,0}, {0,1}, {0,-1} };
        for (int[] d : dirs) {
            ChunkPos neighbor = new ChunkPos(world, x + d[0], z + d[1]);
            if (town.ownsChunk(neighbor)) return true;
        }
        return false;
    }

    public int countClaimIslands(Town town) {
        if (town == null || town.getClaims() == null || town.getClaims().isEmpty()) return 0;
        Set<ChunkPos> claims = new HashSet<>(town.getClaims());
        Set<ChunkPos> visited = new HashSet<>();
        int islands = 0;
        for (ChunkPos start : claims) {
            if (visited.contains(start)) continue;
            islands++;
            Deque<ChunkPos> stack = new ArrayDeque<>();
            stack.push(start);
            while (!stack.isEmpty()) {
                ChunkPos cur = stack.pop();
                if (!visited.add(cur)) continue;
                int x = cur.getX();
                int z = cur.getZ();
                String world = cur.getWorld();
                int[][] dirs = { {1,0}, {-1,0}, {0,1}, {0,-1} };
                for (int[] d : dirs) {
                    ChunkPos neighbor = new ChunkPos(world, x + d[0], z + d[1]);
                    if (claims.contains(neighbor) && !visited.contains(neighbor)) {
                        stack.push(neighbor);
                    }
                }
            }
        }
        return islands;
    }

    public int countClaimIslands(UUID owner) {
        return getTownByOwner(owner).map(this::countClaimIslands).orElse(0);
    }

    public boolean isOverOutpostCap(Town town, boolean bypass) {
        if (bypass || town == null) return false;
        int allowed = computeAllowedOutposts(town.getOwner());
        int current = countClaimIslands(town);
        return current > allowed;
    }

    public boolean wouldExceedOutpostCap(Town town, ChunkPos pos, boolean bypass) {
        if (bypass || town == null) return false;
        int allowed = computeAllowedOutposts(town.getOwner());
        int current = countClaimIslands(town);
        if (current > allowed) return true;
        boolean adjacent = isAdjacentToOwnClaim(town, pos);
        return !adjacent && current >= allowed;
    }

    public List<Set<ChunkPos>> getClaimIslands(Town town) {
        List<Set<ChunkPos>> clusters = new ArrayList<>();
        if (town == null || town.getClaims() == null || town.getClaims().isEmpty()) return clusters;
        Set<ChunkPos> claims = new HashSet<>(town.getClaims());
        Set<ChunkPos> visited = new HashSet<>();
        for (ChunkPos start : claims) {
            if (visited.contains(start)) continue;
            Set<ChunkPos> cluster = new HashSet<>();
            Deque<ChunkPos> stack = new ArrayDeque<>();
            stack.push(start);
            while (!stack.isEmpty()) {
                ChunkPos cur = stack.pop();
                if (!visited.add(cur)) continue;
                cluster.add(cur);
                int x = cur.getX();
                int z = cur.getZ();
                String world = cur.getWorld();
                int[][] dirs = { {1,0}, {-1,0}, {0,1}, {0,-1} };
                for (int[] d : dirs) {
                    ChunkPos neighbor = new ChunkPos(world, x + d[0], z + d[1]);
                    if (claims.contains(neighbor) && !visited.contains(neighbor)) {
                        stack.push(neighbor);
                    }
                }
            }
            clusters.add(cluster);
        }
        return clusters;
    }

    public Set<ChunkPos> getClaimCluster(Town town, ChunkPos start) {
        Set<ChunkPos> cluster = new HashSet<>();
        if (town == null || start == null) return cluster;
        if (!town.ownsChunk(start)) return cluster;
        Set<ChunkPos> claims = new HashSet<>(town.getClaims());
        Deque<ChunkPos> stack = new ArrayDeque<>();
        stack.push(start);
        while (!stack.isEmpty()) {
            ChunkPos cur = stack.pop();
            if (!cluster.add(cur)) continue;
            int x = cur.getX();
            int z = cur.getZ();
            String world = cur.getWorld();
            int[][] dirs = { {1,0}, {-1,0}, {0,1}, {0,-1} };
            for (int[] d : dirs) {
                ChunkPos neighbor = new ChunkPos(world, x + d[0], z + d[1]);
                if (claims.contains(neighbor) && !cluster.contains(neighbor)) {
                    stack.push(neighbor);
                }
            }
        }
        return cluster;
    }

    public RemovalResult trimSmallestOutposts(UUID owner, int clustersToRemove) {
        Town t = townsByOwner.get(owner);
        if (t == null || clustersToRemove <= 0) return new RemovalResult(0, 0);
        List<Set<ChunkPos>> clusters = getClaimIslands(t);
        clusters.sort(Comparator.comparingInt(Set::size));
        int removedClusters = 0;
        int removedChunks = 0;
        for (Set<ChunkPos> cluster : clusters) {
            if (removedClusters >= clustersToRemove) break;
            for (ChunkPos pos : new ArrayList<>(cluster)) {
                if (unclaimChunk(t, pos)) {
                    removedChunks++;
                }
            }
            removedClusters++;
        }
        return new RemovalResult(removedClusters, removedChunks);
    }

    public record RemovalResult(int clusters, int chunks) {}

    private enum ContestResolution {
        KILL,
        HOLD,
        RPS,
        EXPIRE,
        CANCEL
    }

    public enum RpsChoice {
        ROCK,
        PAPER,
        SCISSORS;

        public boolean beats(RpsChoice other) {
            return (this == ROCK && other == SCISSORS)
                    || (this == PAPER && other == ROCK)
                    || (this == SCISSORS && other == PAPER);
        }
    }

    public static class PendingContest {
        private final UUID defenderOwner;
        private final String chunkId;
        private final long createdAt;

        private PendingContest(UUID defenderOwner, String chunkId, long createdAt) {
            this.defenderOwner = defenderOwner;
            this.chunkId = chunkId;
            this.createdAt = createdAt;
        }

        public UUID getDefenderOwner() { return defenderOwner; }
        public String getChunkId() { return chunkId; }
        public long getCreatedAt() { return createdAt; }

        public boolean isExpired(long now, long ttlMs) {
            return createdAt + ttlMs < now;
        }
    }

    private static class PendingRps {
        private final String contestId;
        private final UUID ownerA;
        private final UUID ownerB;
        private long createdAt;
        private final Map<UUID, RpsChoice> choices = new HashMap<>();

        private PendingRps(String contestId, UUID ownerA, UUID ownerB, long createdAt) {
            this.contestId = contestId;
            this.ownerA = ownerA;
            this.ownerB = ownerB;
            this.createdAt = createdAt;
        }

        public boolean isExpired(long now) {
            return createdAt + RPS_TTL_MS < now;
        }
    }

    public boolean exceedsOutpostLimit(Town town, ChunkPos pos, boolean bypass) {
        if (bypass || town == null) return false;
        if (town.claimCount() == 0) return false; // first claim always allowed
        if (isAdjacentToOwnClaim(town, pos)) return false; // expansion of existing cluster
        int allowed = computeAllowedOutposts(town.getOwner());
        int current = countClaimIslands(town);
        return current >= allowed;
    }

    public Optional<ContestState> getContestByChunk(ChunkPos pos) {
        if (pos == null) return Optional.empty();
        return Optional.ofNullable(contestsByChunkId.get(pos.id()));
    }

    public String getContestLabel(ChunkPos pos) {
        if (pos == null) return null;
        ContestState contest = contestsByChunkId.get(pos.id());
        return contest != null ? buildContestLabelPlain(contest) : null;
    }

    public String getContestLabel(ChunkPos pos, UUID viewer) {
        if (pos == null) return null;
        ContestState contest = contestsByChunkId.get(pos.id());
        if (contest == null) return null;
        Town viewerTown = viewer == null ? null : getTownOf(viewer).orElse(null);
        return buildContestLabelForViewer(contest, viewerTown);
    }

    public boolean isCapitalChunk(Town town, ChunkPos pos) {
        return town != null && town.isCapitalChunk(pos);
    }

    public boolean isCapitalOutpost(Town town, Set<ChunkPos> cluster) {
        if (town == null || cluster == null || cluster.isEmpty()) return false;
        Set<ChunkPos> capital = town.getCapitalClaims();
        if (capital.isEmpty()) return false;
        return cluster.containsAll(capital);
    }

    public boolean setCapitalOutpost(Town town, Set<ChunkPos> cluster) {
        if (town == null || cluster == null || cluster.isEmpty()) return false;
        if (town.getClaims() == null || !town.getClaims().containsAll(cluster)) return false;
        town.setCapitalClaims(new HashSet<>(cluster));
        saveTown(town);
        refreshTownAreas(town);
        return true;
    }

    public List<ContestState> getContestsForOwner(UUID owner) {
        if (owner == null) return Collections.emptyList();
        List<ContestState> contests = new ArrayList<>();
        for (ContestState contest : contestsById.values()) {
            if (owner.equals(contest.getDefenderOwner()) || owner.equals(contest.getChallengerOwner())) {
                contests.add(contest);
            }
        }
        return contests;
    }

    public boolean isChunkContested(ChunkPos pos) {
        return getContestByChunk(pos).isPresent();
    }

    public long getOutpostImmunityRemaining(Set<ChunkPos> cluster) {
        long now = System.currentTimeMillis();
        long remaining = 0L;
        for (ChunkPos pos : cluster) {
            Long until = contestImmunityByChunkId.get(pos.id());
            if (until != null && until > now) {
                remaining = Math.max(remaining, until - now);
            }
        }
        return remaining;
    }

    public Optional<PendingContest> getPendingContest(UUID player) {
        PendingContest pending = pendingContestConfirmations.get(player);
        if (pending == null) return Optional.empty();
        if (pending.isExpired(System.currentTimeMillis(), PENDING_CONTEST_TTL_MS)) {
            pendingContestConfirmations.remove(player);
            return Optional.empty();
        }
        return Optional.of(pending);
    }

    public void setPendingContest(UUID player, UUID defenderOwner, ChunkPos pos) {
        pendingContestConfirmations.put(player, new PendingContest(defenderOwner, pos.id(), System.currentTimeMillis()));
    }

    public void clearPendingContest(UUID player) {
        pendingContestConfirmations.remove(player);
    }

    private void pruneExpiredPendingContests() {
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<UUID, PendingContest>> it = pendingContestConfirmations.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<UUID, PendingContest> entry = it.next();
            PendingContest pending = entry.getValue();
            if (pending == null || pending.isExpired(now, PENDING_CONTEST_TTL_MS)) {
                it.remove();
                Player p = Bukkit.getPlayer(entry.getKey());
                if (p != null) {
                    Town defender = townsByOwner.get(pending != null ? pending.getDefenderOwner() : null);
                    String label = defender != null ? coloredTownName(defender) : "that outpost";
                    p.sendMessage("§cContest confirmation expired for §e" + label + "§c. Run §e/claimchunk§c again to contest.");
                }
            }
        }
    }

    public boolean startContest(Town challenger, Town defender, Set<ChunkPos> cluster, int cost) {
        if (challenger == null || defender == null || cluster.isEmpty()) return false;
        for (ChunkPos pos : cluster) {
            if (contestsByChunkId.containsKey(pos.id())) return false;
        }
        long remaining = getOutpostImmunityRemaining(cluster);
        if (remaining > 0) return false;
        long now = System.currentTimeMillis();
        ContestState contest = new ContestState(defender.getOwner(), challenger.getOwner(), cluster, now, now + CONTEST_DURATION_MS);
        contest.setStartCost(Math.max(1, cost));
        contest.setHoldEligible(true);
        boolean bothOnline = isOwnerOnline(defender.getOwner()) && isOwnerOnline(challenger.getOwner());
        contest.setHoldOfflineAllowed(bothOnline);
        contest.setPaused(!bothOnline);
        contestsById.put(contest.getId(), contest);
        indexContest(contest);
        int availableBefore = computeAvailableClaims(challenger.getOwner());
        challenger.addContestedClaimsSpent(Math.max(1, cost));
        int availableAfter = computeAvailableClaims(challenger.getOwner());
        challenger.addReputation(-1);
        saveTown(challenger);
        notifyDebtCrossing(challenger, availableBefore, availableAfter);
        saveContests();
        for (ChunkPos pos : cluster) {
            updateChunkMarker(defender, pos);
            recordHistory(pos, "CONTEST-START", defender);
        }
        refreshLeaderboardScoreboard();
        updateContestBossBar();
        return true;
    }

    public void handleContestKill(UUID killer, UUID victim) {
        if (killer == null || victim == null) return;
        ContestState contest = findContestBetween(killer, victim);
        if (contest == null) return;
        resolveContest(contest, killer, ContestResolution.KILL);
    }

    public boolean cancelContest(UUID challengerOwner, ContestState contest) {
        if (challengerOwner == null || contest == null) return false;
        if (!challengerOwner.equals(contest.getChallengerOwner())) return false;
        resolveContest(contest, null, ContestResolution.CANCEL);
        return true;
    }

    public boolean transferOutpost(Town from, Town to, Set<ChunkPos> cluster) {
        if (from == null || to == null || cluster == null || cluster.isEmpty()) return false;
        if (from.getOwner().equals(to.getOwner())) return false;
        if (!from.getClaims().containsAll(cluster)) return false;
        boolean removedCapital = from.getCapitalClaims().removeAll(cluster);
        for (ChunkPos pos : cluster) {
            from.removeClaim(pos);
        }
        for (ChunkPos pos : cluster) {
            to.addClaim(pos);
            townsByChunkId.put(pos.id(), to);
            updateChunkMarker(to, pos);
            recordHistory(pos, "TRANSFER", to);
        }
        saveTown(from);
        saveTown(to);
        if (removedCapital) refreshTownAreas(from);
        refreshLeaderboardScoreboard();
        return true;
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

    public void recordKill(UUID killer) {
        recordPlayerKill(killer);
        Optional<Town> townOpt = getTownOf(killer);
        if (townOpt.isPresent()) {
            Town t = townOpt.get();
            t.addKill();
            saveTown(t);
        }
        refreshLeaderboardScoreboard();
    }

    public void recordDeath(UUID victim) {
        recordPlayerDeath(victim);
        refreshLeaderboardScoreboard();
    }

    public void recordPlayerKill(UUID player) {
        PlayerStats stats = playerStats.computeIfAbsent(player.toString(), k -> new PlayerStats());
        stats.kills++;
        saveStats();
    }

    public void recordPlayerDeath(UUID player) {
        PlayerStats stats = playerStats.computeIfAbsent(player.toString(), k -> new PlayerStats());
        stats.deaths++;
        saveStats();
    }

    public void recordPlayerClaim(UUID player) {
        PlayerStats stats = playerStats.computeIfAbsent(player.toString(), k -> new PlayerStats());
        stats.claims++;
        saveStats();
    }

    public PlayerStats getPlayerStats(UUID player) {
        return playerStats.getOrDefault(player.toString(), new PlayerStats());
    }

    public boolean toggleSilentVisit(UUID player) {
        boolean enabled;
        if (silentVisitors.remove(player)) {
            enabled = false;
        } else {
            silentVisitors.add(player);
            enabled = true;
        }
        saveSilentVisitors();
        return enabled;
    }

    public boolean isSilentVisitor(UUID player) {
        return silentVisitors.contains(player);
    }

    public void setSilentVisit(UUID player, boolean enabled) {
        if (enabled) silentVisitors.add(player);
        else silentVisitors.remove(player);
        saveSilentVisitors();
    }

    public List<Town> topByClaims(int limit) {
        return townsByOwner.values().stream()
                .sorted(Comparator.comparingInt(Town::claimCount).reversed()
                        .thenComparing(Town::getName, Comparator.nullsLast(String::compareToIgnoreCase))
                        .thenComparing(t -> t.getOwner().toString()))
                .limit(limit)
                .toList();
    }

    public List<Town> topByKills(int limit) {
        return townsByOwner.values().stream()
                .sorted(Comparator.comparingInt(Town::getKills).reversed()
                        .thenComparing(Town::getName, Comparator.nullsLast(String::compareToIgnoreCase))
                        .thenComparing(t -> t.getOwner().toString()))
                .limit(limit)
                .toList();
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
        saveStats();
        saveSilentVisitors();
        saveContests();
        saveContestImmunity();
    }

    public void loadAll() {
        loadScoreboardSettings();
        townsByOwner.clear();
        townsByChunkId.clear();
        townsByMember.clear();
        File[] files = townsDir.listFiles((d, name) -> name.endsWith(".json"));
        if (files != null) {
            for (File f : files) {
                try (FileReader r = new FileReader(f)) {
                    Town t = gson.fromJson(r, Town.class);
                    if (t != null && t.getOwner() != null) {
                        boolean changed = false;
                        if (t.getCreatedAt() <= 0L) {
                            t.setCreatedAt(System.currentTimeMillis() - MIN_TOWN_AGE_MS);
                            changed = true;
                        }
                        if (!t.isReputationInitialized()) {
                            t.setReputation(Town.MAX_REPUTATION);
                            t.setReputationInitialized(true);
                            changed = true;
                        } else if (t.getReputation() > Town.MAX_REPUTATION) {
                            t.setReputation(Town.MAX_REPUTATION);
                            changed = true;
                        } else if (t.getReputation() < Town.MIN_REPUTATION) {
                            t.setReputation(Town.MIN_REPUTATION);
                            changed = true;
                        }
                        if (t.getClaims() != null && !t.getClaims().isEmpty()) {
                            int before = t.getCapitalClaims().size();
                            t.getCapitalClaims().removeIf(pos -> !t.getClaims().contains(pos));
                            if (before != t.getCapitalClaims().size()) {
                                changed = true;
                            }
                        } else if (!t.getCapitalClaims().isEmpty()) {
                            t.getCapitalClaims().clear();
                            changed = true;
                        }
                        if (changed) {
                            saveTown(t);
                        }
                        townsByOwner.put(t.getOwner(), t);
                        indexTown(t);
                    }
                } catch (Exception ex) {
                    plugin.getLogger().warning("Failed to load town file " + f.getName() + ": " + ex.getMessage());
                }
            }
        }
        loadHistory();
        loadStats();
        loadSilentVisitors();
        loadContests();
        loadContestImmunity();
        pruneExpiredContestImmunity();
        rebuildContestIndex();
        processExpiredContests();
        refreshAllTownAreas();
        bootstrapHistoryForExistingClaims();
        refreshLeaderboardScoreboard();
        updateContestBossBar();
    }

    public void reloadAll() {
        plugin.reloadConfig();
        loadAll();
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

    private void loadStats() {
        playerStats.clear();
        if (!statsFile.exists()) return;
        try (FileReader reader = new FileReader(statsFile)) {
            Type type = new TypeToken<Map<String, PlayerStats>>(){}.getType();
            Map<String, PlayerStats> data = gson.fromJson(reader, type);
            if (data != null) playerStats.putAll(data);
        } catch (Exception ex) {
            plugin.getLogger().warning("Failed to load player stats: " + ex.getMessage());
        }
    }

    public void saveStats() {
        try (FileWriter writer = new FileWriter(statsFile)) {
            gson.toJson(playerStats, writer);
        } catch (Exception ex) {
            plugin.getLogger().warning("Failed to save player stats: " + ex.getMessage());
        }
    }

    private void loadSilentVisitors() {
        silentVisitors.clear();
        if (!silentVisitFile.exists()) return;
        try (FileReader reader = new FileReader(silentVisitFile)) {
            Type type = new TypeToken<Set<UUID>>(){}.getType();
            Set<UUID> data = gson.fromJson(reader, type);
            if (data != null) silentVisitors.addAll(data);
        } catch (Exception ex) {
            plugin.getLogger().warning("Failed to load silent visitors: " + ex.getMessage());
        }
    }

    private void saveSilentVisitors() {
        try (FileWriter writer = new FileWriter(silentVisitFile)) {
            gson.toJson(silentVisitors, writer);
        } catch (Exception ex) {
            plugin.getLogger().warning("Failed to save silent visitors: " + ex.getMessage());
        }
    }

    private void loadContests() {
        contestsById.clear();
        contestsByChunkId.clear();
        if (!contestsFile.exists()) return;
        long now = System.currentTimeMillis();
        try (FileReader reader = new FileReader(contestsFile)) {
            Type type = new TypeToken<List<ContestState>>(){}.getType();
            List<ContestState> data = gson.fromJson(reader, type);
            if (data != null) {
                for (ContestState contest : data) {
                    if (contest.getDefenderOwner() == null || contest.getChallengerOwner() == null) continue;
                    String id = contest.getId();
                    if (id == null) {
                        id = ContestState.buildId(contest.getDefenderOwner(), contest.getChallengerOwner(), contest.getStartTime());
                        contest.setId(id);
                    }
                    long remaining = contest.getRemainingMs();
                    if (remaining <= 0) {
                        remaining = contest.getEndTime() > 0 ? Math.max(0L, contest.getEndTime() - now) : CONTEST_DURATION_MS;
                    }
                    contest.setRemainingMs(Math.min(CONTEST_DURATION_MS, Math.max(0L, remaining)));
                    if (contest.getStartCost() <= 0) {
                        contest.setStartCost(Math.max(1, contest.getChunkCount()));
                        contest.setHoldEligible(true);
                    }
                    if (contest.isHoldEligible() && contest.isHoldOfflineAllowed() && contest.getStartTime() <= 0) {
                        contest.setHoldOfflineAllowed(false);
                    }
                    boolean bothOnline = isOwnerOnline(contest.getDefenderOwner()) && isOwnerOnline(contest.getChallengerOwner());
                    boolean challengerInside = isOwnerInContest(contest.getChallengerOwner(), contest);
                    contest.setPaused(!isContestTimerTicking(contest, bothOnline, challengerInside));
                    contest.setLastUpdated(now);
                    contestsById.put(id, contest);
                    indexContest(contest);
                }
            }
        } catch (Exception ex) {
            plugin.getLogger().warning("Failed to load contests: " + ex.getMessage());
        }
    }

    private void saveContests() {
        try (FileWriter writer = new FileWriter(contestsFile)) {
            gson.toJson(new ArrayList<>(contestsById.values()), writer);
        } catch (Exception ex) {
            plugin.getLogger().warning("Failed to save contests: " + ex.getMessage());
        }
    }

    private void loadContestImmunity() {
        contestImmunityByChunkId.clear();
        if (!contestImmunityFile.exists()) return;
        try (FileReader reader = new FileReader(contestImmunityFile)) {
            Type type = new TypeToken<Map<String, Long>>(){}.getType();
            Map<String, Long> data = gson.fromJson(reader, type);
            if (data != null) contestImmunityByChunkId.putAll(data);
        } catch (Exception ex) {
            plugin.getLogger().warning("Failed to load contest immunity: " + ex.getMessage());
        }
    }

    private void saveContestImmunity() {
        try (FileWriter writer = new FileWriter(contestImmunityFile)) {
            gson.toJson(contestImmunityByChunkId, writer);
        } catch (Exception ex) {
            plugin.getLogger().warning("Failed to save contest immunity: " + ex.getMessage());
        }
    }

    private void pruneExpiredContestImmunity() {
        long now = System.currentTimeMillis();
        contestImmunityByChunkId.entrySet().removeIf(e -> e.getValue() == null || e.getValue() <= now);
    }

    private void indexContest(ContestState contest) {
        for (ChunkPos pos : contest.getChunks()) {
            contestsByChunkId.put(pos.id(), contest);
        }
    }

    private void rebuildContestIndex() {
        contestsByChunkId.clear();
        for (ContestState contest : contestsById.values()) {
            indexContest(contest);
        }
    }

    private boolean processExpiredContests() {
        boolean changed = false;
        long now = System.currentTimeMillis();
        for (ContestState contest : new ArrayList<>(contestsById.values())) {
            if (townsByOwner.get(contest.getDefenderOwner()) == null || townsByOwner.get(contest.getChallengerOwner()) == null) {
                resolveContest(contest, null, ContestResolution.EXPIRE);
                changed = true;
                continue;
            }
            if (contest.getStartTime() > 0 && now - contest.getStartTime() >= CONTEST_MAX_DURATION_MS) {
                resolveContest(contest, null, ContestResolution.EXPIRE);
                changed = true;
                continue;
            }
            if (contest.getRemainingMs() <= 0) {
                resolveContest(contest, null, ContestResolution.EXPIRE);
                changed = true;
            }
        }
        return changed;
    }

    private boolean updateContestTimers() {
        long now = System.currentTimeMillis();
        boolean updated = false;
        for (ContestState contest : new ArrayList<>(contestsById.values())) {
            Town defender = townsByOwner.get(contest.getDefenderOwner());
            Town challenger = townsByOwner.get(contest.getChallengerOwner());
            if (defender == null || challenger == null) {
                resolveContest(contest, null, ContestResolution.EXPIRE);
                updated = true;
                continue;
            }

            if (contest.getStartTime() > 0 && now - contest.getStartTime() >= CONTEST_MAX_DURATION_MS) {
                resolveContest(contest, null, ContestResolution.EXPIRE);
                updated = true;
                continue;
            }

            long remaining = contest.getRemainingMs();
            if (remaining <= 0) {
                resolveContest(contest, null, ContestResolution.EXPIRE);
                updated = true;
                continue;
            }

            long last = contest.getLastUpdated();
            if (last <= 0) last = now;

            boolean bothOnline = isOwnerOnline(defender.getOwner()) && isOwnerOnline(challenger.getOwner());
            boolean challengerInside = isOwnerInContest(challenger.getOwner(), contest);
            boolean timerTicking = isContestTimerTicking(contest, bothOnline, challengerInside);
            boolean pausedNow = !timerTicking;
            if (contest.isPaused() != pausedNow) {
                contest.setPaused(pausedNow);
                updated = true;
            }
            if (contest.isHoldEligible() && !challengerInside) {
                contest.setHoldEligible(false);
                Player challengerPlayer = Bukkit.getPlayer(challenger.getOwner());
                Player defenderPlayer = Bukkit.getPlayer(defender.getOwner());
                String challengerName = townLabel(challenger);
                if (challengerPlayer != null) {
                    challengerPlayer.sendMessage("§cYou left the contested land. You can now only win by killing the owner or Rock Paper Scissors.");
                }
                if (defenderPlayer != null) {
                    defenderPlayer.sendMessage("§e" + challengerName + " §cleft the contested land. The only ways to win now are a kill or Rock Paper Scissors.");
                }
                updated = true;
            }
            if (timerTicking) {
                long elapsed = Math.max(0L, now - last);
                if (elapsed > 0) {
                    remaining = Math.max(0L, remaining - elapsed);
                    contest.setRemainingMs(remaining);
                    updated = true;
                }
            }

            contest.setLastUpdated(now);
            if (remaining <= 0) {
                if (contest.isHoldEligible() && challengerInside) {
                    resolveContest(contest, contest.getChallengerOwner(), ContestResolution.HOLD);
                } else {
                    resolveContest(contest, null, ContestResolution.EXPIRE);
                }
                updated = true;
            }
        }
        if (updated) saveContests();
        updateContestBossBar();
        return updated;
    }

    public String handleRpsChoice(Player player, ContestState contest, RpsChoice choice) {
        if (player == null || contest == null || choice == null) return "§cUnable to start Rock Paper Scissors right now.";
        UUID owner = player.getUniqueId();
        UUID defenderOwner = contest.getDefenderOwner();
        UUID challengerOwner = contest.getChallengerOwner();
        if (!owner.equals(defenderOwner) && !owner.equals(challengerOwner)) {
            return "§cOnly the two town owners in the contest can use Rock Paper Scissors.";
        }
        if (!isOwnerOnline(defenderOwner) || !isOwnerOnline(challengerOwner)) {
            return "§cBoth town owners must be online to play Rock Paper Scissors.";
        }

        long now = System.currentTimeMillis();
        PendingRps pending = pendingRpsByContest.get(contest.getId());
        if (pending == null || pending.isExpired(now)) {
            pending = new PendingRps(contest.getId(), defenderOwner, challengerOwner, now);
            pendingRpsByContest.put(contest.getId(), pending);
        }

        pending.choices.put(owner, choice);
        pending.createdAt = now;

        UUID otherOwner = owner.equals(defenderOwner) ? challengerOwner : defenderOwner;
        Player other = Bukkit.getPlayer(otherOwner);

        if (!pending.choices.containsKey(otherOwner)) {
            if (other != null) {
                other.sendMessage("§eRock Paper Scissors challenge: §f" + townLabel(townsByOwner.get(owner)) + " §ehas chosen. Use §f/contest rps <rock|paper|scissors> §ewithin 60s.");
            }
            return "§aYou chose §f" + choice.name().toLowerCase(Locale.ROOT) + "§a. Waiting for the other owner.";
        }

        RpsChoice otherChoice = pending.choices.get(otherOwner);
        if (choice == otherChoice) {
            pending.choices.clear();
            pending.createdAt = now;
            if (other != null) {
                other.sendMessage("§eRPS tied (" + otherChoice.name().toLowerCase(Locale.ROOT) + " vs " + choice.name().toLowerCase(Locale.ROOT) + "). Choose again.");
            }
            return "§eRPS tied (" + choice.name().toLowerCase(Locale.ROOT) + " vs " + otherChoice.name().toLowerCase(Locale.ROOT) + "). Choose again with /contest rps <rock|paper|scissors>.";
        }

        UUID winner = choice.beats(otherChoice) ? owner : otherOwner;
        String winningChoice = choice.beats(otherChoice) ? choice.name().toLowerCase(Locale.ROOT) : otherChoice.name().toLowerCase(Locale.ROOT);
        String losingChoice = choice.beats(otherChoice) ? otherChoice.name().toLowerCase(Locale.ROOT) : choice.name().toLowerCase(Locale.ROOT);
        if (other != null) {
            if (winner.equals(otherOwner)) {
                other.sendMessage("§aYou won Rock Paper Scissors (" + winningChoice + " beats " + losingChoice + ").");
            } else {
                other.sendMessage("§cYou lost Rock Paper Scissors (" + winningChoice + " beats " + losingChoice + ").");
            }
        }
        pendingRpsByContest.remove(contest.getId());
        resolveContest(contest, winner, ContestResolution.RPS);
        return winner.equals(owner)
                ? "§aYou won Rock Paper Scissors (" + winningChoice + " beats " + losingChoice + ")."
                : "§cYou lost Rock Paper Scissors (" + winningChoice + " beats " + losingChoice + ").";
    }

    private void updateContestBossBar() {
        if (contestsById.isEmpty()) {
            clearContestBossBar();
            return;
        }
        ContestState contest = contestsById.values().stream()
                .filter(this::shouldShowBossBar)
                .min(Comparator.comparingLong(ContestState::getRemainingMs))
                .orElse(null);
        if (contest == null) {
            clearContestBossBar();
            return;
        }
        Town defender = townsByOwner.get(contest.getDefenderOwner());
        Town challenger = townsByOwner.get(contest.getChallengerOwner());
        String title = ChatColor.RED + "Contest: " + townLabel(defender) + " vs " + townLabel(challenger);
        if (contestBossBar == null) {
            contestBossBar = Bukkit.createBossBar(title, BarColor.RED, BarStyle.SEGMENTED_10);
        } else {
            contestBossBar.setTitle(title);
        }
        double progress = 1.0d - (contest.getRemainingMs() / (double) CONTEST_DURATION_MS);
        if (progress < 0.0d) progress = 0.0d;
        if (progress > 1.0d) progress = 1.0d;
        contestBossBar.setProgress(progress);
        contestBossBar.setVisible(true);
        contestBossBar.removeAll();
        for (Player p : Bukkit.getOnlinePlayers()) {
            contestBossBar.addPlayer(p);
        }
    }

    private boolean shouldShowBossBar(ContestState contest) {
        if (contest == null) return false;
        if (!contest.isHoldEligible()) return false;
        boolean challengerInside = isOwnerInContest(contest.getChallengerOwner(), contest);
        if (!challengerInside) return false;
        if (contest.isHoldOfflineAllowed()) return true;
        return isOwnerOnline(contest.getDefenderOwner()) && isOwnerOnline(contest.getChallengerOwner());
    }

    private boolean isContestParticipant(UUID playerId) {
        Optional<Town> townOpt = getTownOf(playerId);
        if (townOpt.isEmpty()) return false;
        UUID owner = townOpt.get().getOwner();
        for (ContestState contest : contestsById.values()) {
            if (owner.equals(contest.getDefenderOwner()) || owner.equals(contest.getChallengerOwner())) {
                return true;
            }
        }
        return false;
    }

    private void clearContestBossBar() {
        if (contestBossBar != null) {
            contestBossBar.removeAll();
            contestBossBar.setVisible(false);
            contestBossBar = null;
        }
    }

    private ContestState findContestBetween(UUID ownerA, UUID ownerB) {
        ContestState best = null;
        for (ContestState contest : contestsById.values()) {
            UUID defender = contest.getDefenderOwner();
            UUID challenger = contest.getChallengerOwner();
            if ((defender.equals(ownerA) && challenger.equals(ownerB)) || (defender.equals(ownerB) && challenger.equals(ownerA))) {
                if (best == null || contest.getEndTime() < best.getEndTime()) {
                    best = contest;
                }
            }
        }
        return best;
    }

    private void removeContestsForTown(UUID owner) {
        if (owner == null) return;
        for (ContestState contest : new ArrayList<>(contestsById.values())) {
            if (owner.equals(contest.getDefenderOwner()) || owner.equals(contest.getChallengerOwner())) {
                resolveContest(contest, null, ContestResolution.EXPIRE);
            }
        }
    }

    private void resolveContest(ContestState contest, UUID winnerOwner, ContestResolution resolution) {
        if (contest == null) return;
        pendingRpsByContest.remove(contest.getId());
        contestsById.remove(contest.getId());
        for (ChunkPos pos : contest.getChunks()) {
            contestsByChunkId.remove(pos.id());
        }

        Town defender = townsByOwner.get(contest.getDefenderOwner());
        Town challenger = townsByOwner.get(contest.getChallengerOwner());
        int chunkCount = contest.getChunkCount();

        if (resolution == ContestResolution.CANCEL) {
            if (defender != null) {
                for (ChunkPos pos : contest.getChunks()) {
                    updateChunkMarker(defender, pos);
                    recordHistory(pos, "CONTEST-CANCEL", defender);
                    contestImmunityByChunkId.put(pos.id(), System.currentTimeMillis() + CONTEST_IMMUNITY_MS);
                }
                saveTown(defender);
                saveContestImmunity();
            }
            saveContests();
            broadcastContestUpdate("§7Contest canceled by §e" + townLabel(challenger) + "§7. Land returned to §e" + townLabel(defender) + "§7 (" + chunkCount + " chunks).");
            updateContestBossBar();
            return;
        }

        if (resolution == ContestResolution.EXPIRE || winnerOwner == null) {
            if (defender != null) {
                for (ChunkPos pos : contest.getChunks()) {
                    updateChunkMarker(defender, pos);
                    recordHistory(pos, "CONTEST-EXPIRE", defender);
                    contestImmunityByChunkId.put(pos.id(), System.currentTimeMillis() + CONTEST_IMMUNITY_MS);
                }
                saveTown(defender);
                saveContestImmunity();
            }
            saveContests();
            broadcastContestUpdate("§7Contest expired between §e" + townLabel(defender) + "§7 and §e" + townLabel(challenger) + "§7 (" + chunkCount + " chunks).");
            updateContestBossBar();
            return;
        }

        Town winner = townsByOwner.get(winnerOwner);
        if (winner == null) {
            if (defender != null) {
                for (ChunkPos pos : contest.getChunks()) updateChunkMarker(defender, pos);
            }
            saveContests();
            updateContestBossBar();
            return;
        }

        boolean winnerIsDefender = defender != null && winner.getOwner().equals(defender.getOwner());
        if (!winnerIsDefender) {
            if (defender != null) {
                for (ChunkPos pos : contest.getChunks()) {
                    defender.removeClaim(pos);
                }
                defender.getCapitalClaims().removeAll(contest.getChunks());
                saveTown(defender);
            }
            for (ChunkPos pos : contest.getChunks()) {
                winner.addClaim(pos);
                townsByChunkId.put(pos.id(), winner);
                updateChunkMarker(winner, pos);
                recordHistory(pos, resolution == ContestResolution.HOLD ? "CONTEST-HOLD" : "CONTEST-WIN", winner);
            }
            if (resolution == ContestResolution.HOLD && challenger != null) {
                int availableBefore = computeAvailableClaims(challenger.getOwner());
                int extraCost = Math.max(1, contest.getStartCost());
                challenger.addContestedClaimsSpent(extraCost);
                int availableAfter = computeAvailableClaims(challenger.getOwner());
                saveTown(challenger);
                notifyDebtCrossing(challenger, availableBefore, availableAfter);
                Player challengerPlayer = Bukkit.getPlayer(challenger.getOwner());
                if (challengerPlayer != null) {
                    challengerPlayer.sendMessage("§cHolding the outpost cost an extra §e" + extraCost + "§c claims.");
                }
            }
            saveTown(winner);
        } else if (defender != null) {
            for (ChunkPos pos : contest.getChunks()) {
                updateChunkMarker(defender, pos);
                recordHistory(pos, "CONTEST-DEFENDED", defender);
            }
            saveTown(defender);
        }

        saveContests();
        if (winnerIsDefender) {
            if (resolution == ContestResolution.RPS) {
                broadcastContestUpdate("§a" + townLabel(winner) + " §7defended their outpost via Rock Paper Scissors (" + chunkCount + " chunks).");
            } else {
                broadcastContestUpdate("§a" + townLabel(winner) + " §7defended their outpost (" + chunkCount + " chunks).");
            }
        } else if (resolution == ContestResolution.HOLD) {
            broadcastContestUpdate("§c" + townLabel(winner) + " §7won by occupying the outpost (" + chunkCount + " chunks).");
        } else if (resolution == ContestResolution.RPS) {
            broadcastContestUpdate("§c" + townLabel(winner) + " §7won via Rock Paper Scissors (" + chunkCount + " chunks).");
        } else {
            broadcastContestUpdate("§c" + townLabel(winner) + " §7won a contest over §e" + townLabel(defender) + "§7 (" + chunkCount + " chunks).");
        }
        updateContestBossBar();
    }

    private void updateChunkMarker(Town owner, ChunkPos pos) {
        if (dynmap == null || pos == null) return;
        ContestState contest = contestsByChunkId.get(pos.id());
        if (contest != null) {
            String label = buildContestLabelPlain(contest);
            dynmap.addOrUpdateChunkArea(label, VanillaColor.GRAY.rgb, pos);
        } else if (owner != null) {
            if (owner.isCapitalChunk(pos)) {
                VanillaColor color = owner.getColor();
                int rgb = color != null ? color.rgb : VanillaColor.GREEN.rgb;
                dynmap.addOrUpdateChunkArea(capitalLabelPlain(owner), rgb, pos);
            } else {
                dynmap.addOrUpdateChunkArea(owner, pos);
            }
        } else {
            dynmap.removeAreaMarker(pos);
        }
    }

    private String buildContestLabelPlain(ContestState contest) {
        String defender = plainTownName(contest.getDefenderOwner());
        String challenger = plainTownName(contest.getChallengerOwner());
        return "Contested: " + defender + " vs " + challenger;
    }

    private String capitalLabelPlain(Town town) {
        if (town == null) return "Capital: Unknown";
        String name = town.getName() != null ? town.getName() : ownerName(town.getOwner());
        return "Capital: " + name;
    }

    public String capitalLabel(Town town) {
        if (town == null) return "Capital: Unknown";
        ChatColor color = toBukkitColor(town.getColor());
        String name = town.getName() != null ? town.getName() : ownerName(town.getOwner());
        return (color != null ? color.toString() : ChatColor.GREEN.toString()) + "Capital: " + name + ChatColor.RESET;
    }

    private String buildContestLabelForViewer(ContestState contest, Town viewerTown) {
        String defender = contestTownLabel(contest.getDefenderOwner(), viewerTown);
        String challenger = contestTownLabel(contest.getChallengerOwner(), viewerTown);
        return "Contested: " + defender + " vs " + challenger;
    }

    private String contestTownLabel(UUID owner, Town viewerTown) {
        if (viewerTown != null && owner != null && owner.equals(viewerTown.getOwner())) {
            ChatColor color = toBukkitColor(viewerTown.getColor());
            return (color != null ? color.toString() : ChatColor.GREEN.toString()) + "You" + ChatColor.RESET;
        }
        Town town = owner == null ? null : townsByOwner.get(owner);
        if (town != null) return coloredTownName(town);
        return ownerName(owner);
    }

    private String plainTownName(UUID owner) {
        if (owner == null) return "Unknown";
        Town t = townsByOwner.get(owner);
        if (t != null && t.getName() != null && !t.getName().isBlank()) return t.getName();
        return ownerName(owner);
    }

    public void refreshTownAreas(Town t) {
        if (t == null || dynmap == null) return;
        for (ChunkPos pos : t.getClaims()) {
            updateChunkMarker(t, pos);
        }
    }

    public void refreshAllTownAreas() {
        if (dynmap == null) return;
        for (Town t : townsByOwner.values()) {
            refreshTownAreas(t);
        }
    }

    public List<ChunkHistoryEntry> getHistoryFor(ChunkPos pos) {
        return chunkHistory.getOrDefault(pos.id(), Collections.emptyList());
    }

    private void recordHistory(ChunkPos pos, String action, Town t) {
        List<ChunkHistoryEntry> list = chunkHistory.computeIfAbsent(pos.id(), k -> new ArrayList<>());
        List<String> allies = t == null ? Collections.emptyList() : resolveColoredNames(t.getAllies());
        List<String> wars = t == null ? Collections.emptyList() : resolveColoredNames(t.getWars());
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

    public List<String> buildAllianceGroupLines() {
        List<List<Town>> groups = new ArrayList<>();
        Set<UUID> visited = new HashSet<>();
        for (Town t : townsByOwner.values()) {
            if (t == null || visited.contains(t.getOwner())) continue;
            List<Town> group = new ArrayList<>();
            Deque<UUID> queue = new ArrayDeque<>();
            queue.add(t.getOwner());
            while (!queue.isEmpty()) {
                UUID owner = queue.poll();
                if (owner == null || visited.contains(owner)) continue;
                Town current = townsByOwner.get(owner);
                if (current == null) continue;
                visited.add(owner);
                group.add(current);
                for (UUID ally : current.getAllies()) {
                    if (ally != null && !visited.contains(ally)) queue.add(ally);
                }
            }
            if (group.size() > 1) groups.add(group);
        }
        groups.sort((a, b) -> Integer.compare(b.size(), a.size()));
        List<String> lines = new ArrayList<>();
        int maxNames = 3;
        for (List<Town> group : groups) {
            group.sort(Comparator.comparing(town -> town.getName() == null ? "" : town.getName(), String.CASE_INSENSITIVE_ORDER));
            StringBuilder sb = new StringBuilder();
            int shown = 0;
            for (Town t : group) {
                if (shown >= maxNames) break;
                if (shown > 0) sb.append(ChatColor.GRAY).append(" + ");
                sb.append(coloredTownName(t));
                shown++;
            }
            int remaining = group.size() - shown;
            if (remaining > 0) {
                sb.append(ChatColor.GRAY).append(" +").append(remaining);
            }
            lines.add(sb.toString());
        }
        return lines;
    }

    public List<String> buildContestLines() {
        return buildContestLines(new ArrayList<>(contestsById.values()), null);
    }

    public List<String> buildContestLinesForOwner(UUID owner) {
        if (owner == null) return Collections.emptyList();
        List<ContestState> contests = contestsForOwner(owner);
        Town viewerTown = townsByOwner.get(owner);
        return buildContestLines(contests, viewerTown);
    }

    public List<String> buildContestLinesForPlayer(UUID playerId) {
        Optional<Town> townOpt = getTownOf(playerId);
        if (townOpt.isEmpty()) return Collections.emptyList();
        return buildContestLines(contestsForOwner(townOpt.get().getOwner()), townOpt.get());
    }

    private List<ContestState> contestsForOwner(UUID owner) {
        List<ContestState> contests = new ArrayList<>();
        if (owner == null) return contests;
        for (ContestState contest : contestsById.values()) {
            if (owner.equals(contest.getDefenderOwner()) || owner.equals(contest.getChallengerOwner())) {
                contests.add(contest);
            }
        }
        return contests;
    }

    private List<String> buildContestLines(List<ContestState> contests, Town viewerTown) {
        contests.sort(Comparator.comparingLong(ContestState::getRemainingMs));
        List<String> lines = new ArrayList<>();
        int idx = 1;
        for (ContestState contest : contests) {
            String defenderName = contestTownLabel(contest.getDefenderOwner(), viewerTown);
            String challengerName = contestTownLabel(contest.getChallengerOwner(), viewerTown);
            long remaining = Math.max(0L, contest.getRemainingMs());
            String time = formatRemaining(remaining);
            String paused = contest.isPaused() ? scoreboardContestPausedText : "";
            String line = formatTemplate(
                    scoreboardContestEntryFormat,
                    "index", String.valueOf(idx),
                    "defender", defenderName,
                    "challenger", challengerName,
                    "chunks", String.valueOf(contest.getChunkCount()),
                    "time", time,
                    "paused", paused
            );
            lines.add(line);
            idx++;
        }
        return lines;
    }

    private String formatRemaining(long millis) {
        if (millis <= 0) return "0m";
        long totalSeconds = millis / 1000L;
        long hours = totalSeconds / 3600L;
        long minutes = (totalSeconds % 3600L) / 60L;
        long seconds = totalSeconds % 60L;
        if (hours > 0) {
            return minutes > 0 ? hours + "h" + minutes + "m" : hours + "h";
        }
        if (minutes > 0) {
            return minutes + "m" + (seconds > 0 ? seconds + "s" : "");
        }
        return Math.max(1, seconds) + "s";
    }

    private boolean isContestTimerTicking(ContestState contest, boolean bothOnline, boolean challengerInside) {
        if (contest == null) return false;
        boolean holdTicking = contest.isHoldEligible() && challengerInside && contest.isHoldOfflineAllowed();
        return bothOnline || holdTicking;
    }

    private boolean isOwnerOnline(UUID owner) {
        Player p = owner == null ? null : Bukkit.getPlayer(owner);
        return p != null && p.isOnline();
    }

    private boolean isOwnerInContest(UUID owner, ContestState contest) {
        if (owner == null || contest == null) return false;
        Player p = Bukkit.getPlayer(owner);
        if (p == null || !p.isOnline()) return false;
        ChunkPos pos = ChunkPos.of(p.getLocation().getChunk());
        return contest.containsChunk(pos);
    }

    private ScoreboardMode getScoreboardMode(UUID player) {
        return scoreboardModes.getOrDefault(player, ScoreboardMode.LEADERBOARD);
    }

    public String formatScoreboardMode(ScoreboardMode mode) {
        if (mode == null) return "Off";
        return switch (mode) {
            case LEADERBOARD -> "Leaderboard";
            case ALLIANCES -> "Alliances";
            default -> "Off";
        };
    }

    public ScoreboardMode cycleScoreboardMode(UUID player) {
        ScoreboardMode current = getScoreboardMode(player);
        ScoreboardMode next;
        switch (current) {
            case OFF -> next = ScoreboardMode.LEADERBOARD;
            case LEADERBOARD -> next = ScoreboardMode.ALLIANCES;
            default -> next = ScoreboardMode.OFF;
        }
        scoreboardModes.put(player, next);
        if (next == ScoreboardMode.OFF) {
            leaderboardBoards.remove(player);
        }
        refreshLeaderboardScoreboard();
        Player p = Bukkit.getPlayer(player);
        if (p != null) applyScoreboard(p);
        return next;
    }

    public void applyScoreboard(Player p) {
        ScoreboardManager mgr = Bukkit.getScoreboardManager();
        UUID id = p.getUniqueId();
        if (getScoreboardMode(id) != ScoreboardMode.OFF) {
            if (mgr != null && !leaderboardBoards.containsKey(id)) refreshLeaderboardScoreboard();
            Scoreboard lb = leaderboardBoards.get(id);
            if (lb != null) { p.setScoreboard(lb); return; }
        }
        if (mgr != null) {
            p.setScoreboard(mgr.getMainScoreboard());
        }
    }

    public void refreshLeaderboardScoreboard() {
        ScoreboardManager mgr = Bukkit.getScoreboardManager();
        if (mgr == null) return;

        List<Town> killsTop = topByKills(3);
        List<Town> claimsTop = topByClaims(3);
        List<String> alliances = buildAllianceGroupLines();

        for (Player online : Bukkit.getOnlinePlayers()) {
            UUID viewer = online.getUniqueId();
            ScoreboardMode mode = getScoreboardMode(viewer);
            boolean inContest = isContestParticipant(viewer);
            boolean shouldShow = mode != ScoreboardMode.OFF;
            if (!shouldShow) {
                leaderboardBoards.remove(viewer);
                online.setScoreboard(mgr.getMainScoreboard());
                continue;
            }
            List<String> contests = inContest ? buildContestLinesForPlayer(viewer) : Collections.emptyList();
            Scoreboard board = buildLeaderboardBoard(mgr, viewer, killsTop, claimsTop, alliances, contests, mode);
            leaderboardBoards.put(viewer, board);
            online.setScoreboard(board);
        }
    }

    private Scoreboard buildLeaderboardBoard(ScoreboardManager mgr, UUID viewer, List<Town> killsTop, List<Town> claimsTop, List<String> alliances, List<String> contests, ScoreboardMode mode) {
        Scoreboard board = mgr.getNewScoreboard();
        Objective obj = board.registerNewObjective("vc_leaders", "dummy", scoreboardTitle);
        obj.setDisplaySlot(DisplaySlot.SIDEBAR);

        int score = 15;
        int unique = 0;
        final int TIP_SCORE = 1;
        boolean hasContests = contests != null && !contests.isEmpty();

        if (hasContests) {
            if (score > TIP_SCORE) obj.getScore(uniqueLine(scoreboardContestedTitle, unique++)).setScore(score--);
            for (String line : contests) {
                if (score <= TIP_SCORE) break;
                obj.getScore(uniqueLine(line, unique++)).setScore(score--);
            }
        } else if (mode == ScoreboardMode.ALLIANCES) {
            if (score > TIP_SCORE) obj.getScore(uniqueLine(scoreboardAlliancesTitle, unique++)).setScore(score--);
            if (alliances == null || alliances.isEmpty()) {
                if (score > TIP_SCORE) obj.getScore(uniqueLine(scoreboardNoneLine, unique++)).setScore(score--);
            } else {
                int idx = 1;
                for (String line : alliances) {
                    if (score <= TIP_SCORE) break;
                    String formatted = formatTemplate(
                            scoreboardAllianceEntryFormat,
                            "index", String.valueOf(idx),
                            "alliance", line
                    );
                    obj.getScore(uniqueLine(formatted, unique++)).setScore(score--);
                    idx++;
                }
            }
        } else {
            if (score > TIP_SCORE) obj.getScore(uniqueLine(scoreboardTopKillsTitle, unique++)).setScore(score--);
            if (killsTop.isEmpty()) {
                if (score > TIP_SCORE) obj.getScore(uniqueLine(scoreboardNoneLine, unique++)).setScore(score--);
            } else {
                int idx = 1;
                for (Town t : killsTop) {
                    if (score <= TIP_SCORE) break;
                    String line = formatTemplate(
                            scoreboardTopEntryFormat,
                            "index", String.valueOf(idx),
                            "town", coloredTownNameWithReputation(t),
                            "value", String.valueOf(t.getKills())
                    );
                    obj.getScore(uniqueLine(line, unique++)).setScore(score--);
                    idx++;
                }
            }

            if (score > TIP_SCORE) obj.getScore(uniqueLine(scoreboardTopClaimsTitle, unique++)).setScore(score--);
            if (claimsTop.isEmpty()) {
                if (score > TIP_SCORE) obj.getScore(uniqueLine(scoreboardNoneLine, unique++)).setScore(score--);
            } else {
                int idx = 1;
                for (Town t : claimsTop) {
                    if (score <= TIP_SCORE) break;
                    String line = formatTemplate(
                            scoreboardTopEntryFormat,
                            "index", String.valueOf(idx),
                            "town", coloredTownNameWithReputation(t),
                            "value", String.valueOf(t.claimCount())
                    );
                    obj.getScore(uniqueLine(line, unique++)).setScore(score--);
                    idx++;
                }
            }
        }

        if (!hasContests) {
            // Player personal stats
            PlayerStats stats = getPlayerStats(viewer);
            int claimCount = getTownOf(viewer)
                    .map(Town::claimCount)
                    .orElseGet(() -> {
                        for (Town t : townsByOwner.values()) {
                            if (t.isMember(viewer)) return t.claimCount();
                        }
                        return stats.getClaims();
                    });
            obj.getScore(uniqueLine(scoreboardSeparatorLine, unique++)).setScore(score--);
            if (score > TIP_SCORE) obj.getScore(uniqueLine(scoreboardYouTitle, unique++)).setScore(score--);
            if (score > TIP_SCORE) obj.getScore(uniqueLine(playerStatLine(scoreboardYouKillsFormat, stats.getKills()), unique++)).setScore(score--);
            if (score > TIP_SCORE) obj.getScore(uniqueLine(playerStatLine(scoreboardYouDeathsFormat, stats.getDeaths()), unique++)).setScore(score--);
            if (score > TIP_SCORE) obj.getScore(uniqueLine(playerStatLine(scoreboardYouClaimsFormat, claimCount), unique++)).setScore(score--);
        }

        obj.getScore(uniqueLine(scoreboardHideTip, unique++)).setScore(TIP_SCORE);
        return board;
    }

    private String playerStatLine(String format, int value) {
        return formatTemplate(format, "value", String.valueOf(value));
    }

    private String uniqueLine(String line, int index) {
        ChatColor suffix = SCOREBOARD_SUFFIXES[index % SCOREBOARD_SUFFIXES.length];
        return line + suffix;
    }

    private String townLabel(Town t) {
        if (t == null) return "Unknown";
        ChatColor color = toBukkitColor(t.getColor());
        String base = t.getName() != null ? t.getName() : ownerName(t.getOwner());
        return (color != null ? color.toString() : "") + base + ChatColor.RESET;
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

    private List<String> resolveColoredNames(Set<UUID> ids) {
        List<String> names = new ArrayList<>();
        if (ids == null) return names;
        for (UUID id : ids) {
            Town t = townsByOwner.get(id);
            if (t != null) names.add(coloredTownName(t));
            else {
                OfflinePlayer op = Bukkit.getOfflinePlayer(id);
                names.add(op.getName() != null ? op.getName() : id.toString().substring(0, 8));
            }
        }
        return names;
    }

    public net.md_5.bungee.api.ChatColor toChatColor(VanillaColor c) {
        if (c == null) return null;
        try {
            return net.md_5.bungee.api.ChatColor.valueOf(c.name());
        } catch (Exception ex) {
            return null;
        }
    }

    public ChatColor toBukkitColor(VanillaColor c) {
        if (c == null) return null;
        try {
            return ChatColor.valueOf(c.name());
        } catch (Exception ex) {
            return null;
        }
    }

    public String coloredTownName(Town t) {
        if (t == null) return "Unknown";
        ChatColor color = toBukkitColor(t.getColor());
        String name = t.getName() != null ? t.getName() : ownerName(t.getOwner());
        return (color != null ? color.toString() : ChatColor.GREEN.toString()) + name + ChatColor.RESET;
    }

    public String coloredTownNameWithReputation(Town t) {
        if (t == null) return "Unknown";
        return formatReputation(t) + coloredTownName(t);
    }

    public String coloredTownName(UUID owner, String fallbackName) {
        if (owner != null) {
            Optional<Town> byOwner = getTownByOwner(owner);
            if (byOwner.isPresent()) return coloredTownName(byOwner.get());
        }
        if (fallbackName != null && !fallbackName.isBlank()) {
            Optional<Town> byName = findTown(fallbackName);
            if (byName.isPresent()) return coloredTownName(byName.get());
            return ChatColor.WHITE + fallbackName + ChatColor.RESET;
        }
        return ChatColor.GRAY + "Unknown" + ChatColor.RESET;
    }

    public String formatReputation(Town t) {
        int rep = t != null ? t.getReputation() : 0;
        if (rep <= -6) return ChatColor.DARK_RED + "---" + ChatColor.RESET;
        if (rep <= -3) return ChatColor.RED + "--" + ChatColor.RESET;
        if (rep <= -1) return ChatColor.GOLD + "-" + ChatColor.RESET;
        if (rep <= 2) return ChatColor.YELLOW + "+" + ChatColor.RESET;
        return ChatColor.GREEN + "++" + ChatColor.RESET;
    }

    public String formatReputationWithValue(Town t) {
        int rep = t != null ? t.getReputation() : 0;
        return formatReputation(t) + ChatColor.GRAY + " (" + rep + ")" + ChatColor.RESET;
    }

    public String formatTownAge(Town t) {
        long ageMs = getTownAgeMs(t);
        if (ageMs <= 0L) return "unknown";
        long hours = ageMs / (60L * 60L * 1000L);
        long days = hours / 24L;
        long remHours = hours % 24L;
        if (days > 0) {
            return days + "d " + remHours + "h";
        }
        return hours + "h";
    }

    private void bootstrapHistoryForExistingClaims() {
        for (Town t : townsByOwner.values()) {
            for (ChunkPos pos : t.getClaims()) {
                if (!chunkHistory.containsKey(pos.id()) || chunkHistory.get(pos.id()).isEmpty()) {
                    recordHistory(pos, "EXISTING", t);
                }
            }
        }
    }

    public static class PlayerStats {
        private int kills = 0;
        private int deaths = 0;
        private int claims = 0;

        public int getKills() { return kills; }
        public int getDeaths() { return deaths; }
        public int getClaims() { return claims; }
    }

    public void startContestTicker() {
        if (contestTask != null) contestTask.cancel();
        contestTask = Bukkit.getScheduler().runTaskTimer(plugin, () -> {
            pruneExpiredPendingContests();
            boolean updated = updateContestTimers();
            pruneExpiredContestImmunity();
            if (!contestsById.isEmpty() || updated) {
                refreshLeaderboardScoreboard();
            }
        }, 20L, 20L);
    }

    public void stopContestTicker() {
        if (contestTask != null) {
            contestTask.cancel();
            contestTask = null;
        }
        clearContestBossBar();
    }

    public void broadcastContestUpdate(String message) {
        for (Player p : Bukkit.getOnlinePlayers()) {
            p.sendMessage(message);
        }
        refreshLeaderboardScoreboard();
    }

    public void broadcastWarUpdate(String message) {
        for (Player p : Bukkit.getOnlinePlayers()) {
            p.sendMessage(message);
        }
        refreshLeaderboardScoreboard();
    }
}
