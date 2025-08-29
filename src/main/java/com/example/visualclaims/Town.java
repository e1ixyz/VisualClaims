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

    // For Gson
    public Town() {}

    public Town(UUID owner, String name, String world, String colorName) {
        this.owner = owner;
        this.name = name;
        this.world = world;
        this.colorName = colorName;
    }

    public UUID getOwner() { return owner; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getWorld() { return world; }
    public VanillaColor getColor() { return VanillaColor.fromString(colorName); }
    public void setColor(VanillaColor c) { this.colorName = c.name(); }
    public String getColorName() { return colorName; }

    public Set<ChunkPos> getClaims() { return claims; }

    public boolean addClaim(ChunkPos pos) { return claims.add(pos); }
    public boolean removeClaim(ChunkPos pos) { return claims.remove(pos); }

    public boolean ownsChunk(ChunkPos pos) { return claims.contains(pos); }

    // convenience
    public int claimCount() { return claims.size(); }
}
