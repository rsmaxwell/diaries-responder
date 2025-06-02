package com.rsmaxwell.diaries.response.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class Marquee {

	private Long id;
	private Double x;
	private Double y;
	private Double width;
	private Double height;

}
