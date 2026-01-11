package com.example.visualclaims;

import org.bukkit.Chunk;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.ChatColor;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

public class CommandHandler implements CommandExecutor {
    private final VisualClaims plugin;
    private final TownManager towns;

    public CommandHandler(VisualClaims plugin, TownManager towns) {
        this.plugin = plugin;
        this.towns = towns;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!(sender instanceof Player p)) {
            sender.sendMessage("This command can only be used in-game.");
            return true;
        }

        String cmd = command.getName().toLowerCase(Locale.ROOT);
        switch (cmd) {
            case "createtown": return createTown(p, args);
            case "deletetown": return deleteTown(p);
            case "claimchunk": return claimChunk(p);
            case "unclaim": return unclaimChunk(p);
            case "autoclaim": return autoClaim(p);
            case "autounclaim": return autoUnclaim(p);
            case "autohistory": return autoHistory(p);
            case "settownname": return setTownName(p, args);
            case "settowncolor": return setTownColor(p, args);
            case "settowndesc": return setTownDesc(p, args);
            case "claiminfo": return claimInfo(p);
            case "claimhistory": return claimHistory(p);
            case "claim": return claimHelp(p, args);
            case "claimlimit": return claimLimit(p, args);
            case "adjustclaims": return adjustClaims(p, args);
            case "transferoutpost": return transferOutpost(p, args);
            case "claimalerts": return toggleClaimAlerts(p);
            case "silentvisit": return toggleSilentVisit(p);
            case "leaderboard":
            case "lb": return leaderboardCommand(p, args);
            case "claimreload": return reloadPlugin(p);
            case "trimoutposts": return trimOutposts(p, args);
            case "towninvite": return inviteToTown(p, args);
            case "jointown": return joinTown(p, args);
            case "townmembers": return showTownMembers(p);
            case "removemember": return removeMember(p, args);
            case "towns": return listTowns(p);
            case "towninfo": return townInfo(p, args);
            case "contest": return contestCommand(p, args);
            case "alliance": return allianceCommand(p, args);
            case "claimadmin": return claimAdminHelp(p, args);
            case "admindeletetown": return adminDeleteTown(p, args);
            default: return false;
        }
    }

    private boolean createTown(Player p, String[] args) {
        if (!p.hasPermission("visclaims.createtown")) {
            p.sendMessage("§cNo permission.");
            return true;
        }
        if (args.length < 1) {
            p.sendMessage("Usage: /createtown <name>");
            return true;
        }
        String name = String.join(" ", args).trim();
        if (name.length() > 15) {
            p.sendMessage("§cTown names must be 15 characters or fewer.");
            return true;
        }
        UUID uuid = p.getUniqueId();
        if (towns.getTownOf(uuid).isPresent()) {
            p.sendMessage("§cYou are already in a town.");
            return true;
        }
        VanillaColor defaultColor = VanillaColor.fromString(plugin.getConfig().getString("default-color", "GREEN"));
        boolean ok = towns.createTown(uuid, name, defaultColor == null ? VanillaColor.GREEN : defaultColor, p.getWorld().getName());
        if (ok) {
            String label = towns.getTownOf(uuid).map(towns::coloredTownName).orElse("§e" + name + "§r");
            p.sendMessage("§aCreated town " + label);
        } else {
            p.sendMessage("§cFailed to create town (name taken?).");
        }
        return true;
    }

    private boolean deleteTown(Player p) {
        if (!p.hasPermission("visclaims.deletetown")) {
            p.sendMessage("§cNo permission.");
            return true;
        }
        UUID uuid = p.getUniqueId();
        Optional<Town> tOpt = towns.getTownByOwner(uuid);
        if (tOpt.isEmpty()) {
            p.sendMessage("§cYou don't own a town.");
            return true;
        }
        boolean ok = towns.deleteTown(uuid);
        p.sendMessage(ok ? "§aDeleted your town." : "§cFailed to delete your town.");
        return true;
    }

    private boolean claimChunk(Player p) {
        if (!p.hasPermission("visclaims.claim")) {
            p.sendMessage("§cNo permission.");
            return true;
        }
        UUID uuid = p.getUniqueId();
        Optional<Town> tOpt = towns.getTownOf(uuid);
        if (tOpt.isEmpty()) {
            p.sendMessage("§cYou are not in a town. Create one with /createtown or accept an invite.");
            return true;
        }
        Chunk c = p.getLocation().getChunk();
        ChunkPos pos = ChunkPos.of(c);
        boolean bypass = p.hasPermission("visclaims.admin");
        Town town = tOpt.get();
        Optional<Town> claimed = towns.getTownAt(c);
        if (claimed.isPresent() && !claimed.get().getOwner().equals(town.getOwner())) {
            return handleContestAttempt(p, town, claimed.get(), pos);
        }
        int max = towns.computeMaxClaims(town.getOwner());
        int allowedOutposts = towns.computeAllowedOutposts(town.getOwner());
        int currentOutposts = towns.countClaimIslands(town);
        if (towns.isOverOutpostCap(town, bypass)) {
            p.sendMessage("§cYou have §e" + currentOutposts + "§c outposts, but are allowed §e" + allowedOutposts + "§c. Unclaim to return to your cap before claiming more.");
            return true;
        }
        if (towns.wouldExceedOutpostCap(town, pos, bypass)) {
            p.sendMessage("§cOutpost cap reached: §e" + currentOutposts + "§c / §e" + allowedOutposts + "§c. Expand existing claims or unclaim to free a slot.");
            return true;
        }
        boolean ok = towns.claimChunk(town, c, bypass, p.getUniqueId());
        if (ok) {
            p.sendMessage("§aClaimed chunk at §e(" + c.getX() + ", " + c.getZ() + ")");
        } else {
            Optional<Town> other = towns.getTownAt(c);
            if (other.isPresent()) p.sendMessage("§cChunk already claimed by §e" + towns.coloredTownName(other.get()));
            else p.sendMessage("§cCannot claim chunk: limit reached (§e" + max + "§c).");
        }
        return true;
    }

    private boolean handleContestAttempt(Player p, Town challenger, Town defender, ChunkPos pos) {
        Optional<ContestState> existing = towns.getContestByChunk(pos);
        if (existing.isPresent()) {
            ContestState contest = existing.get();
            String defenderName = towns.coloredTownName(defender);
            Town challengerTown = towns.getTownByOwner(contest.getChallengerOwner()).orElse(null);
            String challengerName = challengerTown != null ? towns.coloredTownName(challengerTown) : contest.getChallengerOwner().toString();
            long remaining = Math.max(0L, contest.getRemainingMs());
            String paused = contest.isPaused() ? " §7(paused)" : "";
            p.sendMessage("§cThis outpost is already contested between §e" + defenderName + "§c and §e" + challengerName + "§c.");
            p.sendMessage("§cTime left: §e" + formatDuration(remaining) + paused);
            return true;
        }

        if (!towns.isTownOldEnough(challenger)) {
            p.sendMessage("§cYour town must be at least 1 day old to start a contest.");
            return true;
        }
        if (!towns.isTownOldEnough(defender)) {
            p.sendMessage("§cThat town is too new to be contested yet (must be at least 1 day old).");
            return true;
        }

        Set<ChunkPos> cluster = towns.getClaimCluster(defender, pos);
        if (cluster.isEmpty()) {
            p.sendMessage("§cUnable to locate the defender outpost.");
            return true;
        }
        long immunity = towns.getOutpostImmunityRemaining(cluster);
        if (immunity > 0) {
            p.sendMessage("§cThis outpost is protected from contesting for §e" + formatDuration(immunity) + "§c.");
            return true;
        }

        int cost = towns.computeContestCost(challenger, cluster.size());
        int available = towns.computeAvailableClaims(challenger.getOwner());
        if (available < cost) {
            p.sendMessage("§cYou need §e" + cost + "§c available claims to contest this outpost.");
            p.sendMessage("§cYou currently have §e" + Math.max(0, available) + "§c available claims.");
            return true;
        }

        Optional<TownManager.PendingContest> pending = towns.getPendingContest(p.getUniqueId());
        if (pending.isPresent()
                && pending.get().getDefenderOwner().equals(defender.getOwner())
                && pending.get().getChunkId().equals(pos.id())) {
            towns.clearPendingContest(p.getUniqueId());
            boolean ok = towns.startContest(challenger, defender, cluster, cost);
            if (!ok) {
                p.sendMessage("§cUnable to start contest. This outpost may already be contested.");
                return true;
            }
            towns.broadcastContestUpdate("§cContest started: §e" + towns.coloredTownName(challenger) + " §7vs §e" + towns.coloredTownName(defender) + " §7(" + cluster.size() + " chunks, §e" + cost + "§7 claims).");
            p.sendMessage("§cThis permanently deducts §e" + cost + "§c of your claims.");
            p.sendMessage("§eHold the outpost for the full timer to win without a kill, but it costs an extra §f" + cost + "§e claims.");
            return true;
        }

        towns.setPendingContest(p.getUniqueId(), defender.getOwner(), pos);
        p.sendMessage("§eThis chunk belongs to §f" + towns.coloredTownName(defender) + "§e.");
        p.sendMessage("§eContest entire outpost of §f" + cluster.size() + "§e chunks for §f" + cost + "§e claims (scaled by size + reputation).");
        p.sendMessage("§eThis permanently deducts §f" + cost + "§e of your claims.");
        p.sendMessage("§eHolding the land for the full timer wins without a kill but costs an extra §f" + cost + "§e claims.");
        p.sendMessage("§7Leaving the outpost removes the hold-win option.");
        p.sendMessage("§7Timer only ticks while both owners are online.");
        p.sendMessage("§7Type §e/claimchunk §7again within 15 seconds to confirm.");
        return true;
    }

    private boolean unclaimChunk(Player p) {
        if (!p.hasPermission("visclaims.unclaim")) {
            p.sendMessage("§cNo permission.");
            return true;
        }
        UUID uuid = p.getUniqueId();
        Chunk c = p.getLocation().getChunk();
        ChunkPos pos = ChunkPos.of(c);

        Optional<Town> tOpt = towns.getTownOf(uuid);
        if (tOpt.isEmpty()) {
            p.sendMessage("§cYou are not in a town.");
            return true;
        }
        Town t = tOpt.get();
        if (towns.isChunkContested(pos)) {
            p.sendMessage("§cYou cannot unclaim contested chunks.");
            return true;
        }
        boolean ok = towns.unclaimChunk(t, pos);

        // Admin override: allow force-unclaim regardless of owner
        if (!ok && p.hasPermission("visclaims.admin")) {
            ok = towns.forceUnclaim(pos);
            if (ok) {
                p.sendMessage("§c[Admin] Force-unclaimed chunk at §e(" + c.getX() + ", " + c.getZ() + ")");
                return true;
            }
        }

        if (ok) {
            p.sendMessage("§aUnclaimed chunk at §e(" + c.getX() + ", " + c.getZ() + ")");
        } else {
            p.sendMessage("§cThis chunk is not part of your town.");
        }
        return true;
    }

    private boolean autoClaim(Player p) {
        if (!p.hasPermission("visclaims.autoclaim")) {
            p.sendMessage("§cNo permission.");
            return true;
        }
        if (towns.getTownOf(p.getUniqueId()).isEmpty()) {
            p.sendMessage("§cYou need to be in a town to enable autoclaim.");
            return true;
        }
        boolean now = plugin.getMoveListener().toggleAutoclaim(p.getUniqueId());
        p.sendMessage(now ? "§aAutoclaim enabled." : "§cAutoclaim disabled.");
        return true;
    }

    private boolean setTownName(Player p, String[] args) {
        if (!p.hasPermission("visclaims.setname")) {
            p.sendMessage("§cNo permission.");
            return true;
        }
        if (args.length < 1) {
            p.sendMessage("§cUsage: /settownname <name>");
            return true;
        }
        Optional<Town> tOpt = towns.getTownByOwner(p.getUniqueId());
        if (tOpt.isEmpty()) {
            p.sendMessage("§cYou don't own a town.");
            return true;
        }
        String newName = String.join(" ", args).trim();
        if (newName.length() > 15) {
            p.sendMessage("§cTown names must be 15 characters or fewer.");
            return true;
        }
        Town t = tOpt.get();
        t.setName(newName);
        towns.saveTown(t);
        towns.refreshTownAreas(t);
        towns.refreshLeaderboardScoreboard();
        p.sendMessage("§aTown renamed to " + towns.coloredTownName(t));
        return true;
    }

    private boolean setTownColor(Player p, String[] args) {
        if (!p.hasPermission("visclaims.setcolor")) {
            p.sendMessage("§cNo permission.");
            return true;
        }
        if (args.length != 1) {
            p.sendMessage("§cUsage: /settowncolor <color>");
            return true;
        }
        VanillaColor c = VanillaColor.fromString(args[0]);
        if (c == null) {
            p.sendMessage("§cInvalid color. Valid: BLACK, DARK_BLUE, DARK_GREEN, DARK_AQUA, DARK_RED, DARK_PURPLE, GOLD, GRAY, DARK_GRAY, BLUE, GREEN, AQUA, RED, LIGHT_PURPLE, YELLOW, WHITE");
            return true;
        }
        Optional<Town> tOpt = towns.getTownByOwner(p.getUniqueId());
        if (tOpt.isEmpty()) {
            p.sendMessage("§cYou don't own a town.");
            return true;
        }
        Town t = tOpt.get();
        t.setColor(c);
        towns.saveTown(t);
        towns.refreshTownAreas(t);
        towns.refreshLeaderboardScoreboard();
        p.sendMessage("§aTown color set to §e" + c.name());
        return true;
    }

    private boolean claimInfo(Player p) {
        if (!p.hasPermission("visclaims.claiminfo")) {
            p.sendMessage("§cNo permission.");
            return true;
        }
        Optional<Town> tOpt = towns.getTownOf(p.getUniqueId());
        if (tOpt.isEmpty()) {
            p.sendMessage("§cYou are not in a town.");
            return true;
        }
        sendTownInfo(p, tOpt.get());
        return true;
    }

    private boolean claimLimit(Player p, String[] args) {
        boolean isAdmin = p.hasPermission("visclaims.admin");
        UUID targetId = p.getUniqueId();
        String targetName = p.getName();

        if (args.length == 1) {
            if (!isAdmin) {
                p.sendMessage("§cNo permission to check others.");
                return true;
            }
            var offline = plugin.getServer().getOfflinePlayer(args[0]);
            targetId = offline.getUniqueId();
            targetName = offline.getName() != null ? offline.getName() : args[0];
        } else if (args.length > 1) {
            p.sendMessage("Usage: /claimlimit [player]");
            return true;
        }

        Optional<Town> townOpt = towns.getTownOf(targetId);
        if (townOpt.isEmpty()) {
            p.sendMessage("§cThat player is not in a town.");
            return true;
        }

        Town t = townOpt.get();
        int playtimeHours = towns.getPlaytimeHours(t.getOwner());
        int claimed = townOpt.get().claimCount();
        int bonus = townOpt.get().getBonusChunks();
        int contestedSpent = townOpt.get().getContestedClaimsSpent();
        int chunksPerHour = Math.max(1, plugin.getConfig().getInt("chunks-per-hour", 2));
        boolean usingPlaytime = plugin.getConfig().getBoolean("use-playtime-scaling", false);
        int baseMax = plugin.getConfig().getInt("max-claims-per-player", 64);
        int playtimeAllowance = usingPlaytime ? playtimeHours * chunksPerHour : 0;
        int theoretical = Math.max(0, baseMax + playtimeAllowance + bonus - contestedSpent);
        int limit = Math.max(theoretical, claimed);
        int overflow = Math.max(0, claimed - theoretical);
        int allowedOutposts = towns.computeAllowedOutposts(t.getOwner());
        int currentOutposts = towns.countClaimIslands(t);

        p.sendMessage("§e--- Claim Limit for " + targetName + " ---");
        p.sendMessage("§7Base cap: §f" + baseMax);
        if (usingPlaytime) {
            p.sendMessage("§7Playtime: §f" + playtimeHours + "h §7@ §f" + chunksPerHour + " §7chunks/hour => §f" + playtimeAllowance);
        }
        p.sendMessage("§7Bonus: §f" + bonus);
        if (contestedSpent > 0) {
            p.sendMessage("§7Contested claims: §c-" + contestedSpent);
        }
        p.sendMessage("§7Allowed total: §f" + theoretical + (overflow > 0 ? " §8(overflow by " + overflow + " grandfathered)" : ""));
        p.sendMessage("§7Claims held: §f" + claimed);
        p.sendMessage("§7Effective limit (never lowers below claims): §f" + limit);
        p.sendMessage("§7Outposts (separate clusters): §f" + currentOutposts + " §7/ §f" + allowedOutposts + " §8(first claim exempt; new isolated clusters blocked when over the cap; expansions allowed)");
        return true;
    }

    private boolean transferOutpost(Player p, String[] args) {
        if (!p.hasPermission("visclaims.transferoutpost")) {
            p.sendMessage("§cNo permission.");
            return true;
        }
        if (args.length < 1) {
            p.sendMessage("Usage: /transferoutpost <town>");
            return true;
        }
        ChunkPos pos = ChunkPos.of(p.getLocation().getChunk());
        Optional<Town> fromOpt = towns.getTownAt(p.getLocation().getChunk());
        if (fromOpt.isEmpty()) {
            p.sendMessage("§cThis chunk is not claimed.");
            return true;
        }
        Town from = fromOpt.get();
        boolean isAdmin = p.hasPermission("visclaims.admin");
        if (!isAdmin && !from.getOwner().equals(p.getUniqueId())) {
            p.sendMessage("§cOnly the town owner can transfer outposts.");
            return true;
        }
        if (towns.isChunkContested(pos)) {
            p.sendMessage("§cYou cannot transfer contested chunks.");
            return true;
        }
        String targetName = String.join(" ", args).trim();
        Optional<Town> toOpt = towns.findTown(targetName);
        if (toOpt.isEmpty()) {
            p.sendMessage("§cTown not found: §f" + targetName);
            return true;
        }
        Town to = toOpt.get();
        if (from.getOwner().equals(to.getOwner())) {
            p.sendMessage("§cThat town already owns this outpost.");
            return true;
        }
        Set<ChunkPos> cluster = towns.getClaimCluster(from, pos);
        if (cluster.isEmpty()) {
            p.sendMessage("§cUnable to locate the outpost cluster.");
            return true;
        }
        boolean ok = towns.transferOutpost(from, to, cluster);
        if (!ok) {
            p.sendMessage("§cUnable to transfer this outpost.");
            return true;
        }
        p.sendMessage("§aTransferred §e" + cluster.size() + "§a chunks to §e" + towns.coloredTownName(to) + "§a.");
        Player targetOwner = plugin.getServer().getPlayer(to.getOwner());
        if (targetOwner != null) {
            targetOwner.sendMessage("§e" + towns.coloredTownName(from) + " §7transferred an outpost (" + cluster.size() + " chunks) to your town.");
        }
        return true;
    }

    private boolean adjustClaims(Player p, String[] args) {
        if (!p.hasPermission("visclaims.admin")) {
            p.sendMessage("§cNo permission.");
            return true;
        }
        if (args.length != 3) {
            p.sendMessage("Usage: /adjustclaims <player> <add|remove> <amount>");
            return true;
        }
        var offline = plugin.getServer().getOfflinePlayer(args[0]);
        UUID targetId = offline.getUniqueId();
        String mode = args[1].toLowerCase(Locale.ROOT);
        int amount;
        try {
            amount = Integer.parseInt(args[2]);
        } catch (NumberFormatException ex) {
            p.sendMessage("§cAmount must be a number.");
            return true;
        }
        if (amount < 0) {
            p.sendMessage("§cAmount must be positive.");
            return true;
        }
        int delta = mode.equals("add") ? amount : mode.equals("remove") ? -amount : 0;
        if (delta == 0) {
            p.sendMessage("§cMode must be add or remove.");
            return true;
        }
        boolean ok = towns.adjustBonus(targetId, delta);
        if (!ok) {
            p.sendMessage("§cThat player does not own a town.");
            return true;
        }
        Optional<Town> tOpt = towns.getTownByOwner(targetId);
        int newBonus = tOpt.map(Town::getBonusChunks).orElse(0);
        int newLimit = towns.computeMaxClaims(targetId);
        String name = offline.getName() != null ? offline.getName() : args[0];
        p.sendMessage("§aUpdated bonus for §e" + name + "§a to §e" + newBonus + "§a. New limit: §e" + newLimit);
        return true;
    }

    private boolean claimHelp(Player p, String[] args) {
        if (!p.hasPermission("visclaims.help")) {
            p.sendMessage("§cNo permission.");
            return true;
        }
        if (args.length > 0 && args[0].equalsIgnoreCase("admin")) {
            if (!p.hasPermission("visclaims.adminhelp")) {
                p.sendMessage("§cNo permission.");
                return true;
            }
        p.sendMessage("§c--- Admin Claim Commands ---");
        p.sendMessage("§f/claimreload §7- Reload VisualClaims config and data");
        p.sendMessage("§f/adjustclaims <player> <add|remove> <amount> §7- Modify bonus claims");
        p.sendMessage("§f/admindeletetown <town> §7- Delete a town by name/owner");
        p.sendMessage("§f/unclaim §7(with visclaims.admin) - Force unclaim any chunk");
            p.sendMessage("§f/trimoutposts <player> [count] §7- Remove the smallest outpost clusters for a player");
            return true;
        }
        p.sendMessage("§e--- Claim Commands ---");
        p.sendMessage("§f/createtown <name> §7- Create your town");
        p.sendMessage("§f/deletetown §7- Delete your town");
        p.sendMessage("§f/claimchunk §7- Claim the current chunk (or contest enemy outposts)");
        p.sendMessage("§7Contests: 1h timer (ticks only while both owners are online); holding the land wins but costs extra claims.");
        p.sendMessage("§f/contest §7- Learn about contesting land and Rock Paper Scissors");
        p.sendMessage("§f/contest cancel §7- Forfeit your active contest (no refund)");
        p.sendMessage("§f/unclaim §7- Unclaim the current chunk");
        p.sendMessage("§f/autoclaim §7- Toggle autoclaim for chunks");
        p.sendMessage("§f/autounclaim §7- Toggle auto-unclaim for owned chunks as you move");
        p.sendMessage("§f/autohistory §7- Toggle automatic chunk history feed");
        p.sendMessage("§f/claimalerts §7- Toggle your own entering/leaving messages");
        p.sendMessage("§f/silentvisit §7- Toggle silent entries into other towns (permission)");
        p.sendMessage("§f/leaderboard [toggle] §7- View leaderboard or toggle the sidebar");
        p.sendMessage("§f/settownname <name> §7- Rename your town");
        p.sendMessage("§f/settowncolor <color> §7- Change your town's color");
        p.sendMessage("§f/settowndesc <text> §7- Set your town description");
        p.sendMessage("§f/towninvite <player> §7- Invite a player to your town");
        p.sendMessage("§f/jointown <town> §7- Accept a town invite");
        p.sendMessage("§f/townmembers §7- List members in your town");
        p.sendMessage("§f/removemember <player> §7- Remove a member from your town");
        p.sendMessage("§f/transferoutpost <town> §7- Transfer the current outpost to another town (owner only)");
        p.sendMessage("§f/towns §7- Public town listing");
        p.sendMessage("§f/towninfo [town] §7- View town details (defaults to yours)");
        p.sendMessage("§f/claimhistory §7- Show history for the current chunk");
        p.sendMessage("§f/alliance <town>|accept <town>|remove <town> §7- Manage alliances");
        p.sendMessage("§f/claimlimit [player] §7- Show playtime-based claim limits");
        p.sendMessage("§f/claim help §7- Show this help message");
        if (p.hasPermission("visclaims.adminhelp")) {
            p.sendMessage("§f/claim admin §7- Show admin claim commands");
        }
        return true;
    }

    private boolean setTownDesc(Player p, String[] args) {
        if (!p.hasPermission("visclaims.setdesc")) {
            p.sendMessage("§cNo permission.");
            return true;
        }
        if (args.length < 1) {
            p.sendMessage("Usage: /settowndesc <text>");
            return true;
        }
        Optional<Town> tOpt = towns.getTownByOwner(p.getUniqueId());
        if (tOpt.isEmpty()) {
            p.sendMessage("§cYou don't own a town.");
            return true;
        }
        Town t = tOpt.get();
        String desc = String.join(" ", args).trim();
        t.setDescription(desc);
        towns.saveTown(t);
        p.sendMessage("§aUpdated town description.");
        return true;
    }

    private boolean inviteToTown(Player p, String[] args) {
        if (!p.hasPermission("visclaims.invite")) {
            p.sendMessage("§cNo permission.");
            return true;
        }
        if (args.length != 1) {
            p.sendMessage("Usage: /towninvite <player>");
            return true;
        }
        Optional<Town> tOpt = towns.getTownByOwner(p.getUniqueId());
        if (tOpt.isEmpty()) {
            p.sendMessage("§cYou must own a town to invite players.");
            return true;
        }
        var target = plugin.getServer().getOfflinePlayer(args[0]);
        if (target == null || target.getUniqueId() == null) {
            p.sendMessage("§cUnknown player.");
            return true;
        }
        if (towns.getTownOf(target.getUniqueId()).isPresent()) {
            p.sendMessage("§cThat player is already in a town.");
            return true;
        }
        boolean ok = towns.invitePlayer(p.getUniqueId(), target.getUniqueId());
        if (!ok) {
            p.sendMessage("§cFailed to send invite.");
            return true;
        }
        p.sendMessage("§aInvitation sent to §e" + (target.getName() != null ? target.getName() : args[0]));
        if (target.isOnline()) {
            target.getPlayer().sendMessage("§aYou have been invited to join §e" + towns.coloredTownName(tOpt.get()) + "§a. Use §e/jointown " + tOpt.get().getName() + " §ato accept.");
        }
        return true;
    }

    private boolean joinTown(Player p, String[] args) {
        if (!p.hasPermission("visclaims.join")) {
            p.sendMessage("§cNo permission.");
            return true;
        }
        if (towns.getTownOf(p.getUniqueId()).isPresent()) {
            p.sendMessage("§cYou are already in a town. Leave or ask an admin if you need to switch.");
            return true;
        }
        String hint = args.length >= 1 ? String.join(" ", args) : "";
        Optional<Town> joined = towns.acceptInvite(p.getUniqueId(), hint);
        if (joined.isEmpty()) {
            p.sendMessage("§cYou do not have a valid invite for that town.");
            return true;
        }
        Town t = joined.get();
        p.sendMessage("§aJoined town §e" + towns.coloredTownName(t));
        towns.messageTown(t, "§e" + p.getName() + " §7joined your town.");
        return true;
    }

    private boolean showTownMembers(Player p) {
        if (!p.hasPermission("visclaims.members")) {
            p.sendMessage("§cNo permission.");
            return true;
        }
        Optional<Town> tOpt = towns.getTownByOwner(p.getUniqueId());
        if (tOpt.isEmpty()) {
            p.sendMessage("§cYou must own a town to view members.");
            return true;
        }
        Town t = tOpt.get();
        List<String> members = resolveNames(t.getMembers());
        p.sendMessage("§e--- Members of " + towns.coloredTownName(t) + "§e ---");
        p.sendMessage("§7Owner: §f" + p.getName());
        p.sendMessage("§7Members: §f" + (members.isEmpty() ? "none" : String.join(", ", members)));
        return true;
    }

    private boolean removeMember(Player p, String[] args) {
        if (!p.hasPermission("visclaims.kick")) {
            p.sendMessage("§cNo permission.");
            return true;
        }
        if (args.length != 1) {
            p.sendMessage("Usage: /removemember <player>");
            return true;
        }
        Optional<Town> tOpt = towns.getTownByOwner(p.getUniqueId());
        if (tOpt.isEmpty()) {
            p.sendMessage("§cYou must own a town to remove members.");
            return true;
        }
        var target = plugin.getServer().getOfflinePlayer(args[0]);
        if (target == null || target.getUniqueId() == null) {
            p.sendMessage("§cUnknown player.");
            return true;
        }
        if (!tOpt.get().getMembers().contains(target.getUniqueId())) {
            p.sendMessage("§cThat player is not a member of your town.");
            return true;
        }
        boolean ok = towns.removeMember(p.getUniqueId(), target.getUniqueId());
        if (ok) {
            p.sendMessage("§aRemoved §e" + (target.getName() != null ? target.getName() : args[0]) + " §afrom your town.");
            if (target.isOnline()) target.getPlayer().sendMessage("§cYou have been removed from town §e" + towns.coloredTownName(tOpt.get()));
        } else {
            p.sendMessage("§cFailed to remove member.");
        }
        return true;
    }

    private boolean listTowns(Player p) {
        if (!p.hasPermission("visclaims.towns")) {
            p.sendMessage("§cNo permission.");
            return true;
        }
        p.sendMessage("§e--- Towns ---");
        for (Town t : towns.allTowns()) {
            TextComponent line = new TextComponent(" - ");
            ChatColor color = towns.toChatColor(t.getColor());
            TextComponent name = new TextComponent(t.getName());
            if (color != null) name.setColor(color);
            TextComponent info = new TextComponent(" [Info]");
            info.setColor(ChatColor.YELLOW);
            info.setBold(true);
            info.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, "/towninfo " + t.getName()));
            line.addExtra(name);
            line.addExtra(info);
            p.spigot().sendMessage(line);
        }
        return true;
    }

    private boolean townInfo(Player p, String[] args) {
        if (!p.hasPermission("visclaims.towninfo")) {
            p.sendMessage("§cNo permission.");
            return true;
        }
        Optional<Town> tOpt;
        if (args.length < 1) {
            tOpt = towns.getTownOf(p.getUniqueId());
            if (tOpt.isEmpty()) {
                p.sendMessage("§cYou are not in a town.");
                p.sendMessage("Usage: /towninfo <town or owner>");
                return true;
            }
        } else {
            tOpt = towns.findTown(String.join(" ", args));
        }
        if (tOpt.isEmpty()) {
            p.sendMessage("§cNo matching town found.");
            return true;
        }
        sendTownInfo(p, tOpt.get());
        return true;
    }

    private void sendTownInfo(Player p, Town t) {
        String ownerName = plugin.getServer().getOfflinePlayer(t.getOwner()).getName();
        List<String> members = resolveNames(t.getMembers());
        List<String> allies = resolveTownNames(t.getAllies());
        p.sendMessage("§e--- Town " + towns.coloredTownName(t) + "§e ---");
        p.sendMessage("§7Owner: §f" + (ownerName != null ? ownerName : t.getOwner()));
        p.sendMessage("§7Description: §f" + t.getDescription());
        p.sendMessage("§7Color: §f" + t.getColorName());
        p.sendMessage("§7Chunks claimed: §f" + t.claimCount());
        p.sendMessage("§7Members: §f" + (members.isEmpty() ? "none" : String.join(", ", members)));
        p.sendMessage("§7Allies: §f" + (allies.isEmpty() ? "none" : String.join(", ", allies)));
        p.sendMessage("§7Town age: §f" + towns.formatTownAge(t));
        p.sendMessage("§7Reputation: " + towns.formatReputation(t));
    }

    private boolean claimHistory(Player p) {
        if (!p.hasPermission("visclaims.history")) {
            p.sendMessage("§cNo permission.");
            return true;
        }
        ChunkPos pos = ChunkPos.of(p.getLocation().getChunk());
        List<ChunkHistoryEntry> entries = towns.getHistoryFor(pos);
        p.sendMessage("§e--- Claim History (" + pos.getX() + "," + pos.getZ() + ") ---");
        if (entries.isEmpty()) {
            p.sendMessage("§7No history for this chunk.");
            return true;
        }
        int shown = 0;
        for (ChunkHistoryEntry e : entries) {
            if (shown >= 5) break;
            String allies = e.getAlliances().isEmpty() ? "none" : String.join(", ", e.getAlliances());
            String coloredName = towns.coloredTownName(e.getTownOwner(), e.getTownName());
            p.sendMessage("§f" + e.getAction() + " §7by " + coloredName + " §7(" + formatAgo(e.getTimestamp()) + ")");
            p.sendMessage("§7Allies: §f" + allies);
            shown++;
        }
        return true;
    }

    private boolean autoHistory(Player p) {
        if (!p.hasPermission("visclaims.autohistory")) {
            p.sendMessage("§cNo permission.");
            return true;
        }
        boolean now = plugin.getMoveListener().toggleAutohistory(p.getUniqueId());
        p.sendMessage(now ? "§aAutohistory enabled." : "§cAutohistory disabled.");
        return true;
    }

    private boolean autoUnclaim(Player p) {
        if (!p.hasPermission("visclaims.autounclaim")) {
            p.sendMessage("§cNo permission.");
            return true;
        }
        boolean now = plugin.getMoveListener().toggleAutounclaim(p.getUniqueId());
        p.sendMessage(now ? "§cAutounclaim enabled. You will unclaim owned chunks as you move." : "§aAutounclaim disabled.");
        return true;
    }

    private boolean toggleClaimAlerts(Player p) {
        boolean enabled = plugin.getMoveListener().toggleChunkAlerts(p.getUniqueId());
        p.sendMessage(enabled ? "§aClaim entry/exit messages enabled." : "§cClaim entry/exit messages disabled.");
        return true;
    }

    private boolean toggleSilentVisit(Player p) {
        if (!p.hasPermission("visclaims.silentvisit")) {
            p.sendMessage("§cNo permission.");
            return true;
        }
        boolean silent = towns.toggleSilentVisit(p.getUniqueId());
        p.sendMessage(silent ? "§aSilent visiting enabled. Towns will not be alerted when you enter their land." : "§cSilent visiting disabled. Towns will be alerted when you enter their land.");
        return true;
    }

    private boolean reloadPlugin(Player p) {
        if (!p.hasPermission("visclaims.admin")) {
            p.sendMessage("§cNo permission.");
            return true;
        }
        plugin.reloadConfig();
        towns.reloadAll();
        p.sendMessage("§aVisualClaims reloaded.");
        return true;
    }

    private boolean trimOutposts(Player p, String[] args) {
        if (!p.hasPermission("visclaims.admin")) {
            p.sendMessage("§cNo permission.");
            return true;
        }
        if (args.length < 1 || args.length > 2) {
            p.sendMessage("Usage: /trimoutposts <player> [count]");
            return true;
        }
        var target = plugin.getServer().getOfflinePlayer(args[0]);
        UUID targetId = target.getUniqueId();
        if (targetId == null) {
            p.sendMessage("§cUnknown player.");
            return true;
        }
        int count = 1;
        if (args.length == 2) {
            try {
                count = Math.max(1, Integer.parseInt(args[1]));
            } catch (NumberFormatException ex) {
                p.sendMessage("§cCount must be a number.");
                return true;
            }
        }
        TownManager.RemovalResult res = towns.trimSmallestOutposts(targetId, count);
        String name = target.getName() != null ? target.getName() : args[0];
        p.sendMessage("§eTrimmed §f" + res.clusters() + " §eoutpost(s) for §f" + name + " §e(removed §f" + res.chunks() + " §echunk(s)).");
        return true;
    }

    private boolean leaderboardCommand(Player p, String[] args) {
        if (!p.hasPermission("visclaims.leaderboard")) {
            p.sendMessage("§cNo permission.");
            return true;
        }
        if (args.length > 0 && (args[0].equalsIgnoreCase("toggle") || args[0].equalsIgnoreCase("scoreboard") || args[0].equalsIgnoreCase("sb"))) {
            boolean enabled = towns.toggleLeaderboardScoreboard(p.getUniqueId());
            p.sendMessage(enabled ? "§aLeaderboard scoreboard enabled." : "§cLeaderboard scoreboard disabled.");
            return true;
        }
        List<Town> topKills = towns.topByKills(3);
        List<Town> topClaims = towns.topByClaims(3);

        p.sendMessage("§e--- Town Leaderboard ---");
        p.sendMessage("§bTop Kills:");
        if (topKills.isEmpty()) {
            p.sendMessage("  §7None yet.");
        } else {
            int idx = 1;
            for (Town t : topKills) {
                p.sendMessage("  §7" + idx + ". §f" + towns.coloredTownNameWithReputation(t) + " §7- §e" + t.getKills() + " §7kills");
                idx++;
            }
        }

        p.sendMessage("§6Top Claims:");
        if (topClaims.isEmpty()) {
            p.sendMessage("  §7None yet.");
        } else {
            int idx = 1;
            for (Town t : topClaims) {
                p.sendMessage("  §7" + idx + ". §f" + towns.coloredTownNameWithReputation(t) + " §7- §e" + t.claimCount() + " §7claims");
                idx++;
            }
        }

        TownManager.PlayerStats stats = towns.getPlayerStats(p.getUniqueId());
        int townClaims = towns.getTownOf(p.getUniqueId()).map(Town::claimCount).orElse(stats.getClaims());
        p.sendMessage("§aYour Stats: §fKills §e" + stats.getKills() + " §7/ §fDeaths §e" + stats.getDeaths() + " §7/ §fClaims §e" + townClaims);
        p.sendMessage("§7Use §e/leaderboard toggle §7to enable the sidebar.");
        return true;
    }

    private boolean contestCommand(Player p, String[] args) {
        if (!p.hasPermission("visclaims.contest")) {
            p.sendMessage("§cNo permission.");
            return true;
        }
        if (args.length == 0) {
            p.sendMessage("§e--- Contested Land ---");
            p.sendMessage("§fHow contests work:");
            p.sendMessage("§7- Start by claiming a chunk already owned by another town.");
            p.sendMessage("§7- Your town and the defender must both be at least 1 day old.");
            p.sendMessage("§7- The contest costs claims based on outpost size and your reputation.");
            p.sendMessage("§7- This permanently deducts those claims from your total (never refunded).");
            p.sendMessage("§7- Timer is 1 hour and only ticks while both owners are online.");
            p.sendMessage("§7- Only one contest can be active per outpost at a time.");
            p.sendMessage("§7- Win by: owner kill, hold the outpost for the full timer, or Rock Paper Scissors.");
            p.sendMessage("§7- Holding wins only if you stay inside the outpost the whole time.");
            p.sendMessage("§7- Leaving the outpost removes the hold-win option.");
            p.sendMessage("§7- Holding costs an extra set of claims equal to your original cost.");
            p.sendMessage("§7- If the timer ends without a win, land reverts and is immune for 7 days.");
            p.sendMessage("§7- Contests auto-expire after 7 days with no refund.");
            p.sendMessage("§7- Use §e/contest cancel§7 to forfeit your contest (no refund).");
            p.sendMessage("§fRock Paper Scissors:");
            p.sendMessage("§7- Both owners must be online.");
            p.sendMessage("§7- Use §e/contest rps <rock|paper|scissors>§7 to choose.");
            return true;
        }
        if (args[0].equalsIgnoreCase("cancel")) {
            Optional<Town> ownerTown = towns.getTownByOwner(p.getUniqueId());
            if (ownerTown.isEmpty()) {
                p.sendMessage("§cOnly town owners can cancel a contest.");
                return true;
            }
            ContestState contest = selectContestForChallenger(p);
            if (contest == null) return true;
            if (!towns.cancelContest(p.getUniqueId(), contest)) {
                p.sendMessage("§cUnable to cancel this contest.");
                return true;
            }
            p.sendMessage("§cContest canceled. Your claims are not refunded.");
            return true;
        }
        if (args[0].equalsIgnoreCase("rps")) {
            if (args.length != 2) {
                p.sendMessage("Usage: /contest rps <rock|paper|scissors>");
                return true;
            }
            Optional<Town> ownerTown = towns.getTownByOwner(p.getUniqueId());
            if (ownerTown.isEmpty()) {
                p.sendMessage("§cOnly town owners can play Rock Paper Scissors for a contest.");
                return true;
            }
            TownManager.RpsChoice choice;
            try {
                choice = TownManager.RpsChoice.valueOf(args[1].toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException ex) {
                p.sendMessage("§cInvalid choice. Use: rock, paper, or scissors.");
                return true;
            }
            ContestState contest = selectContestForPlayer(p);
            if (contest == null) return true;
            String msg = towns.handleRpsChoice(p, contest, choice);
            if (msg != null && !msg.isBlank()) {
                p.sendMessage(msg);
            }
            return true;
        }

        p.sendMessage("Usage: /contest [rps <rock|paper|scissors>|cancel]");
        return true;
    }

    private ContestState selectContestForPlayer(Player p) {
        List<ContestState> contests = towns.getContestsForOwner(p.getUniqueId());
        if (contests.isEmpty()) {
            p.sendMessage("§cYou are not part of any active contest.");
            return null;
        }
        if (contests.size() == 1) {
            return contests.get(0);
        }
        ContestState byChunk = towns.getContestByChunk(ChunkPos.of(p.getLocation().getChunk())).orElse(null);
        if (byChunk != null && (byChunk.getDefenderOwner().equals(p.getUniqueId()) || byChunk.getChallengerOwner().equals(p.getUniqueId()))) {
            return byChunk;
        }
        p.sendMessage("§cYou are in multiple contests. Stand inside the contested land you want to resolve and run the command again.");
        return null;
    }

    private ContestState selectContestForChallenger(Player p) {
        List<ContestState> contests = towns.getContestsForOwner(p.getUniqueId());
        if (contests.isEmpty()) {
            p.sendMessage("§cYou are not contesting any outposts right now.");
            return null;
        }
        List<ContestState> challengerContests = new ArrayList<>();
        UUID ownerId = p.getUniqueId();
        for (ContestState contest : contests) {
            if (ownerId.equals(contest.getChallengerOwner())) {
                challengerContests.add(contest);
            }
        }
        if (challengerContests.isEmpty()) {
            p.sendMessage("§cOnly the contesting town owner can cancel a contest.");
            return null;
        }
        if (challengerContests.size() == 1) {
            return challengerContests.get(0);
        }
        ContestState byChunk = towns.getContestByChunk(ChunkPos.of(p.getLocation().getChunk())).orElse(null);
        if (byChunk != null && ownerId.equals(byChunk.getChallengerOwner())) {
            return byChunk;
        }
        p.sendMessage("§cYou are contesting multiple outposts. Stand inside the contested land you want to cancel and run the command again.");
        return null;
    }

    private boolean allianceCommand(Player p, String[] args) {
        Optional<Town> myTownOpt = towns.getTownOf(p.getUniqueId());
        if (myTownOpt.isEmpty()) {
            p.sendMessage("§cYou need to be in a town to manage alliances.");
            return true;
        }
        if (args.length < 1) {
            p.sendMessage("Usage: /alliance <town>|accept <town>|remove <town>");
            return true;
        }
        Town myTown = myTownOpt.get();
        String sub = args[0].toLowerCase(Locale.ROOT);
        if (sub.equals("accept")) {
            if (args.length < 2) {
                p.sendMessage("Usage: /alliance accept <town>");
                return true;
            }
            Optional<Town> targetOpt = towns.findTown(String.join(" ", Arrays.copyOfRange(args, 1, args.length)));
            if (targetOpt.isEmpty()) {
                p.sendMessage("§cNo matching town found.");
                return true;
            }
            Town other = targetOpt.get();
            boolean ok = towns.acceptAlliance(myTown.getOwner(), other.getOwner());
            p.sendMessage(ok ? "§aAlliance accepted with §e" + towns.coloredTownName(other) : "§cNo pending alliance invite from that town.");
            if (ok) {
                towns.messageTown(other, "§a" + towns.coloredTownName(myTown) + " §7accepted your alliance.");
            }
            return true;
        } else if (sub.equals("remove")) {
            if (args.length < 2) {
                p.sendMessage("Usage: /alliance remove <town>");
                return true;
            }
            Optional<Town> targetOpt = towns.findTown(String.join(" ", Arrays.copyOfRange(args, 1, args.length)));
        if (targetOpt.isEmpty()) {
            p.sendMessage("§cNo matching town found.");
            return true;
        }
        Town other = targetOpt.get();
        boolean ok = towns.removeAlliance(myTown.getOwner(), other.getOwner());
        p.sendMessage(ok ? "§cAlliance removed with §e" + towns.coloredTownName(other) : "§cNo alliance existed with that town.");
        if (ok) {
            towns.messageTown(other, "§c" + towns.coloredTownName(myTown) + " §7ended the alliance.");
        }
        return true;
        }

        Optional<Town> targetOpt = towns.findTown(String.join(" ", args));
        if (targetOpt.isEmpty()) {
            p.sendMessage("§cNo matching town found.");
            return true;
        }
        Town other = targetOpt.get();
        if (myTown.getOwner().equals(other.getOwner())) {
            p.sendMessage("§cYou cannot ally with yourself.");
            return true;
        }
        if (myTown.getAllies().contains(other.getOwner())) {
            p.sendMessage("§cYou are already allied with that town.");
            return true;
        }
        boolean ok = towns.sendAllianceInvite(myTown.getOwner(), other.getOwner());
        if (!ok) {
            p.sendMessage("§cFailed to send alliance invite.");
            return true;
        }
        p.sendMessage("§aAlliance invite sent to §e" + towns.coloredTownName(other));
        towns.messageTown(other, "§a" + towns.coloredTownName(myTown) + " §7has invited you to form an alliance. Owner can use §e/alliance accept " + myTown.getName() + " §7to accept.");
        return true;
    }

    private boolean claimAdminHelp(Player p, String[] args) {
        if (!p.hasPermission("visclaims.adminhelp")) {
            p.sendMessage("§cNo permission.");
            return true;
        }
        p.sendMessage("§c--- Admin Claim Commands ---");
        p.sendMessage("§f/claimreload §7- Reload VisualClaims config and data");
        p.sendMessage("§f/adjustclaims <player> <add|remove> <amount> §7- Modify bonus claims");
        p.sendMessage("§f/admindeletetown <town> §7- Delete a town by name/owner");
        p.sendMessage("§f/unclaim (with visclaims.admin) §7- Force unclaim any chunk");
        p.sendMessage("§f/trimoutposts <player> [count] §7- Remove the smallest outpost clusters for a player");
        return true;
    }

    private boolean adminDeleteTown(Player p, String[] args) {
        if (!p.hasPermission("visclaims.admindelete")) {
            p.sendMessage("§cNo permission.");
            return true;
        }
        if (args.length != 1) {
            p.sendMessage("Usage: /admindeletetown <town or owner>");
            return true;
        }
        Optional<Town> target = towns.findTown(String.join(" ", args));
        if (target.isEmpty()) {
            p.sendMessage("§cNo matching town found.");
            return true;
        }
        boolean ok = towns.adminDeleteTown(target.get());
        p.sendMessage(ok ? "§cDeleted town §e" + towns.coloredTownName(target.get()) : "§cFailed to delete town.");
        return true;
    }

    private List<String> resolveNames(Collection<UUID> ids) {
        List<String> names = new ArrayList<>();
        if (ids == null) return names;
        for (UUID id : ids) {
            var off = plugin.getServer().getOfflinePlayer(id);
            names.add(off.getName() != null ? off.getName() : id.toString().substring(0, 8));
        }
        return names;
    }

    private List<String> resolveTownNames(Collection<UUID> owners) {
        List<String> names = new ArrayList<>();
        if (owners == null) return names;
        for (UUID id : owners) {
            Optional<Town> t = towns.getTownByOwner(id);
            names.add(t.map(towns::coloredTownName).orElseGet(() -> {
                var off = plugin.getServer().getOfflinePlayer(id);
                return off.getName() != null ? off.getName() : id.toString().substring(0, 8);
            }));
        }
        return names;
    }

    private String formatAgo(long timestamp) {
        long diff = Math.max(0, System.currentTimeMillis() - timestamp);
        long minutes = TimeUnit.MILLISECONDS.toMinutes(diff);
        if (minutes < 1) return "just now";
        if (minutes < 60) return minutes + "m ago";
        long hours = minutes / 60;
        if (hours < 24) return hours + "h ago";
        long days = hours / 24;
        return days + "d ago";
    }

    private String formatDuration(long millis) {
        if (millis <= 0) return "0m";
        long totalMinutes = millis / 60000L;
        long hours = totalMinutes / 60;
        long minutes = totalMinutes % 60;
        if (hours > 0) return minutes > 0 ? hours + "h" + minutes + "m" : hours + "h";
        return Math.max(1, minutes) + "m";
    }
}
