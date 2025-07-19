package com.rsmaxwell.diaries.response.dto;

import java.math.BigDecimal;

import com.rsmaxwell.diaries.response.model.Marquee;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;

// This is a Persistence-Oriented DTO
//
// It matches the full database structure, and can include full nested objects to simplify saving.
//
// (Note: We are not concerned with circular references, because this layer doesn’t serialize to JSON)

@EqualsAndHashCode(callSuper = false)
@Data
@AllArgsConstructor
public class FragmentDBDTO extends Jsonable {

	private Long id;
	private Marquee marquee;
	private Integer year;
	private Integer month;
	private Integer day;
	private BigDecimal sequence;
	private String text;
}
