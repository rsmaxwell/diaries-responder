package com.rsmaxwell.diaries.responder;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.HashMap;
import com.rsmaxwell.diaries.responder.dto.ImagePublishDTO;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import com.rsmaxwell.diaries.responder.config.Config;
import com.rsmaxwell.diaries.responder.config.DbConfig;
import com.rsmaxwell.diaries.responder.config.Jdbc;
import com.rsmaxwell.diaries.responder.config.User;
import com.rsmaxwell.diaries.responder.model.Image;
import com.rsmaxwell.diaries.responder.repositoryImpl.ImageRepositoryImpl;
import com.rsmaxwell.diaries.responder.utilities.DiaryContext;
import com.rsmaxwell.diaries.responder.utilities.GetEntityManager;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;
import jakarta.persistence.PersistenceException;

/** Uses the production factory and startup wiring with a dedicated restored database. */
@EnabledIfEnvironmentVariable(named = "DIARIES_IMAGE_WIRING_TEST_URL", matches = ".+")
class ImageWiringIntegrationTest {

	private static EntityManagerFactory factory;
	private static Config config;
	private static List<?> chronologyBefore;
	private EntityManager em;
	private DiaryContext context;

	@BeforeAll
	static void start() {
		String url = System.getenv("DIARIES_IMAGE_WIRING_TEST_URL");
		assertTrue(url.matches("jdbc:postgresql://127\\.0\\.0\\.1:[0-9]+/image_wiring_test"));
		URI uri = URI.create(url.substring(5));
		Jdbc jdbc = new Jdbc();
		jdbc.setDbms("postgresql");
		jdbc.setDriver("org.postgresql.Driver");
		User user = new User();
		user.setUsername("diaries");
		user.setPassword("");
		DbConfig db = new DbConfig();
		db.setJdbc(jdbc);
		db.setHost(uri.getHost());
		db.setPort(uri.getPort());
		db.setDatabase("image_wiring_test");
		db.setAdmin(user);
		db.setUsers(List.of(user));
		db.setAdditionalConnectionProperties(Map.of("hibernate.hbm2ddl.auto", "validate"));
		config = new Config();
		config.setDb(db);
		config.setRefreshPeriod("10s");
		config.setRefreshExpiration("60s");
		factory = GetEntityManager.adminFactory(db);
		chronologyBefore = chronology();
	}

	@BeforeEach
	void wire() throws Exception {
		em = factory.createEntityManager();
		context = Responder.createContext(config, factory, em);
		assertEquals(0, context.getImageRepository().count());
	}

	@AfterEach
	void cleanup() {
		if (em != null) {
			if (em.getTransaction().isActive()) { em.getTransaction().rollback(); }
			// Only this new fixture's Image rows: helper tests intentionally commit.
			em.getTransaction().begin();
			context.getImageRepository().deleteAll();
			em.getTransaction().commit();
			em.close();
		}
	}

	@AfterAll
	static void stop() {
		if (factory != null) {
			try { assertEquals(chronologyBefore, chronology()); }
			finally { factory.close(); }
		}
	}

	private static List<?> chronology() {
		try (EntityManager reader = factory.createEntityManager()) {
			return reader.createNativeQuery("""
					SELECT md5(coalesce(jsonb_agg(to_jsonb(t) ORDER BY id)::text,'[]')) FROM public.diary t
					UNION ALL SELECT md5(coalesce(jsonb_agg(to_jsonb(t) ORDER BY id)::text,'[]')) FROM public.page t
					UNION ALL SELECT md5(coalesce(jsonb_agg(to_jsonb(t) ORDER BY id)::text,'[]')) FROM public.fragment t
					UNION ALL SELECT md5(coalesce(jsonb_agg(to_jsonb(t) ORDER BY id)::text,'[]')) FROM public.marquee t
					""", String.class).getResultList();
		}
	}

	private Image candidate(String path) {
		return Image.builder().relativePath(path).mimeType("image/png").originalFilename("image.png")
				.width(5).height(6).checksum("a".repeat(64)).build();
	}

	@Test
	void actualFactoryAndResponderWiringRegisterAndExposeImage() {
		assertEquals(1, factory.getMetamodel().getEntities().stream().filter(e -> e.getJavaType() == Image.class).count());
		assertInstanceOf(ImageRepositoryImpl.class, context.getImageRepository());
		assertSame(factory, context.getEntityManagerFactory());
		assertSame(em, context.getEntityManager());
		assertNotNull(context.getDiaryRepository());
		assertNotNull(context.getPageRepository());
		assertNotNull(context.getPersonRepository());
		assertNotNull(context.getFragmentRepository());
		assertNotNull(context.getMarqueeRepository());
		assertEquals(10, context.getRefreshPeriod());
		assertEquals(60, context.getRefreshExpiration());
	}

	@Test
	void saveAndUpdateCommitBeforeReturningAndInflateIndependently() throws Exception {
		Image source = candidate("wiring/image.png");
		Image saved = context.saveImage(source);
		assertNull(source.getId());
		assertFalse(em.getTransaction().isActive());
		try (EntityManager reader = factory.createEntityManager()) {
			assertEquals(saved, reader.find(Image.class, saved.getId()));
		}
		saved.setCaption("Updated");
		saved.setVersion(1L);
		assertEquals(1, context.updateImage(saved));
		assertEquals(saved, context.inflateImage(saved.getId()));
		assertThrows(Exception.class, () -> context.inflateImage(Long.MAX_VALUE));
	}

	@Test
	void uniqueConflictRollsBackAndContextRemainsUsable() throws Exception {
		Image first = context.saveImage(candidate("wiring/image.png"));
		Image duplicate = candidate("WIRING/IMAGE.PNG");
		assertThrows(PersistenceException.class, () -> context.saveImage(duplicate));
		assertNull(duplicate.getId());
		assertFalse(em.getTransaction().isActive());
		assertEquals(1, context.getImageRepository().count());
		assertEquals(first, context.inflateImage(first.getId()));
		assertNotNull(context.saveImage(candidate("other.png")).getId());
	}

	@Test
	void activeTransactionRemainsOwnedByCaller() {
		em.getTransaction().begin();
		assertThrows(IllegalStateException.class, () -> context.saveImage(candidate("nested.png")));
		assertTrue(em.getTransaction().isActive());
		assertEquals(0, context.getImageRepository().count());
	}

	@Test
	void catalogueServicePublishesCommittedRowsAndUsesDatabasePathIdentity(@org.junit.jupiter.api.io.TempDir java.nio.file.Path root) throws Exception {
		var store = com.rsmaxwell.diaries.responder.utilities.ImageCatalogueService.jpaCatalogue(factory);
		var service = new com.rsmaxwell.diaries.responder.utilities.ImageCatalogueService(
				new com.rsmaxwell.diaries.responder.utilities.ImagePathPolicy(root),
				new com.rsmaxwell.diaries.responder.utilities.ImageMetadataInspector(), store, dto -> {
					try (EntityManager reader = factory.createEntityManager()) {
						assertEquals(dto.getChecksum(), reader.find(Image.class, dto.getId()).getChecksum());
					}
				});
		byte[] bytes;
		try (var in = getClass().getResourceAsStream("/image-inspection/sample.webp")) { bytes = in.readAllBytes(); }
		var staged = service.stage(new java.io.ByteArrayInputStream(bytes), "maps", "Caf\u00e9 50%_1.txt",
				"application/octet-stream", bytes.length, null);
		Image saved = service.complete(staged, false).orElseThrow();
		assertEquals("image/webp", saved.getMimeType());
		assertTrue(store.owns("MAPS/CAF\u00c9 50%_1.TXT"));
		assertFalse(store.owns("maps/Caf\u00e9 50ZZ1.txt"));
		var duplicate = candidate("MAPS/CAF\u00c9 50%_1.TXT");
		var failure = assertThrows(com.rsmaxwell.diaries.responder.utilities.ImageCatalogueService.WriteFailedException.class,
				() -> store.insert(duplicate));
		assertFalse(failure.outcomeUnknown());
		assertEquals(1, context.getImageRepository().count());
		assertArrayEquals(bytes, java.nio.file.Files.readAllBytes(staged.target()));
	}

	@Test
	void databaseReplayAddsUnreferencedImagesAndPreservesChronologyTopics() throws Exception {
		Map<String, String> before = context.loadFromDatabase();
		assertTrue(before.keySet().stream().noneMatch(t -> t.startsWith("diaries/images/")));
		Image first = context.saveImage(candidate("unreferenced.png"));
		Image second = context.saveImage(candidate("maps/Caf\u00e9 50%_1.png"));
		Map<String, String> expected = new HashMap<>(before);
		expected.put("diaries/images/" + first.getId(), new ImagePublishDTO(first).toJson());
		expected.put("diaries/images/" + second.getId(), new ImagePublishDTO(second).toJson());
		assertEquals(expected, context.loadFromDatabase());
		// A fresh startup context reads the same durable catalogue without any file access.
		try (EntityManager reader = factory.createEntityManager()) {
			assertEquals(expected, Responder.createContext(config, factory, reader).loadFromDatabase());
		}
	}
    @Test
    void uploadGuardUsesPostgresUnicodeIdentityWithoutChangingRows(@org.junit.jupiter.api.io.TempDir java.nio.file.Path root) throws Exception {
        var cfg=new Config();
        var files=new com.rsmaxwell.diaries.responder.config.DiariesConfig();
        files.setRoot(root.toString()); files.setFiles("files"); cfg.setDiaries(files);
        var uploadContext=new DiaryContext(); uploadContext.setConfig(cfg); uploadContext.setEntityManagerFactory(factory);
        uploadContext.setSecret(java.util.Base64.getEncoder().encodeToString("01234567890123456789012345678901".getBytes()));
        String token=com.rsmaxwell.diaries.responder.utilities.Authorization.getTokenWithClaims(uploadContext.getSecret(),"access",5,
            java.time.temporal.ChronoUnit.MINUTES,Map.of("status","ACTIVE","role","EDITOR"));
        var properties=List.of(new org.eclipse.paho.mqttv5.common.packet.UserProperty("accessToken",token));
        var directory=java.nio.file.Files.createDirectories(root.resolve("files/maps"));
        var target=directory.resolve("Caf\u00e9 50%_1.png"); java.nio.file.Files.writeString(target,"original");
        Image saved=context.saveImage(candidate("maps/Caf\u00e9 50%_1.png"));
        String before=new ImagePublishDTO(saved).toJson();
        var args=new HashMap<String,Object>();
        args.put("contentType","application/octet-stream"); args.put("bytes","bmV3"); args.put("size",3L);
        args.put("subdir","MAPS\\."); args.put("name","CAFE\u0301 50%_1.PNG");
        for(boolean overwrite:List.of(false,true)) {
            args.put("overwrite",overwrite);
            var failure=assertThrows(com.rsmaxwell.mqtt.rpc.exceptions.RpcStatusException.class,
                () -> new com.rsmaxwell.diaries.responder.handlers.UploadFile().handleRequest(uploadContext,args,properties));
            assertEquals(409,failure.getStatus().code());
            assertEquals("original",java.nio.file.Files.readString(target));
        }
        java.nio.file.Files.delete(target);
        assertThrows(com.rsmaxwell.mqtt.rpc.exceptions.RpcStatusException.class,
            () -> new com.rsmaxwell.diaries.responder.handlers.UploadFile().handleRequest(uploadContext,args,properties));
        assertFalse(java.nio.file.Files.exists(target));
        // SQL wildcard characters remain literal: this different path is not owned.
        args.put("subdir","maps"); args.put("name","Cafe 50ZZ1.png");
        new com.rsmaxwell.diaries.responder.handlers.UploadFile().handleRequest(uploadContext,args,properties);
        assertEquals("new",java.nio.file.Files.readString(directory.resolve("Cafe 50ZZ1.png")));
        assertEquals(1,context.getImageRepository().count());
        assertEquals(before,new ImagePublishDTO(context.inflateImage(saved.getId())).toJson());
    }

    @Test
    @EnabledIfEnvironmentVariable(named="DIARIES_IMAGE_MQTT_TEST_URL", matches=".+")
    void uploadCommitsPublishesAndReplaysAfterPublisherFailure(@org.junit.jupiter.api.io.TempDir java.nio.file.Path root) throws Exception {
        String broker=System.getenv("DIARIES_IMAGE_MQTT_TEST_URL");
        assertTrue(broker.matches("tcp://127\\.0\\.0\\.1:[0-9]+"));
        var cfg=new Config(); var files=new com.rsmaxwell.diaries.responder.config.DiariesConfig();
        files.setRoot(root.toString()); files.setFiles("files"); cfg.setDiaries(files);
        java.nio.file.Files.createDirectory(root.resolve("files"));
        var uploadContext=new DiaryContext(); uploadContext.setConfig(cfg); uploadContext.setEntityManagerFactory(factory);
        uploadContext.setSecret(java.util.Base64.getEncoder().encodeToString("01234567890123456789012345678901".getBytes()));
        String token=com.rsmaxwell.diaries.responder.utilities.Authorization.getTokenWithClaims(uploadContext.getSecret(),"access",5,
            java.time.temporal.ChronoUnit.MINUTES,Map.of("status","ACTIVE","role","EDITOR"));
        var properties=List.of(new org.eclipse.paho.mqttv5.common.packet.UserProperty("accessToken",token));
        var options=new org.eclipse.paho.mqttv5.client.MqttConnectionOptions();
        options.setUserName("diaries-responder"); options.setPassword("phase4-fixture".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        var publisher=new org.eclipse.paho.mqttv5.client.MqttAsyncClient(broker,"upload-pub-"+java.util.UUID.randomUUID(),new org.eclipse.paho.mqttv5.client.persist.MemoryPersistence());
        var observer=new org.eclipse.paho.mqttv5.client.MqttAsyncClient(broker,"upload-sub-"+java.util.UUID.randomUUID(),new org.eclipse.paho.mqttv5.client.persist.MemoryPersistence());
        try {
            publisher.connect(options).waitForCompletion(10000); uploadContext.setPublisherClient(publisher);
            byte[] bytes;
            try(var in=getClass().getResourceAsStream("/image-inspection/sample.webp")){bytes=in.readAllBytes();}
            var args=new HashMap<String,Object>(); args.put("name","image.txt"); args.put("contentType","application/octet-stream");
            args.put("bytes",java.util.Base64.getEncoder().encodeToString(bytes)); args.put("size",(long)bytes.length);
            var handler=new com.rsmaxwell.diaries.responder.handlers.UploadFile() {
                @Override protected com.rsmaxwell.diaries.responder.utilities.ImageCatalogueService.Publication uploadPublication(DiaryContext ctx) {
                    var real=super.uploadPublication(ctx);
                    return dto -> {
                        try(var reader=factory.createEntityManager()) { assertNotNull(reader.find(Image.class,dto.getId()),"row must be committed before MQTT"); }
                        real.publish(dto);
                    };
                }
            };
            var response=(com.rsmaxwell.diaries.responder.dto.UploadFileResponse)handler.handleRequest(uploadContext,args,properties).payload();
            assertEquals(1,context.getImageRepository().count()); assertEquals(response.imageId(),response.image().getId());
            assertEquals("image/webp",response.image().getMimeType());
            assertArrayEquals(bytes,java.nio.file.Files.readAllBytes(root.resolve("files/image.txt")));
            observer.connect(options).waitForCompletion(10000);
            var events=new java.util.concurrent.LinkedBlockingQueue<org.eclipse.paho.mqttv5.common.MqttMessage>();
            observer.setCallback(new com.rsmaxwell.mqtt.rpc.common.Adapter() {
                @Override public void messageArrived(String topic,org.eclipse.paho.mqttv5.common.MqttMessage message) { events.add(message); }
            });
            observer.subscribe(new org.eclipse.paho.mqttv5.common.MqttSubscription("diaries/images/"+response.imageId(),1)).waitForCompletion(10000);
            var retained=events.poll(5,java.util.concurrent.TimeUnit.SECONDS); assertNotNull(retained);
            assertTrue(retained.isRetained()); assertEquals(1,retained.getQos());
            assertEquals(response.image().toJson(),new String(retained.getPayload(),java.nio.charset.StandardCharsets.UTF_8));
            for(boolean overwrite:List.of(false,true)) {
                args.put("overwrite",overwrite);
                assertEquals(409,assertThrows(com.rsmaxwell.mqtt.rpc.exceptions.RpcStatusException.class,
                    ()->handler.handleRequest(uploadContext,args,properties)).getStatus().code());
            }
            var deletionFailure=assertThrows(com.rsmaxwell.mqtt.rpc.exceptions.RpcStatusException.class,
                ()->new com.rsmaxwell.diaries.responder.handlers.DeleteFile().handleRequest(uploadContext,Map.of("name","image.txt"),properties));
            assertEquals(409,deletionFailure.getStatus().code());
            assertArrayEquals(bytes,java.nio.file.Files.readAllBytes(root.resolve("files/image.txt")));
            assertNull(events.poll(250,java.util.concurrent.TimeUnit.MILLISECONDS)); assertEquals(1,context.getImageRepository().count());
            publisher.disconnect().waitForCompletion(10000);
            args.put("name","recover.webp");
            var failure=assertThrows(com.rsmaxwell.mqtt.rpc.exceptions.RpcStatusException.class,
                ()->handler.handleRequest(uploadContext,args,properties));
            assertEquals(500,failure.getStatus().code()); assertTrue(failure.getMessage().contains("committed"));
            assertEquals(2,context.getImageRepository().count());
            assertArrayEquals(bytes,java.nio.file.Files.readAllBytes(root.resolve("files/recover.webp")));
            Image recover=new Image(context.getImageRepository().findByRelativePath("recover.webp").orElseThrow());
            String topic="diaries/images/"+recover.getId();
            Map<String,String> replay=context.loadFromDatabase();
            assertEquals(new ImagePublishDTO(recover).toJson(),replay.get(topic));
            publisher.connect(options).waitForCompletion(10000);
            publisher.publish(topic,replay.get(topic).getBytes(java.nio.charset.StandardCharsets.UTF_8),1,true).waitForCompletion(10000);
            observer.subscribe(new org.eclipse.paho.mqttv5.common.MqttSubscription(topic,1)).waitForCompletion(10000);
            var repaired=events.poll(5,java.util.concurrent.TimeUnit.SECONDS); assertNotNull(repaired); assertTrue(repaired.isRetained());
            assertEquals(replay.get(topic),new String(repaired.getPayload(),java.nio.charset.StandardCharsets.UTF_8));
            // Remove only this test's retained fixtures before its database cleanup.
            for(Long id:List.of(response.imageId(),recover.getId())) publisher.publish("diaries/images/"+id,new byte[0],1,true).waitForCompletion(10000);
        } finally {
            if(observer.isConnected()) observer.disconnect().waitForCompletion(10000); observer.close();
            if(publisher.isConnected()) publisher.disconnect().waitForCompletion(10000); publisher.close();
        }
    }

    @Test
    void deleteGuardUsesLiteralPostgresPrefixesAndPreservesCatalogue(@org.junit.jupiter.api.io.TempDir java.nio.file.Path root) throws Exception {
        var cfg=new Config(); var files=new com.rsmaxwell.diaries.responder.config.DiariesConfig();
        files.setRoot(root.toString()); files.setFiles("files"); cfg.setDiaries(files);
        var deleteContext=new DiaryContext(); deleteContext.setConfig(cfg); deleteContext.setEntityManagerFactory(factory);
        deleteContext.setSecret(java.util.Base64.getEncoder().encodeToString("01234567890123456789012345678901".getBytes()));
        String token=com.rsmaxwell.diaries.responder.utilities.Authorization.getTokenWithClaims(deleteContext.getSecret(),"access",5,
            java.time.temporal.ChronoUnit.MINUTES,Map.of("status","ACTIVE","role","EDITOR"));
        var properties=List.of(new org.eclipse.paho.mqttv5.common.packet.UserProperty("accessToken",token));
        var directory=java.nio.file.Files.createDirectories(root.resolve("files/maps%_!"));
        var target=java.nio.file.Files.writeString(directory.resolve("Caf\u00e9.png"),"original");
        Image saved=context.saveImage(candidate("maps%_!/Caf\u00e9.png"));
        String before=new ImagePublishDTO(saved).toJson();
        var handler=new com.rsmaxwell.diaries.responder.handlers.DeleteFile();
        for(var args:List.of(Map.of("subdir","maps%_!","name","Caf\u00e9.png"),
                Map.of("subdir","MAPS%_!\\.","name","CAFE\u0301.PNG"),Map.of("name","maps%_!"),Map.of("name","MAPS%_!"))) {
            var failure=assertThrows(com.rsmaxwell.mqtt.rpc.exceptions.RpcStatusException.class,
                ()->handler.handleRequest(deleteContext,new HashMap<String,Object>(args),properties));
            assertEquals(409,failure.getStatus().code()); assertFalse(failure.getMessage().contains(root.toString()));
            assertEquals("original",java.nio.file.Files.readString(target));
        }
        java.nio.file.Files.delete(target);
        assertThrows(com.rsmaxwell.mqtt.rpc.exceptions.RpcStatusException.class,
            ()->handler.handleRequest(deleteContext,Map.of("name","maps%_!"),properties));
        java.nio.file.Files.delete(directory);
        assertThrows(com.rsmaxwell.mqtt.rpc.exceptions.RpcStatusException.class,
            ()->handler.handleRequest(deleteContext,Map.of("name","maps%_!"),properties));
        // SQL wildcard and escape characters are literal; boundary-similar names are unrelated.
        for(String name:List.of("mapsZZ!","maps%_!suffix")) {
            java.nio.file.Files.createDirectory(root.resolve("files").resolve(name));
            handler.handleRequest(deleteContext,Map.of("name",name),properties);
            assertFalse(java.nio.file.Files.exists(root.resolve("files").resolve(name)));
        }
        handler.handleRequest(deleteContext,Map.of("subdir","missing/nested","name","absent"),properties);
        assertFalse(java.nio.file.Files.exists(root.resolve("files/missing")));
        assertEquals(1,context.getImageRepository().count());
        assertEquals(before,new ImagePublishDTO(context.inflateImage(saved.getId())).toJson());
    }

}
