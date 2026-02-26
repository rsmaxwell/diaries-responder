package com.rsmaxwell.diaries.responder.utilities;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;

public class MyGeneralUtilities {

	public static String readTextResource(String filename, Class<?> clazz) throws IOException {

		// Read the template for the 'image.svg' file
		InputStream inputStream = clazz.getResourceAsStream(filename);

		StringBuilder resultStringBuilder = new StringBuilder();
		try (BufferedReader br = new BufferedReader(new InputStreamReader(inputStream))) {
			String line;
			while ((line = br.readLine()) != null) {
				resultStringBuilder.append(line).append("\n");
			}
		}

		return resultStringBuilder.toString();
	}
}
