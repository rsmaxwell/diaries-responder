package com.rsmaxwell.diaries.response.model;

import java.math.BigDecimal;

import com.rsmaxwell.diaries.response.dto.MarqueeDTO;

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
@Table(name = "marquee")
@Data
@AllArgsConstructor
@RequiredArgsConstructor
@NoArgsConstructor
public class Marquee {

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
	@Column(precision = 10, scale = 4)
	private BigDecimal sequence;

	public Marquee(Page page, MarqueeDTO dto) {
		this.id = dto.getId();
		this.page = page;
		this.x = dto.getY();
		this.y = dto.getY();
		this.width = dto.getWidth();
		this.height = dto.getHeight();
		this.sequence = dto.getSequence();
	}

	public MarqueeDTO toDTO() {
		return new MarqueeDTO(this.id, this.page.getId(), this.x, this.y, this.width, this.height, this.sequence);
	}
}
