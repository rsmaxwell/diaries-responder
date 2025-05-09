package com.rsmaxwell.diaries.response.dto;

import java.math.BigDecimal;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class FragmentDTO {

	private Long id;
	private Long pageId;
	private Double x;
	private Double y;
	private Double width;
	private Double height;
	private BigDecimal sequence;
	private String text;
}
