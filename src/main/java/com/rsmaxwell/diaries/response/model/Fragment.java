package com.rsmaxwell.diaries.response.model;

import java.math.BigDecimal;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

import org.eclipse.paho.mqttv5.client.MqttAsyncClient;

import com.rsmaxwell.diaries.response.dto.FragmentDTO;
import com.rsmaxwell.diaries.response.dto.Jsonable;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

@Entity
@Table(name = "fragment")
@Data
@EqualsAndHashCode(callSuper = false)
@AllArgsConstructor
@RequiredArgsConstructor
@NoArgsConstructor
public class Fragment extends Publishable {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "id", nullable = false, unique = true)
	private Long id;

	@NonNull
	@ManyToOne
	@JoinColumn(name = "page_id")
	private Page page;

	@NonNull
	private Double x;

	@NonNull
	private Double y;

	@NonNull
	private Double width;

	@NonNull
	private Double height;

	@NonNull
	private Integer year;

	@NonNull
	private Integer month;

	@NonNull
	private Integer day;

	@NonNull
	@Column(precision = 10, scale = 4)
	private BigDecimal sequence;

	@NonNull
	@Column(length = 4096)
	private String text;

	public Fragment(Page page, FragmentDTO dto) {
		this.id = dto.getId();
		this.page = page;
		this.x = dto.getX();
		this.y = dto.getY();
		this.width = dto.getWidth();
		this.height = dto.getHeight();
		this.year = dto.getYear();
		this.month = dto.getMonth();
		this.day = dto.getDay();
		this.sequence = dto.getSequence();
	}

	public FragmentDTO toDTO() {
		return new FragmentDTO(this.id, this.page.getId(), this.x, this.y, this.width, this.height, this.year, this.month, this.day, this.sequence, this.text);
	}

	private String getTopic1() {
		return String.format("fragments/%d", getId());
	}

	private String getTopic2() {
		return String.format("diaries/%d/%d/%d", page.getDiary().getId(), page.getId(), getId());
	}

	private String getTopic3() {
		return String.format("dates/%s/%s/%s/%s", year, month, day, id);
	}

	public void publish(ConcurrentHashMap<String, String> x) throws Exception {
		FragmentDTO dto = this.toDTO();
		Function<Jsonable, byte[]> payloadFn = dto.publishFn;
		publishOne(mapFn, x, payloadFn, dto, getTopic1());
		publishOne(mapFn, x, payloadFn, dto, getTopic2());
		publishOne(mapFn, x, payloadFn, dto, getTopic3());
	}

	public void publish(MqttAsyncClient x) throws Exception {
		FragmentDTO dto = this.toDTO();
		Function<Jsonable, byte[]> payloadFn = dto.publishFn;
		publishOne(mqttFn, x, payloadFn, dto, getTopic1());
		publishOne(mqttFn, x, payloadFn, dto, getTopic2());
		publishOne(mqttFn, x, payloadFn, dto, getTopic3());
	}

	public void removePublication(ConcurrentHashMap<String, String> x) throws Exception {
		FragmentDTO dto = this.toDTO();
		Function<Jsonable, byte[]> payloadFn = dto.removeFn;
		publishOne(mapFn, x, payloadFn, dto, getTopic1());
		publishOne(mapFn, x, payloadFn, dto, getTopic2());
		publishOne(mapFn, x, payloadFn, dto, getTopic3());
	}

	public void removePublication(MqttAsyncClient x) throws Exception {
		FragmentDTO dto = this.toDTO();
		Function<Jsonable, byte[]> payloadFn = dto.removeFn;
		publishOne(mqttFn, x, payloadFn, dto, getTopic1());
		publishOne(mqttFn, x, payloadFn, dto, getTopic2());
		publishOne(mqttFn, x, payloadFn, dto, getTopic3());
	}
}
