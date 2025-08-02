package com.rsmaxwell.diaries.response.dto;

import java.math.BigDecimal;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rsmaxwell.diaries.response.model.Base;
import com.rsmaxwell.diaries.response.model.Marquee;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

// This is a Persistence-Oriented DTO
//
// It matches the full database structure, and can include full nested objects to simplify saving.
//
// (Note: We are not concerned with circular references, because this layer doesn’t serialize to JSON)

@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class FragmentDBDTO extends Base implements Jsonable {

	private static final ObjectMapper objectMapper = new ObjectMapper();

	private Marquee marquee;
	private Integer year;
	private Integer month;
	private Integer day;
	private BigDecimal sequence;
	private String text;

	@Override
	public String toJson() throws JsonProcessingException {
		return objectMapper.writeValueAsString(this);
	}

	@Override
	public byte[] toJsonAsBytes() throws JsonProcessingException {
		return objectMapper.writeValueAsBytes(this);
	}
}
