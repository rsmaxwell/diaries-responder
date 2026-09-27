package com.rsmaxwell.diaries.responder.utilities;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rsmaxwell.diaries.responder.dto.FragmentDBDTO;
import com.rsmaxwell.diaries.responder.dto.ImageDBDTO;
import com.rsmaxwell.diaries.responder.dto.ImagePublishDTO;
import com.rsmaxwell.diaries.responder.model.FragmentType;
import com.rsmaxwell.diaries.responder.repository.DiaryRepository;
import com.rsmaxwell.diaries.responder.repository.FragmentRepository;
import com.rsmaxwell.diaries.responder.repository.MarqueeRepository;
import com.rsmaxwell.diaries.responder.repository.ImageRepository;

class DiaryContextTest {

	private static final ObjectMapper MAPPER = new ObjectMapper();

	@Test
	void databaseReplayPublishesAFragmentWithoutDependingOnAMarquee() throws Exception {
		FragmentDBDTO fragment = FragmentDBDTO.builder()
				.id(33L)
				.version(2L)
				.pageId(null)
				.type(null)
				.year(1830)
				.month(3)
				.day(8)
				.sequence(BigDecimal.ONE)
				.text("Legacy candidate")
				.build();

		DiaryContext context = new DiaryContext();
		context.setImageRepository(proxy(ImageRepository.class, Map.of("findAll", List.of())));
		context.setDiaryRepository(proxy(DiaryRepository.class, Map.of("findAll", List.of())));
		context.setFragmentRepository(proxy(FragmentRepository.class, Map.of(
				"findAll", List.of(fragment),
				"findById", Optional.of(fragment))));
		context.setMarqueeRepository(proxy(MarqueeRepository.class, Map.of(
				"findByFragment", Optional.empty())));

		Map<String, String> retained = context.loadFromDatabase();

		assertEquals(2, retained.size());
		assertTrue(retained.containsKey("diaries/fragments/33"));
		assertTrue(retained.containsKey("diaries/dates/1830/3/8/33"));
		JsonNode payload = MAPPER.readTree(retained.get("diaries/fragments/33"));
		assertTrue(payload.get("pageId").isNull());
		assertTrue(payload.get("type").isNull());
	}

	@Test
	void imageCatalogueReplaysWithoutAnyChronologyOrFileConfiguration() throws Exception {
		ImageDBDTO first = ImageDBDTO.builder().id(91L).version(4L)
				.relativePath("maps/Caf\u00e9 50%_1.png").mimeType("image/png")
				.originalFilename("Caf\u00e9 50%_1.png").width(1200).height(800)
				.checksum("ab".repeat(32)).caption("Harbour").altText("Old map").build();
		ImageDBDTO second = ImageDBDTO.builder().id(12L).relativePath("a.jpg")
				.mimeType("image/jpeg").originalFilename("a.jpg").width(20).height(30)
				.checksum("cd".repeat(32)).build();
		DiaryContext context = new DiaryContext();
		context.setDiaryRepository(proxy(DiaryRepository.class, Map.of("findAll", List.of())));
		context.setFragmentRepository(proxy(FragmentRepository.class, Map.of("findAll", List.of())));
		context.setImageRepository(proxy(ImageRepository.class, Map.of("findAll", List.of(first, second))));
		Map<String, String> expected = Map.of("diaries/images/91", new ImagePublishDTO(first).toJson(),
				"diaries/images/12", new ImagePublishDTO(second).toJson());
		assertEquals(expected, context.loadFromDatabase());
		// Repository iteration order cannot change the canonical topic/payload set.
		context.setImageRepository(proxy(ImageRepository.class, Map.of("findAll", List.of(second, first))));
		assertEquals(expected, context.loadFromDatabase());
		context.setImageRepository(proxy(ImageRepository.class, Map.of("findAll", List.of())));
		assertEquals(Map.of(), context.loadFromDatabase());
	}

	@Test
	void replayPreservesAnImageFragmentWhoseOptionalImageIsMissing() throws Exception {
		var dto = FragmentDBDTO.builder().id(41L).version(0L).type(FragmentType.IMAGE)
				.imageId(91L).year(1830).month(3).day(8).sequence(BigDecimal.ONE).text("Repair me").build();
		DiaryContext context = new DiaryContext();
		context.setDiaryRepository(proxy(DiaryRepository.class, Map.of("findAll", List.of())));
		context.setFragmentRepository(proxy(FragmentRepository.class, Map.of("findAll", List.of(dto))));
		context.setImageRepository(proxy(ImageRepository.class, Map.of("findById", Optional.empty(), "findAll", List.of())));
		context.setMarqueeRepository(proxy(MarqueeRepository.class, Map.of("findByFragment", Optional.empty())));
		var state = context.resolveFragmentState(context.inflateFragment(dto));
		assertEquals(91L, state.getFragment().getImageId());
		org.junit.jupiter.api.Assertions.assertNull(state.getImage());
		org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, state::validateForWrite);
		assertTrue(context.loadFromDatabase().containsKey("diaries/fragments/41"));
	}

	@Test
	void replayPublishesImageFragmentsWithAndWithoutImageOnBothAliases() throws Exception {
		var image = ImageDBDTO.builder().id(91L).relativePath("image.png").mimeType("image/png")
				.originalFilename("image.png").width(20).height(30).checksum("ab".repeat(32)).build();
		for (Long imageId : new Long[] {91L, null}) {
			var dto = FragmentDBDTO.builder().id(42L).version(0L).type(FragmentType.IMAGE).imageId(imageId)
					.year(1830).month(3).day(8).sequence(BigDecimal.ONE).text("Image").build();
			DiaryContext context = new DiaryContext();
			context.setDiaryRepository(proxy(DiaryRepository.class, Map.of("findAll", List.of())));
			context.setFragmentRepository(proxy(FragmentRepository.class, Map.of("findAll", List.of(dto))));
			context.setImageRepository(proxy(ImageRepository.class, Map.of("findById", Optional.of(image), "findAll", List.of(image))));
			context.setMarqueeRepository(proxy(MarqueeRepository.class, Map.of("findByFragment", Optional.empty())));
			var retained = context.loadFromDatabase();
			String json = retained.get("diaries/fragments/42");
			assertEquals(json, retained.get("diaries/dates/1830/3/8/42"));
			var payload = MAPPER.readTree(json);
			assertTrue(payload.has("imageId"));
			if (imageId == null) assertTrue(payload.get("imageId").isNull());
			else assertEquals(91L, payload.get("imageId").longValue());
			assertTrue(payload.get("marqueeId").isNull());
			assertEquals("IMAGE", payload.get("type").textValue());
		}
	}

	@Test
	void writerRejectsCrossTypeAndMismatchedRelationships() {
		var page = new com.rsmaxwell.diaries.responder.model.Page();
		page.setId(10L);
		var fragment = com.rsmaxwell.diaries.responder.model.Fragment.builder().page(page).type(FragmentType.IMAGE)
				.year(1830).month(3).day(8).sequence(BigDecimal.ONE).text("Image").build();
		var marquee = com.rsmaxwell.diaries.responder.model.Marquee.builder().fragment(fragment).page(page)
				.x(0d).y(0d).width(40d).height(40d).build();
		var state = new ResolvedFragmentState(fragment, marquee);
		org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, state::validateForWrite);
		fragment.setType(FragmentType.MARQUEE);
		state.validateForWrite();
		fragment.setPersistedImageId(91L);
		org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, state::validateForWrite);
		fragment.setImage(null);
		var otherPage = new com.rsmaxwell.diaries.responder.model.Page();
		otherPage.setId(11L);
		marquee.setPage(otherPage);
		org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, state::validateForWrite);
		marquee.setPage(page);
		marquee.setFragment(new com.rsmaxwell.diaries.responder.model.Fragment());
		org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, state::validateForWrite);
		fragment.setType(null);
		org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, state::validateForWrite);
	}

	@SuppressWarnings("unchecked")
	private static <T> T proxy(Class<T> type, Map<String, Object> results) {
		return (T) Proxy.newProxyInstance(
				type.getClassLoader(),
				new Class<?>[] { type },
				(instance, method, args) -> {
					if (results.containsKey(method.getName())) {
						return results.get(method.getName());
					}
					if (method.getName().equals("toString")) {
						return type.getSimpleName() + " test proxy";
					}
					throw new UnsupportedOperationException(method.getName());
				});
	}
}
