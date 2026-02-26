package com.rsmaxwell.diaries.responder.repository;

import java.util.Optional;

import com.rsmaxwell.diaries.responder.dto.DiaryDTO;
import com.rsmaxwell.diaries.responder.model.Diary;

public interface DiaryRepository extends CrudRepository<Diary, DiaryDTO, Long> {

	Optional<DiaryDTO> findByName(String path);
}