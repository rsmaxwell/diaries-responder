package com.rsmaxwell.diaries.response.repository;

import java.util.Optional;

import com.rsmaxwell.diaries.response.dto.FragmentDBDTO;
import com.rsmaxwell.diaries.response.model.Fragment;
import com.rsmaxwell.diaries.response.model.Marquee;

public interface FragmentRepository extends CrudRepository<Fragment, FragmentDBDTO, Long> {

	Iterable<FragmentDBDTO> findByDate(Integer year, Integer month, Integer day);

	Iterable<FragmentDBDTO> findByPage(Long pageId);

	int updateWithMarquee(Marquee marquee) throws Exception;

	Optional<FragmentDBDTO> findByMarqueeId(Long id);
}