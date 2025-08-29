package com.example.visualclaims;

import org.bukkit.Chunk;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

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
            case "settownname": return setTownName(p, args);
            case "settowncolor": return setTownColor(p, args);
            case "claiminfo": return claimInfo(p);
            case "claim": return claimHelp(p, args);
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
        if (towns.getTownByOwner(uuid).isPresent()) {
            p.sendMessage("§cYou already own a town.");
            return true;
        }
        VanillaColor defaultColor = VanillaColor.fromString(plugin.getConfig().getString("default-color", "GREEN"));
        boolean ok = towns.createTown(uuid, name, defaultColor == null ? VanillaColor.GREEN : defaultColor, p.getWorld().getName());
        p.sendMessage(ok ? "§aCreated town §e" + name : "§cFailed to create town (name taken?).");
        return true;
    }

    private boolean deleteTown(Player p) {
        if (!p.hasPermission("visclaims.deletetown")) {
            p.sendMessage("§cNo permission.");
            return true;
        }
        UUID uuid = p.getUniqueId();
        boolean ok = towns.deleteTown(uuid);
        p.sendMessage(ok ? "§aDeleted your town." : "§cYou don't own a town.");
        return true;
    }

    private boolean claimChunk(Player p) {
        if (!p.hasPermission("visclaims.claim")) {
            p.sendMessage("§cNo permission.");
            return true;
        }
        UUID uuid = p.getUniqueId();
        Optional<Town> tOpt = towns.getTownByOwner(uuid);
        if (tOpt.isEmpty()) {
            p.sendMessage("§cYou don't own a town. Use /createtown first.");
            return true;
        }
        Chunk c = p.getLocation().getChunk();
        int max = plugin.getConfig().getInt("max-claims-per-player", 64);
        boolean bypass = p.hasPermission("visclaims.admin");
        boolean ok = towns.claimChunk(uuid, c, max, bypass);
        if (ok) {
            p.sendMessage("§aClaimed chunk at §e(" + c.getX() + ", " + c.getZ() + ")");
        } else {
            Optional<Town> other = towns.getTownAt(c);
            if (other.isPresent()) p.sendMessage("§cChunk already claimed by §e" + other.get().getName());
            else p.sendMessage("§cCannot claim chunk: limit reached.");
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

        boolean ok = towns.unclaimChunk(uuid, pos);

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
            p.sendMessage("Usage: /settownname <name>");
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
        p.sendMessage("§aTown renamed to §e" + newName);
        return true;
    }

    private boolean setTownColor(Player p, String[] args) {
        if (!p.hasPermission("visclaims.setcolor")) {
            p.sendMessage("§cNo permission.");
            return true;
        }
        if (args.length != 1) {
            p.sendMessage("Usage: /settowncolor <color>");
            return true;
        }
        Optional<Town> tOpt = towns.getTownByOwner(p.getUniqueId());
        if (tOpt.isEmpty()) {
            p.sendMessage("§cYou don't own a town.");
            return true;
        }
        VanillaColor c = VanillaColor.fromString(args[0]);
        if (c == null) {
            p.sendMessage("§cInvalid color. Valid: BLACK, DARK_BLUE, DARK_GREEN, DARK_AQUA, DARK_RED, DARK_PURPLE, GOLD, GRAY, DARK_GRAY, BLUE, GREEN, AQUA, RED, LIGHT_PURPLE, YELLOW, WHITE");
            return true;
        }
        Town t = tOpt.get();
        t.setColor(c);
        towns.saveTown(t);
        if (plugin.getDynmapHook() != null) plugin.getDynmapHook().refreshTownAreas(t);
        p.sendMessage("§aTown color set to §e" + c.name());
        return true;
    }

    private boolean claimInfo(Player p) {
        if (!p.hasPermission("visclaims.claiminfo")) {
            p.sendMessage("§cNo permission.");
            return true;
        }
        Optional<Town> tOpt = towns.getTownByOwner(p.getUniqueId());
        if (tOpt.isEmpty()) {
            p.sendMessage("§cYou don't own a town.");
            return true;
        }
        Town t = tOpt.get();
        p.sendMessage("§e--- Town Info ---");
        p.sendMessage("§7Name: §f" + t.getName());
        p.sendMessage("§7Color: §f" + t.getColorName());
        p.sendMessage("§7Chunks claimed: §f" + t.claimCount());
        return true;
    }

    private boolean claimHelp(Player p, String[] args) {
        if (!p.hasPermission("visclaims.help")) {
            p.sendMessage("§cNo permission.");
            return true;
        }
        if (args.length == 0 || !args[0].equalsIgnoreCase("help")) {
            p.sendMessage("§cUsage: /claim help");
            return true;
        }
        p.sendMessage("§e--- Claim Commands ---");
        p.sendMessage("§f/createtown <name> §7- Create your town");
        p.sendMessage("§f/deletetown §7- Delete your town");
        p.sendMessage("§f/claimchunk §7- Claim the current chunk");
        p.sendMessage("§f/unclaim §7- Unclaim the current chunk");
        p.sendMessage("§f/autoclaim §7- Toggle autoclaim for chunks");
        p.sendMessage("§f/settownname <name> §7- Rename your town");
        p.sendMessage("§f/settowncolor <color> §7- Change your town's color");
        p.sendMessage("§f/claiminfo §7- Show info about your town");
        p.sendMessage("§f/claim help §7- Show this help message");
        return true;
    }
}
