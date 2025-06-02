package com.rsmaxwell.diaries.response.model;

import java.math.BigDecimal;
import java.util.concurrent.ConcurrentHashMap;

import org.eclipse.paho.mqttv5.client.MqttAsyncClient;

import com.rsmaxwell.diaries.response.dto.FragmentDTO;
import com.rsmaxwell.diaries.response.utilities.TriFunction;

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

		Page page = this.getPage();
		Diary diary = page.getDiary();
		FragmentDTO dto = this.toDTO();
		String payload = dto.toJson();

		publishOne(function, x, payload, String.format("fragments/%d", dto.getId()));
		publishOne(function, x, payload, String.format("diaries/%d/%d/fragments/%d", diary.getId(), page.getId(), this.getId()));
		publishOne(function, x, payload, String.format("dates/%s/%s/%s/fragments/%s", this.getYear(), this.getMonth(), this.getDay(), this.getId()));
	}

	private <X> void removePublicationRaw(TriFunction<X, String, String, Object> function, X x) throws Exception {

		Page page = this.getPage();
		Diary diary = page.getDiary();
		FragmentDTO dto = this.toDTO();
		String payload = "";

		publishOne(function, x, payload, String.format("fragments/%d", dto.getId()));
		publishOne(function, x, payload, String.format("diaries/%d/%d/fragments/%d", diary.getId(), page.getId(), this.getId()));
		publishOne(function, x, payload, String.format("dates/%s/%s/%s/fragments/%s", this.getYear(), this.getMonth(), this.getDay(), this.getId()));
	}
}
