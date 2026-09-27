package com.rsmaxwell.diaries.responder.dto;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.eclipse.paho.mqttv5.client.IMqttToken;
import org.eclipse.paho.mqttv5.client.MqttAsyncClient;
import org.eclipse.paho.mqttv5.client.persist.MemoryPersistence;
import org.junit.jupiter.api.Test;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rsmaxwell.diaries.responder.model.LockInfo;
import com.rsmaxwell.diaries.responder.model.FragmentType;
import com.rsmaxwell.diaries.responder.model.Fragment;
import com.rsmaxwell.diaries.responder.utilities.Rectangle;

class RetainedStateDtoContractTest {

	private static final ObjectMapper MAPPER = new ObjectMapper();

	@Test
	void diaryContractUsesItsCanonicalTopicAndPayloadShape() throws Exception {
		DiaryDTO dto = DiaryDTO.builder()
				.id(11L)
				.version(2L)
				.name("Family diary")
				.sequence(new BigDecimal("1.2500"))
				.build();
		ConcurrentHashMap<String, String> topics = new ConcurrentHashMap<>();

		dto.publish(topics);

		assertEquals(Set.of("diaries/diaries/11"), topics.keySet());
		JsonNode payload = onlyPayload(topics);
		assertFields(payload, "id", "version", "name", "sequence");
		assertEquals(11L, payload.get("id").longValue());
		assertEquals(2L, payload.get("version").longValue());
		assertEquals("Family diary", payload.get("name").textValue());
		assertEquals(0, new BigDecimal("1.2500")
				.compareTo(payload.get("sequence").decimalValue()));

		dto.remove(topics);
		assertTrue(topics.isEmpty());
	}

	@Test
	void pageContractPublishesTheSamePayloadToHierarchyAndLookupTopics() throws Exception {
		PageDTO dto = PageDTO.builder()
				.id(22L)
				.version(3L)
				.diaryId(11L)
				.name("page-001")
				.sequence(new BigDecimal("2.0000"))
				.extension("jpg")
				.width(1200)
				.height(800)
				.build();
		ConcurrentHashMap<String, String> topics = new ConcurrentHashMap<>();

		dto.publish(topics);

		assertEquals(Set.of(
				"diaries/diaries/11/22",
				"diaries/pages/22"), topics.keySet());
		assertSinglePayload(topics);
		JsonNode payload = onlyPayload(topics);
		assertFields(payload,
				"id", "version", "diaryId", "name", "sequence",
				"extension", "width", "height");
		assertEquals(11L, payload.get("diaryId").longValue());
		assertEquals("page-001", payload.get("name").textValue());
		assertEquals("jpg", payload.get("extension").textValue());
		assertEquals(1200, payload.get("width").intValue());
		assertEquals(800, payload.get("height").intValue());

		dto.remove(topics);
		assertTrue(topics.isEmpty());
	}

	@Test
	void fragmentContractIncludesDateAliasMarqueeIdAndLockOwner() throws Exception {
		LockInfo lock = LockInfo.locked(
				42L,
				"alice",
				"Ali",
				"session-1",
				Instant.ofEpochMilli(1_700_000_000_000L));
		FragmentPublishDTO dto = FragmentPublishDTO.builder()
				.id(33L)
				.version(4L)
				.pageId(22L)
				.type(FragmentType.MARQUEE)
				.year(2026)
				.month(9)
				.day(1)
				.sequence(new BigDecimal("3.5000"))
				.text("A diary entry")
				.marqueeId(44L)
				.lock(lock)
				.build();
		ConcurrentHashMap<String, String> topics = new ConcurrentHashMap<>();

		dto.publish(topics);

		assertEquals(Set.of(
				"diaries/fragments/33",
				"diaries/dates/2026/9/1/33"), topics.keySet());
		assertSinglePayload(topics);
		JsonNode payload = onlyPayload(topics);
		assertFields(payload,
				"id", "version", "pageId", "type", "year", "month", "day", "sequence",
				"text", "imageId", "marqueeId", "lock");
		assertEquals(22L, payload.get("pageId").longValue());
		assertEquals("MARQUEE", payload.get("type").textValue());
		assertTrue(payload.get("imageId").isNull());
		assertEquals(44L, payload.get("marqueeId").longValue());

		JsonNode lockPayload = payload.get("lock");
		assertFields(lockPayload,
				"lockUserId", "lockUserName", "lockKnownAs",
				"lockTimeStamp", "lockSessionId", "locked");
		assertEquals(42L, lockPayload.get("lockUserId").longValue());
		assertEquals("alice", lockPayload.get("lockUserName").textValue());
		assertEquals("Ali", lockPayload.get("lockKnownAs").textValue());
		assertEquals(1_700_000_000_000L, lockPayload.get("lockTimeStamp").longValue());
		assertEquals("session-1", lockPayload.get("lockSessionId").textValue());
		assertTrue(lockPayload.get("locked").booleanValue());

		dto.remove(topics);
		assertTrue(topics.isEmpty());
	}

	@Test
	void unlockedFragmentSerialisesAnExplicitNullLockAndMarquee() throws Exception {
		FragmentPublishDTO dto = FragmentPublishDTO.builder()
				.id(34L)
				.version(0L)
				.year(2026)
				.month(9)
				.day(2)
				.sequence(BigDecimal.ONE)
				.text("Unlocked")
				.marqueeId(null)
				.lock(null)
				.build();

		JsonNode payload = MAPPER.readTree(dto.toJson());

		assertTrue(payload.has("imageId"));
		assertTrue(payload.get("imageId").isNull());
		assertTrue(payload.has("lock"));
		assertTrue(payload.get("lock").isNull());
		assertTrue(payload.has("marqueeId"));
		assertTrue(payload.get("marqueeId").isNull());
		assertTrue(payload.has("pageId"));
		assertTrue(payload.get("pageId").isNull());
		assertTrue(payload.has("type"));
		assertTrue(payload.get("type").isNull());
	}

	@Test
	void fragmentConstructedFromPersistenceDtoPublishesExplicitOwnership() throws Exception {
		Fragment fragment = new Fragment(FragmentDBDTO.builder()
				.id(35L)
				.version(1L)
				.pageId(85L)
				.type(FragmentType.MARQUEE)
				.year(1830)
				.month(3)
				.day(8)
				.sequence(BigDecimal.ONE)
				.text("Persisted")
				.build());

		JsonNode payload = MAPPER.readTree(new FragmentPublishDTO(fragment, null).toJson());

		assertEquals(85L, payload.get("pageId").longValue());
		assertEquals("MARQUEE", payload.get("type").textValue());
		assertTrue(payload.get("imageId").isNull());
	}

	@Test
	void marqueeContractPublishesTheSamePayloadToHierarchyAndLookupTopics() throws Exception {
		MarqueePublishDTO dto = MarqueePublishDTO.builder()
				.id(44L)
				.version(5L)
				.pageId(22L)
				.fragmentId(33L)
				.rectangle(new Rectangle(10.5, 20.5, 300.0, 200.0))
				.build();
		ConcurrentHashMap<String, String> topics = new ConcurrentHashMap<>();

		dto.publish(topics, 11L);

		assertEquals(Set.of(
				"diaries/diaries/11/22/44",
				"diaries/marquees/44"), topics.keySet());
		assertSinglePayload(topics);
		JsonNode payload = onlyPayload(topics);
		assertFields(payload,
				"id", "version", "pageId", "fragmentId", "rectangle");
		assertEquals(22L, payload.get("pageId").longValue());
		assertEquals(33L, payload.get("fragmentId").longValue());

		JsonNode rectangle = payload.get("rectangle");
		assertFields(rectangle, "x", "y", "width", "height");
		assertEquals(10.5, rectangle.get("x").doubleValue());
		assertEquals(20.5, rectangle.get("y").doubleValue());
		assertEquals(300.0, rectangle.get("width").doubleValue());
		assertEquals(200.0, rectangle.get("height").doubleValue());

		dto.remove(topics, 11L);
		assertTrue(topics.isEmpty());
	}

	@Test
	void mqttPublicationUsesQosOneAndRetainsPayloadAndTombstone() throws Exception {
		RecordingMqttClient client = new RecordingMqttClient();
		Publisher publisher = new Publisher();

		try {
			publisher.publish(client, "diaries/fragments/33", "payload".getBytes(StandardCharsets.UTF_8));

			assertEquals("diaries/fragments/33", client.topic);
			assertArrayEquals("payload".getBytes(StandardCharsets.UTF_8), client.payload);
			assertEquals(1, client.qos);
			assertTrue(client.retained);

			publisher.publish(client, "diaries/fragments/33", new byte[0]);

			assertEquals(0, client.payload.length);
			assertEquals(1, client.qos);
			assertTrue(client.retained);
		} finally {
			client.close();
		}
	}

	@Test
	void imageFragmentsPublishBothAliasesAndTombstonesWithOptionalReference() throws Exception {
		for (Long imageId : new Long[] {91L, null}) {
			Fragment fragment = new Fragment(FragmentDBDTO.builder().id(34L).version(1L).pageId(22L)
					.type(FragmentType.IMAGE).imageId(imageId).year(1830).month(3).day(8)
					.sequence(BigDecimal.ONE).text("Image fragment").build());
			FragmentPublishDTO dto = new FragmentPublishDTO(fragment, null);
			var topics = new ConcurrentHashMap<String, String>();
			dto.publish(topics);
			assertEquals(Set.of("diaries/fragments/34", "diaries/dates/1830/3/8/34"), topics.keySet());
			assertSinglePayload(topics);
			JsonNode payload = onlyPayload(topics);
			assertFields(payload, "id", "version", "pageId", "type", "year", "month", "day", "sequence",
					"text", "imageId", "marqueeId", "lock");
			assertEquals("IMAGE", payload.get("type").textValue());
			assertTrue(payload.get("marqueeId").isNull());
			if (imageId == null) assertTrue(payload.get("imageId").isNull());
			else assertEquals(imageId.longValue(), payload.get("imageId").longValue());
			assertEquals(payload, MAPPER.readTree(dto.toJsonAsBytes()));
			RecordingMqttClient client = new RecordingMqttClient();
			try {
				dto.publish(client);
				assertEquals(topics.keySet(), client.messages.keySet());
				for (byte[] bytes : client.messages.values()) assertEquals(payload, MAPPER.readTree(bytes));
				dto.remove(client);
				assertEquals(topics.keySet(), client.messages.keySet());
				for (byte[] bytes : client.messages.values()) assertEquals(0, bytes.length);
			} finally {
				client.close();
			}
			dto.remove(topics);
			assertTrue(topics.isEmpty());
		}
	}

	private static JsonNode onlyPayload(ConcurrentHashMap<String, String> topics) throws Exception {
		return MAPPER.readTree(topics.values().iterator().next());
	}

	private static void assertSinglePayload(ConcurrentHashMap<String, String> topics) {
		assertEquals(1, new HashSet<>(topics.values()).size());
	}

	private static void assertFields(JsonNode node, String... expected) {
		Set<String> actual = new HashSet<>();
		node.fieldNames().forEachRemaining(actual::add);
		assertEquals(Set.of(expected), actual);
		assertFalse(node.isMissingNode());
	}

	private static final class RecordingMqttClient extends MqttAsyncClient {

		private final java.util.Map<String, byte[]> messages = new java.util.HashMap<>();
		private String topic;
		private byte[] payload;
		private int qos;
		private boolean retained;

		RecordingMqttClient() throws Exception {
			super("tcp://localhost:1883", "dto-contract-test", new MemoryPersistence());
		}

		@Override
		public IMqttToken publish(String topic, byte[] payload, int qos, boolean retained) {
			assertEquals(1, qos);
			assertTrue(retained);
			messages.put(topic, payload.clone());
			this.topic = topic;
			this.payload = payload;
			this.qos = qos;
			this.retained = retained;
			return null;
		}
	}
}
