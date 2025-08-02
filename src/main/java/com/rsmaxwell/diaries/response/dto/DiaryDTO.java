package com.rsmaxwell.diaries.response.dto;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import org.eclipse.paho.mqttv5.client.MqttAsyncClient;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rsmaxwell.diaries.response.model.Base;
import com.rsmaxwell.diaries.response.model.Diary;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class DiaryDTO extends Base implements Jsonable {

	private static final ObjectMapper objectMapper = new ObjectMapper();

	private String name;
	private BigDecimal sequence;

	@JsonIgnore
	private final Publisher publisher = new Publisher();

	public DiaryDTO(Diary diary) {
		this.id = diary.getId();
		this.name = diary.getName();
		this.sequence = diary.getSequence();
		this.version = diary.getVersion();
	}

	@Override
	public String toJson() throws JsonProcessingException {
		return objectMapper.writeValueAsString(this);
	}

	@Override
	public byte[] toJsonAsBytes() throws JsonProcessingException {
		return objectMapper.writeValueAsBytes(this);
	}

	List<String> getTopics() {
		List<String> topics = new ArrayList<String>();
		topics.add(String.format("diaries/%d", id));
		return topics;
	}

	public void publish(ConcurrentHashMap<String, String> map) throws Exception {
		for (String topic : getTopics()) {
			publisher.publish(map, topic, toJson().getBytes());
		}
	}

	public void publish(MqttAsyncClient client) throws Exception {
		for (String topic : getTopics()) {
			publisher.publish(client, topic, toJson().getBytes());
		}
	}

	public void remove(ConcurrentHashMap<String, String> map) throws Exception {
		for (String topic : getTopics()) {
			publisher.publish(map, topic, Publisher.emptyPayload);
		}
	}

	public void remove(MqttAsyncClient client) throws Exception {
		for (String topic : getTopics()) {
			publisher.publish(client, topic, Publisher.emptyPayload);
		}
	}
}
