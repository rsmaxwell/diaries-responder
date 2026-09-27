package com.rsmaxwell.diaries.responder.dto;

import java.util.concurrent.ConcurrentHashMap;

import org.eclipse.paho.mqttv5.client.MqttAsyncClient;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rsmaxwell.diaries.responder.model.Base;
import com.rsmaxwell.diaries.responder.model.Image;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.NonNull;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

/** Metadata-only retained projection; no file bytes, URL or chronology ownership. */
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class ImagePublishDTO extends Base implements Jsonable {

	private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

	@JsonIgnore
	@EqualsAndHashCode.Exclude
	@ToString.Exclude
	private final Publisher publisher = new Publisher();

	private String relativePath;

	private String mimeType;

	private String originalFilename;

	private Integer width;

	private Integer height;

	private String checksum;

	@Builder.Default
	@NonNull
	private String caption = "";

	@Builder.Default
	@NonNull
	private String altText = "";

	public ImagePublishDTO(Image image) {
		image.validate();
		this.id = image.getId();
		this.version = image.getVersion();
		this.relativePath = image.getRelativePath();
		this.mimeType = image.getMimeType();
		this.originalFilename = image.getOriginalFilename();
		this.width = image.getWidth();
		this.height = image.getHeight();
		this.checksum = image.getChecksum();
		this.caption = image.getCaption();
		this.altText = image.getAltText();
	}

	public ImagePublishDTO(ImageDBDTO dto) {
		this(new Image(dto));
	}

	/** Validates mutable/builder/deserialized DTOs before crossing a boundary. */
	public void validate() {
		new Image(this);
	}

	@Override
	public String toJson() throws JsonProcessingException {
		validate();
		requirePersistedId();
		return OBJECT_MAPPER.writeValueAsString(this);
	}

	@Override
	public byte[] toJsonAsBytes() throws JsonProcessingException {
		validate();
		requirePersistedId();
		return OBJECT_MAPPER.writeValueAsBytes(this);
	}

	private void requirePersistedId() {
		if (id == null || id <= 0) {
			throw new IllegalArgumentException("Image publication requires a positive persisted id");
		}
	}

	private String topic() {
		requirePersistedId();
		return "diaries/images/" + id;
	}

	public void publish(ConcurrentHashMap<String, String> map) throws Exception {
		publisher.publish(map, topic(), toJsonAsBytes());
	}

	public void publish(MqttAsyncClient client) throws Exception {
		publisher.publish(client, topic(), toJsonAsBytes());
	}

	/** Tombstones need only identity, allowing removal without loading metadata. */
	public void remove(ConcurrentHashMap<String, String> map) throws Exception {
		publisher.publish(map, topic(), Publisher.emptyPayload);
	}

	public void remove(MqttAsyncClient client) throws Exception {
		publisher.publish(client, topic(), Publisher.emptyPayload);
	}

    /** Lifecycle deletion must observe broker acknowledgement before discarding its backup. */
    public void removeAndAwait(MqttAsyncClient client) throws Exception {
        var delivery = client.publish(topic(), Publisher.emptyPayload, 1, true);
        delivery.waitForCompletion(10_000);
        var reasons = delivery.getReasonCodes();
        if (reasons != null) for (int reason : reasons) {
            if (reason >= 128) throw new java.io.IOException("Image tombstone rejected by broker");
        }
    }

}
