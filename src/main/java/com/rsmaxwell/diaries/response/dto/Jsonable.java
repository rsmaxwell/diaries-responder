package com.rsmaxwell.diaries.response.dto;

import com.fasterxml.jackson.core.JsonProcessingException;

interface Jsonable {

	public String toJson() throws JsonProcessingException;

	public byte[] toJsonAsBytes() throws JsonProcessingException;
}