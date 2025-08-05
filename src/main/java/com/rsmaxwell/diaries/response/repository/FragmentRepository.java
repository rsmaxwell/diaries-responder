package com.rsmaxwell.diaries.response.repository;

import com.rsmaxwell.diaries.response.dto.FragmentDBDTO;
import com.rsmaxwell.diaries.response.model.Fragment;
import com.rsmaxwell.diaries.response.model.Marquee;

public interface FragmentRepository extends CrudRepository<Fragment, FragmentDBDTO, Long> {

	Iterable<FragmentDBDTO> findAllByDate(Integer year, Integer month, Integer day);

	int updateWithMarquee(Marquee marquee) throws Exception;

	public Iterable<FragmentDBDTO> findAllWithoutMarquee();
}