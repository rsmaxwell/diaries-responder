package com.rsmaxwell.diaries.response.model;

import java.math.BigDecimal;
import java.util.concurrent.ConcurrentHashMap;

import org.eclipse.paho.mqttv5.client.MqttAsyncClient;

import com.rsmaxwell.diaries.response.dto.FragmentDBDTO;
import com.rsmaxwell.diaries.response.dto.FragmentPublishDTO;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToOne;
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

	@OneToOne(mappedBy = "fragment", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY, optional = true)
	private Marquee marquee;

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

	public Fragment(Page page, FragmentDBDTO dto) {
		this.id = dto.getId();
		this.page = page;
		this.marquee = dto.getMarquee();
		this.year = dto.getYear();
		this.month = dto.getMonth();
		this.day = dto.getDay();
		this.sequence = dto.getSequence();
	}

	public FragmentDBDTO toDBDTO() {
		return new FragmentDBDTO(this.id, this.page.getId(), this.marquee, this.year, this.month, this.day, this.sequence, this.text);
	}

	public FragmentPublishDTO toPublishDTO() {
		Long marqueeId = null;
		if (this.marquee != null) {
			marqueeId = this.marquee.getId();
		}
		return new FragmentPublishDTO(this.id, this.page.getId(), marqueeId, this.year, this.month, this.day, this.sequence, this.text);
	}

	private String getTopic1() {
		return String.format("fragments/%d", id);
	}

	private String getTopic2() {
		return String.format("dates/%s/%s/%s/%s", year, month, day, id);
	}

	public void publish(ConcurrentHashMap<String, String> map) throws Exception {
		String payloadString = this.toPublishDTO().toJson();
		byte[] payload = payloadString.getBytes();
		publish(mapFn, map, payload, getTopic1());
		publish(mapFn, map, payload, getTopic2());
	}

	public void publish(MqttAsyncClient client) throws Exception {
		String payloadString = this.toPublishDTO().toJson();
		byte[] payload = payloadString.getBytes();
		publish(mqttFn, client, payload, getTopic1());
		publish(mqttFn, client, payload, getTopic2());
	}

	public void removePublication(ConcurrentHashMap<String, String> map) throws Exception {

		if (marquee != null) {
			marquee.removePublication(map);
		}

		byte[] payload = new byte[0];
		publish(mapFn, map, payload, getTopic1());
		publish(mapFn, map, payload, getTopic2());
	}

	public void removePublication(MqttAsyncClient client) throws Exception {

		if (marquee != null) {
			marquee.removePublication(client);
		}

		byte[] payload = new byte[0];
		publish(mqttFn, client, payload, getTopic1());
		publish(mqttFn, client, payload, getTopic2());
	}
}
