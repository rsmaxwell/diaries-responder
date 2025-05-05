package com.rsmaxwell.diaries.response.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

@EqualsAndHashCode(exclude = { "id" })
@Data
@AllArgsConstructor
@NoArgsConstructor
public class PageDTO {

	private Long id;
	private Long diaryId;
	private String name;
	private String extension;
	private Integer width;
	private Integer height;

	public void updateFrom(PageDTO other) {
		this.diaryId = other.diaryId;
		this.name = other.name;
		this.extension = other.extension;
		this.width = other.width;
		this.height = other.height;
	}
}
