
package com.rsmaxwell.diaries.response.dto;

import java.math.BigDecimal;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

@EqualsAndHashCode(exclude = { "id" }, callSuper = false)
@Data
@AllArgsConstructor
@NoArgsConstructor
public class PageDTO extends Jsonable {

	private Long id;
	private Long diaryId;
	private String name;
	private BigDecimal sequence;
	private String extension;
	private Integer width;
	private Integer height;

	public void updateFrom(PageDTO other) {
		this.diaryId = other.diaryId;
		this.name = other.name;
		this.sequence = other.sequence;
		this.extension = other.extension;
		this.width = other.width;
		this.height = other.height;
	}

	public boolean equalsExcludingId(PageDTO other) {
		if (other == null) {
			return false;
		}

		// @formatter:off
			if (!equalsNullable(this.diaryId, other.diaryId)) { return false; }
			if (!equalsNullable(this.name, other.name)) { return false; }
			if (!compareToNullable(this.sequence, other.sequence)) { return false; }
			if (!equalsNullable(this.extension, other.extension)) { return false; }
			if (!equalsNullable(this.width, other.width)) { return false; }
			if (!equalsNullable(this.height, other.height)) { return false; }
			// @formatter:on

		return true;
	}

	private boolean equalsNullable(Object a, Object b) {
		return a == null ? b == null : a.equals(b);
	}

	private boolean compareToNullable(BigDecimal a, BigDecimal b) {
		return a == null ? b == null : (a.compareTo(b) == 0);
	}

	public String getTopic() {
		return String.format("diary/%s/%s", diaryId, id);
	}
}
