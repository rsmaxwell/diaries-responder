package com.rsmaxwell.diaries.responder.config;

import lombok.Data;

@Data
public class MqttConfig {

	private String host;
	private int port;
	private User user;

	public String getServer() {
		return String.format("tcp://%s:%d", host, port);
	}

}
