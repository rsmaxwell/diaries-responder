package com.rsmaxwell.diaries.response.utilities;

import java.util.Optional;

import org.eclipse.paho.mqttv5.client.MqttAsyncClient;

import com.rsmaxwell.diaries.common.config.DiariesConfig;
import com.rsmaxwell.diaries.response.dto.DiaryDTO;
import com.rsmaxwell.diaries.response.dto.FragmentDBDTO;
import com.rsmaxwell.diaries.response.dto.MarqueeDBDTO;
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

	public Fragment save(Fragment fragment) throws Exception {

		EntityTransaction tx = entityManager.getTransaction();
		try {
			tx.begin();
			this.fragmentRepository.save(fragment); // this also updates fragment.id

			Marquee marquee = fragment.getMarquee();
			if (marquee != null) {
				marqueeRepository.save(marquee); // this also updates marquee.id
			}

			tx.commit();
			return fragment;

		} catch (Exception e) {
			tx.rollback();
			throw e;
		}
	}

	public Optional<FragmentDBDTO> findFragmentWithMarqueeById(Long id) throws Exception {

		Optional<FragmentDBDTO> optionalFragment = fragmentRepository.findById(id);
		if (optionalFragment.isPresent()) {
			FragmentDBDTO fragmentDTO = optionalFragment.get();

			Optional<MarqueeDBDTO> optionalMarquee = marqueeRepository.findByFragmentId(fragmentDTO.getId());
			if (optionalMarquee.isPresent()) {
				MarqueeDBDTO marqueeDTO = optionalMarquee.get();
				Marquee marquee = inflateMarquee(marqueeDTO);
				fragmentDTO.setMarquee(marquee);
			}
		}
		return optionalFragment;
	}

	public Iterable<FragmentDBDTO> findAllFragmentsWithMarquees() throws Exception {

		Iterable<FragmentDBDTO> fragments = fragmentRepository.findAll();
		for (FragmentDBDTO fragmentDTO : fragments) {

			Optional<MarqueeDBDTO> optional = marqueeRepository.findByFragmentId(fragmentDTO.getId());
			if (optional.isPresent()) {
				MarqueeDBDTO marqueeDTO = optional.get();
				Marquee marquee = inflateMarquee(marqueeDTO);
				fragmentDTO.setMarquee(marquee);
			}
		}
		return fragments;
	}

	public Iterable<FragmentDBDTO> findFragmentsWithMarqueesByDate(Integer year, Integer month, Integer day) throws Exception {

		Iterable<FragmentDBDTO> fragments = fragmentRepository.findByDate(year, month, day);
		for (FragmentDBDTO fragmentDTO : fragments) {

			Optional<MarqueeDBDTO> optional = marqueeRepository.findByFragmentId(fragmentDTO.getId());
			if (optional.isPresent()) {
				MarqueeDBDTO marqueeDTO = optional.get();
				Marquee marquee = inflateMarquee(marqueeDTO);
				fragmentDTO.setMarquee(marquee);
			}
		}
		return fragments;
	}

	public Fragment toFragment(FragmentDBDTO fragmentDTO) throws Exception {
		Fragment fragment = new Fragment(fragmentDTO);
		return fragment;
	}

	public Page toPage(PageDTO pageDTO) throws Exception {

		Optional<DiaryDTO> optionalDiaryDTO = diaryRepository.findById(pageDTO.getDiaryId());
		if (optionalDiaryDTO.isEmpty()) {
			throw new Exception("Diary not found: id: " + pageDTO.getDiaryId());
		}
		DiaryDTO diaryDTO = optionalDiaryDTO.get();

		Diary diary = new Diary(diaryDTO);
		Page page = new Page(diary, pageDTO);

		return page;
	}

	public void deleteFragment(Fragment fragment) {

		EntityTransaction tx = entityManager.getTransaction();
		try {
			tx.begin();
			Marquee marquee = fragment.getMarquee();
			if (marquee != null) {
				marqueeRepository.delete(marquee);
			}

			fragmentRepository.delete(fragment);
			tx.commit();
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
		Optional<FragmentDBDTO> optionalFragmentDTO = findFragmentWithMarqueeById(fragmentId);
		if (optionalFragmentDTO.isEmpty()) {
			throw new Exception("Fragment not found: id: " + fragmentId);
		}
		return inflateFragment(optionalFragmentDTO.get());
	}

	public Fragment inflateFragment(FragmentDBDTO fragmentDTO) throws Exception {
		return new Fragment(fragmentDTO);
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
		Fragment fragment = new Fragment(fragmentDTO);
		Marquee marquee = new Marquee(page, fragment, marqueeDTO);

		fragment.setMarquee(marquee);

		return marquee;
	}
}
