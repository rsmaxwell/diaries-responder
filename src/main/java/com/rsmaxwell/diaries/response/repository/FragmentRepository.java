package com.rsmaxwell.diaries.response.repository;

import com.rsmaxwell.diaries.response.dto.FragmentDTO;
import com.rsmaxwell.diaries.response.model.Fragment;
import com.rsmaxwell.diaries.response.model.Marquee;

public interface FragmentRepository extends CrudRepository<Fragment, FragmentDTO, Long> {

	Iterable<FragmentDTO> findByDate(Integer year, Integer month, Integer day);

	Iterable<FragmentDTO> findByPage(Long pageId);

	int updateWithMarquee(Marquee marquee) throws Exception;
}