package com.rsmaxwell.diaries.responder.repositoryImpl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

import com.rsmaxwell.diaries.responder.dto.FragmentDBDTO;
import com.rsmaxwell.diaries.responder.dto.FragmentPublishDTO;
import com.rsmaxwell.diaries.responder.model.Image;
import com.rsmaxwell.diaries.responder.model.Fragment;
import com.rsmaxwell.diaries.responder.model.FragmentType;

class FragmentRepositoryImplTest {

	private final FragmentRepositoryImpl repository = new FragmentRepositoryImpl(null);

	@Test
	void positionalMappingIncludesPageTypeAndImageBeforeLockColumns() {
		Object[] row = {
				12L, 3L, new BigDecimal("4.0000"), 1830, 3, 8, "Text",
				85L, "IMAGE", 91L, 42L, "alice", "Ali", 123456L, "session"
		};

		FragmentDBDTO dto = repository.newDTO(row);

		assertEquals(85L, dto.getPageId());
		assertEquals(91L, dto.getImageId());
		Fragment fragment = new Fragment(dto);
		assertEquals(91L, fragment.getImageId());
		assertEquals(repository.getFields().size(), repository.getValues(fragment).size());
		assertEquals(91L, repository.getValues(fragment).get(repository.getFields().indexOf("image_id")));
		Fragment copy = new Fragment(new FragmentPublishDTO(fragment, null));
		assertEquals(fragment, copy);
		fragment.setImage(Image.builder().id(92L).build());
		assertEquals(92L, fragment.getImageId());
		fragment.setImage(null);
		assertNull(fragment.getImageId());
		assertEquals(FragmentType.IMAGE, dto.getType());
		assertEquals(42L, dto.getLock().lockUserId());
		assertEquals("session", dto.getLock().lockSessionId());
		assertEquals("alice", dto.getLock().lockUserName());
		assertEquals("Ali", dto.getLock().lockKnownAs());
		assertEquals(123456L, dto.getLock().lockTimeStamp());
	}

	@Test
	void migrationNullsRemainNullAndRoundTripThroughRepositoryValues() {
		Object[] row = {
				13L, 0L, BigDecimal.ONE, 1830, 3, 9, "Candidate",
				null, null, null, null, null, null, null, null
		};

		FragmentDBDTO dto = repository.newDTO(row);
		Fragment fragment = new Fragment(dto);

		assertNull(dto.getImageId());
		assertNull(fragment.getImageId());
		assertNull(dto.getPageId());
		assertNull(dto.getType());
		assertNull(fragment.getPageId());
		assertEquals(repository.getFields().size(), repository.getValues(fragment).size());
	}
	@Test
	void typedFragmentsWithoutImageRemainUnreferenced() throws Exception {
		for (FragmentType type : FragmentType.values()) {
			Fragment fragment = new Fragment(FragmentDBDTO.builder().id(14L).version(0L)
					.year(1830).month(3).day(9).sequence(BigDecimal.ONE).text("Text")
					.pageId(85L).type(type).build());
			FragmentPublishDTO publish = new FragmentPublishDTO(fragment, null);
			assertEquals(fragment, new Fragment(publish));
			assertNull(fragment.getImageId());
			assertNull(repository.getValues(fragment).get(repository.getFields().indexOf("image_id")));
			// The retained contract exposes an explicit null Image reference.
			org.junit.jupiter.api.Assertions.assertTrue(new com.fasterxml.jackson.databind.ObjectMapper()
					.readTree(publish.toJson()).get("imageId").isNull());
		}
	}

}
