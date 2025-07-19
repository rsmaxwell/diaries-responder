package com.rsmaxwell.diaries.response.dto;

import com.rsmaxwell.diaries.response.utilities.Rectangle;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;

@EqualsAndHashCode(callSuper = false)
@Data
@AllArgsConstructor
public class MarqueePublishDTO extends Jsonable {

	private Long id;
	private Long pageId;
	private Long fragmentId;
	private Rectangle rectangle;
}
