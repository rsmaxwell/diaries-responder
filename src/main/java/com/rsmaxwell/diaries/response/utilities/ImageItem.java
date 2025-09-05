package com.rsmaxwell.diaries.response.utilities;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

import com.fasterxml.jackson.annotation.JsonInclude;

import lombok.Data;

@Data
@lombok.Value
@lombok.Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ImageItem {

	private static final ZoneId LOG_ZONE = ZoneId.systemDefault();
	private static final DateTimeFormatter LOG_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(LOG_ZONE);

	private final String name; // file or directory name
	private final String url; // null for directories
	private final long size; // 0 for directories
	private final long mtime; // last-modified millis (file or dir)
	private final Long dateTaken; // null for directories (and for files when unknown)
	private final boolean dir; // true = directory, false = file

	/** Factory for a directory item */
	public static ImageItem directory(String name, long mtime) {
		return new ImageItem(name, null, 0L, mtime, null, true);
	}

	/** Factory for a file item */
	public static ImageItem file(String name, String url, long size, long mtime, Long dateTaken) {
		return new ImageItem(name, url, size, mtime, dateTaken, false);
	}

	private static String fmt(long millis) {
		try {
			return LOG_FMT.format(Instant.ofEpochMilli(millis));
		} catch (Exception e) {
			return Long.toString(millis);
		}
	}

	public String mtimeStr() {
		return fmt(mtime);
	}

	/**
	 * Format dateTaken if present. Accept negative values (pre-1970) and zero. If null, do not attempt to format and return an em dash.
	 */
	public String dateTakenStr() {
		final Long dt = this.dateTaken;
		if (dt == null) {
			return "—";
		}
		return fmt(dt.longValue());
	}
}