package com.rsmaxwell.diaries.response.model;

import java.math.BigDecimal;
import java.util.concurrent.ConcurrentHashMap;

import org.eclipse.paho.mqttv5.client.MqttAsyncClient;

import com.rsmaxwell.diaries.response.dto.PageDTO;
import com.rsmaxwell.diaries.response.utilities.TriFunction;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

@Entity
@Table(name = "page", uniqueConstraints = { @UniqueConstraint(columnNames = { "diary_id", "name" }) })
@Data
@EqualsAndHashCode(callSuper = false)
@AllArgsConstructor
@RequiredArgsConstructor
@NoArgsConstructor
public class Page extends Publishable {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@NonNull
	@ManyToOne
	@JoinColumn(name = "diary_id")
	private Diary diary;

	@NonNull
	private String name;

	@NonNull
	@Column(precision = 10, scale = 4)
	private BigDecimal sequence;

	@NonNull
	private String extension;

	@NonNull
	private Integer width;

	@NonNull
	private Integer height;

	public Page(Diary diary, PageDTO dto) {
		this.diary = diary;
		this.id = dto.getId();
		this.name = dto.getName();
		this.sequence = dto.getSequence();
		this.extension = dto.getExtension();
		this.width = dto.getWidth();
		this.height = dto.getHeight();
	}

	public void updateFrom(Page other) {
		this.diary = other.diary;
		this.name = other.name;
		this.sequence = other.sequence;
		this.extension = other.extension;
		this.width = other.width;
		this.height = other.height;
	}

	public PageDTO toDTO() {
		// @formatter:off
		return new PageDTO(
				this.id,
				this.diary.getId(),
				this.name,
				this.sequence,
				this.extension,
				this.width,
				this.height				
				);
		// @formatter:on
	}

	public void publish(ConcurrentHashMap<String, String> x) throws Exception {
		publishRaw(mapFn, x);
	}

	public void publish(MqttAsyncClient x) throws Exception {
		publishRaw(mqttFn, x);
	}

	public void removePublication(ConcurrentHashMap<String, String> x) throws Exception {
		removePublicationRaw(mapFn, x);
	}

	public void removePublication(MqttAsyncClient x) throws Exception {
		removePublicationRaw(mqttFn, x);
	}

	private <X> void publishRaw(TriFunction<X, String, String, Object> function, X x) throws Exception {

		Diary diary = this.getDiary();
		PageDTO dto = this.toDTO();
		String payload = dto.toJson();

		publishOne(function, x, payload, String.format("diaries/%d/%d", diary.getId(), this.getId()));
	}

	private <X> void removePublicationRaw(TriFunction<X, String, String, Object> function, X x) throws Exception {

		Diary diary = this.getDiary();
		String payload = "";

		publishOne(function, x, payload, String.format("diaries/%d/%d", diary.getId(), this.getId()));
	}
}
