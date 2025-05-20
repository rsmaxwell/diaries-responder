package com.rsmaxwell.diaries.response.model;

import java.math.BigDecimal;

import com.rsmaxwell.diaries.response.dto.FragmentDTO;

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
	private Integer year;

	@NonNull
	private Integer month;

	@NonNull
	private Integer day;

	@NonNull
	@Column(precision = 10, scale = 4)
	private BigDecimal sequence;

	@NonNull
	@ManyToOne
	@JoinColumn(name = "marquee_id")
	private Marquee marquee;

	@NonNull
	@Column(length = 4096)
	private String text;

	public Fragment(Marquee marquee, FragmentDTO dto) {
		this.id = dto.getId();
		this.marquee = marquee;
		this.text = dto.getText();
		this.sequence = dto.getSequence();
	}

	public FragmentDTO toDTO() {
		return new FragmentDTO(this.id, this.year, this.month, this.day, this.sequence, this.marquee.getId(), this.text);
	}
}
