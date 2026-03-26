package com.rsmaxwell.diaries.responder.dto;

import java.util.concurrent.ConcurrentHashMap;

import org.eclipse.paho.mqttv5.client.MqttAsyncClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Publisher implements Publishable {

	private static final Logger log = LoggerFactory.getLogger(Publisher.class);
	protected static byte[] emptyPayload = new byte[0];

	@Override
	public void publish(ConcurrentHashMap<String, String> map, String topic, byte[] payload) throws Exception {
		if (payload.length == 0) {
			map.remove(topic);
		} else {
			String payloadString = new String(payload);
			map.put(topic, payloadString);
		}
	}

	@Override
	public void publish(MqttAsyncClient client, String topic, byte[] payload) throws Exception {
		int qos = 1;
		boolean retained = true;

		log.info("Publishing payload using: retained: {}, qos {}", retained, qos);

		client.publish(topic, payload, qos, retained);
	}
}
