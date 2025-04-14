package com.rsmaxwell.diaries.response.model;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rsmaxwell.diaries.response.dto.PageDTO;
import com.rsmaxwell.diaries.response.repository.PageRepository;
import com.rsmaxwell.diaries.response.utilities.DiaryContext;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;

@Entity
@Table(name = "diary")
@Data
@AllArgsConstructor
@RequiredArgsConstructor
@NoArgsConstructor
public class Diary {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@NonNull
	private String name;

	// Lombok-generated method to convert object to JSON
	@SneakyThrows
	public String toJson() {
		return new ObjectMapper().writeValueAsString(this);
	}

	// Lombok-generated method to convert object to JSON as bytes
	@SneakyThrows
	public byte[] toJsonAsBytes() {
		return new ObjectMapper().writeValueAsBytes(this);
	}

	@JsonIgnore
	public List<PageResponse> getPages(DiaryContext context) {

		PageRepository pageRepository = context.getPageRepository();
		List<PageResponse> pages = new ArrayList<PageResponse>();

		Iterable<PageDTO> all = pageRepository.findAllByDiaryId(this.id);
		for (PageDTO page : all) {
			PageResponse pageResponse = new PageResponse(page);
			pages.add(pageResponse);
		}

		return pages;
	}
}
