package com.rsmaxwell.diaries.response.repository;

import com.rsmaxwell.diaries.response.dto.FragmentDTO;
import com.rsmaxwell.diaries.response.model.Fragment;
import com.rsmaxwell.diaries.response.model.Page;

public interface FragmentRepository extends CrudRepository<Fragment, FragmentDTO, Long> {

	Iterable<FragmentDTO> findAllByPage(Page Page);

	Iterable<FragmentDTO> findAllByPageId(Long pageId);
}