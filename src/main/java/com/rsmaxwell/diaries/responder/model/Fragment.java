package com.rsmaxwell.diaries.responder.model;

import java.math.BigDecimal;

import com.rsmaxwell.diaries.responder.dto.FragmentDBDTO;
import com.rsmaxwell.diaries.responder.dto.FragmentPublishDTO;

import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.NonNull;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

@Entity
@Table(name = "fragment")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class Fragment extends Base {

	@NonNull
	private Integer year;

	@NonNull
	private Integer month;

	@NonNull
	private Integer day;

	@NonNull
	@Column(precision = 10, scale = 4)
	private BigDecimal sequence;

	@NonNull
	@Column(length = 4096)
	private String text;

	/**
	 * Lock state for this fragment (null/empty => unlocked).
	 */
	@Embedded
	private LockInfo lock;

	public Fragment(FragmentDBDTO dto) {
		copyCommonFieldsFrom(dto);
		copyLockFrom(dto.getLock());
	}

	public Fragment(FragmentPublishDTO dto) {
		copyCommonFieldsFrom(dto);
		copyLockFrom(dto.getLock());
	}

	private void copyCommonFieldsFrom(Base dto) {
		// Base
		this.id = dto.getId();
		this.version = dto.getVersion();

		// Fragment-specific (DTO types both expose these, so we downcast safely)
		if (dto instanceof FragmentDBDTO f) {
			this.sequence = f.getSequence();
			this.year = f.getYear();
			this.month = f.getMonth();
			this.day = f.getDay();
			this.text = f.getText();
		} else if (dto instanceof FragmentPublishDTO f) {
			this.sequence = f.getSequence();
			this.year = f.getYear();
			this.month = f.getMonth();
			this.day = f.getDay();
			this.text = f.getText();
		} else {
			throw new IllegalArgumentException("Unsupported DTO type: " + dto.getClass());
		}
	}

	/**
	 * Defensive copy so we don't share the same LockInfo instance between entity and DTOs. (Safer if DTOs are reused/mutated elsewhere.)
	 */
	private void copyLockFrom(LockInfo src) {
		if (src == null) {
			this.lock = null;
			return;
		}

		// @formatter:off
		this.lock = LockInfo.builder()
				  .lockUserId(src.getLockUserId())
				  .lockUserName(src.getLockUserName())
				  .lockKnownAs(src.getLockKnownAs())
				  .lockTimeStamp(src.getLockTimeStamp())
				  .lockSessionId(src.getLockSessionId())
				  .build();
		// @formatter:on		
	}

	public boolean keyFieldsChanged(Fragment other) {
		if (this.id.longValue() != other.id.longValue()) {
			return true;
		}
		if (this.year.longValue() != other.year.longValue()) {
			return true;
		}
		if (this.month.longValue() != other.month.longValue()) {
			return true;
		}
		if (this.day.longValue() != other.day.longValue()) {
			return true;
		}

		return false;
	}
}