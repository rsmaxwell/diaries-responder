package com.rsmaxwell.diaries.response.utilities;

import java.io.File;

public class MyFileUtilities {

	public static String removeExtension(File f) {
		return getFileName(f.getName());
	}

	public static String getFileName(String fname) {
		int pos = fname.lastIndexOf('.');
		if (pos > -1)
			return fname.substring(0, pos);
		else
			return fname;
	}

	public static String getFileExtension(String filename) {
		if (filename == null || filename.isEmpty()) {
			return "";
		}

		int lastDot = filename.lastIndexOf('.');
		int lastSeparator = Math.max(filename.lastIndexOf('/'), filename.lastIndexOf('\\'));

		// Make sure the dot comes after the last file separator (i.e., not part of a
		// directory name)
		if (lastDot > lastSeparator && lastDot < filename.length() - 1) {
			return filename.substring(lastDot);
		}

		return "";
	}
}
