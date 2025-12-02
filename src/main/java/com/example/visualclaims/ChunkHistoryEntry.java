package com.example.visualclaims;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class ChunkHistoryEntry {
    private long timestamp;
    private String action;
    private String townName;
    private UUID townOwner;
    private List<String> alliances = new ArrayList<>();
    private List<String> wars = new ArrayList<>();

    public ChunkHistoryEntry() {}

    public ChunkHistoryEntry(long timestamp, String action, String townName, UUID townOwner, List<String> alliances, List<String> wars) {
        this.timestamp = timestamp;
        this.action = action;
        this.townName = townName;
        this.townOwner = townOwner;
        if (alliances != null) this.alliances = alliances;
        if (wars != null) this.wars = wars;
    }

    public long getTimestamp() { return timestamp; }
    public String getAction() { return action; }
    public String getTownName() { return townName; }
    public UUID getTownOwner() { return townOwner; }
    public List<String> getAlliances() { return alliances == null ? new ArrayList<>() : alliances; }
    public List<String> getWars() { return wars == null ? new ArrayList<>() : wars; }
}
