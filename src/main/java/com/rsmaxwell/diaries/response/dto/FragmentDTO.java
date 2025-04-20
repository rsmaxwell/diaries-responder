package com.rsmaxwell.diaries.response.dto;

import java.math.BigDecimal;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

@EqualsAndHashCode(exclude = { "id" })
@Data
@AllArgsConstructor
@NoArgsConstructor
public class FragmentDTO {

	private Long id;
	private Integer x;
	private Integer y;
	private Integer cx;
	private Integer cy;
	private BigDecimal sequence;
	private String text;
}
