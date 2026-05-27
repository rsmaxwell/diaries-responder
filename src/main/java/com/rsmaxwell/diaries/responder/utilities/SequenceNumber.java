package com.rsmaxwell.diaries.responder.utilities;

import java.math.BigDecimal;
import java.math.RoundingMode;

public final class SequenceNumber {

	public static final int SCALE = 4;

	private SequenceNumber() {
	}

	public static BigDecimal normalise(BigDecimal value) {
		if (value == null) {
			return null;
		}

		return value.setScale(SCALE, RoundingMode.UNNECESSARY);
	}
}
