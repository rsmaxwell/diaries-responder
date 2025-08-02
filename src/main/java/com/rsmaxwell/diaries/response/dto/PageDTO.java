
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
import com.rsmaxwell.diaries.response.model.Page;

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
public class PageDTO extends Base implements Jsonable {

	private static final ObjectMapper objectMapper = new ObjectMapper();

	private Long diaryId;
	private String name;
	private BigDecimal sequence;
	private String extension;
	private Integer width;
	private Integer height;

	@JsonIgnore
	private final Publisher publisher = new Publisher();

	public PageDTO(Page page) {
		this.id = page.getId();
		this.diaryId = page.getDiary().getId();
		this.name = page.getName();
		this.sequence = page.getSequence();
		this.extension = page.getExtension();
		this.width = page.getWidth();
		this.height = page.getHeight();
		this.version = page.getVersion();
	}

	public void updateFrom(PageDTO other) {
		this.diaryId = other.diaryId;
		this.name = other.name;
		this.sequence = other.sequence;
		this.extension = other.extension;
		this.width = other.width;
		this.height = other.height;
		this.version = other.version;
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
		topics.add(String.format("diaries/%d/%d", diaryId, id));
		topics.add(String.format("pages/%d", id));
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

	public boolean equalsExcludingIdAndVersion(PageDTO other) {
		if (other == null) {
			return false;
		}

		// @formatter:off
		if (!equalsNullable(this.diaryId, other.diaryId)) { return false; }
		if (!equalsNullable(this.name, other.name)) { return false; }
		if (!compareToNullable(this.sequence, other.sequence)) { return false; }
		if (!equalsNullable(this.extension, other.extension)) { return false; }
		if (!equalsNullable(this.width, other.width)) { return false; }
		if (!equalsNullable(this.height, other.height)) { return false; }
		// @formatter:on

		return true;
	}

	private boolean equalsNullable(Object a, Object b) {
		return a == null ? b == null : a.equals(b);
	}

	private boolean compareToNullable(BigDecimal a, BigDecimal b) {
		return a == null ? b == null : (a.compareTo(b) == 0);
	}
}
