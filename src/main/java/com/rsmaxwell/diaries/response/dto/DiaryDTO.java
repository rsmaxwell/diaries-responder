package com.rsmaxwell.diaries.response.dto;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.rsmaxwell.diaries.response.model.Diary;
import com.rsmaxwell.diaries.response.model.PageResponse;
import com.rsmaxwell.diaries.response.repository.PageRepository;
import com.rsmaxwell.diaries.response.utilities.DiaryContext;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

@EqualsAndHashCode(exclude = { "id" })
@Data
@AllArgsConstructor
@NoArgsConstructor
public class DiaryDTO {

	private Long id;
	private String name;
	private BigDecimal sequence;

	public Diary toDiary() {
		return new Diary(id, name, sequence);
	}

	@JsonIgnore
	public List<PageResponse> getPages(DiaryContext context) {

		PageRepository pageRepository = context.getPageRepository();
		List<PageResponse> pages = new ArrayList<PageResponse>();

		Iterable<PageDTO> all = pageRepository.findAllByDiary(this.id);
		for (PageDTO page : all) {
			PageResponse pageResponse = new PageResponse(page);
			pages.add(pageResponse);
		}

		return pages;
	}
}
