package com.rsmaxwell.diaries.response.dto;

import java.util.concurrent.ConcurrentHashMap;

import org.eclipse.paho.mqttv5.client.MqttAsyncClient;

public class Publisher implements Publishable {

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
		client.publish(topic, payload, qos, retained);
	}
}
