package com.rsmaxwell.diaries.response.repository;

import com.rsmaxwell.diaries.response.dto.FragmentDTO;
import com.rsmaxwell.diaries.response.model.Fragment;

public interface FragmentRepository extends CrudRepository<Fragment, FragmentDTO, Long> {

	Iterable<FragmentDTO> findAllByDate(Integer year, Integer month, Integer day);

	Iterable<FragmentDTO> findAllByPage(Long pageId);
}