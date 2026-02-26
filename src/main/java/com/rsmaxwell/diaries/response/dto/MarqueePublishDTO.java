package com.rsmaxwell.diaries.response.dto;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.paho.mqttv5.client.MqttAsyncClient;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rsmaxwell.diaries.response.model.Base;
import com.rsmaxwell.diaries.response.model.Marquee;
import com.rsmaxwell.diaries.response.utilities.Rectangle;

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
public class MarqueePublishDTO extends Base implements Jsonable {

	private static final Logger log = LogManager.getLogger(MarqueePublishDTO.class);
	private static final ObjectMapper objectMapper = new ObjectMapper();

	private Long pageId;
	private Long fragmentId;
	private Rectangle rectangle;

	@JsonIgnore
	private final Publisher publisher = new Publisher();

	public MarqueePublishDTO(Marquee marquee) {
		this.id = marquee.getId();
		this.version = marquee.getVersion();
		this.pageId = marquee.getPage().getId();
		this.fragmentId = marquee.getFragment().getId();
		this.rectangle = new Rectangle(marquee.getX(), marquee.getY(), marquee.getWidth(), marquee.getHeight());
	}

	@Override
	public String toJson() throws JsonProcessingException {
		return objectMapper.writeValueAsString(this);
	}

	@Override
	public byte[] toJsonAsBytes() throws JsonProcessingException {
		return objectMapper.writeValueAsBytes(this);
	}

	List<String> getTopics(Long diaryId) {
		List<String> topics = new ArrayList<String>();
		topics.add(String.format("diaries/%d/%d/%d", diaryId, pageId, id));
		topics.add(String.format("marquees/%d", id));
		return topics;
	}

	public void publish(ConcurrentHashMap<String, String> map, Long diaryId) throws Exception {
		for (String topic : getTopics(diaryId)) {
			publisher.publish(map, topic, toJson().getBytes());
		}
	}

	public void publish(MqttAsyncClient client, Long diaryId) throws Exception {
		for (String topic : getTopics(diaryId)) {
			log.info(String.format("publishing to topic: %s --> %s", topic, toJson()));
			publisher.publish(client, topic, toJson().getBytes());
		}
	}

	public void remove(ConcurrentHashMap<String, String> map, Long diaryId) throws Exception {
		for (String topic : getTopics(diaryId)) {
			publisher.publish(map, topic, Publisher.emptyPayload);
		}
	}

	public void remove(MqttAsyncClient client, Long diaryId) throws Exception {
		for (String topic : getTopics(diaryId)) {
			log.info(String.format("removing topic: %s", topic));
			publisher.publish(client, topic, Publisher.emptyPayload);
		}
	}
}
