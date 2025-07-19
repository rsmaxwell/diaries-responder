package com.rsmaxwell.diaries.response.model;

import java.util.ArrayList;
import java.util.List;

import com.rsmaxwell.diaries.response.dto.MarqueeDBDTO;
import com.rsmaxwell.diaries.response.dto.MarqueePublishDTO;
import com.rsmaxwell.diaries.response.utilities.Rectangle;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.NonNull;

@Entity
@Table(name = "marquee")
@Data
@EqualsAndHashCode(callSuper = false)
@AllArgsConstructor
@NoArgsConstructor
public class Marquee extends Publishable {

	// private static final Logger log = LogManager.getLogger(Marquee.class);

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	@Column(name = "id", nullable = false, unique = true)
	private Long id;

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

	public MarqueeDBDTO toDBDTO() {
		return new MarqueeDBDTO(this.id, this.getPage().getId(), this.getFragment().getId(), this.x, this.y, this.width, this.height);
	}

	public MarqueePublishDTO toPublishDTO() {
		Rectangle rectangle = new Rectangle(this.x, this.y, this.width, this.height);
		return new MarqueePublishDTO(this.id, this.getPage().getId(), this.getFragment().getId(), rectangle);
	}

	public Marquee(Page page, Fragment fragment, MarqueeDBDTO dto) {
		this.id = dto.getId();
		this.page = page;
		this.fragment = fragment;
		this.x = dto.getX();
		this.y = dto.getY();
		this.width = dto.getWidth();
		this.height = dto.getHeight();
	}

	@Override
	List<String> getTopics() {
		Diary diary = page.getDiary();
		List<String> topics = new ArrayList<String>();
		topics.add(String.format("diaries/%d/%d/%d", diary.getId(), page.getId(), id));
		topics.add(String.format("marquees/%d", id));
		return topics;
	}

	@Override
	String getPayload() {
		return this.toPublishDTO().toJson();
	}
}
