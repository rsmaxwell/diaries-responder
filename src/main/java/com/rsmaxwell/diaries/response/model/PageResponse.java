package com.rsmaxwell.diaries.response.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rsmaxwell.diaries.response.dto.PageDTO;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.SneakyThrows;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class PageResponse {

	private Long id;
	private String name;
	private String extension;
	private Integer width;
	private Integer height;

	public PageResponse(PageDTO page) {
		this.id = page.getId();
		this.name = page.getName();
		this.extension = page.getExtension();
		this.width = page.getWidth();
		this.height = page.getHeight();
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
