package com.rsmaxwell.diaries.response.dto;

import java.math.BigDecimal;
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
import com.rsmaxwell.diaries.response.model.Fragment;

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
public class FragmentPublishDTO extends Base implements Jsonable {

	private static final Logger log = LogManager.getLogger(FragmentPublishDTO.class);
	private static final ObjectMapper objectMapper = new ObjectMapper();

	private Integer year;
	private Integer month;
	private Integer day;
	private BigDecimal sequence;
	private String text;
	private Long marqueeId;

	@JsonIgnore
	private final Publisher publisher = new Publisher();

	public FragmentPublishDTO(Fragment fragment) {
		this.id = fragment.getId();
		this.version = fragment.getVersion();
		this.sequence = fragment.getSequence();
		this.year = fragment.getYear();
		this.month = fragment.getMonth();
		this.day = fragment.getDay();
		this.text = fragment.getText();
		this.marqueeId = fragment.getMarqueeId();
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
		topics.add(String.format("fragments/%d", id));
		topics.add(String.format("dates/%s/%s/%s/%s", year, month, day, id));
		return topics;
	}

	public void publish(ConcurrentHashMap<String, String> map) throws Exception {
		for (String topic : getTopics()) {
			publisher.publish(map, topic, toJson().getBytes());
		}
	}

	public void publish(MqttAsyncClient client) throws Exception {
		for (String topic : getTopics()) {
			log.info(String.format("publishing to topic: %s --> %s", topic, toJson()));
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
