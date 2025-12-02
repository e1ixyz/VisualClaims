package com.example.visualclaims;

import java.util.UUID;

public class TownInvite {
    private UUID townOwner;
    private long createdAt;

    public TownInvite() {}

    public TownInvite(UUID townOwner, long createdAt) {
        this.townOwner = townOwner;
        this.createdAt = createdAt;
    }

    public UUID getTownOwner() { return townOwner; }
    public long getCreatedAt() { return createdAt; }

    public boolean isExpired(long now, long ttlMs) {
        return now - createdAt > ttlMs;
    }
}
