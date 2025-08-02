package com.rsmaxwell.diaries.response.model;

import java.math.BigDecimal;

import com.rsmaxwell.diaries.response.dto.DiaryDTO;

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
@Table(name = "diary")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class Diary extends Base {

	@NonNull
	private String name;

	@NonNull
	@Column(precision = 10, scale = 4)
	private BigDecimal sequence;

	public Diary(String name) {
		this.id = 0L;
		this.name = name;
		this.sequence = new BigDecimal(1);
		this.version = 0L;
	}

	public Diary(DiaryDTO diaryDTO) {
		this.id = diaryDTO.getId();
		this.name = diaryDTO.getName();
		this.sequence = diaryDTO.getSequence();
	}

	public boolean keyFieldsChanged(Diary other) {
		if (this.id.longValue() != other.id.longValue()) {
			return true;
		}

		return false;
	}
}
