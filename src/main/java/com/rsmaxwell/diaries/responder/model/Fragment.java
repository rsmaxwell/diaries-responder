package com.rsmaxwell.diaries.responder.model;

import java.math.BigDecimal;

import com.rsmaxwell.diaries.responder.dto.FragmentDBDTO;
import com.rsmaxwell.diaries.responder.dto.FragmentPublishDTO;

import jakarta.persistence.Column;
import jakarta.persistence.Embedded;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
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

	/**
	 * Authoritative Page ownership. Nullable only during the 0022 migration
	 * compatibility window.
	 */
	@ManyToOne(optional = true)
	@JoinColumn(name = "page_id")
	private Page page;

	/**
	 * Retains the Page id when a persistence-oriented DTO is used without
	 * inflating the Page object (for example sequence normalization).
	 */
	@Transient
	private Long persistedPageId;

	/** Nullable only while legacy image candidates are awaiting 0028 review. */
	@Enumerated(EnumType.STRING)
	@Column(name = "type", length = 16)
	private FragmentType type;

	@ManyToOne(optional = true)
	@JoinColumn(name = "image_id")
	private Image image;

	/** Retains the Image id for native repository DTOs without loading the Image. */
	@Transient
	private Long persistedImageId;

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
		this.persistedPageId = dto.getPageId();
		this.persistedImageId = dto.getImageId();
		copyLockFrom(dto.getLock());
	}

	public Fragment(Page page, FragmentDBDTO dto) {
		this(dto);
		if (page != null) {
			setPage(page);
		}
	}

	public Fragment(FragmentPublishDTO dto) {
		copyCommonFieldsFrom(dto);
		this.persistedPageId = dto.getPageId();
		this.persistedImageId = dto.getImageId();
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
			this.type = f.getType();
		} else if (dto instanceof FragmentPublishDTO f) {
			this.sequence = f.getSequence();
			this.year = f.getYear();
			this.month = f.getMonth();
			this.day = f.getDay();
			this.text = f.getText();
			this.type = f.getType();
		} else {
			throw new IllegalArgumentException("Unsupported DTO type: " + dto.getClass());
		}
	}

	public Long getImageId() {
		return image == null ? persistedImageId : image.getId();
	}

	public void setImage(Image image) {
		this.image = image;
		this.persistedImageId = image == null ? null : image.getId();
	}

	public Long getPageId() {
		return page == null ? persistedPageId : page.getId();
	}

	public void setPage(Page page) {
		this.page = page;
		this.persistedPageId = page == null ? null : page.getId();
	}

	/**
	 * Defensive copy so we don't share the same LockInfo instance between entity and DTOs. (Safer if DTOs are reused/mutated elsewhere.)
	 */
	private void copyLockFrom(LockInfo source) {
	    this.lock = source;
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
