package com.rsmaxwell.diaries.responder.config;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class TimeParser {

	public static int parseTimeToSeconds(String input) {
		// Regular expression to match days (d), hours (h), minutes (m), and seconds (s)
		Pattern pattern = Pattern.compile("^\\s*(?:(\\d+)d)?(?:(\\d+)h)?(?:(\\d+)m)?(?:(\\d+)s)?\\s*$");
		Matcher matcher = pattern.matcher(input);

		if (!matcher.matches()) {
			throw new IllegalArgumentException("Invalid time format");
		}

		// Parse the group values
		String daysString = matcher.group(1);
		String hoursString = matcher.group(2);
		String minutesString = matcher.group(3);
		String secondsString = matcher.group(4);

		// Check that at least one group matched
		if (daysString == null && hoursString == null && minutesString == null && secondsString == null) {
			throw new IllegalArgumentException("Invalid time format");
		}

		int days = daysString != null ? Integer.parseInt(daysString) : 0;
		int hours = hoursString != null ? Integer.parseInt(hoursString) : 0;
		int minutes = minutesString != null ? Integer.parseInt(minutesString) : 0;
		int seconds = secondsString != null ? Integer.parseInt(secondsString) : 0;

		// Calculate total seconds
		return (days * 24 * 3600) + (hours * 3600) + (minutes * 60) + seconds;
	}

	public static void main(String[] args) {
		String input = "1h2m3s"; // Example input
		int totalSeconds = parseTimeToSeconds(input);
		System.out.println("Total seconds: " + totalSeconds);
	}
}
