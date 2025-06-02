package com.rsmaxwell.diaries.response.model;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;

import org.eclipse.paho.mqttv5.client.MqttAsyncClient;

import com.rsmaxwell.diaries.response.utilities.TriFunction;

public class Publishable {

	// private static final Logger log = LogManager.getLogger(Publishable.class);

	TriFunction<MqttAsyncClient, String, String, Object> mqttFn = (client, payload, topic) -> {
		int qos = 1;
		boolean retained = true;
		byte[] payloadBytes = payload.getBytes(StandardCharsets.UTF_8);
		return client.publish(topic, payloadBytes, qos, retained);
	};

	TriFunction<ConcurrentHashMap<String, String>, String, String, Object> mapFn = (map, payload, topic) -> {
		return map.put(topic, payload);
	};

	public <A> Object publishOne(TriFunction<A, String, String, Object> function, A a, String b, String c) throws Exception {

		// String topic = c;
		// String payload = b;
		// log.info(String.format("Publishable.publishOne: topic: '%s', value: '%s'", topic, payload));

		return function.apply(a, b, c);
	}
}
