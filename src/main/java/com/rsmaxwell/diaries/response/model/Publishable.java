package com.rsmaxwell.diaries.response.model;

import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

import org.eclipse.paho.mqttv5.client.MqttAsyncClient;

import com.rsmaxwell.diaries.response.utilities.TriFunction;

public class Publishable {

	TriFunction<MqttAsyncClient, byte[], String, Object> mqttFn = (client, payload, topic) -> {
		int qos = 1;
		boolean retained = true;
		return client.publish(topic, payload, qos, retained);
	};

	TriFunction<ConcurrentHashMap<String, String>, byte[], String, Object> mapFn = (map, payload, topic) -> {
		String payloadString = new String(payload);
		return map.put(topic, payloadString);
	};

	Function<String, byte[]> publishFn = (x) -> {
		return x.getBytes();
	};

	Function<String, byte[]> removeFn = (x) -> {
		return new byte[0];
	};

	public <A> Object publish(TriFunction<A, byte[], String, Object> function, A a, Function<String, byte[]> payloadFn, String payload, String c) throws Exception {
		byte[] payloadBytes = payloadFn.apply(payload);
		return function.apply(a, payloadBytes, c);
	}
}
