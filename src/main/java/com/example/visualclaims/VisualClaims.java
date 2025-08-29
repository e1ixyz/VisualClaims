package com.example.visualclaims;

import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

public class VisualClaims extends JavaPlugin {
    private static VisualClaims instance;
    private TownManager townManager;
    private DynmapHook dynmapHook;
    private MoveListener moveListener;

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
        getCommand("settownname").setExecutor(handler);
        getCommand("settowncolor").setExecutor(handler);
        getCommand("claiminfo").setExecutor(handler);     // ✅ Added
        getCommand("claim").setExecutor(handler);         // ✅ Added (claim help)

        // Move listener
        moveListener = new MoveListener(this, townManager);
        Bukkit.getPluginManager().registerEvents(moveListener, this);

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
}