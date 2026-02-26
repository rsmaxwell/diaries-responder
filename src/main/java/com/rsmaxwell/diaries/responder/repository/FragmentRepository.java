package com.rsmaxwell.diaries.responder.repository;

import com.rsmaxwell.diaries.responder.dto.FragmentDBDTO;
import com.rsmaxwell.diaries.responder.model.Fragment;
import com.rsmaxwell.diaries.responder.model.Marquee;

public interface FragmentRepository extends CrudRepository<Fragment, FragmentDBDTO, Long> {

	Iterable<FragmentDBDTO> findAllByDate(Integer year, Integer month, Integer day);

	int updateWithMarquee(Marquee marquee) throws Exception;

	public Iterable<FragmentDBDTO> findAllWithoutMarquee();
}