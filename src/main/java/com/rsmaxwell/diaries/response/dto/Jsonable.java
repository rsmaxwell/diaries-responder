package com.rsmaxwell.diaries.response.dto;

import java.util.function.Function;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.SneakyThrows;

public class Jsonable {

	@JsonIgnore
	public Function<Jsonable, byte[]> publishFn = (x) -> {
		return x.toJsonAsBytes();
	};

	@JsonIgnore
	public Function<Jsonable, byte[]> removeFn = (x) -> {
		return new byte[0];
	};

	@SneakyThrows
	public String toJson() {
		return new ObjectMapper().writeValueAsString(this);
	}

	@SneakyThrows
	public byte[] toJsonAsBytes() {
		return new ObjectMapper().writeValueAsBytes(this);
	}
}