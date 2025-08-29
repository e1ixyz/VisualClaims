package com.example.visualclaims;

import java.util.Objects;

public class ChunkPos {
    private String world;
    private int x;
    private int z;

    public ChunkPos() {} // for Gson

    public ChunkPos(String world, int x, int z) {
        this.world = world;
        this.x = x;
        this.z = z;
    }

    public static ChunkPos of(org.bukkit.Chunk c) {
        return new ChunkPos(c.getWorld().getName(), c.getX(), c.getZ());
    }

    public String getWorld() { return world; }
    public int getX() { return x; }
    public int getZ() { return z; }

    public String id() { return world + ":" + x + ":" + z; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ChunkPos chunkPos = (ChunkPos) o;
        return x == chunkPos.x &&
               z == chunkPos.z &&
               Objects.equals(world, chunkPos.world);
    }

    @Override
    public int hashCode() {
        return Objects.hash(world, x, z);
    }
}
