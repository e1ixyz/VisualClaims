package com.example.visualclaims;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

public class Town {
    private UUID owner;
    private String name;
    private String world;
    private String colorName; // vanilla enum name
    private Set<ChunkPos> claims = new HashSet<>();
    private Set<UUID> members = new HashSet<>();
    private String description = "";
    private Set<UUID> allies = new HashSet<>();
    private Set<UUID> wars = new HashSet<>();
    private int bonusChunks = 0; // manual adjustments
    private int contestedClaimsSpent = 0; // spent claims on contests
    private int kills = 0; // tracked town kills
    private long createdAt = 0L;
    private int reputation = 0;

    // For Gson
    public Town() {}

    public Town(UUID owner, String name, String world, String colorName) {
        this.owner = owner;
        this.name = name;
        this.world = world;
        this.colorName = colorName;
        this.createdAt = System.currentTimeMillis();
    }

    public UUID getOwner() { return owner; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getWorld() { return world; }
    public VanillaColor getColor() { return VanillaColor.fromString(colorName); }
    public void setColor(VanillaColor c) { this.colorName = c.name(); }
    public String getColorName() { return colorName; }
    public int getBonusChunks() { return bonusChunks; }
    public void addBonusChunks(int delta) { this.bonusChunks += delta; }
    public void setBonusChunks(int bonusChunks) { this.bonusChunks = bonusChunks; }
    public int getContestedClaimsSpent() { return contestedClaimsSpent; }
    public void addContestedClaimsSpent(int delta) { this.contestedClaimsSpent += delta; }
    public void setContestedClaimsSpent(int contestedClaimsSpent) { this.contestedClaimsSpent = contestedClaimsSpent; }
    public String getDescription() { return description == null ? "" : description; }
    public void setDescription(String description) { this.description = description == null ? "" : description; }

    public Set<ChunkPos> getClaims() { return claims; }
    public Set<UUID> getMembers() { return members; }
    public Set<UUID> getAllies() { return allies; }
    public Set<UUID> getWars() { return wars; }

    public boolean addClaim(ChunkPos pos) { return claims.add(pos); }
    public boolean removeClaim(ChunkPos pos) { return claims.remove(pos); }
    public boolean addMember(UUID uuid) { return members.add(uuid); }
    public boolean removeMember(UUID uuid) { return members.remove(uuid); }
    public boolean isMember(UUID uuid) { return owner != null && owner.equals(uuid) || members.contains(uuid); }

    public boolean ownsChunk(ChunkPos pos) { return claims.contains(pos); }

    // convenience
    public int claimCount() { return claims.size(); }

    public void addAlly(UUID ownerId) { allies.add(ownerId); }
    public void removeAlly(UUID ownerId) { allies.remove(ownerId); }
    public void addWar(UUID ownerId) { wars.add(ownerId); }
    public void removeWar(UUID ownerId) { wars.remove(ownerId); }

    public int getKills() { return kills; }
    public void addKill() { this.kills++; }
    public void setKills(int kills) { this.kills = kills; }

    public long getCreatedAt() { return createdAt; }
    public void setCreatedAt(long createdAt) { this.createdAt = createdAt; }

    public int getReputation() { return reputation; }
    public void setReputation(int reputation) { this.reputation = reputation; }
    public void addReputation(int delta) {
        int next = this.reputation + delta;
        if (next > 10) next = 10;
        if (next < -10) next = -10;
        this.reputation = next;
    }
}
