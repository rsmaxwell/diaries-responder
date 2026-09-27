package com.rsmaxwell.diaries.responder.utilities;

import com.rsmaxwell.diaries.responder.model.Fragment;
import com.rsmaxwell.diaries.responder.model.Marquee;
import com.rsmaxwell.diaries.responder.model.Image;
import com.rsmaxwell.diaries.responder.model.FragmentType;
import java.util.Objects;

public class ResolvedFragmentState {
	private final Fragment fragment;
	private final Marquee marquee;

	public ResolvedFragmentState(Fragment fragment, Marquee marquee) {
		this.fragment = fragment;
		this.marquee = marquee;
	}

	public Image getImage() { return fragment.getImage(); }

	/** Writer validation; readers may expose incomplete legacy state for repair. */
	public void validateForWrite() {
		if (fragment.getType() == FragmentType.IMAGE) {
			if (marquee != null) throw new IllegalArgumentException("IMAGE fragment cannot have a Marquee");
			if (fragment.getImageId() != null && getImage() == null)
				throw new IllegalArgumentException("Fragment Image reference is unresolved");
		} else if (fragment.getType() == FragmentType.MARQUEE) {
			if (fragment.getImageId() != null) throw new IllegalArgumentException("MARQUEE fragment cannot reference an Image");
		} else {
			throw new IllegalArgumentException("Fragment type is required for writes");
		}
		if (marquee != null && (marquee.getFragment() != fragment
				|| marquee.getPage() == null || fragment.getPageId() == null
				|| !Objects.equals(marquee.getPage().getId(), fragment.getPageId())))
			throw new IllegalArgumentException("Marquee must belong to the same Fragment and Page");
	}

	public Fragment getFragment() {
		return fragment;
	}

	public Marquee getMarquee() {
		return marquee;
	}
}
