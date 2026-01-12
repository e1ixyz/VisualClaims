package com.example.visualclaims;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

public class ContestState {
    private String id;
    private UUID defenderOwner;
    private UUID challengerOwner;
    private Set<ChunkPos> chunks = new HashSet<>();
    private long startTime;
    private long endTime;
    private long remainingMs;
    private long lastUpdated;
    private boolean paused;
    private boolean holdEligible = true;
    private boolean holdOfflineAllowed = false;
    private int startCost;

    public ContestState() {}

    public ContestState(UUID defenderOwner, UUID challengerOwner, Set<ChunkPos> chunks, long startTime, long endTime) {
        this.defenderOwner = defenderOwner;
        this.challengerOwner = challengerOwner;
        if (chunks != null) this.chunks = new HashSet<>(chunks);
        this.startTime = startTime;
        this.endTime = endTime;
        this.remainingMs = Math.max(0L, endTime - startTime);
        this.lastUpdated = startTime;
        this.id = buildId(defenderOwner, challengerOwner, startTime);
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public UUID getDefenderOwner() { return defenderOwner; }
    public UUID getChallengerOwner() { return challengerOwner; }
    public Set<ChunkPos> getChunks() { return chunks == null ? new HashSet<>() : new HashSet<>(chunks); }
    public boolean containsChunk(ChunkPos pos) { return chunks != null && pos != null && chunks.contains(pos); }
    public long getStartTime() { return startTime; }
    public long getEndTime() { return endTime; }
    public long getRemainingMs() { return remainingMs; }
    public void setRemainingMs(long remainingMs) { this.remainingMs = remainingMs; }
    public long getLastUpdated() { return lastUpdated; }
    public void setLastUpdated(long lastUpdated) { this.lastUpdated = lastUpdated; }
    public boolean isPaused() { return paused; }
    public void setPaused(boolean paused) { this.paused = paused; }
    public boolean isHoldEligible() { return holdEligible; }
    public void setHoldEligible(boolean holdEligible) { this.holdEligible = holdEligible; }
    public boolean isHoldOfflineAllowed() { return holdOfflineAllowed; }
    public void setHoldOfflineAllowed(boolean holdOfflineAllowed) { this.holdOfflineAllowed = holdOfflineAllowed; }
    public int getStartCost() { return startCost; }
    public void setStartCost(int startCost) { this.startCost = startCost; }

    public int getChunkCount() { return chunks == null ? 0 : chunks.size(); }

    public static String buildId(UUID defenderOwner, UUID challengerOwner, long startTime) {
        return defenderOwner + ":" + challengerOwner + ":" + startTime;
    }
}
