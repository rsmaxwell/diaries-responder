package com.rsmaxwell.diaries.response.model;

import java.math.BigDecimal;

import com.rsmaxwell.diaries.response.dto.FragmentDBDTO;

import jakarta.persistence.Column;
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

	public Fragment(FragmentDBDTO dto) {
		this.id = dto.getId();
		this.version = dto.getVersion();
		this.sequence = dto.getSequence();
		this.year = dto.getYear();
		this.month = dto.getMonth();
		this.day = dto.getDay();
		this.text = dto.getText();
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
