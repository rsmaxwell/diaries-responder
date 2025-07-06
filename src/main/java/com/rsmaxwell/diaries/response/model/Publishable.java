package com.rsmaxwell.diaries.response.model;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import org.eclipse.paho.mqttv5.client.MqttAsyncClient;

import com.rsmaxwell.diaries.response.utilities.TriFunction;

public abstract class Publishable {

	TriFunction<MqttAsyncClient, byte[], String, Object> mqttFn = (client, payload, topic) -> {
		int qos = 1;
		boolean retained = true;
		return client.publish(topic, payload, qos, retained);
	};

	TriFunction<ConcurrentHashMap<String, String>, byte[], String, Object> mapFn = (map, payload, topic) -> {
		String payloadString = new String(payload);
		return map.put(topic, payloadString);
	};

	public <A> Object publish(TriFunction<A, byte[], String, Object> function, A a, byte[] payload, String topic) throws Exception {
		return function.apply(a, payload, topic);
	}

	abstract List<String> getTopics();

	abstract String getPayload();

	void removeChildren(ConcurrentHashMap<String, String> map) throws Exception {
	}

	void removeChildren(MqttAsyncClient client) throws Exception {
	}

	public void publishAll(ConcurrentHashMap<String, String> map) throws Exception {
		for (String topic : getTopics()) {
			publish(mapFn, map, getPayload().getBytes(), topic);
		}
	}

	public void publishAll(MqttAsyncClient client) throws Exception {
		for (String topic : getTopics()) {
			publish(mqttFn, client, getPayload().getBytes(), topic);
		}
	}

	public void removeAll(ConcurrentHashMap<String, String> map) throws Exception {

		removeChildren(map);

		byte[] payload = new byte[0];
		for (String topic : getTopics()) {
			publish(mapFn, map, payload, topic);
		}
	}

	public void removeAll(MqttAsyncClient client) throws Exception {

		removeChildren(client);

		byte[] payload = new byte[0];
		for (String topic : getTopics()) {
			publish(mqttFn, client, payload, topic);
		}
	}
}
