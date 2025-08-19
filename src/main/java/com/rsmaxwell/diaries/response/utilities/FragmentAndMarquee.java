package com.rsmaxwell.diaries.response.utilities;

import com.rsmaxwell.diaries.response.model.Fragment;
import com.rsmaxwell.diaries.response.model.Marquee;

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
