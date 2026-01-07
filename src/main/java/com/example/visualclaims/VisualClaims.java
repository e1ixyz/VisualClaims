package com.example.visualclaims;

import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

public class VisualClaims extends JavaPlugin {
    private static VisualClaims instance;
    private TownManager townManager;
    private DynmapHook dynmapHook;
    private MoveListener moveListener;
    private CombatListener combatListener;

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

        // Register command handler
        CommandHandler handler = new CommandHandler(this, townManager);
        getCommand("createtown").setExecutor(handler);
        getCommand("deletetown").setExecutor(handler);
        getCommand("claimchunk").setExecutor(handler);
        getCommand("unclaim").setExecutor(handler);       // ✅ Added
        getCommand("autoclaim").setExecutor(handler);
        getCommand("autounclaim").setExecutor(handler);
        getCommand("settownname").setExecutor(handler);
        getCommand("settowncolor").setExecutor(handler);
        getCommand("settowndesc").setExecutor(handler);
        getCommand("claiminfo").setExecutor(handler);     // ✅ Added
        getCommand("claimhistory").setExecutor(handler);
        getCommand("claim").setExecutor(handler);         // ✅ Added (claim help)
        getCommand("claimlimit").setExecutor(handler);    // Experimental limit/info
        getCommand("adjustclaims").setExecutor(handler);  // Admin bonus adjustments
        getCommand("towninvite").setExecutor(handler);
        getCommand("jointown").setExecutor(handler);
        getCommand("townmembers").setExecutor(handler);
        getCommand("removemember").setExecutor(handler);
        getCommand("towns").setExecutor(handler);
        getCommand("towninfo").setExecutor(handler);
        getCommand("autohistory").setExecutor(handler);
        getCommand("war").setExecutor(handler);
        getCommand("alliance").setExecutor(handler);
        getCommand("warmode").setExecutor(handler);
        getCommand("warscoreboard").setExecutor(handler);
        getCommand("claimadmin").setExecutor(handler);
        getCommand("admindeletetown").setExecutor(handler);
        getCommand("leaderboard").setExecutor(handler);
        getCommand("claimalerts").setExecutor(handler);
        getCommand("silentvisit").setExecutor(handler);
        getCommand("claimreload").setExecutor(handler);

        // Move listener
        moveListener = new MoveListener(this, townManager);
        Bukkit.getPluginManager().registerEvents(moveListener, this);
        combatListener = new CombatListener(townManager);
        Bukkit.getPluginManager().registerEvents(combatListener, this);

        getLogger().info("VisualClaims enabled.");
    }

    @Override
    public void onDisable() {
        if (townManager != null) townManager.saveAll();
        if (dynmapHook != null) dynmapHook.clearAll();
        getLogger().info("VisualClaims disabled.");
    }

    public static VisualClaims get() { return instance; }

    public DynmapHook getDynmapHook() { return dynmapHook; }
    public TownManager getTownManager() { return townManager; }
    public MoveListener getMoveListener() { return moveListener; }
    public CombatListener getCombatListener() { return combatListener; }
}
