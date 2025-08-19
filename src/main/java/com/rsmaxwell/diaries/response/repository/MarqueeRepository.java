package com.rsmaxwell.diaries.response.repository;

import java.util.Optional;

import com.rsmaxwell.diaries.response.dto.MarqueeDBDTO;
import com.rsmaxwell.diaries.response.model.Fragment;
import com.rsmaxwell.diaries.response.model.Marquee;

public interface MarqueeRepository extends CrudRepository<Marquee, MarqueeDBDTO, Long> {

	Iterable<MarqueeDBDTO> findAllByPage(Long pageId);

	Iterable<MarqueeDBDTO> findAllByFragment(Fragment fragment);

	Optional<MarqueeDBDTO> findByFragment(Fragment fragment);

	Optional<MarqueeDBDTO> findByFragmentId(Long id);
}