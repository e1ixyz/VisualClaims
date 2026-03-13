package io.github.e1ixyz.visualclaims;

import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;

public class VisualClaims extends JavaPlugin {
    private static VisualClaims instance;
    private TownManager townManager;
    private DynmapHook dynmapHook;
    private MoveListener moveListener;
    private CombatListener combatListener;
    private ClaimProtectionListener claimProtectionListener;

    @Override
    public void onEnable() {
        instance = this;
        saveDefaultConfig();

        // Ensure data folder exists
        if (!getDataFolder().exists()) getDataFolder().mkdirs();

        // Dynmap hook (plugin requires dynmap; plugin.yml marked depend)
        dynmapHook = new DynmapHook(this);
        if (!dynmapHook.hook()) {
            getLogger().warning("Dynmap not found or failed to hook. Dynmap features disabled.");
        }

        // Town manager (loads towns)
        townManager = new TownManager(this, dynmapHook);
        townManager.loadAll();
        townManager.startContestTicker();

        // Register command handler
        CommandHandler handler = new CommandHandler(this, townManager);
        registerCommand("createtown", handler);
        registerCommand("deletetown", handler);
        registerCommand("claimchunk", handler);
        registerCommand("unclaim", handler);
        registerCommand("unclaimoutpost", handler);
        registerCommand("autoclaim", handler);
        registerCommand("autounclaim", handler);
        registerCommand("settownname", handler);
        registerCommand("settowncolor", handler);
        registerCommand("settowndesc", handler);
        registerCommand("setcapital", handler);
        registerCommand("claiminfo", handler);
        registerCommand("claimhistory", handler);
        registerCommand("claim", handler);
        registerCommand("claimlimit", handler);
        registerCommand("adjustclaims", handler);
        registerCommand("transferoutpost", handler);
        registerCommand("towninvite", handler);
        registerCommand("jointown", handler);
        registerCommand("leavetown", handler);
        registerCommand("townmembers", handler);
        registerCommand("removemember", handler);
        registerCommand("towns", handler);
        registerCommand("towninfo", handler);
        registerCommand("autohistory", handler);
        registerCommand("contest", handler);
        registerCommand("alliance", handler);
        registerCommand("claimadmin", handler);
        registerCommand("admindeletetown", handler);
        registerCommand("leaderboard", handler);
        registerCommand("claimalerts", handler);
        registerCommand("silentvisit", handler);
        registerCommand("claimreload", handler);
        registerCommand("trimoutposts", handler);
        registerCommand("warmode", handler);

        // Move listener
        moveListener = new MoveListener(this, townManager);
        Bukkit.getPluginManager().registerEvents(moveListener, this);
        combatListener = new CombatListener(townManager);
        Bukkit.getPluginManager().registerEvents(combatListener, this);
        claimProtectionListener = new ClaimProtectionListener(this, townManager);
        Bukkit.getPluginManager().registerEvents(claimProtectionListener, this);

        getLogger().info("VisualClaims enabled.");
    }

    @Override
    public void onDisable() {
        if (townManager != null) {
            townManager.stopContestTicker();
            townManager.saveAll();
        }
        if (dynmapHook != null) dynmapHook.clearAll();
        getLogger().info("VisualClaims disabled.");
    }

    public static VisualClaims get() { return instance; }

    public DynmapHook getDynmapHook() { return dynmapHook; }
    public TownManager getTownManager() { return townManager; }
    public MoveListener getMoveListener() { return moveListener; }
    public CombatListener getCombatListener() { return combatListener; }

    private void registerCommand(String name, CommandHandler handler) {
        PluginCommand command = getCommand(name);
        if (command == null) {
            getLogger().severe("Missing command registration in plugin.yml: " + name);
            return;
        }
        command.setExecutor(handler);
    }
}
