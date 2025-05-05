package com.rsmaxwell.diaries.response.repository;

import java.util.Optional;

import com.rsmaxwell.diaries.response.dto.PageDTO;
import com.rsmaxwell.diaries.response.model.Page;

public interface PageRepository extends CrudRepository<Page, PageDTO, Long> {

	Iterable<PageDTO> findAllByDiary(Long diaryId);

	Optional<PageDTO> findByDiaryAndName(Long diaryId, String name);
}