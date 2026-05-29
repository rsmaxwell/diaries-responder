package com.rsmaxwell.diaries.responder.config;

import java.io.File;
import java.io.IOException;
import java.time.Duration;

import com.fasterxml.jackson.core.exc.StreamReadException;
import com.fasterxml.jackson.databind.DatabindException;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.Data;

@Data
public class Config {

	static private ObjectMapper mapper = new ObjectMapper();

	private MqttConfig mqtt;
	private DbConfig db;
	private DiariesConfig diaries;
	private String refreshPeriod;
	private String refreshExpiration;
	private String secret;
	private Boolean normaliseOnStartup;
	private Integer fragmentLockTtlSeconds;

	public static Config read(String filename) throws StreamReadException, DatabindException, IOException {
		File file = new File(filename);
		return mapper.readValue(file, Config.class);
	}

	public Integer getRefreshPeriodSeconds() {
		return TimeParser.parseTimeToSeconds(this.refreshPeriod);
	}

	public Integer getRefreshExpirationSeconds() {
		return TimeParser.parseTimeToSeconds(this.refreshExpiration);
	}

	public boolean isNormaliseOnStartup() {
		return normaliseOnStartup;
	}

	public Duration getFragmentLockTtl() {
		Integer seconds = fragmentLockTtlSeconds;

		if (seconds == null || seconds <= 0) {
			return FragmentLockPolicy.DEFAULT_TTL;
		}

		return Duration.ofSeconds(seconds);
	}
}
