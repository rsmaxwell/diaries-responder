package com.rsmaxwell.diaries.response.sync;

import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.paho.mqttv5.client.IMqttToken;
import org.eclipse.paho.mqttv5.client.MqttCallback;
import org.eclipse.paho.mqttv5.common.MqttMessage;

import com.rsmaxwell.mqtt.rpc.common.Adapter;

public class SynchroniseCallback extends Adapter implements MqttCallback {

	private static final Logger log = LogManager.getLogger(SynchroniseCallback.class);

	private final AtomicLong lastMessageTime = new AtomicLong(System.currentTimeMillis());

	private static final long QUIET_PERIOD_MS = 1000; // consider done after 1 second of inactivity
	private static final long CHECK_INTERVAL_MS = 200; // how often to check

	private Map<String, String> topicMap;

	public SynchroniseCallback(Map<String, String> topicMap) {
		this.topicMap = topicMap;
	}

	@Override
	public void messageArrived(String topic, MqttMessage message) {

		lastMessageTime.set(System.currentTimeMillis());

		byte[] payload = message.getPayload();

		if (payload == null || payload.length == 0) {
			topicMap.remove(topic);
			// log.info(String.format("Deleted topic: %s, retained=%b, (empty payload)", topic, message.isRetained()));
		} else {
			topicMap.put(topic, new String(payload));
			// log.info(String.format("Received topic: %s, retained=%b, %s", topic, message.isRetained(), new String(payload)));
		}
	}

	public void waitForMessages() throws InterruptedException {
		log.info("SynchroniseCallback.waitForMessages");
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
