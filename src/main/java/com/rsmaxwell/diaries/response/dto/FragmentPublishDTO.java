package com.rsmaxwell.diaries.response.dto;

import java.math.BigDecimal;

import com.fasterxml.jackson.annotation.JsonInclude;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;

// This is a Publishing-Oriented  DTO
//
// It is intended to be serialised for clients, APIs, or MQTT topic publishing.
//
// (Note: Uses fragmentId in the MarqueeDTO instead of full FragmentDTO.)

@JsonInclude(JsonInclude.Include.NON_NULL)
@EqualsAndHashCode(callSuper = false)
@Data
@AllArgsConstructor
public class FragmentPublishDTO extends Jsonable {

	private Long id;
	private Long pageId;
	private MarqueePublishDTO marquee;
	private Integer year;
	private Integer month;
	private Integer day;
	private BigDecimal sequence;
	private String text;
}
