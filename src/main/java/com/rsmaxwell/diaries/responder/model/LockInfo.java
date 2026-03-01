package com.rsmaxwell.diaries.responder.model;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Lock information for a Fragment (or other entity).
 *
 * Stored as simple columns (via @Embeddable) but grouped as a single value object in Java.
 *
 * Conventions: - lockTimestamp is epoch-millis (UTC). - "Unlocked" is represented by all fields being null/blank.
 */
@Embeddable
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class LockInfo {

	@Column(name = "lock_user_id")
	private Long lockUserId;

	@Column(name = "lock_username", length = 128)
	private String lockUserName;

	@Column(name = "lock_known_as", length = 128)
	private String lockKnownAs;

	/**
	 * Epoch millis (UTC) when the lock was obtained (or last refreshed).
	 */
	@Column(name = "lock_timestamp")
	private Long lockTimeStamp;

	@Column(name = "lock_session_id", length = 128)
	private String lockSessionId;

	/**
	 * Treat as "locked" when a user + session are present. (Timestamp is helpful but not required to consider it locked.)
	 */
	public boolean isLocked() {
		return lockUserId != null && lockSessionId != null && !lockSessionId.isBlank();
	}

	public boolean isLockedBy(Long userId, String sessionId) {
		if (!isLocked()) {
			return false;
		}
		return Objects.equals(lockUserId, userId) && Objects.equals(lockSessionId, sessionId);
	}

	/**
	 * Returns the lock time as an Instant, or null if no timestamp is set.
	 */
	public Instant getLockInstant() {
		return lockTimeStamp == null ? null : Instant.ofEpochMilli(lockTimeStamp);
	}

	/**
	 * Sets the lock timestamp from an Instant (stored as epoch millis), or clears if null.
	 */
	public void setLockInstant(Instant instant) {
		this.lockTimeStamp = (instant == null) ? null : instant.toEpochMilli();
	}

	/**
	 * True if currently locked and older than the supplied TTL (requires a timestamp to decide staleness).
	 */
	public boolean isStale(Instant now, Duration ttl) {
		if (!isLocked()) {
			return false;
		}
		if (lockTimeStamp == null || now == null || ttl == null) {
			return false;
		}
		return Instant.ofEpochMilli(lockTimeStamp).plus(ttl).isBefore(now);
	}

	/**
	 * Clears all lock fields (represents "unlocked").
	 */
	public void clear() {
		this.lockUserId = null;
		this.lockUserName = null;
		this.lockKnownAs = null;
		this.lockTimeStamp = null;
		this.lockSessionId = null;
	}

	/**
	 * Convenience: set/refresh the lock to (userId, sessionId) at the given time.
	 */
	public void lock(Long userId, String userName, String knownAs, String sessionId, Instant at) {
		this.lockUserId = userId;
		this.lockUserName = userName;
		this.lockKnownAs = knownAs;
		this.lockSessionId = sessionId;
		this.lockTimeStamp = (at == null) ? null : at.toEpochMilli();
	}

	/**
	 * Convenience: set/refresh the lock to (userId, sessionId) at "now".
	 */
	public void lockNow(Long userId, String userName, String knownAs, String sessionId) {
		lock(userId, userName, knownAs, sessionId, Instant.now());
	}
}