package com.rsmaxwell.diaries.response.utilities;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.paho.mqttv5.client.IMqttToken;
import org.eclipse.paho.mqttv5.client.MqttAsyncClient;
import org.eclipse.paho.mqttv5.client.MqttCallback;
import org.eclipse.paho.mqttv5.client.MqttDisconnectResponse;
import org.eclipse.paho.mqttv5.common.MqttException;
import org.eclipse.paho.mqttv5.common.MqttMessage;
import org.eclipse.paho.mqttv5.common.MqttSubscription;
import org.eclipse.paho.mqttv5.common.packet.MqttProperties;

import com.rsmaxwell.mqtt.rpc.response.MessageHandler;

public class MyMessageHandler implements MqttCallback {

	private static final Logger log = LogManager.getLogger(MyMessageHandler.class);

	static final String requestTopic = "request";

	private MessageHandler messageHandler;

	public MyMessageHandler(MessageHandler messageHandler) {
		this.messageHandler = messageHandler;
	}

	@Override
	public void disconnected(MqttDisconnectResponse disconnectResponse) {
		messageHandler.disconnected(disconnectResponse);
	}

	@Override
	public void mqttErrorOccurred(MqttException exception) {
		messageHandler.mqttErrorOccurred(exception);
	}

	@Override
	public void messageArrived(String topic, MqttMessage message) throws Exception {
		log.info("Message received on topic '{}': {}", topic, new String(message.getPayload()));
		messageHandler.messageArrived(topic, message);
	}

	@Override
	public void deliveryComplete(IMqttToken token) {
		messageHandler.deliveryComplete(token);
	}

	@Override
	public void connectComplete(boolean reconnect, String serverURI) {

		try {
			if (reconnect) {
				log.info(String.format("Reconnected, re-subscribing to: '%s'", requestTopic));
			} else {
				log.info(String.format("Connected, subscribing to: '%s'", requestTopic));
			}

			int qos = 1;
			MqttSubscription subscription = new MqttSubscription(requestTopic, qos);

			MqttAsyncClient listenerClient = messageHandler.getListenerClient();
			listenerClient.subscribe(subscription);

		} catch (MqttException e) {
			log.catching(e);
		}

		messageHandler.connectComplete(reconnect, serverURI);
	}

	@Override
	public void authPacketArrived(int reasonCode, MqttProperties properties) {
		messageHandler.authPacketArrived(reasonCode, properties);
	}

}
