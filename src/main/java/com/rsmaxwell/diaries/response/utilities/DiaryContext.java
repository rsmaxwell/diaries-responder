package com.rsmaxwell.diaries.response.utilities;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.eclipse.paho.mqttv5.client.MqttAsyncClient;

import com.rsmaxwell.diaries.common.config.DiariesConfig;
import com.rsmaxwell.diaries.response.dto.DiaryDTO;
import com.rsmaxwell.diaries.response.dto.FragmentDBDTO;
import com.rsmaxwell.diaries.response.dto.FragmentPublishDTO;
import com.rsmaxwell.diaries.response.dto.MarqueeDBDTO;
import com.rsmaxwell.diaries.response.dto.MarqueePublishDTO;
import com.rsmaxwell.diaries.response.dto.PageDTO;
import com.rsmaxwell.diaries.response.model.Diary;
import com.rsmaxwell.diaries.response.model.Fragment;
import com.rsmaxwell.diaries.response.model.Marquee;
import com.rsmaxwell.diaries.response.model.Page;
import com.rsmaxwell.diaries.response.repository.DiaryRepository;
import com.rsmaxwell.diaries.response.repository.FragmentRepository;
import com.rsmaxwell.diaries.response.repository.MarqueeRepository;
import com.rsmaxwell.diaries.response.repository.PageRepository;
import com.rsmaxwell.diaries.response.repository.PersonRepository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityTransaction;
import lombok.Data;

@Data
public class DiaryContext {

	private EntityManager entityManager;
	private DiaryRepository diaryRepository;
	private PageRepository pageRepository;
	private PersonRepository personRepository;
	private FragmentRepository fragmentRepository;
	private MarqueeRepository marqueeRepository;
	private Integer refreshPeriod;
	private Integer refreshExpiration;
	private String secret;
	private DiariesConfig diaries;
	private MqttAsyncClient publisherClient;

	public Map<String, String> loadFromDatabase() throws Exception {
		ConcurrentHashMap<String, String> map = new ConcurrentHashMap<>();

		// Publish the diaries and and their child pages, marquees and fragments
		Iterable<DiaryDTO> diaries = diaryRepository.findAll();
		for (DiaryDTO diaryDTO : diaries) {
			diaryDTO.publish(map);

			Iterable<PageDTO> pages = pageRepository.findAllByDiary(diaryDTO.getId());
			for (PageDTO pageDTO : pages) {
				pageDTO.publish(map);

				Iterable<MarqueeDBDTO> marquees = marqueeRepository.findAllByPage(pageDTO.getId());
				for (MarqueeDBDTO marqueeDBDTO : marquees) {
					Marquee marquee = inflateMarquee(marqueeDBDTO);
					MarqueePublishDTO marqueePublishDTO = new MarqueePublishDTO(marquee);
					marqueePublishDTO.publish(map, diaryDTO.getId());

					Fragment fragment = marquee.getFragment();
					FragmentPublishDTO fragmentPublishDTO = new FragmentPublishDTO(fragment);
					fragmentPublishDTO.publish(map);
				}
			}
		}

		// And also publish those fragments which do NOT have an associated marquee/page
		Iterable<FragmentDBDTO> fragments = fragmentRepository.findAllWithoutMarquee();
		for (FragmentDBDTO fragmentDTO : fragments) {
			Fragment fragment = inflateFragment(fragmentDTO);
			FragmentPublishDTO fragmentPublishDTO = new FragmentPublishDTO(fragment);
			fragmentPublishDTO.publish(map);
		}

		return map;
	}

	public Fragment save(Fragment fragment) throws Exception {

		EntityTransaction tx = entityManager.getTransaction();
		try {
			tx.begin();
			this.fragmentRepository.save(fragment); // this also updates fragment.id

			tx.commit();
			return fragment;

		} catch (Exception e) {
			tx.rollback();
			throw e;
		}
	}

	public Diary inflateDiary(Long diaryId) throws Exception {
		Optional<DiaryDTO> optionalDiaryDTO = diaryRepository.findById(diaryId);
		if (optionalDiaryDTO.isEmpty()) {
			throw new Exception("Diary not found: id: " + diaryId);
		}
		DiaryDTO diaryDTO = optionalDiaryDTO.get();
		return new Diary(diaryDTO);
	}

	public Page inflatePage(Long pageId) throws Exception {
		Optional<PageDTO> optionalPageDTO = pageRepository.findById(pageId);
		if (optionalPageDTO.isEmpty()) {
			throw new Exception("Page not found: id: " + pageId);
		}
		PageDTO pageDTO = optionalPageDTO.get();
		return inflatePage(pageDTO);
	}

	public Page inflatePage(PageDTO pageDTO) throws Exception {
		Diary diary = inflateDiary(pageDTO.getDiaryId());
		return new Page(diary, pageDTO);
	}

	public Fragment inflateFragment(Long fragmentId) throws Exception {
		Optional<FragmentDBDTO> optionalFragmentDTO = fragmentRepository.findById(fragmentId);
		if (optionalFragmentDTO.isEmpty()) {
			throw new Exception("Fragment not found: id: " + fragmentId);
		}
		FragmentDBDTO fragmentDTO = optionalFragmentDTO.get();
		return new Fragment(fragmentDTO, 0L);
	}

	public Fragment inflateFragment(FragmentDBDTO fragmentDTO) throws Exception {
		return new Fragment(fragmentDTO, 0L);
	}

	public Marquee inflateMarquee(Long marqueeId) throws Exception {
		Optional<MarqueeDBDTO> optionalMarqueeDTO = marqueeRepository.findById(marqueeId);
		if (optionalMarqueeDTO.isEmpty()) {
			throw new Exception("Marquee not found: id: " + marqueeId);
		}
		return inflateMarquee(optionalMarqueeDTO.get());
	}

	public Marquee inflateMarquee(MarqueeDBDTO marqueeDTO) throws Exception {

		Optional<FragmentDBDTO> optionalFragmentDTO = fragmentRepository.findById(marqueeDTO.getFragmentId());
		if (optionalFragmentDTO.isEmpty()) {
			throw new Exception("Fragment not found: id: " + marqueeDTO.getFragmentId());
		}
		FragmentDBDTO fragmentDTO = optionalFragmentDTO.get();

		Page page = inflatePage(marqueeDTO.getPageId());
		Fragment fragment = new Fragment(fragmentDTO, 0L);
		return new Marquee(page, fragment, marqueeDTO);
	}

	public Integer deleteFragment(Fragment fragment) {

		for (MarqueeDBDTO marqueeDTO : marqueeRepository.findAllByFragment(fragment)) {
			marqueeRepository.deleteById(marqueeDTO.getId());
		}

		return fragmentRepository.delete(fragment);
	}
}
