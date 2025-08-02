package com.rsmaxwell.diaries.response.model;

import com.rsmaxwell.diaries.response.dto.MarqueeDBDTO;

import jakarta.persistence.Entity;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.NonNull;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

@Entity
@Table(name = "marquee")
@Data
@EqualsAndHashCode(callSuper = true)
@ToString(callSuper = true)
@SuperBuilder
@NoArgsConstructor
@AllArgsConstructor
public class Marquee extends Base {

	@NonNull
	@ManyToOne
	@JoinColumn(name = "page_id")
	private Page page;

	@OneToOne(optional = false)
	@JoinColumn(name = "fragment_id", nullable = false, unique = true)
	private Fragment fragment;

	@NonNull
	private Double x;

	@NonNull
	private Double y;

	@NonNull
	private Double width;

	@NonNull
	private Double height;

	public Marquee(Page page, Fragment fragment, MarqueeDBDTO dto) {
		this.id = dto.getId();
		this.page = page;
		this.fragment = fragment;
		this.x = dto.getX();
		this.y = dto.getY();
		this.width = dto.getWidth();
		this.height = dto.getHeight();
		this.version = dto.getVersion();
	}
}
