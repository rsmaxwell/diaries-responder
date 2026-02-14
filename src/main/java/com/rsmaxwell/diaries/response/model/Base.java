package com.rsmaxwell.diaries.response.model;

import com.rsmaxwell.mqtt.rpc.utilities.BadRequest;

import jakarta.persistence.Column;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.experimental.SuperBuilder;

@MappedSuperclass
@SuperBuilder
@Data
@NoArgsConstructor
@AllArgsConstructor
public abstract class Base {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "id", nullable = false, unique = true)
	protected Long id;

	@Builder.Default
	@Column(name = "version", nullable = false, columnDefinition = "bigint DEFAULT 0")
	protected Long version = 0L;

	public void checkAndIncrementVersion(Base other) throws BadRequest {
		if (version != other.version) {
			throw new BadRequest(String.format("Stale update. incoming version: %d, original version: %d", version, other.version));
		}

		version += 1;
	}

	public void incrementVersion() {
		version += 1;
	}
}
