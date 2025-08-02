package com.rsmaxwell.diaries.response.model;

import java.math.BigDecimal;

import com.rsmaxwell.diaries.response.dto.PageDTO;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.NonNull;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

@Entity
@Table(name = "page", uniqueConstraints = { @UniqueConstraint(columnNames = { "diary_id", "name" }) })
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class Page extends Base {

	@NonNull
	@ManyToOne
	@JoinColumn(name = "diary_id")
	private Diary diary;

	@NonNull
	private String name;

	@NonNull
	@Column(precision = 10, scale = 4)
	private BigDecimal sequence;

	@NonNull
	private String extension;

	@NonNull
	private Integer width;

	@NonNull
	private Integer height;

	public Page(Diary diary, PageDTO dto) {
		this.diary = diary;
		this.id = dto.getId();
		this.name = dto.getName();
		this.sequence = dto.getSequence();
		this.extension = dto.getExtension();
		this.width = dto.getWidth();
		this.height = dto.getHeight();
		this.version = dto.getVersion();
	}

	public void updateFrom(Page other) {
		this.diary = other.diary;
		this.name = other.name;
		this.sequence = other.sequence;
		this.extension = other.extension;
		this.width = other.width;
		this.height = other.height;
		this.version = other.version;
	}

	public boolean keyFieldsChanged(Page other) {
		if (this.id.longValue() != other.id.longValue()) {
			return true;
		}

		return false;
	}
}
