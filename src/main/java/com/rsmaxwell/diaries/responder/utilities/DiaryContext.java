package com.rsmaxwell.diaries.responder.utilities;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.eclipse.paho.mqttv5.client.MqttAsyncClient;

import com.rsmaxwell.diaries.responder.config.Config;
import com.rsmaxwell.diaries.responder.config.DiariesConfig;
import com.rsmaxwell.diaries.responder.dto.DiaryDTO;
import com.rsmaxwell.diaries.responder.dto.FragmentDBDTO;
import com.rsmaxwell.diaries.responder.dto.FragmentPublishDTO;
import com.rsmaxwell.diaries.responder.dto.ImageDBDTO;
import com.rsmaxwell.diaries.responder.dto.ImagePublishDTO;
import com.rsmaxwell.diaries.responder.dto.MarqueeDBDTO;
import com.rsmaxwell.diaries.responder.dto.MarqueePublishDTO;
import com.rsmaxwell.diaries.responder.dto.PageDTO;
import com.rsmaxwell.diaries.responder.model.Diary;
import com.rsmaxwell.diaries.responder.model.Fragment;
import com.rsmaxwell.diaries.responder.model.Image;
import com.rsmaxwell.diaries.responder.model.Marquee;
import com.rsmaxwell.diaries.responder.model.Page;
import com.rsmaxwell.diaries.responder.repository.DiaryRepository;
import com.rsmaxwell.diaries.responder.repository.FragmentRepository;
import com.rsmaxwell.diaries.responder.repository.ImageRepository;
import com.rsmaxwell.diaries.responder.repository.MarqueeRepository;
import com.rsmaxwell.diaries.responder.repository.PageRepository;
import com.rsmaxwell.diaries.responder.repository.PersonRepository;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.EntityTransaction;
import jakarta.persistence.LockModeType;
import com.rsmaxwell.diaries.responder.model.FragmentType;
import org.slf4j.LoggerFactory;
import lombok.Data;

@Data
public class DiaryContext {

	private EntityManagerFactory entityManagerFactory;
	private EntityManager entityManager;
	private DiaryRepository diaryRepository;
	private PageRepository pageRepository;
	private PersonRepository personRepository;
	private FragmentRepository fragmentRepository;
	private MarqueeRepository marqueeRepository;
	private ImageRepository imageRepository;
	private Integer refreshPeriod;
	private Integer refreshExpiration;
	private String secret;
	private DiariesConfig diaries;
	private MqttAsyncClient publisherClient;
	private Config config;

	public Map<String, String> loadFromDatabase() throws Exception {
		ConcurrentHashMap<String, String> map = new ConcurrentHashMap<>();

		// Publish diaries, pages and marquees through their existing hierarchy.
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

				}
			}
		}

		// Fragments are authoritative rows in their own right. Publish all of them
		// independently of whether a Marquee currently exists.
		Iterable<FragmentDBDTO> fragments = fragmentRepository.findAll();
		for (FragmentDBDTO fragmentDTO : fragments) {
			Fragment fragment = inflateFragment(fragmentDTO);

			ResolvedFragmentState state = resolveFragmentState(fragment);
			FragmentPublishDTO fragmentPublishDTO = new FragmentPublishDTO(state.getFragment(), state.getMarquee());
			fragmentPublishDTO.publish(map);
		}

		// Catalogue rows have no chronology ownership and may have no references.
		for (ImageDBDTO imageDTO : imageRepository.findAll()) {
			new ImagePublishDTO(imageDTO).publish(map);
		}

		return map;
	}

	/**
	 * Low-level MARQUEE persistence helper retained for integration fixtures and
	 * controlled seeding. Live RPC creation must use
	 * {@link #saveMarqueeFragmentAndNormalise(Fragment, Marquee)} so chronology is
	 * closed in the same operation.
	 */
	public ResolvedFragmentState saveMarqueeFragment(Fragment fragment, Marquee marquee) throws Exception {
		requireNewFragment(fragment, FragmentType.MARQUEE);
		if (marquee == null || marquee.getFragment() != fragment || marquee.getPage() != fragment.getPage())
			throw new IllegalArgumentException("Marquee must use the candidate Fragment and Page");
		new ResolvedFragmentState(fragment, marquee).validateForWrite();
		Fragment candidate = copyFragment(fragment);
		Marquee candidateMarquee = Marquee.builder().page(candidate.getPage()).fragment(candidate)
				.x(marquee.getX()).y(marquee.getY()).width(marquee.getWidth()).height(marquee.getHeight())
				.version(marquee.getVersion()).build();
		return inOwnedTransaction(() -> {
			fragmentRepository.save(candidate);
			marqueeRepository.save(candidateMarquee);
			return new ResolvedFragmentState(candidate, candidateMarquee);
		});
	}

	/**
	 * Create a MARQUEE Fragment and its Marquee, then normalise the affected date
	 * before committing. This is the handler-facing creation path; the returned
	 * state is reloaded after commit so its sequence/version are authoritative.
	 */
	public FragmentCreationResult saveMarqueeFragmentAndNormalise(Fragment fragment, Marquee marquee) throws Exception {
		requireNewFragment(fragment, FragmentType.MARQUEE);
		if (marquee == null || marquee.getFragment() != fragment || marquee.getPage() != fragment.getPage())
			throw new IllegalArgumentException("Marquee must use the candidate Fragment and Page");
		new ResolvedFragmentState(fragment, marquee).validateForWrite();

		Fragment candidate = copyFragment(fragment);
		Marquee candidateMarquee = Marquee.builder().page(candidate.getPage()).fragment(candidate)
				.x(marquee.getX()).y(marquee.getY()).width(marquee.getWidth()).height(marquee.getHeight())
				.version(marquee.getVersion()).build();

		FragmentCreationResult persisted = inOwnedTransaction(() -> {
			fragmentRepository.save(candidate);
			marqueeRepository.save(candidateMarquee);
			var normalised = FragmentSequenceNormaliser.normaliseDate(
					fragmentRepository, candidate.getYear(), candidate.getMonth(), candidate.getDay());
			return new FragmentCreationResult(new ResolvedFragmentState(candidate, candidateMarquee), normalised);
		});

		ResolvedFragmentState committed = resolveFragmentState(inflateFragment(persisted.getFragment().getId()));
		return new FragmentCreationResult(committed, persisted.getNormalisedFragments());
	}

	/**
	 * Create an IMAGE Fragment, then normalise the affected date before committing.
	 * No Marquee is synthesised.
	 */
	public FragmentCreationResult saveImageFragmentAndNormalise(Fragment fragment) throws Exception {
		ImageFragmentWritePolicy.requireEnabled(this);
		requireNewFragment(fragment, FragmentType.IMAGE);
		Fragment candidate = copyFragment(fragment);

		FragmentCreationResult persisted = inOwnedTransaction(() -> {
			candidate.setImage(lockImageForFragmentWrite(candidate.getImageId()));
			ResolvedFragmentState state = new ResolvedFragmentState(candidate, null);
			state.validateForWrite();
			fragmentRepository.save(candidate);
			var normalised = FragmentSequenceNormaliser.normaliseDate(
					fragmentRepository, candidate.getYear(), candidate.getMonth(), candidate.getDay());
			return new FragmentCreationResult(state, normalised);
		});

		ResolvedFragmentState committed = resolveFragmentState(inflateFragment(persisted.getFragment().getId()));
		return new FragmentCreationResult(committed, persisted.getNormalisedFragments());
	}

	/**
	 * Low-level IMAGE persistence helper retained for integration fixtures and
	 * controlled seeding. Live RPC creation must use
	 * {@link #saveImageFragmentAndNormalise(Fragment)} so chronology is closed in
	 * the same operation.
	 */
	public ResolvedFragmentState saveImageFragment(Fragment fragment) throws Exception {
        ImageFragmentWritePolicy.requireEnabled(this);
		requireNewFragment(fragment, FragmentType.IMAGE);
		Fragment candidate = copyFragment(fragment);
		return inOwnedTransaction(() -> {
			candidate.setImage(lockImageForFragmentWrite(candidate.getImageId()));
			ResolvedFragmentState state = new ResolvedFragmentState(candidate, null);
			state.validateForWrite();
			fragmentRepository.save(candidate);
			return state;
		});
	}

	private static void requireNewFragment(Fragment fragment, FragmentType type) {
		if (fragment == null || fragment.getType() != type)
			throw new IllegalArgumentException("Expected " + type + " Fragment");
		if (fragment.getId() != null && fragment.getId() != 0)
			throw new IllegalArgumentException("Creation requires a new Fragment");
		if (fragment.getPage() == null || fragment.getPageId() == null || fragment.getPageId() <= 0)
			throw new IllegalArgumentException("Fragment requires an existing Page");
	}

	private static Fragment copyFragment(Fragment fragment) {
		Fragment copy = new Fragment(new FragmentPublishDTO(fragment, null));
		copy.setPage(fragment.getPage());
		return copy;
	}

	/** Must be called inside the transaction attaching the reference. */
	public Image lockImageForFragmentWrite(Long imageId) {
		if (!entityManager.getTransaction().isActive())
			throw new IllegalStateException("Image reference lookup requires an active transaction");
		if (imageId == null) return null;
		if (imageId <= 0) throw new IllegalArgumentException("Image id must be positive");
		Image image = entityManager.find(Image.class, imageId, LockModeType.PESSIMISTIC_READ);
		if (image == null) throw new IllegalArgumentException("Image not found: id: " + imageId);
		return image;
	}

	/** Tolerant resolution for readers/replay; writers call validateForWrite explicitly. */
	public ResolvedFragmentState resolveFragmentState(Fragment fragment) throws Exception {
		resolveImageForRead(fragment);
		Optional<MarqueeDBDTO> dto = marqueeRepository.findByFragment(fragment);
		Marquee marquee = dto.isEmpty() ? null
				: new Marquee(inflatePage(dto.get().getPageId()), fragment, dto.get());
		return new ResolvedFragmentState(fragment, marquee);
	}

	private void resolveImageForRead(Fragment fragment) {
		if (fragment.getImageId() == null) return;
		Optional<ImageDBDTO> image = imageRepository.findById(fragment.getImageId());
		if (image.isPresent()) fragment.setImage(inflateImage(image.get()));
		else {
			Long imageId = fragment.getImageId();
			fragment.setImage(null);
			fragment.setPersistedImageId(imageId);
			LoggerFactory.getLogger(DiaryContext.class).warn(
					"Fragment {} references missing Image {}; preserving unresolved id for repair",
					fragment.getId(), imageId);
		}
	}

	/** Image metadata has no Page/Diary/Fragment dependency. */
	public Image inflateImage(ImageDBDTO imageDTO) {
		return new Image(imageDTO);
	}

	public Image inflateImage(Long imageId) throws Exception {
		ImageDBDTO dto = imageRepository.findById(imageId)
				.orElseThrow(() -> new Exception("Image not found: id: " + imageId));
		return inflateImage(dto);
	}

	/** Returns a committed copy; the caller's candidate is unchanged even on rollback. */
	public Image saveImage(Image image) throws Exception {
		Image candidate = new Image(new ImageDBDTO(image));
		return inOwnedTransaction(() -> {
			imageRepository.save(candidate);
			return candidate;
		});
	}

	/** Persists the caller-supplied version, following the repository CRUD contract. */
	public int updateImage(Image image) throws Exception {
		Image candidate = new Image(new ImageDBDTO(image));
		return inOwnedTransaction(() -> imageRepository.update(candidate));
	}

	@FunctionalInterface
	private interface PersistenceTransaction<T> {
		T execute() throws Exception;
	}

	/** These helpers own a transaction; callers with a transaction use the repository directly. */
	private <T> T inOwnedTransaction(PersistenceTransaction<T> operation) throws Exception {
		EntityTransaction tx = entityManager.getTransaction();
		if (tx.isActive()) {
			throw new IllegalStateException("Persistence helper cannot join or commit an existing transaction");
		}
		// Begin outside the catch: a failed begin must not roll back a caller transaction.
		tx.begin();
		try {
			T result = operation.execute();
			tx.commit();
			return result;
		} catch (Exception | Error failure) {
			try {
				if (tx.isActive()) {
					tx.rollback();
				}
			} catch (Exception | Error rollbackFailure) {
				failure.addSuppressed(rollbackFailure);
			}
			throw failure;
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

	public Fragment inflateFragment(FragmentDBDTO fragmentDTO) throws Exception {
		Fragment fragment = new Fragment(fragmentDTO);
		if (fragmentDTO.getPageId() != null) {
			fragment.setPage(inflatePage(fragmentDTO.getPageId()));
		}
		resolveImageForRead(fragment);
		return fragment;
	}

	public Fragment inflateFragment(Long fragmentId) throws Exception {
		Optional<FragmentDBDTO> optionalFragmentDTO = fragmentRepository.findById(fragmentId);
		if (optionalFragmentDTO.isEmpty()) {
			throw new Exception("Fragment not found: id: " + fragmentId);
		}
		FragmentDBDTO fragmentDTO = optionalFragmentDTO.get();
		return inflateFragment(fragmentDTO);
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
		Fragment fragment = inflateFragment(fragmentDTO);
		return new Marquee(page, fragment, marqueeDTO);
	}

	public Integer deleteFragment(Fragment fragment) {
		return fragmentRepository.delete(fragment);
	}

	public void deleteMarquee(Marquee marquee) {
		marqueeRepository.delete(marquee);
	}
}
