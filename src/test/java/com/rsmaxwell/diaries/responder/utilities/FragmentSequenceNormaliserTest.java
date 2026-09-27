package com.rsmaxwell.diaries.responder.utilities;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.rsmaxwell.diaries.responder.dto.FragmentDBDTO;
import com.rsmaxwell.diaries.responder.model.Fragment;
import com.rsmaxwell.diaries.responder.model.FragmentType;
import com.rsmaxwell.diaries.responder.model.LockInfo;
import com.rsmaxwell.diaries.responder.repository.FragmentRepository;
import com.rsmaxwell.mqtt.rpc.exceptions.RpcStatusException;

class FragmentSequenceNormaliserTest {

	@Test
	void normalisesBothDatesAfterAFragmentMoves() throws Exception {
		RecordingFragmentRepository repository = new RecordingFragmentRepository(List.of(
				dto(1, 1830, 1, 31, "1.0000"),
				dto(2, 1830, 1, 31, "3.0000"),
				dto(3, 1830, 2, 1, "1.0000"),
				dto(4, 1830, 2, 1, "1.0000")));
		Fragment original = new Fragment(dto(3, 1830, 1, 31, "2.0000"));
		Fragment incoming = new Fragment(dto(3, 1830, 2, 1, "1.0000"));

		List<Fragment> updates = FragmentSequenceNormaliser.normaliseAffectedDates(
				repository,
				original,
				incoming);

		assertEquals(
				List.of(new DateKey(1830, 1, 31), new DateKey(1830, 2, 1)),
				repository.requestedDates);
		assertEquals(List.of(2L, 4L), updates.stream().map(Fragment::getId).toList());
		assertEquals(new BigDecimal("1.0000"), repository.get(1).getSequence());
		assertEquals(new BigDecimal("2.0000"), repository.get(2).getSequence());
		assertEquals(new BigDecimal("1.0000"), repository.get(3).getSequence());
		assertEquals(new BigDecimal("2.0000"), repository.get(4).getSequence());
		assertEquals(1L, repository.get(2).getVersion());
		assertEquals(1L, repository.get(4).getVersion());
	}

	@Test
	void normalisesTheCurrentDateAfterASequenceOnlyMove() throws Exception {
		RecordingFragmentRepository repository = new RecordingFragmentRepository(List.of(
				dto(1, 1830, 2, 1, "-999.0000"),
				dto(2, 1830, 2, 1, "1.0000"),
				dto(3, 1830, 2, 1, "2.0000")));
		Fragment original = new Fragment(dto(1, 1830, 2, 1, "3.0000"));
		Fragment incoming = new Fragment(dto(1, 1830, 2, 1, "-999.0000"));

		List<Fragment> updates = FragmentSequenceNormaliser.normaliseAffectedDates(
				repository,
				original,
				incoming);

		assertEquals(List.of(new DateKey(1830, 2, 1)), repository.requestedDates);
		assertEquals(List.of(1L, 2L, 3L), updates.stream().map(Fragment::getId).toList());
		assertEquals(new BigDecimal("1.0000"), repository.get(1).getSequence());
		assertEquals(new BigDecimal("2.0000"), repository.get(2).getSequence());
		assertEquals(new BigDecimal("3.0000"), repository.get(3).getSequence());
		assertEquals(1L, repository.get(1).getVersion());
		assertEquals(1L, repository.get(2).getVersion());
		assertEquals(1L, repository.get(3).getVersion());
	}

	@Test
	void doesNothingWhenNeitherDateNorSequenceChanged() throws Exception {
		RecordingFragmentRepository repository = new RecordingFragmentRepository(List.of(
				dto(1, 1830, 2, 1, "1.0000")));
		Fragment original = new Fragment(dto(1, 1830, 2, 1, "1.0"));
		Fragment incoming = new Fragment(dto(1, 1830, 2, 1, "1.0000"));

		List<Fragment> updates = FragmentSequenceNormaliser.normaliseAffectedDates(
				repository,
				original,
				incoming);

		assertTrue(updates.isEmpty());
		assertTrue(repository.requestedDates.isEmpty());
	}

	@Test
	void normalisationChangesOnlySequenceAndVersion() throws Exception {
		LockInfo lock = new LockInfo(
				42L,
				"alice",
				"Ali",
				1_700_000_000_000L,
				"session-1");
		FragmentDBDTO original = FragmentDBDTO.builder()
				.id(7L)
				.version(9L)
				.year(1830)
				.month(2)
				.day(1)
				.sequence(new BigDecimal("9.0000"))
				.text("unchanged text")
				.pageId(85L)
				.type(FragmentType.MARQUEE)
				.lock(lock)
				.build();
		RecordingFragmentRepository repository = new RecordingFragmentRepository(List.of(original));

		List<Fragment> updates = FragmentSequenceNormaliser.normaliseDate(
				repository,
				1830,
				2,
				1);

		FragmentDBDTO stored = repository.get(7L);
		assertEquals(1, updates.size());
		assertEquals(new BigDecimal("1.0000"), stored.getSequence());
		assertEquals(10L, stored.getVersion());
		assertEquals("unchanged text", stored.getText());
		assertEquals(1830, stored.getYear());
		assertEquals(2, stored.getMonth());
		assertEquals(1, stored.getDay());
		assertEquals(lock, stored.getLock());
		assertEquals(85L, stored.getPageId());
		assertEquals(FragmentType.MARQUEE, stored.getType());
	}

	@Test
	void rejectsAConcurrentVersionChange() {
		RecordingFragmentRepository repository = new RecordingFragmentRepository(List.of(
				dto(1, 1830, 2, 1, "2.0000")));
		repository.conflictingIds.add(1L);

		assertThrows(
				RpcStatusException.class,
				() -> FragmentSequenceNormaliser.normaliseDate(repository, 1830, 2, 1));
	}

	@Test
	void mixedTypesShareOneSequenceAndKeepImageReferences() throws Exception {
		for(int multiplier:new int[]{1,10}) {
			var rows=new ArrayList<FragmentDBDTO>();
			int[] order={4,2,1,3};
			for(int i=0;i<4;i++) {
				var row=dto(i+1,1830,2,1,Integer.toString(order[i]*multiplier));
				row.setType(i%2==0?FragmentType.MARQUEE:FragmentType.IMAGE);
				row.setImageId(i%2==0?null:91L);rows.add(row);
			}
			var repository=new RecordingFragmentRepository(rows);
			var updates=FragmentSequenceNormaliser.normaliseDate(repository,1830,2,1);
			assertEquals(List.of(3L,2L,4L,1L),java.util.stream.StreamSupport.stream(repository.findAllByDate(1830,2,1).spliterator(),false).map(FragmentDBDTO::getId).toList());
			for(int i=0;i<4;i++) {
				assertEquals(0,BigDecimal.valueOf(order[i]).compareTo(repository.get(i+1).getSequence()));
				assertEquals(i%2==0?null:91L,repository.get(i+1).getImageId());
			}
			for(var update:updates)assertEquals(repository.get(update.getId()).getImageId(),update.getImageId());
		}
	}

	@Test
	void marqueeGuardRejectsImagesAndPreservesLegacyCompatibility() throws Exception {
		var fragment=new Fragment(dto(1,1830,2,1,"1"));
		FragmentLocking.requireMarqueeCompatible(fragment);
		fragment.setType(FragmentType.MARQUEE);FragmentLocking.requireMarqueeCompatible(fragment);
		fragment.setPersistedImageId(91L);
		assertThrows(RpcStatusException.class,()->FragmentLocking.requireMarqueeCompatible(fragment));
		fragment.setImage(null);fragment.setType(FragmentType.IMAGE);
		assertThrows(RpcStatusException.class,()->FragmentLocking.requireMarqueeCompatible(fragment));
	}

	private static FragmentDBDTO dto(long id, int year, int month, int day, String sequence) {
		return FragmentDBDTO.builder()
				.id(id)
				.version(0L)
				.year(year)
				.month(month)
				.day(day)
				.sequence(new BigDecimal(sequence))
				.text("fragment " + id)
				.build();
	}

	private record DateKey(int year, int month, int day) {
	}

	private static final class RecordingFragmentRepository implements FragmentRepository {
		@Override public boolean existsByImageId(Long imageId) { throw new UnsupportedOperationException(); }
		private final Map<Long, FragmentDBDTO> fragments = new LinkedHashMap<>();
		private final List<DateKey> requestedDates = new ArrayList<>();
		private final List<Long> conflictingIds = new ArrayList<>();

		private RecordingFragmentRepository(List<FragmentDBDTO> initial) {
			initial.forEach(fragment -> fragments.put(fragment.getId(), fragment));
		}

		private FragmentDBDTO get(long id) {
			return fragments.get(id);
		}

		@Override
		public Iterable<FragmentDBDTO> findAllByDate(Integer year, Integer month, Integer day) {
			requestedDates.add(new DateKey(year, month, day));
			return fragments.values().stream()
					.filter(fragment -> fragment.getYear().equals(year))
					.filter(fragment -> fragment.getMonth().equals(month))
					.filter(fragment -> fragment.getDay().equals(day))
					.sorted(Comparator
							.comparing(FragmentDBDTO::getSequence, Comparator.nullsLast(BigDecimal::compareTo))
							.thenComparingLong(FragmentDBDTO::getId))
					.toList();
		}

		@Override
		public <S extends Fragment> int update(S fragment) {
			throw new AssertionError("Normalisation must not call the full fragment update");
		}

		@Override
		public int updateSequence(Long id, Long expectedVersion, BigDecimal sequence) {
			if (conflictingIds.contains(id)) {
				return 0;
			}

			FragmentDBDTO original = fragments.get(id);
			if (original == null || !original.getVersion().equals(expectedVersion)) {
				return 0;
			}

			fragments.put(id, FragmentDBDTO.builder()
					.id(original.getId())
					.version(original.getVersion() + 1)
					.year(original.getYear())
					.month(original.getMonth())
					.day(original.getDay())
					.sequence(sequence)
					.text(original.getText())
					.pageId(original.getPageId())
					.type(original.getType())
					.imageId(original.getImageId())
					.lock(original.getLock())
					.build());
			return 1;
		}

		@Override
		public Optional<FragmentDBDTO> findById(Long id) {
			return Optional.ofNullable(fragments.get(id));
		}

		@Override
		public Iterable<FragmentDBDTO> findAll() {
			return List.copyOf(fragments.values());
		}

		@Override
		public long count() {
			return fragments.size();
		}

		@Override
		public boolean existsById(Long id) {
			return fragments.containsKey(id);
		}

		@Override
		public Iterable<FragmentDBDTO> find(String where) {
			throw new UnsupportedOperationException();
		}

		@Override
		public Iterable<FragmentDBDTO> findAllWithoutMarquee() {
			throw new UnsupportedOperationException();
		}

		@Override
		public Iterable<FragmentDBDTO> findStaleLocks(Instant olderThan) {
			throw new UnsupportedOperationException();
		}

		@Override
		public Long getId(Object value) {
			throw new UnsupportedOperationException();
		}

		@Override
		public <S extends Fragment> Long save(S entity) {
			throw new UnsupportedOperationException();
		}

		@Override
		public int delete(Fragment entity) {
			throw new UnsupportedOperationException();
		}

		@Override
		public int deleteAll() {
			throw new UnsupportedOperationException();
		}

		@Override
		public int deleteById(Long id) {
			throw new UnsupportedOperationException();
		}
	}
}
