package com.rsmaxwell.diaries.response.model;

import java.math.BigDecimal;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rsmaxwell.diaries.response.dto.FragmentDTO;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.SneakyThrows;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class FragmentResponse {

	private Long id;
	private Long pageId;
	private Double x;
	private Double y;
	private Double width;
	private Double height;
	private BigDecimal sequence;
	private String text;

	public FragmentResponse(FragmentDTO fragment) {
		this.id = fragment.getId();
		this.pageId = fragment.getPageId();
		this.x = fragment.getX();
		this.y = fragment.getY();
		this.width = fragment.getWidth();
		this.height = fragment.getHeight();
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
