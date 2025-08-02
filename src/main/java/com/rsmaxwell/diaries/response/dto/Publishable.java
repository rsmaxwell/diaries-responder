package com.rsmaxwell.diaries.response.dto;

import java.util.concurrent.ConcurrentHashMap;

import org.eclipse.paho.mqttv5.client.MqttAsyncClient;

public interface Publishable {

	public void publish(ConcurrentHashMap<String, String> map, String topic, byte[] payload) throws Exception;

	public void publish(MqttAsyncClient client, String topic, byte[] payload) throws Exception;
}