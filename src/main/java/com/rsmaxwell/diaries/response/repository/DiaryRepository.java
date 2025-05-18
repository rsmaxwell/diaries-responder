package com.rsmaxwell.diaries.response.repository;

import java.util.Optional;

import com.rsmaxwell.diaries.response.dto.DiaryDTO;
import com.rsmaxwell.diaries.response.model.Diary;

public interface DiaryRepository extends CrudRepository<Diary, DiaryDTO, Long> {

	@Override
	Optional<DiaryDTO> findById(Long id);

	Optional<DiaryDTO> findByName(String path);
}