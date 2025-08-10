package com.rsmaxwell.diaries.response.repository;

import java.util.Optional;

import com.rsmaxwell.diaries.response.dto.MarqueeDBDTO;
import com.rsmaxwell.diaries.response.model.Fragment;
import com.rsmaxwell.diaries.response.model.Marquee;

public interface MarqueeRepository extends CrudRepository<Marquee, MarqueeDBDTO, Long> {

	Iterable<MarqueeDBDTO> findAllByFragment(Fragment fragment);

	Iterable<MarqueeDBDTO> findAllByPage(Long pageId);

	Optional<MarqueeDBDTO> findByFragmentId(Long id);

	Optional<MarqueeDBDTO> findByFragment(Long id);
}