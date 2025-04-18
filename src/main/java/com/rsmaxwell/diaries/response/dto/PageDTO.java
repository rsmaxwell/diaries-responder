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
	private String name;
	private String extension;
	private Integer width;
	private Integer height;
}
