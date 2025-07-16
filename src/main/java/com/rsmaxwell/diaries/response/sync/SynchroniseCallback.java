package com.rsmaxwell.diaries.response.sync;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import org.eclipse.paho.mqttv5.client.IMqttToken;
import org.eclipse.paho.mqttv5.client.MqttCallback;
import org.eclipse.paho.mqttv5.common.MqttMessage;

import com.rsmaxwell.mqtt.rpc.common.Adapter;

public class SynchroniseCallback extends Adapter implements MqttCallback {

	private final ConcurrentHashMap<String, String> topicMap = new ConcurrentHashMap<>();
	private final AtomicLong lastMessageTime = new AtomicLong(System.currentTimeMillis());

	private static final long QUIET_PERIOD_MS = 1000; // consider done after 1 second of inactivity
	private static final long CHECK_INTERVAL_MS = 200; // how often to check

	@Override
	public void messageArrived(String topic, MqttMessage message) {
		lastMessageTime.set(System.currentTimeMillis());

		byte[] payload = message.getPayload();

		if (payload == null || payload.length == 0) {
			topicMap.remove(topic);
			// System.out.printf("Deleted topic (empty payload): %s%n", topic);
		} else {
			topicMap.put(topic, new String(payload));
			// System.out.printf("Received topic: %s (%d bytes)%n", topic, payload.length);
		}
	}

	public Map<String, String> getTopicMap() {
		return topicMap;
	}

	public void waitForRetainedMessages() throws InterruptedException {

		lastMessageTime.set(System.currentTimeMillis());

		while (true) {
			Thread.sleep(CHECK_INTERVAL_MS);
			long idle = System.currentTimeMillis() - lastMessageTime.get();

			if (idle > QUIET_PERIOD_MS) {
				break; // Done receiving retained messages
			}
		}
	}

	@Override
	public void deliveryComplete(IMqttToken token) {
	}

	@Override
	public void connectComplete(boolean reconnect, String serverURI) {
	}
}
