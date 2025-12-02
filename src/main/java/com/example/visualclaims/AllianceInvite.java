package com.example.visualclaims;

import java.util.UUID;

public class AllianceInvite {
    private UUID fromOwner;
    private UUID toOwner;
    private long createdAt;

    public AllianceInvite() {}

    public AllianceInvite(UUID fromOwner, UUID toOwner, long createdAt) {
        this.fromOwner = fromOwner;
        this.toOwner = toOwner;
        this.createdAt = createdAt;
    }

    public UUID getFromOwner() { return fromOwner; }
    public UUID getToOwner() { return toOwner; }
    public long getCreatedAt() { return createdAt; }

    public boolean isExpired(long now, long ttlMs) {
        return now - createdAt > ttlMs;
    }
}
