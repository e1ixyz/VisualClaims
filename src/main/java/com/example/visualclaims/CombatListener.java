package com.example.visualclaims;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;

public class CombatListener implements Listener {
    private final TownManager townManager;

    public CombatListener(TownManager townManager) {
        this.townManager = townManager;
    }

    @EventHandler
    public void onPlayerDeath(PlayerDeathEvent event) {
        if (event.getEntity() == null) return;
        townManager.recordDeath(event.getEntity().getUniqueId());
        if (event.getEntity().getKiller() == null) return;
        if (event.getEntity().getUniqueId().equals(event.getEntity().getKiller().getUniqueId())) return;
        townManager.recordKill(event.getEntity().getKiller().getUniqueId());
    }
}
