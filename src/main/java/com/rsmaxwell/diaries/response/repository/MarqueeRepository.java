package com.rsmaxwell.diaries.response.repository;

import com.rsmaxwell.diaries.response.dto.MarqueeDTO;
import com.rsmaxwell.diaries.response.model.Marquee;

public interface MarqueeRepository extends CrudRepository<Marquee, MarqueeDTO, Long> {

	Iterable<MarqueeDTO> findAllByPage(Long marqueeId);
}