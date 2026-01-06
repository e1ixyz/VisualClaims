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
            case "autohistory": return autoHistory(p);
            case "settownname": return setTownName(p, args);
            case "settowncolor": return setTownColor(p, args);
            case "settowndesc": return setTownDesc(p, args);
            case "claiminfo": return claimInfo(p);
            case "claimhistory": return claimHistory(p);
            case "claim": return claimHelp(p, args);
            case "claimlimit": return claimLimit(p, args);
            case "adjustclaims": return adjustClaims(p, args);
            case "claimalerts": return toggleClaimAlerts(p);
            case "silentvisit": return toggleSilentVisit(p);
            case "leaderboard":
            case "lb": return leaderboardCommand(p, args);
            case "towninvite": return inviteToTown(p, args);
            case "jointown": return joinTown(p, args);
            case "townmembers": return showTownMembers(p);
            case "removemember": return removeMember(p, args);
            case "towns": return listTowns(p);
            case "towninfo": return townInfo(p, args);
            case "war": return warCommand(p, args);
            case "alliance": return allianceCommand(p, args);
            case "warmode": return warModeAdmin(p, args);
            case "warscoreboard": return toggleWarScoreboard(p);
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
        boolean bypass = p.hasPermission("visclaims.admin");
        Town town = tOpt.get();
        int max = towns.computeMaxClaims(town.getOwner());
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
        Town t = tOpt.get();
        t.setName(newName);
        towns.saveTown(t);
        if (plugin.getDynmapHook() != null) plugin.getDynmapHook().refreshTownAreas(t);
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
        if (plugin.getDynmapHook() != null) plugin.getDynmapHook().refreshTownAreas(t);
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
        Town t = tOpt.get();
        String ownerName = plugin.getServer().getOfflinePlayer(t.getOwner()).getName();
        List<String> memberNames = resolveNames(t.getMembers());
        List<String> allyNames = resolveTownNames(t.getAllies());
        List<String> warNames = resolveTownNames(t.getWars());
        p.sendMessage("§e--- Town Info ---");
        p.sendMessage("§7Name: §f" + towns.coloredTownName(t));
        p.sendMessage("§7Owner: §f" + (ownerName != null ? ownerName : t.getOwner()));
        p.sendMessage("§7Description: §f" + t.getDescription());
        p.sendMessage("§7Color: §f" + t.getColorName());
        p.sendMessage("§7Chunks claimed: §f" + t.claimCount());
        p.sendMessage("§7Members: §f" + (memberNames.isEmpty() ? "none" : String.join(", ", memberNames)));
        p.sendMessage("§7Allies: §f" + (allyNames.isEmpty() ? "none" : String.join(", ", allyNames)));
        p.sendMessage("§7Wars: §f" + (warNames.isEmpty() ? "none" : String.join(", ", warNames)));
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
        int chunksPerHour = Math.max(1, plugin.getConfig().getInt("chunks-per-hour", 2));
        boolean usingPlaytime = plugin.getConfig().getBoolean("use-playtime-scaling", false);
        int baseMax = plugin.getConfig().getInt("max-claims-per-player", 64);
        int playtimeAllowance = usingPlaytime ? playtimeHours * chunksPerHour : 0;
        int theoretical = baseMax + playtimeAllowance + bonus;
        int limit = Math.max(theoretical, claimed);
        int overflow = Math.max(0, claimed - theoretical);

        p.sendMessage("§e--- Claim Limit for " + targetName + " ---");
        p.sendMessage("§7Base cap: §f" + baseMax);
        if (usingPlaytime) {
            p.sendMessage("§7Playtime: §f" + playtimeHours + "h §7@ §f" + chunksPerHour + " §7chunks/hour => §f" + playtimeAllowance);
        }
        p.sendMessage("§7Bonus: §f" + bonus);
        p.sendMessage("§7Allowed total: §f" + theoretical + (overflow > 0 ? " §8(overflow by " + overflow + " grandfathered)" : ""));
        p.sendMessage("§7Claims held: §f" + claimed);
        p.sendMessage("§7Effective limit (never lowers below claims): §f" + limit);
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
        p.sendMessage("§e--- Claim Commands ---");
        p.sendMessage("§f/createtown <name> §7- Create your town");
        p.sendMessage("§f/deletetown §7- Delete your town");
        p.sendMessage("§f/claimchunk §7- Claim the current chunk");
        p.sendMessage("§f/unclaim §7- Unclaim the current chunk");
        p.sendMessage("§f/autoclaim §7- Toggle autoclaim for chunks");
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
        p.sendMessage("§f/towns §7- Public town listing");
        p.sendMessage("§f/towninfo <town> §7- Public town details");
        p.sendMessage("§f/claimhistory §7- Show history for the current chunk");
        p.sendMessage("§f/war <town> §7- Declare/resolve war with another town");
        p.sendMessage("§f/alliance <town>|accept <town>|remove <town> §7- Manage alliances");
        p.sendMessage("§f/claiminfo §7- Show info about your town");
        p.sendMessage("§f/claimlimit [player] §7- Show playtime-based claim limits");
        p.sendMessage("§f/claim help §7- Show this help message");
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
        if (args.length < 1) {
            p.sendMessage("Usage: /towninfo <town or owner>");
            return true;
        }
        Optional<Town> tOpt = towns.findTown(String.join(" ", args));
        if (tOpt.isEmpty()) {
            p.sendMessage("§cNo matching town found.");
            return true;
        }
        Town t = tOpt.get();
        String ownerName = plugin.getServer().getOfflinePlayer(t.getOwner()).getName();
        List<String> members = resolveNames(t.getMembers());
        List<String> allies = resolveTownNames(t.getAllies());
        List<String> wars = resolveTownNames(t.getWars());
        p.sendMessage("§e--- Town " + towns.coloredTownName(t) + "§e ---");
        p.sendMessage("§7Owner: §f" + (ownerName != null ? ownerName : t.getOwner()));
        p.sendMessage("§7Description: §f" + t.getDescription());
        p.sendMessage("§7Members: §f" + (members.isEmpty() ? "none" : String.join(", ", members)));
        p.sendMessage("§7Allies: §f" + (allies.isEmpty() ? "none" : String.join(", ", allies)));
        p.sendMessage("§7Wars: §f" + (wars.isEmpty() ? "none" : String.join(", ", wars)));
        p.sendMessage("§7Claims: §f" + t.claimCount());
        return true;
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
            String wars = e.getWars().isEmpty() ? "none" : String.join(", ", e.getWars());
            String coloredName = towns.coloredTownName(e.getTownOwner(), e.getTownName());
            p.sendMessage("§f" + e.getAction() + " §7by " + coloredName + " §7(" + formatAgo(e.getTimestamp()) + ")");
            p.sendMessage("§7Allies: §f" + allies + " §7Wars: §f" + wars);
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
        boolean silent = plugin.getMoveListener().toggleSilentVisits(p.getUniqueId());
        p.sendMessage(silent ? "§aSilent visiting enabled. Towns will not be alerted when you enter their land." : "§cSilent visiting disabled. Towns will be alerted when you enter their land.");
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
                p.sendMessage("  §7" + idx + ". §f" + towns.coloredTownName(t) + " §7- §e" + t.getKills() + " §7kills");
                idx++;
            }
        }

        p.sendMessage("§6Top Claims:");
        if (topClaims.isEmpty()) {
            p.sendMessage("  §7None yet.");
        } else {
            int idx = 1;
            for (Town t : topClaims) {
                p.sendMessage("  §7" + idx + ". §f" + towns.coloredTownName(t) + " §7- §e" + t.claimCount() + " §7claims");
                idx++;
            }
        }

        TownManager.PlayerStats stats = towns.getPlayerStats(p.getUniqueId());
        p.sendMessage("§aYour Stats: §fKills §e" + stats.getKills() + " §7/ §fDeaths §e" + stats.getDeaths() + " §7/ §fClaims §e" + stats.getClaims());
        p.sendMessage("§7Use §e/leaderboard toggle §7to enable the sidebar.");
        return true;
    }

    private boolean warCommand(Player p, String[] args) {
        if (!p.hasPermission("visclaims.war")) {
            p.sendMessage("§cNo permission.");
            return true;
        }
        if (!towns.isWarModeEnabled()) {
            p.sendMessage("§cWar mode is currently disabled by admins.");
            return true;
        }
        Optional<Town> myTownOpt = towns.getTownByOwner(p.getUniqueId());
        if (myTownOpt.isEmpty()) {
            p.sendMessage("§cOnly town owners can manage wars.");
            return true;
        }
        if (args.length != 1) {
            p.sendMessage("Usage: /war <town or owner>");
            return true;
        }
        Optional<Town> targetOpt = towns.findTown(String.join(" ", args));
        if (targetOpt.isEmpty()) {
            p.sendMessage("§cNo matching town found.");
            return true;
        }
        Town mine = myTownOpt.get();
        Town other = targetOpt.get();
        if (mine.getOwner().equals(other.getOwner())) {
            p.sendMessage("§cYou cannot declare war on yourself.");
            return true;
        }
        boolean alreadyWar = mine.getWars().contains(other.getOwner());
        boolean ok = towns.toggleWar(mine.getOwner(), other.getOwner());
        if (!ok) {
            p.sendMessage("§cFailed to update war state.");
            return true;
        }
        if (alreadyWar) {
            p.sendMessage("§aYou are no longer at war with §e" + towns.coloredTownName(other));
            towns.messageTown(other, "§e" + towns.coloredTownName(mine) + " §7ended the war.");
        } else {
            p.sendMessage("§cYou declared war on §e" + towns.coloredTownName(other));
            towns.messageTown(other, "§c" + towns.coloredTownName(mine) + " §7declared war on you!");
        }
        return true;
    }

    private boolean allianceCommand(Player p, String[] args) {
        if (!p.hasPermission("visclaims.alliance")) {
            p.sendMessage("§cNo permission.");
            return true;
        }
        if (!towns.isWarModeEnabled()) {
            p.sendMessage("§cAlliances and wars are disabled until war mode is enabled by an admin.");
            return true;
        }
        Optional<Town> myTownOpt = towns.getTownByOwner(p.getUniqueId());
        if (myTownOpt.isEmpty()) {
            p.sendMessage("§cOnly town owners can manage alliances.");
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

    private boolean warModeAdmin(Player p, String[] args) {
        if (!p.hasPermission("visclaims.warmode")) {
            p.sendMessage("§cNo permission.");
            return true;
        }
        if (args.length != 1 || !(args[0].equalsIgnoreCase("on") || args[0].equalsIgnoreCase("off"))) {
            p.sendMessage("Usage: /warmode <on|off>");
            return true;
        }
        boolean enable = args[0].equalsIgnoreCase("on");
        towns.setWarModeEnabled(enable);
        p.sendMessage(enable ? "§cWar mode enabled. War commands and scoreboards are now active." : "§aWar mode disabled. Scoreboards reset.");
        return true;
    }

    private boolean toggleWarScoreboard(Player p) {
        if (!p.hasPermission("visclaims.warscoreboard")) {
            p.sendMessage("§cNo permission.");
            return true;
        }
        boolean enabled = towns.toggleScoreboard(p.getUniqueId());
        if (towns.isWarModeEnabled() && enabled) towns.applyScoreboard(p);
        p.sendMessage(enabled ? "§aWar scoreboard enabled." : "§cWar scoreboard disabled.");
        return true;
    }

    private boolean claimAdminHelp(Player p, String[] args) {
        if (!p.hasPermission("visclaims.adminhelp")) {
            p.sendMessage("§cNo permission.");
            return true;
        }
        p.sendMessage("§c--- Admin Claim Commands ---");
        p.sendMessage("§f/adjustclaims <player> <add|remove> <amount> §7- Modify bonus claims");
        p.sendMessage("§f/warmode <on|off> §7- Toggle global war mode & scoreboards");
        p.sendMessage("§f/warscoreboard §7- Toggle your war scoreboard view");
        p.sendMessage("§f/admindeletetown <town> §7- Delete a town by name/owner");
        p.sendMessage("§f/unclaim (with visclaims.admin) §7- Force unclaim any chunk");
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
}
