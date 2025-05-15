package com.rsmaxwell.diaries.response.dto;

import java.math.BigDecimal;

import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.SneakyThrows;

@Data
@AllArgsConstructor
public class MarqueeDTO {

	private Long id;
	private Long pageId;
	private Double x;
	private Double y;
	private Double width;
	private Double height;
	private BigDecimal sequence;

	@SneakyThrows
	public String toJson() {
		return new ObjectMapper().writeValueAsString(this);
	}

	@SneakyThrows
	public byte[] toJsonAsBytes() {
		return new ObjectMapper().writeValueAsBytes(this);
	}
}
