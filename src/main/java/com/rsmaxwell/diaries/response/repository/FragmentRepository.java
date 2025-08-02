package com.rsmaxwell.diaries.response.repository;

import java.util.Optional;

import com.rsmaxwell.diaries.response.dto.FragmentDBDTO;
import com.rsmaxwell.diaries.response.model.Fragment;
import com.rsmaxwell.diaries.response.model.Marquee;

public interface FragmentRepository extends CrudRepository<Fragment, FragmentDBDTO, Long> {

	Iterable<FragmentDBDTO> findAllByDate(Integer year, Integer month, Integer day);

	Iterable<FragmentDBDTO> findAllByMarquee(Long id);

	Optional<FragmentDBDTO> findByMarquee(Long id);

	int updateWithMarquee(Marquee marquee) throws Exception;

	public Iterable<FragmentDBDTO> findAllWithoutMarquee();
}