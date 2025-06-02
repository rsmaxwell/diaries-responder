package com.rsmaxwell.diaries.response.dto;

import java.math.BigDecimal;

import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.SneakyThrows;

@Data
@AllArgsConstructor
public class FragmentDTO {

	private Long id;
	private Long pageId;
	private Double x;
	private Double y;
	private Double width;
	private Double height;
	private Integer year;
	private Integer month;
	private Integer day;
	private BigDecimal sequence;
	private String text;

	@SneakyThrows
	public String toJson() {
		return new ObjectMapper().writeValueAsString(this);
	}

	@SneakyThrows
	public byte[] toJsonAsBytes() {
		return new ObjectMapper().writeValueAsBytes(this);
	}
}
