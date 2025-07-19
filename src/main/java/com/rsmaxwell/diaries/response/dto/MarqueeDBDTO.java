package com.rsmaxwell.diaries.response.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;

@EqualsAndHashCode(callSuper = false)
@Data
@AllArgsConstructor
public class MarqueeDBDTO extends Jsonable {

	private Long id;
	private Long pageId;
	private Long fragmentId;
	private Double x;
	private Double y;
	private Double width;
	private Double height;
}
