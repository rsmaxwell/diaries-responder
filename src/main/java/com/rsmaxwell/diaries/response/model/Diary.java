package com.rsmaxwell.diaries.response.model;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

import org.eclipse.paho.mqttv5.client.MqttAsyncClient;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.rsmaxwell.diaries.response.dto.DiaryDTO;
import com.rsmaxwell.diaries.response.dto.Jsonable;
import com.rsmaxwell.diaries.response.dto.PageDTO;
import com.rsmaxwell.diaries.response.repository.PageRepository;
import com.rsmaxwell.diaries.response.utilities.DiaryContext;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

@Entity
@Table(name = "diary")
@Data
@EqualsAndHashCode(callSuper = false)
@AllArgsConstructor
@RequiredArgsConstructor
@NoArgsConstructor
public class Diary extends Publishable {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@NonNull
	private String name;

	@NonNull
	@Column(precision = 10, scale = 4)
	private BigDecimal sequence;

	@JsonIgnore
	public List<PageDTO> getPages(DiaryContext context) {

		PageRepository pageRepository = context.getPageRepository();
		List<PageDTO> pages = new ArrayList<PageDTO>();

		Iterable<PageDTO> all = pageRepository.findAllByDiary(this.id);
		for (PageDTO page : all) {
			pages.add(page);
		}

		return pages;
	}

	public Diary(String name) {
		this.id = 0L;
		this.name = name;
		this.sequence = new BigDecimal(1);
	}

	public Diary(DiaryDTO diaryDTO) {
		this.id = diaryDTO.getId();
		this.name = diaryDTO.getName();
		this.sequence = diaryDTO.getSequence();
	}

	public DiaryDTO toDTO() {
		return new DiaryDTO(id, name, sequence);
	}

	private String getTopic() {
		return String.format("diaries/%d", this.getId());
	}

	public void publish(ConcurrentHashMap<String, String> x) throws Exception {
		DiaryDTO dto = this.toDTO();
		Function<Jsonable, byte[]> payloadFn = dto.publishFn;
		publishOne(mapFn, x, payloadFn, dto, getTopic());
	}

	public void publish(MqttAsyncClient x) throws Exception {
		DiaryDTO dto = this.toDTO();
		Function<Jsonable, byte[]> payloadFn = dto.publishFn;
		publishOne(mqttFn, x, payloadFn, dto, getTopic());
	}

	public void removePublication(ConcurrentHashMap<String, String> x) throws Exception {
		DiaryDTO dto = this.toDTO();
		Function<Jsonable, byte[]> payloadFn = dto.removeFn;
		publishOne(mapFn, x, payloadFn, dto, getTopic());
	}

	public void removePublication(MqttAsyncClient x) throws Exception {
		DiaryDTO dto = this.toDTO();
		Function<Jsonable, byte[]> payloadFn = dto.removeFn;
		publishOne(mqttFn, x, payloadFn, dto, getTopic());
	}
}
