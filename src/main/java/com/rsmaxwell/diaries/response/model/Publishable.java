package com.rsmaxwell.diaries.response.model;

import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

import org.eclipse.paho.mqttv5.client.MqttAsyncClient;

import com.rsmaxwell.diaries.response.dto.Jsonable;
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

	public <A> Object publishOne(TriFunction<A, byte[], String, Object> function, A a, Function<Jsonable, byte[]> payloadFn, Jsonable dto, String c) throws Exception {
		byte[] payload = payloadFn.apply(dto);
		return function.apply(a, payload, c);
	}
}
