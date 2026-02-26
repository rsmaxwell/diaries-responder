package com.rsmaxwell.diaries.responder.repository;

import java.util.Optional;

import com.rsmaxwell.diaries.responder.dto.PageDTO;
import com.rsmaxwell.diaries.responder.model.Page;

public interface PageRepository extends CrudRepository<Page, PageDTO, Long> {

	Iterable<PageDTO> findAllByDiary(Long diaryId);

	Optional<PageDTO> findByDiaryAndName(Long diaryId, String name);
}