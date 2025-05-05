package com.rsmaxwell.diaries.response.model;

import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rsmaxwell.diaries.response.dto.DiaryDTO;
import com.rsmaxwell.diaries.response.utilities.DiaryContext;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.SneakyThrows;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class DiaryResponse {

	private DiaryDTO diary;

	private List<PageResponse> pages;

	public DiaryResponse(DiaryDTO diary, DiaryContext context) {
		this.diary = diary;
		this.pages = diary.getPages(context);
	}

	@SneakyThrows
	public String toJson() {
		return new ObjectMapper().writeValueAsString(this);
	}

	@SneakyThrows
	public byte[] toJsonAsBytes() {
		return new ObjectMapper().writeValueAsBytes(this);
	}
}
