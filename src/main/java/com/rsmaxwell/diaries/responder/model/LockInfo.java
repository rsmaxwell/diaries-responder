package com.rsmaxwell.diaries.responder.model;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

import com.fasterxml.jackson.annotation.JsonIgnore;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

@Embeddable
public record LockInfo(

        @Column(name = "lock_user_id")
        Long lockUserId,

        @Column(name = "lock_username", length = 128)
        String lockUserName,

        @Column(name = "lock_known_as", length = 128)
        String lockKnownAs,

        @Column(name = "lock_timestamp")
        Long lockTimeStamp,

        @Column(name = "lock_session_id", length = 128)
        String lockSessionId) {

    public static LockInfo locked(
            Long userId,
            String userName,
            String knownAs,
            String sessionId,
            Instant at) {

        return new LockInfo(
                userId,
                userName,
                knownAs,
                at == null ? null : at.toEpochMilli(),
                sessionId);
    }

    public static LockInfo lockedNow(
            Long userId,
            String userName,
            String knownAs,
            String sessionId) {

        return locked(
                userId,
                userName,
                knownAs,
                sessionId,
                Instant.now());
    }

    public boolean isLocked() {
        return lockUserId != null
                && lockSessionId != null
                && !lockSessionId.isBlank();
    }

    public boolean isLockedBy(Long userId, String sessionId) {
        return isLocked()
                && Objects.equals(lockUserId, userId)
                && Objects.equals(lockSessionId, sessionId);
    }

    @JsonIgnore
    public Instant lockInstant() {
        return lockTimeStamp == null
                ? null
                : Instant.ofEpochMilli(lockTimeStamp);
    }

    public boolean isStale(Instant now, Duration ttl) {
        if (!isLocked()
                || lockTimeStamp == null
                || now == null
                || ttl == null) {
            return false;
        }

        return Instant.ofEpochMilli(lockTimeStamp)
                .plus(ttl)
                .isBefore(now);
    }
}
