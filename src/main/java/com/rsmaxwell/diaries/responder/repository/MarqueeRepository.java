package com.rsmaxwell.diaries.responder.repository;

import java.util.Optional;

import com.rsmaxwell.diaries.responder.dto.MarqueeDBDTO;
import com.rsmaxwell.diaries.responder.model.Fragment;
import com.rsmaxwell.diaries.responder.model.Marquee;

public interface MarqueeRepository extends CrudRepository<Marquee, MarqueeDBDTO, Long> {

	Iterable<MarqueeDBDTO> findAllByPage(Long pageId);

	Iterable<MarqueeDBDTO> findAllByFragment(Fragment fragment);

	Optional<MarqueeDBDTO> findByFragment(Fragment fragment);

	Optional<MarqueeDBDTO> findByFragmentId(Long id);
}