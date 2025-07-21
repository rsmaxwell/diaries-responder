package com.rsmaxwell.diaries.response.model;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
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

	private static final Logger log = LogManager.getLogger(Fragment.class);

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "id", nullable = false, unique = true)
	private Long id;

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

	@Column(name = "version", nullable = false)
	private long version;

	public Fragment(FragmentDBDTO dto) {
		this.id = dto.getId();
		this.marquee = dto.getMarquee();
		this.year = dto.getYear();
		this.month = dto.getMonth();
		this.day = dto.getDay();
		this.sequence = dto.getSequence();
		this.version = dto.getVersion();
		this.text = dto.getText();
	}

	public FragmentDBDTO toDBDTO() {
		return new FragmentDBDTO(this.id, this.marquee, this.year, this.month, this.day, this.sequence, this.version, this.text);
	}

	public FragmentPublishDTO toPublishDTO() {
		Long marqueeId = null;
		if (this.marquee != null) {
			marqueeId = this.marquee.getId();
		}
		return new FragmentPublishDTO(this.id, marqueeId, this.year, this.month, this.day, this.sequence, this.version, this.text);
	}

	@Override
	List<String> getTopics() {
		List<String> topics = new ArrayList<String>();
		topics.add(String.format("fragments/%d", id));
		topics.add(String.format("dates/%s/%s/%s/%s", year, month, day, id));
		return topics;
	}

	@Override
	String getPayload() {
		return this.toPublishDTO().toJson();
	}

	@Override
	void removeChildren(ConcurrentHashMap<String, String> map) throws Exception {
		if (marquee != null) {
			marquee.remove(map);
		}
	}

	@Override
	void removeChildren(MqttAsyncClient client) throws Exception {
		if (marquee != null) {
			marquee.remove(client);
		}
	}

	public boolean keyFieldsChanged(Fragment other) {
		if (this.id.longValue() != other.id.longValue()) {
			return true;
		}
		if (this.year.longValue() != other.year.longValue()) {
			return true;
		}
		if (this.month.longValue() != other.month.longValue()) {
			return true;
		}
		if (this.day.longValue() != other.day.longValue()) {
			return true;
		}

		return false;
	}
}
