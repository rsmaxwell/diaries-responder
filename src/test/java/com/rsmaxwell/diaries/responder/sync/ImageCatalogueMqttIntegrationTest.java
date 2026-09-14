package com.rsmaxwell.diaries.responder.sync;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.eclipse.paho.mqttv5.client.MqttAsyncClient;
import org.eclipse.paho.mqttv5.client.MqttConnectionOptions;
import org.eclipse.paho.mqttv5.client.persist.MemoryPersistence;
import org.eclipse.paho.mqttv5.common.MqttMessage;
import org.eclipse.paho.mqttv5.common.MqttSubscription;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import com.rsmaxwell.diaries.responder.config.Config;
import com.rsmaxwell.diaries.responder.config.User;
import com.rsmaxwell.diaries.responder.dto.ImagePublishDTO;
import com.rsmaxwell.diaries.responder.model.Image;
import com.rsmaxwell.diaries.responder.utilities.DiaryContext;
import com.rsmaxwell.mqtt.rpc.common.Adapter;

/** Opt in only against a fresh, disposable broker: synchronisation owns diaries/#. */
@EnabledIfEnvironmentVariable(named = "DIARIES_IMAGE_MQTT_TEST_URL", matches = ".+")
class ImageCatalogueMqttIntegrationTest {

	private record Publication(String topic, String payload, int qos, boolean retained) {}

	@Test
	void startupReplaysUpdatesAndTombstonesCanonicalImagesForLateSubscribers() throws Exception {
		String url = System.getenv("DIARIES_IMAGE_MQTT_TEST_URL");
		assertTrue(url.matches("tcp://127\\.0\\.0\\.1:[0-9]+"));
		Config config = new Config();
		config.setNormaliseOnStartup(false);
		User user = new User();
		user.setUsername("diaries-responder");
		user.setPassword("phase4-fixture");
		Image image = Image.builder().id(41L).version(3L).relativePath("maps/Caf\u00e9 50%_1.png")
				.mimeType("image/png").originalFilename("Caf\u00e9 50%_1.png")
				.width(1200).height(800).checksum("ab".repeat(32)).caption("Harbour").altText("Old map").build();
		String imageTopic = "diaries/images/41";
		String fragmentTopic = "diaries/fragments/7";
		String fragment = "{\"id\":7,\"text\":\"unchanged\"}";
		Map<String, String> database = new HashMap<>(Map.of(fragmentTopic, fragment,
				imageTopic, new ImagePublishDTO(image).toJson()));
		DiaryContext context = new DiaryContext() {
			@Override public Map<String, String> loadFromDatabase() { return Map.copyOf(database); }
		};
		try (FixtureClient publisher = connect(url)) {
			// Exercise a stale row, a removed row and a legacy/noncanonical alias.
			publish(publisher, imageTopic, "stale");
			publish(publisher, "diaries/images/999", "orphan");
			publish(publisher, "diaries/pages/7/images/41", "alias");
			publish(publisher, fragmentTopic, fragment);
			new Synchronise().perform(config, context, url, user);
			assertEquals(database, retainedSnapshot(url));
			// No-op replay should not republish unchanged metadata or chronology.
			BlockingQueue<Publication> events = new LinkedBlockingQueue<>();
			try (FixtureClient observer = connect(url)) {
				observe(observer, events);
				awaitRetained(events, database.size());
				new Synchronise().perform(config, context, url, user);
				assertNull(events.poll(300, TimeUnit.MILLISECONDS));
				image.setCaption("Updated caf\u00e9");
				image.setVersion(4L);
				database.put(imageTopic, new ImagePublishDTO(image).toJson());
				new Synchronise().perform(config, context, url, user);
				Publication update = events.poll(5, TimeUnit.SECONDS);
				assertNotNull(update);
				assertEquals(imageTopic, update.topic());
				assertEquals(database.get(imageTopic), update.payload());
				assertEquals(1, update.qos());
				assertNull(events.poll(300, TimeUnit.MILLISECONDS));
				assertEquals(database, retainedSnapshot(url));
				database.remove(imageTopic);
				new Synchronise().perform(config, context, url, user);
				Publication deletion = events.poll(5, TimeUnit.SECONDS);
				assertNotNull(deletion);
				assertEquals(imageTopic, deletion.topic());
				assertEquals("", deletion.payload());
				assertEquals(1, deletion.qos());
				assertEquals(Map.of(fragmentTopic, fragment), retainedSnapshot(url));
				// The identity-only DTO helper must also remove the broker's retained copy.
				new ImagePublishDTO(image).publish(publisher);
				assertNotNull(events.poll(5, TimeUnit.SECONDS));
				ImagePublishDTO.builder().id(41L).build().remove(publisher);
				Publication tombstone = events.poll(5, TimeUnit.SECONDS);
				assertNotNull(tombstone);
				assertEquals("", tombstone.payload());
				assertEquals(Map.of(fragmentTopic, fragment), retainedSnapshot(url));
				observer.disconnect().waitForCompletion(5000);
			}
			publisher.disconnect().waitForCompletion(5000);
		}
	}

	private static final class FixtureClient extends MqttAsyncClient implements AutoCloseable {
		FixtureClient(String url) throws org.eclipse.paho.mqttv5.common.MqttException {
			super(url, "image-test-" + UUID.randomUUID(), new MemoryPersistence());
		}
		@Override public void close() throws org.eclipse.paho.mqttv5.common.MqttException {
			try { if (isConnected()) disconnect().waitForCompletion(5000); }
			finally { super.close(); }
		}
	}

	private static FixtureClient connect(String url) throws Exception {
		FixtureClient client = new FixtureClient(url);
		MqttConnectionOptions options = new MqttConnectionOptions();
		options.setCleanStart(true);
		options.setUserName("diaries-responder");
		options.setPassword("phase4-fixture".getBytes(StandardCharsets.UTF_8));
		client.connect(options).waitForCompletion(5000);
		return client;
	}

	private static void publish(MqttAsyncClient client, String topic, String payload) throws Exception {
		client.publish(topic, payload.getBytes(StandardCharsets.UTF_8), 1, true).waitForCompletion(5000);
	}

	private static void observe(MqttAsyncClient client, BlockingQueue<Publication> events) throws Exception {
		client.setCallback(new Adapter() {
			@Override public void messageArrived(String topic, MqttMessage message) {
				// Drain checkpoints are transient protocol traffic, never catalogue state.
				if (!message.isRetained() && topic.startsWith("diaries/diaries/_sync/")) return;
				events.add(new Publication(topic, new String(message.getPayload(), StandardCharsets.UTF_8),
						message.getQos(), message.isRetained()));
			}
		});
		client.subscribe(new MqttSubscription("diaries/#", 1)).waitForCompletion(5000);
	}

	private static Map<String, String> awaitRetained(BlockingQueue<Publication> events, int expected) throws Exception {
		Map<String, String> result = new HashMap<>();
		for (int i = 0; i < expected; i++) {
			Publication message = events.poll(5, TimeUnit.SECONDS);
			assertNotNull(message);
			assertEquals(1, message.qos());
			assertTrue(message.retained());
			assertNull(result.put(message.topic(), message.payload()));
		}
		return result;
	}

	private static Map<String, String> retainedSnapshot(String url) throws Exception {
		BlockingQueue<Publication> events = new LinkedBlockingQueue<>();
		try (FixtureClient client = connect(url)) {
			observe(client, events);
			// A fresh subscriber sees retained messages only; quiet interval detects extra aliases.
			Map<String, String> result = new HashMap<>();
			Publication message;
			while ((message = events.poll(1200, TimeUnit.MILLISECONDS)) != null) {
				assertTrue(message.retained());
				assertEquals(1, message.qos());
				assertNull(result.put(message.topic(), message.payload()));
			}
			client.disconnect().waitForCompletion(5000);
			return result;
		}
	}
}
