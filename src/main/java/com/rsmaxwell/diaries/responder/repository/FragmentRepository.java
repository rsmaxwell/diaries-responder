package com.rsmaxwell.diaries.responder.repository;

import java.math.BigDecimal;
import java.time.Instant;

import com.rsmaxwell.diaries.responder.dto.FragmentDBDTO;
import com.rsmaxwell.diaries.responder.model.Fragment;

public interface FragmentRepository extends CrudRepository<Fragment, FragmentDBDTO, Long> {

	/** Whether any Fragment currently references this Image (null has no references). */
	boolean existsByImageId(Long imageId);

	Iterable<FragmentDBDTO> findAllByDate(Integer year, Integer month, Integer day);

	/**
	 * Update only a fragment's sequence and version.
	 *
	 * @return the number of updated rows; zero means that the fragment no longer
	 *         has {@code expectedVersion}
	 */
	int updateSequence(Long id, Long expectedVersion, BigDecimal sequence);

	// int updateWithMarquee(Marquee marquee) throws Exception;

	public Iterable<FragmentDBDTO> findAllWithoutMarquee();

	Iterable<FragmentDBDTO> findStaleLocks(Instant olderThan);
}