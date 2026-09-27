package com.rsmaxwell.diaries.responder.utilities;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import com.rsmaxwell.diaries.responder.dto.FragmentDBDTO;
import com.rsmaxwell.diaries.responder.dto.FragmentPublishDTO;
import com.rsmaxwell.diaries.responder.model.Fragment;
import com.rsmaxwell.diaries.responder.repository.FragmentRepository;
import com.rsmaxwell.mqtt.rpc.exceptions.RpcStatusException;

public final class FragmentSequenceNormaliser {

	private static final BigDecimal INITIAL_SEQUENCE = new BigDecimal("1.0000");
	private static final BigDecimal SEQUENCE_INCREMENT = new BigDecimal("1.0000");

	private FragmentSequenceNormaliser() {
	}

	/**
	 * Renumber one date using the repository's established date/sequence/id order.
	 * The caller owns the surrounding database transaction.
	 */
	public static List<Fragment> normaliseDate(
			FragmentRepository fragmentRepository,
			Integer year,
			Integer month,
			Integer day) throws Exception {

		List<Fragment> updates = new ArrayList<>();
		BigDecimal sequence = INITIAL_SEQUENCE;

		for (FragmentDBDTO fragmentDTO : fragmentRepository.findAllByDate(year, month, day)) {
			BigDecimal currentSequence = fragmentDTO.getSequence();
			if (currentSequence == null || currentSequence.compareTo(sequence) != 0) {
				Fragment fragment = new Fragment(fragmentDTO);
				int count = fragmentRepository.updateSequence(
						fragment.getId(),
						fragment.getVersion(),
						sequence);

				if (count != 1) {
					throw RpcStatusException.conflict(String.format(
							"Fragment changed while normalising date %d-%02d-%02d: id=%d, expectedVersion=%d",
							year,
							month,
							day,
							fragment.getId(),
							fragment.getVersion()));
				}

				fragment.setSequence(sequence);
				fragment.incrementVersion();
				updates.add(fragment);
			}
			sequence = sequence.add(SEQUENCE_INCREMENT);
		}

		return updates;
	}

	/**
	 * Renumber every date affected by a fragment update. A date move affects both
	 * the old and new dates; a sequence-only move affects the fragment's current
	 * date. The repository must already contain the incoming fragment and the
	 * caller owns the surrounding transaction.
	 */
	public static List<Fragment> normaliseAffectedDates(
			FragmentRepository fragmentRepository,
			Fragment originalFragment,
			Fragment incomingFragment) throws Exception {

		if (!dateChanged(originalFragment, incomingFragment)) {
			if (sequenceChanged(originalFragment, incomingFragment)) {
				return normaliseDate(
						fragmentRepository,
						incomingFragment.getYear(),
						incomingFragment.getMonth(),
						incomingFragment.getDay());
			}
			return List.of();
		}

		List<Fragment> updates = new ArrayList<>();
		updates.addAll(normaliseDate(
				fragmentRepository,
				originalFragment.getYear(),
				originalFragment.getMonth(),
				originalFragment.getDay()));
		updates.addAll(normaliseDate(
				fragmentRepository,
				incomingFragment.getYear(),
				incomingFragment.getMonth(),
				incomingFragment.getDay()));
		return updates;
	}

	public static boolean dateChanged(Fragment originalFragment, Fragment incomingFragment) {
		return !Objects.equals(originalFragment.getYear(), incomingFragment.getYear())
				|| !Objects.equals(originalFragment.getMonth(), incomingFragment.getMonth())
				|| !Objects.equals(originalFragment.getDay(), incomingFragment.getDay());
	}

	public static boolean sequenceChanged(Fragment originalFragment, Fragment incomingFragment) {
		BigDecimal originalSequence = originalFragment.getSequence();
		BigDecimal incomingSequence = incomingFragment.getSequence();

		if (originalSequence == null || incomingSequence == null) {
			return !Objects.equals(originalSequence, incomingSequence);
		}

		return originalSequence.compareTo(incomingSequence) != 0;
	}

	/** Publish committed sequence changes to both canonical and date topics. */
	public static void publish(DiaryContext context, Iterable<Fragment> fragments) throws Exception {
		for (Fragment fragment : fragments) {
			ResolvedFragmentState state = context.resolveFragmentState(fragment);
			new FragmentPublishDTO(state.getFragment(), state.getMarquee()).publish(context.getPublisherClient());
		}
	}
}
