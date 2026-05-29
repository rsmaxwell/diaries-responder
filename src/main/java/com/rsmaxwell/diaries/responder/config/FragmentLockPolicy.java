package com.rsmaxwell.diaries.responder.config;

import java.time.Duration;

public final class FragmentLockPolicy {

	private FragmentLockPolicy() {
	}

	public static final Duration DEFAULT_TTL = Duration.ofMinutes(15);
}
