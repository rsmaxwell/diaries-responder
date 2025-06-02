package com.rsmaxwell.diaries.response.dto;

import java.math.BigDecimal;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;

@EqualsAndHashCode(callSuper = false)
@Data
@AllArgsConstructor
public class FragmentDTO extends Jsonable {

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
}
