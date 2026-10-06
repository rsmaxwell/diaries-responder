package com.rsmaxwell.diaries.responder.utilities;

import java.util.List;

import com.rsmaxwell.diaries.responder.model.Fragment;

/**
 * Result of an atomic Fragment creation plus chronology normalisation.
 *
 * <p>The created state is reloaded after commit so its sequence/version match
 * the database. {@code normalisedFragments} contains every Fragment whose
 * sequence/version was changed while closing the affected date chronology.
 */
public final class FragmentCreationResult {

	private final ResolvedFragmentState createdState;
	private final List<Fragment> normalisedFragments;

	public FragmentCreationResult(ResolvedFragmentState createdState, List<Fragment> normalisedFragments) {
		if (createdState == null || createdState.getFragment() == null) {
			throw new IllegalArgumentException("Created Fragment state is required");
		}
		this.createdState = createdState;
		this.normalisedFragments = List.copyOf(normalisedFragments == null ? List.of() : normalisedFragments);
	}

	public ResolvedFragmentState getCreatedState() {
		return createdState;
	}

	public Fragment getFragment() {
		return createdState.getFragment();
	}

	public com.rsmaxwell.diaries.responder.model.Marquee getMarquee() {
		return createdState.getMarquee();
	}

	public List<Fragment> getNormalisedFragments() {
		return normalisedFragments;
	}
}
