package com.rsmaxwell.diaries.response.model;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import com.rsmaxwell.diaries.response.dto.PageDTO;

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

	@Override
	List<String> getTopics() {
		List<String> topics = new ArrayList<String>();
		topics.add(String.format("diaries/%d/%d", diary.getId(), this.getId()));
		topics.add(String.format("pages/%d", this.getId()));
		return topics;
	}

	@Override
	String getPayload() {
		return this.toDTO().toJson();
	}
}
