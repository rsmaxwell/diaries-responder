package com.rsmaxwell.diaries.responder.utilities;

import com.rsmaxwell.diaries.responder.model.Fragment;
import com.rsmaxwell.diaries.responder.model.Marquee;

public class FragmentAndMarquee {
	private final Fragment fragment;
	private final Marquee marquee;

	public FragmentAndMarquee(Fragment fragment, Marquee marquee) {
		this.fragment = fragment;
		this.marquee = marquee;
	}

	public Fragment getFragment() {
		return fragment;
	}

	public Marquee getMarquee() {
		return marquee;
	}
}
