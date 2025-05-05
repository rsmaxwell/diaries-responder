package com.rsmaxwell.diaries.response.model;

import com.rsmaxwell.diaries.response.dto.PageDTO;

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
@EqualsAndHashCode(exclude = { "id" })
@Data
@AllArgsConstructor
@RequiredArgsConstructor
@NoArgsConstructor
public class Page {

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
	private String extension;

	@NonNull
	private Integer width;

	@NonNull
	private Integer height;

	public Page(Diary diary, PageDTO dbPageDTO) {
		this.diary = diary;
		this.id = dbPageDTO.getId();
		this.name = dbPageDTO.getName();
		this.extension = dbPageDTO.getExtension();
		this.width = dbPageDTO.getWidth();
		this.height = dbPageDTO.getHeight();
	}

	public void updateFrom(Page other) {
		this.diary = other.diary;
		this.name = other.name;
		this.extension = other.extension;
		this.width = other.width;
		this.height = other.height;
	}
}
