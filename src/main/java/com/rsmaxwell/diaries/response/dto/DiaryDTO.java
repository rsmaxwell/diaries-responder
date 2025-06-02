package com.rsmaxwell.diaries.response.dto;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.rsmaxwell.diaries.response.model.Diary;
import com.rsmaxwell.diaries.response.repository.PageRepository;
import com.rsmaxwell.diaries.response.utilities.DiaryContext;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

@EqualsAndHashCode(exclude = { "id" }, callSuper = false)
@Data
@AllArgsConstructor
@NoArgsConstructor
public class DiaryDTO extends Jsonable {

	private Long id;
	private String name;
	private BigDecimal sequence;

	public Diary toDiary() {
		return new Diary(id, name, sequence);
	}

	@JsonIgnore
	public List<PageDTO> getPages(DiaryContext context) {

		PageRepository pageRepository = context.getPageRepository();
		List<PageDTO> pages = new ArrayList<PageDTO>();

		Iterable<PageDTO> all = pageRepository.findAllByDiary(this.id);
		for (PageDTO page : all) {
			pages.add(page);
		}

		return pages;
	}
}
