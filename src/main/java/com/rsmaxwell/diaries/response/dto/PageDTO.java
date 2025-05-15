package com.rsmaxwell.diaries.response.dto;

import java.math.BigDecimal;

import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.SneakyThrows;

@EqualsAndHashCode(exclude = { "id" })
@Data
@AllArgsConstructor
@NoArgsConstructor
public class PageDTO {

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

	@SneakyThrows
	public String toJson() {
		return new ObjectMapper().writeValueAsString(this);
	}

	@SneakyThrows
	public byte[] toJsonAsBytes() {
		return new ObjectMapper().writeValueAsBytes(this);
	}
}
