package com.rsmaxwell.diaries.response.model;

import java.math.BigDecimal;

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
import lombok.NoArgsConstructor;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

@Entity
@Table(name = "fragment")
@Data
@AllArgsConstructor
@RequiredArgsConstructor
@NoArgsConstructor
public class Fragment {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "id", nullable = false, unique = true)
	private Long id;

	@NonNull
	@ManyToOne
	@JoinColumn(name = "page_id")
	private Page page;

	@NonNull
	private Integer x;

	@NonNull
	private Integer y;

	@NonNull
	private Integer width;

	@NonNull
	private Integer height;

	@NonNull
	@Column(precision = 10, scale = 4)
	private BigDecimal sequence;

	@NonNull
	@Column(length = 4096)
	private String text;

	// ✅ New constructor
	public Fragment(Page page, Integer x, Integer y, Integer width, Integer height, String text) {
		this.page = page;
		this.x = x;
		this.y = y;
		this.width = width;
		this.height = height;
		this.text = text;
		this.sequence = BigDecimal.ZERO;
	}
}
