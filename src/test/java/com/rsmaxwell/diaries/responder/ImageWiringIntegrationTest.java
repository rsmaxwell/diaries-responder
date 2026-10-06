package com.rsmaxwell.diaries.responder;

import static org.junit.jupiter.api.Assertions.*;

import com.rsmaxwell.diaries.responder.model.Fragment;
import com.rsmaxwell.diaries.responder.model.FragmentType;
import com.rsmaxwell.diaries.responder.model.LockInfo;
import com.rsmaxwell.diaries.responder.repositoryImpl.FragmentRepositoryImpl;
import java.net.URI;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.stream.StreamSupport;
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
        config.setImageFragmentWritesEnabled(true);
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
    @EnabledIfEnvironmentVariable(named = "DIARIES_IMAGE_MQTT_TEST_URL", matches = ".+")
    void authoringGateRejectsMutationsButPreservesLifecycle(@org.junit.jupiter.api.io.TempDir java.nio.file.Path root) throws Exception {
        String broker=System.getenv("DIARIES_IMAGE_MQTT_TEST_URL");
        assertTrue(broker.matches("tcp://127\\.0\\.0\\.1:[0-9]+"));
        var page=context.inflatePage(context.getPageRepository().findAll().iterator().next().getId());
        Image first=context.saveImage(candidate("gate-first.png")),second=context.saveImage(candidate("gate-second.png"));
        java.nio.file.Files.createDirectory(root.resolve("files"));java.nio.file.Files.writeString(root.resolve("files/gate-first.png"),"preserve");
        var fc=new com.rsmaxwell.diaries.responder.config.DiariesConfig();fc.setRoot(root.toString());fc.setFiles("files");config.setDiaries(fc);
        var ids=new java.util.ArrayList<Long>();
        long originalCount=context.getFragmentRepository().count();
        try(var rpc=new Step12Rpc(broker,factory)) {
            var create=new HashMap<String,Object>(Map.of("pageId",page.getId(),"year",2095,"month",3,"day",13,"sequence",1,"text","Gate"));
            // Missing configuration value is the production default, including explicit-null wire requests.
            config.setImageFragmentWritesEnabled(null);
            rpc.call("addImageFragment",create,403);create.put("imageId",null);rpc.call("addImageFragment",create,403);
            create.put("imageId",first.getId());rpc.call("addImageFragment",create,403);
            assertEquals(originalCount,context.getFragmentRepository().count());
            assertTrue(step11RetainedSnapshot(broker,rpc.publisher,"diaries/images/"+first.getId(),"diaries/images/"+second.getId()).isEmpty());
            config.setImageFragmentWritesEnabled(true);
            Long selected=rpc.call("addImageFragment",create).get("id").longValue();ids.add(selected);
            create.put("imageId",null);create.put("sequence",2);
            Long empty=rpc.call("addImageFragment",create).get("id").longValue();ids.add(empty);
            new ImagePublishDTO(first).publish(rpc.publisher);
            String[] gateTopics={"diaries/fragments/"+selected,"diaries/fragments/"+empty,
                    "diaries/dates/2095/3/13/"+selected,"diaries/dates/2095/3/13/"+empty,"diaries/images/"+first.getId()};
            config.setImageFragmentWritesEnabled(false);
            for(Long id:ids) {
                rpc.call("lockFragment",Map.of("id",id));
                var before=context.getFragmentRepository().findById(id).orElseThrow();
                var topics=step11RetainedSnapshot(broker,rpc.publisher,gateTopics);
                for(Long reference:id.equals(selected)?new Long[]{second.getId(),null}:new Long[]{first.getId()}) {
                    var edit=step7Args(context.inflateFragment(id));edit.put("imageId",reference);edit.put("text","Must not change");
                    rpc.call("updateFragment",edit,403);
                    assertEquals(before,context.getFragmentRepository().findById(id).orElseThrow());
                    assertEquals(topics,step11RetainedSnapshot(broker,rpc.publisher,gateTopics));
                }
                var edit=step7Args(context.inflateFragment(id));edit.put("text","Allowed text edit");
                rpc.call("updateFragment",edit);
                assertEquals(before.getImageId(),context.getFragmentRepository().findById(id).orElseThrow().getImageId());
                rpc.call("lockFragment",Map.of("id",id));rpc.call("unlockFragment",Map.of("id",id));
            }
            rpc.call("normaliseFragments",Map.of("year",2095,"month",3,"day",13));step12AssertRetained(broker,rpc.publisher,ids);
            rpc.call("deleteImage",Map.of("name","gate-first.png"),409);
            try(var reader=factory.createEntityManager()) {
                var restarted=Responder.createContext(config,factory,reader);assertFalse(restarted.getConfig().isImageFragmentWritesEnabled());
                var replay=restarted.loadFromDatabase();
                var payload=new com.fasterxml.jackson.databind.ObjectMapper().readTree(replay.get("diaries/fragments/"+selected));
                assertEquals(first.getId().longValue(),payload.get("imageId").longValue());assertTrue(payload.get("marqueeId").isNull());
            }
            rpc.call("deleteFragment",Map.of("id",selected));
            assertFalse(context.getFragmentRepository().existsById(selected));assertTrue(context.getImageRepository().existsById(first.getId()));
            assertEquals("preserve",java.nio.file.Files.readString(root.resolve("files/gate-first.png")));
            rpc.call("deleteImage",Map.of("name","gate-first.png"));
            assertFalse(context.getImageRepository().existsById(first.getId()));assertFalse(java.nio.file.Files.exists(root.resolve("files/gate-first.png")));
        } finally {
            config.setImageFragmentWritesEnabled(true);
            if(em.getTransaction().isActive())em.getTransaction().rollback();
            em.getTransaction().begin();for(Long id:ids)context.getFragmentRepository().deleteById(id);em.getTransaction().commit();
        }
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "DIARIES_IMAGE_MQTT_TEST_URL", matches = ".+")
    void liveImageFragmentRpcAndRestartReplay(@org.junit.jupiter.api.io.TempDir java.nio.file.Path root) throws Exception {
        String broker=System.getenv("DIARIES_IMAGE_MQTT_TEST_URL");
        assertTrue(broker.matches("tcp://127\\.0\\.0\\.1:[0-9]+"));
        var page=context.inflatePage(context.getPageRepository().findAll().iterator().next().getId());
        var ids=new java.util.ArrayList<Long>();
        var fileConfig=new com.rsmaxwell.diaries.responder.config.DiariesConfig();fileConfig.setRoot(root.toString());fileConfig.setFiles("files");config.setDiaries(fileConfig);
        java.nio.file.Files.createDirectory(root.resolve("files"));
        Image first=context.saveImage(candidate("step12-first.png")),second=context.saveImage(candidate("step12-second.png"));
        java.nio.file.Files.writeString(root.resolve("files/step12-first.png"),"original first");
        java.nio.file.Files.writeString(root.resolve("files/step12-second.png"),"original second");
        Long marqueeId=null;
        try {
            Long target,survivor,marquee;
            try(var rpc=new Step12Rpc(broker,factory)) {
                new ImagePublishDTO(first).publish(rpc.publisher);new ImagePublishDTO(second).publish(rpc.publisher);
                var args=new HashMap<String,Object>(Map.of("pageId",page.getId(),"year",2094,"month",3,"day",12,"sequence",30,"text","Live RPC"));
                args.put("imageId",first.getId());
                target=rpc.call("addImageFragment",args).get("id").longValue();ids.add(target);
                assertEquals(first.getId(),context.getFragmentRepository().findById(target).orElseThrow().getImageId());
                assertTrue(context.getMarqueeRepository().findByFragmentId(target).isEmpty());
                step12AssertRetained(broker,rpc.publisher,ids);
                assertEquals(new ImagePublishDTO(first).toJson(),step11RetainedSnapshot(broker,rpc.publisher).get("diaries/images/"+first.getId()));
                // Each edit traverses the broker, registered dispatcher and committed database path.
                for(String operation:List.of("text","sequence","date","replace","clear","reattach")) {
                    assertEquals(target.longValue(),rpc.call("lockFragment",Map.of("id",target)).longValue());
                    var before=context.inflateFragment(target);assertTrue(before.getLock().isLockedBy(42L,"step12"));
                    var edit=step7Args(before);
                    switch(operation) {
                        case "text" -> edit.put("text","Edited live");
                        case "sequence" -> edit.put("sequence",7);
                        case "date" -> edit.put("day",13);
                        case "replace" -> edit.put("imageId",second.getId());
                        case "clear" -> edit.put("imageId",null);
                        case "reattach" -> edit.put("imageId",first.getId());
                    }
                    assertEquals(target.longValue(),rpc.call("updateFragment",edit).longValue());
                    var after=context.getFragmentRepository().findById(target).orElseThrow();
                    assertNull(after.getLock());assertTrue(after.getVersion()>before.getVersion());assertEquals("Edited live",after.getText());
                    if(operation.equals("date"))assertEquals(13,after.getDay());
                    Long expected=operation.equals("replace")?second.getId():operation.equals("clear")?null:first.getId();
                    assertEquals(expected,after.getImageId());
                    step12AssertRetained(broker,rpc.publisher,ids);
                    if(operation.equals("date"))assertFalse(step11RetainedSnapshot(broker,rpc.publisher).containsKey("diaries/dates/2094/3/12/"+target));
                }
                args.put("day",13);args.put("sequence",20);args.put("imageId",second.getId());
                survivor=rpc.call("addImageFragment",args).get("id").longValue();ids.add(survivor);
                args.remove("imageId");args.put("sequence",10);args.put("x",0d);args.put("y",0d);args.put("width",40d);args.put("height",40d);
                var created=rpc.call("addFragment",args);marquee=created.get("id").longValue();ids.add(marquee);marqueeId=created.get("marqueeId").longValue();
                rpc.call("lockFragment",Map.of("id",target));
                var reorder=step7Args(context.inflateFragment(target));reorder.put("sequence",40);rpc.call("updateFragment",reorder);
                rpc.call("normaliseFragments",Map.of("year",2094,"month",3,"day",13));
                // Add/update operations now normalise chronology atomically as they commit.
                // At this point survivor precedes marquee, while target was explicitly moved
                // to the end; an explicit normalise call must preserve that committed order.
                for(int i=0;i<3;i++)assertEquals(0,BigDecimal.valueOf(i+1).compareTo(context.getFragmentRepository().findById(List.of(survivor,marquee,target).get(i)).orElseThrow().getSequence()));
                step12AssertRetained(broker,rpc.publisher,ids);
                var before=step11RetainedSnapshot(broker,rpc.publisher);
                rpc.call("deleteFragment",Map.of("id",target));
                assertFalse(context.getFragmentRepository().existsById(target));
                var after=step11RetainedSnapshot(broker,rpc.publisher);
                assertFalse(after.containsKey("diaries/fragments/"+target));assertFalse(after.containsKey("diaries/dates/2094/3/13/"+target));
                assertEquals(before.get("diaries/images/"+first.getId()),after.get("diaries/images/"+first.getId()));
                assertTrue(context.getImageRepository().existsById(first.getId()));assertEquals("original first",java.nio.file.Files.readString(root.resolve("files/step12-first.png")));
                // Corrupt/miss retained entries to prove startup actually repairs the tree.
                rpc.publisher.publish("diaries/fragments/"+survivor,new byte[0],1,true).waitForCompletion(10000);
                rpc.publisher.publish("diaries/dates/2094/3/13/"+survivor,"{}".getBytes(),1,true).waitForCompletion(10000);
                rpc.publisher.publish("diaries/fragments/"+target,before.get("diaries/fragments/"+target).getBytes(),1,true).waitForCompletion(10000);
            }
            // Rebuild the persistence factory, request connections and production startup reconciliation.
            try(var restartedFactory=GetEntityManager.adminFactory(config.getDb());var fresh=restartedFactory.createEntityManager()) {
                var restarted=Responder.createContext(config,restartedFactory,fresh);
                var syncConfig=new Config();syncConfig.setNormaliseOnStartup(false);
                var user=new User();user.setUsername("step12-fixture");user.setPassword("fixture");
                new com.rsmaxwell.diaries.responder.sync.Synchronise().perform(syncConfig,restarted,broker,user);
                try(var rpc=new Step12Rpc(broker,restartedFactory)) {
                    String[] checkedTopics={"diaries/fragments/"+survivor,"diaries/dates/2094/3/13/"+survivor,
                            "diaries/fragments/"+target,"diaries/dates/2094/3/13/"+target,
                            "diaries/images/"+first.getId(),"diaries/images/"+second.getId(),
                            "diaries/fragments/"+marquee,"diaries/dates/2094/3/13/"+marquee};
                    var topics=step11RetainedSnapshot(broker,rpc.publisher,checkedTopics);
                    var expected=new HashMap<String,String>();var replay=restarted.loadFromDatabase();
                    for(String topic:checkedTopics)if(replay.containsKey(topic))expected.put(topic,replay.get(topic));
                    assertEquals(expected.keySet(),topics.keySet(),"Startup replay topic set");
                    for(var entry:expected.entrySet())assertEquals(entry.getValue(),topics.get(entry.getKey()),entry.getKey());
                    assertFalse(topics.containsKey("diaries/fragments/"+target));
                    var payload=new com.fasterxml.jackson.databind.ObjectMapper().readTree(topics.get("diaries/fragments/"+survivor));
                    assertEquals(second.getId().longValue(),payload.get("imageId").longValue());assertTrue(payload.get("marqueeId").isNull());
                    assertEquals("IMAGE",payload.get("type").textValue());assertEquals(page.getId().longValue(),payload.get("pageId").longValue());
                    assertEquals(topics.get("diaries/fragments/"+survivor),topics.get("diaries/dates/2094/3/13/"+survivor));
                    rpc.call("lockFragment",Map.of("id",survivor));rpc.call("unlockFragment",Map.of("id",survivor));
                    step12AssertRetained(broker,rpc.publisher,List.of(survivor,marquee));
                }
            }
        } finally {
            if(em.getTransaction().isActive())em.getTransaction().rollback();
            em.getTransaction().begin();if(marqueeId!=null)context.getMarqueeRepository().deleteById(marqueeId);
            for(Long id:ids)context.getFragmentRepository().deleteById(id);em.getTransaction().commit();
        }
    }

    private void step12AssertRetained(String broker,org.eclipse.paho.mqttv5.client.MqttAsyncClient publisher,List<Long> ids) throws Exception {
        var filters=new java.util.ArrayList<String>();
        for(Long id:ids) {
            var row=context.getFragmentRepository().findById(id).orElseThrow();
            filters.add("diaries/fragments/"+id);filters.add("diaries/dates/"+row.getYear()+"/"+row.getMonth()+"/"+row.getDay()+"/"+id);
        }
        var topics=step11RetainedSnapshot(broker,publisher,filters.toArray(String[]::new));
        var mapper=new com.fasterxml.jackson.databind.ObjectMapper();
        try(var reader=factory.createEntityManager()) {
            var committed=Responder.createContext(config,factory,reader);
            for(Long id:ids) {
                var fragment=committed.inflateFragment(id);var state=committed.resolveFragmentState(fragment);
                var expected=new com.rsmaxwell.diaries.responder.dto.FragmentPublishDTO(fragment,state.getMarquee());
                var json=mapper.readTree(expected.toJson());
                assertEquals(json,mapper.readTree(topics.get("diaries/fragments/"+id)));
                assertEquals(json,mapper.readTree(topics.get("diaries/dates/"+fragment.getYear()+"/"+fragment.getMonth()+"/"+fragment.getDay()+"/"+id)));
            }
        }
    }

    /** Real broker transport; each serial server callback owns a fresh persistence context. */
    private static final class Step12Rpc implements AutoCloseable {
        final org.eclipse.paho.mqttv5.client.MqttAsyncClient publisher,server,client;
        final java.util.concurrent.LinkedBlockingQueue<org.eclipse.paho.mqttv5.common.MqttMessage> replies=new java.util.concurrent.LinkedBlockingQueue<>();
        final java.util.concurrent.atomic.AtomicReference<Throwable> failure=new java.util.concurrent.atomic.AtomicReference<>();
        final com.fasterxml.jackson.databind.ObjectMapper mapper=new com.fasterxml.jackson.databind.ObjectMapper();
        final String replyTopic="fixture/step12/reply/"+java.util.UUID.randomUUID();
        final String secret=java.util.Base64.getEncoder().encodeToString("01234567890123456789012345678901".getBytes());
        Step12Rpc(String broker,EntityManagerFactory persistence) throws Exception {
            publisher=new org.eclipse.paho.mqttv5.client.MqttAsyncClient(broker,"rpc-pub-"+java.util.UUID.randomUUID(),new org.eclipse.paho.mqttv5.client.persist.MemoryPersistence());
            server=new org.eclipse.paho.mqttv5.client.MqttAsyncClient(broker,"rpc-server-"+java.util.UUID.randomUUID(),new org.eclipse.paho.mqttv5.client.persist.MemoryPersistence());
            client=new org.eclipse.paho.mqttv5.client.MqttAsyncClient(broker,"rpc-client-"+java.util.UUID.randomUUID(),new org.eclipse.paho.mqttv5.client.persist.MemoryPersistence());
            try {
                publisher.connect().waitForCompletion(10000);
                server.setCallback(new com.rsmaxwell.mqtt.rpc.common.Adapter() {
                    public void messageArrived(String topic,org.eclipse.paho.mqttv5.common.MqttMessage message) {
                        try(var requestEm=persistence.createEntityManager()) {
                            var requestContext=Responder.createContext(config,persistence,requestEm);requestContext.setSecret(secret);requestContext.setPublisherClient(publisher);
                            Responder.messageHandler.setContext(requestContext);Responder.messageHandler.setPublisherClient(publisher);
                            Responder.messageHandler.messageArrived(topic,message);
                        } catch(Throwable error) {failure.set(error);}
                    }
                });
                server.connect().waitForCompletion(10000);server.subscribe("diaries/rpc/request",1).waitForCompletion(10000);
                client.setCallback(new com.rsmaxwell.mqtt.rpc.common.Adapter() {
                    public void messageArrived(String topic,org.eclipse.paho.mqttv5.common.MqttMessage message) {replies.add(message);}
                });
                client.connect().waitForCompletion(10000);client.subscribe(replyTopic,1).waitForCompletion(10000);
            } catch(Exception error) {close();throw error;}
        }
        com.fasterxml.jackson.databind.JsonNode call(String function,Map<String,Object> args) throws Exception {
            return call(function,args,200);
        }
        com.fasterxml.jackson.databind.JsonNode call(String function,Map<String,Object> args,int expectedStatus) throws Exception {
            byte[] correlation=java.util.UUID.randomUUID().toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
            var request=new org.eclipse.paho.mqttv5.common.MqttMessage(mapper.writeValueAsBytes(Map.of("function",function,"args",args)));request.setQos(1);
            var props=new org.eclipse.paho.mqttv5.common.packet.MqttProperties();props.setResponseTopic(replyTopic);props.setCorrelationData(correlation);
            props.setUserProperties(List.of(new org.eclipse.paho.mqttv5.common.packet.UserProperty("accessToken",
                    com.rsmaxwell.diaries.responder.utilities.Authorization.getTokenWithClaims(secret,"access",5,java.time.temporal.ChronoUnit.MINUTES,
                    Map.of("role","EDITOR","status","ACTIVE","userId",42L,"sessionId","step12","username","editor","knownAs","Editor")))));
            request.setProperties(props);client.publish("diaries/rpc/request",request).waitForCompletion(10000);
            var reply=replies.poll(15,java.util.concurrent.TimeUnit.SECONDS);
            assertNull(failure.get(),()->String.valueOf(failure.get()));assertNotNull(reply,"RPC timed out: "+function);
            assertArrayEquals(correlation,reply.getProperties().getCorrelationData());
            String status=reply.getProperties().getUserProperties().stream().filter(p->p.getKey().equals("status")).findFirst().orElseThrow().getValue();
            assertEquals(expectedStatus,mapper.readTree(status).get("code").intValue(),function+": "+status);
            return mapper.readTree(reply.getPayload());
        }
        public void close() throws Exception {
            for(var connection:List.of(server,client,publisher)) {if(connection.isConnected())connection.disconnect().waitForCompletion(10000);connection.close();}
            Responder.messageHandler.setContext(null);Responder.messageHandler.setPublisherClient(null);
        }
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "DIARIES_IMAGE_MQTT_TEST_URL", matches = ".+")
    void imageFragmentDatabaseLifecycleWithRealRetainedTopics(@org.junit.jupiter.api.io.TempDir java.nio.file.Path root) throws Exception {
        String broker = System.getenv("DIARIES_IMAGE_MQTT_TEST_URL");
        assertTrue(broker.matches("tcp://127\\.0\\.0\\.1:[0-9]+"));
        var publisher = new org.eclipse.paho.mqttv5.client.MqttAsyncClient(broker, "step11-pub-" + java.util.UUID.randomUUID(),
                new org.eclipse.paho.mqttv5.client.persist.MemoryPersistence());
        var diary = com.rsmaxwell.diaries.responder.model.Diary.builder().name("0025-step11-fixture").sequence(BigDecimal.ONE).build();
        var page = com.rsmaxwell.diaries.responder.model.Page.builder().diary(diary).name("fixture-page").extension("png")
                .sequence(BigDecimal.ONE).width(100).height(100).build();
        var files = root.resolve("files"); java.nio.file.Files.createDirectory(files);
        var fc = new com.rsmaxwell.diaries.responder.config.DiariesConfig(); fc.setRoot(root.toString()); fc.setFiles("files"); config.setDiaries(fc);
        context.setSecret(java.util.Base64.getEncoder().encodeToString("01234567890123456789012345678901".getBytes()));
        var auth = List.of(new org.eclipse.paho.mqttv5.common.packet.UserProperty("accessToken",
                com.rsmaxwell.diaries.responder.utilities.Authorization.getTokenWithClaims(context.getSecret(), "access", 5,
                java.time.temporal.ChronoUnit.MINUTES, Map.of("role","EDITOR","status","ACTIVE","userId",42L,"sessionId","step7"))));
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        try {
            publisher.connect().waitForCompletion(10000); context.setPublisherClient(publisher);
            em.getTransaction().begin(); context.getDiaryRepository().save(diary); context.getPageRepository().save(page); em.getTransaction().commit();
            assertNotNull(diary.getId()); assertNotNull(page.getId());
            Image first = context.saveImage(candidate("step11-first.png")), second = context.saveImage(candidate("step11-second.png"));
            for (Image image : List.of(first, second)) {
                java.nio.file.Files.writeString(files.resolve(image.getRelativePath()), "fixture bytes " + image.getId());
                new ImagePublishDTO(image).publish(publisher);
            }
            var args = new HashMap<String,Object>(Map.of("pageId",page.getId(),"year",2093,"month",3,"day",11,"sequence",40,"text","Shared first"));
            args.put("imageId", first.getId());
            var add = new com.rsmaxwell.diaries.responder.handlers.AddImageFragment();
            var a = (com.rsmaxwell.diaries.responder.dto.FragmentPublishDTO) add.handleRequest(context,args,auth).payload();
            args.put("sequence",20); args.put("text","Shared second");
            var b = (com.rsmaxwell.diaries.responder.dto.FragmentPublishDTO) add.handleRequest(context,args,auth).payload();
            args.remove("imageId"); args.put("sequence",10);
            var empty = (com.rsmaxwell.diaries.responder.dto.FragmentPublishDTO) add.handleRequest(context,args,auth).payload();
            for (var f : List.of(a,b,empty)) {
                var db = context.getFragmentRepository().findById(f.getId()).orElseThrow();
                assertEquals(FragmentType.IMAGE,db.getType()); assertEquals(page.getId(),db.getPageId());
                assertEquals(f == empty ? null : first.getId(),db.getImageId());
                assertTrue(context.getMarqueeRepository().findByFragmentId(f.getId()).isEmpty());
            }
            var initialTopics = step11RetainedSnapshot(broker,publisher);
            for (Image image : List.of(first,second))
                assertEquals(mapper.readTree(new ImagePublishDTO(image).toJson()),mapper.readTree(initialTopics.get("diaries/images/"+image.getId())));
            for (var f : List.of(a,b,empty)) {
                assertEquals(mapper.readTree(f.toJson()),mapper.readTree(initialTopics.get("diaries/fragments/"+f.getId())));
                assertEquals(initialTopics.get("diaries/fragments/"+f.getId()),initialTopics.get("diaries/dates/2093/3/11/"+f.getId()));
            }
            // Bypass responder validation to prove the actual database constraints and SQL states.
            step11RejectedSql("UPDATE fragment SET image_id = " + Long.MAX_VALUE + " WHERE id = " + empty.getId(), "23503");
            step11RejectedSql("UPDATE fragment SET type = 'MARQUEE' WHERE id = " + a.getId(), "23514");
            step11RejectedSql("DELETE FROM image WHERE id = " + first.getId(), "23503");
            assertNull(context.getFragmentRepository().findById(empty.getId()).orElseThrow().getImageId());
            assertEquals(FragmentType.IMAGE,context.getFragmentRepository().findById(a.getId()).orElseThrow().getType());
            args.put("sequence",30); args.put("x",0d); args.put("y",0d); args.put("width",40d); args.put("height",40d);
            var marquee = (com.rsmaxwell.diaries.responder.dto.FragmentPublishDTO) new com.rsmaxwell.diaries.responder.handlers.AddFragment().handleRequest(context,args,auth).payload();
            assertEquals(FragmentType.MARQUEE,marquee.getType()); assertNotNull(marquee.getMarqueeId());
            var update = new com.rsmaxwell.diaries.responder.handlers.UpdateFragment();
            for (Long reference : new Long[] {first.getId(),second.getId(),null}) {
                Fragment before = lockStep7(empty.getId(),42L);
                var edit = step7Args(before); edit.put("imageId",reference);
                assertEquals(200,update.handleRequest(context,edit,auth).status().code());
                var after = context.getFragmentRepository().findById(empty.getId()).orElseThrow();
                assertEquals(reference,after.getImageId()); assertNull(after.getLock()); assertEquals(before.getVersion()+1,after.getVersion());
                var topics = step11RetainedSnapshot(broker,publisher);
                var payload = mapper.readTree(topics.get("diaries/fragments/"+empty.getId()));
                if(reference == null) assertTrue(payload.get("imageId").isNull()); else assertEquals(reference.longValue(),payload.get("imageId").longValue());
            }
            for (boolean wrongOwner : new boolean[] {false,true}) {
                Fragment before = lockStep7(empty.getId(),wrongOwner ? 43L : 42L);
                var edit = step7Args(before); edit.put("imageId",second.getId());
                if(!wrongOwner) edit.put("version",before.getVersion()-1);
                var original = context.getFragmentRepository().findById(empty.getId()).orElseThrow();
                var topics = step11RetainedSnapshot(broker,publisher);
                var failure = assertThrows(com.rsmaxwell.mqtt.rpc.exceptions.RpcStatusException.class,()->update.handleRequest(context,edit,auth));
                assertEquals(wrongOwner ? 409 : 400,failure.getStatus().code());
                assertEquals(original,context.getFragmentRepository().findById(empty.getId()).orElseThrow());
                assertEquals(topics,step11RetainedSnapshot(broker,publisher));
            }
            new com.rsmaxwell.diaries.responder.handlers.NormaliseFragments().handleRequest(context,Map.of("year",2093,"month",3,"day",11),auth);
            // Each add now closes chronology immediately, so the committed insertion order
            // is already a=1, b=2, empty=3, marquee=4. Explicit normalisation is idempotent.
            var ordered = List.of(a,b,empty,marquee);
            for (int i=0;i<ordered.size();i++) {
                var row = context.getFragmentRepository().findById(ordered.get(i).getId()).orElseThrow();
                assertEquals(0,BigDecimal.valueOf(i+1).compareTo(row.getSequence()));
                assertEquals(row.getId().equals(a.getId()) || row.getId().equals(b.getId()) ? first.getId() : null,row.getImageId());
            }
            var deleteImage = new com.rsmaxwell.diaries.responder.handlers.DeleteImage();
            var deleteFragment = new com.rsmaxwell.diaries.responder.handlers.DeleteFragment();
            String originalBytes = java.nio.file.Files.readString(files.resolve(first.getRelativePath()));
            for (Long id : List.of(a.getId(),b.getId())) {
                var before = step11RetainedSnapshot(broker,publisher);
                var imageBefore = context.getImageRepository().findById(first.getId()).orElseThrow();
                var conflict = assertThrows(com.rsmaxwell.mqtt.rpc.exceptions.RpcStatusException.class,
                        () -> deleteImage.handleRequest(context,Map.of("name",first.getRelativePath()),auth));
                assertEquals(409,conflict.getStatus().code());
                assertEquals(imageBefore,context.getImageRepository().findById(first.getId()).orElseThrow());
                assertEquals(originalBytes,java.nio.file.Files.readString(files.resolve(first.getRelativePath())));
                assertEquals(before,step11RetainedSnapshot(broker,publisher));
                assertEquals(200,deleteFragment.handleRequest(context,Map.of("id",id),auth).status().code());
                var after = step11RetainedSnapshot(broker,publisher);
                assertFalse(after.containsKey("diaries/fragments/"+id)); assertFalse(after.containsKey("diaries/dates/2093/3/11/"+id));
                assertEquals(before.get("diaries/images/"+first.getId()),after.get("diaries/images/"+first.getId()));
                assertEquals(imageBefore,context.getImageRepository().findById(first.getId()).orElseThrow());
                assertEquals(originalBytes,java.nio.file.Files.readString(files.resolve(first.getRelativePath())));
                if(id.equals(a.getId())) {
                    var survivor = context.getFragmentRepository().findById(b.getId()).orElseThrow();
                    assertEquals(first.getId(),survivor.getImageId());
                    // Deleting a closes the chronology gap immediately. b therefore moves
                    // from sequence 2 to 1 and its retained Fragment payload must be republished.
                    assertEquals(0,BigDecimal.ONE.compareTo(survivor.getSequence()));
                    assertNotEquals(before.get("diaries/fragments/"+b.getId()),after.get("diaries/fragments/"+b.getId()));
                    step12AssertRetained(broker,publisher,List.of(b.getId()));
                }
            }
            assertEquals(200,deleteImage.handleRequest(context,Map.of("name",first.getRelativePath()),auth).status().code());
            assertFalse(context.getImageRepository().existsById(first.getId())); assertFalse(java.nio.file.Files.exists(files.resolve(first.getRelativePath())));
            var topics = step11RetainedSnapshot(broker,publisher);
            assertFalse(topics.containsKey("diaries/images/"+first.getId())); assertTrue(topics.containsKey("diaries/images/"+second.getId()));
            assertTrue(java.nio.file.Files.exists(files.resolve(second.getRelativePath())));
            try(var staged=java.nio.file.Files.list(files.resolve(".image-staging"))) {
                assertEquals(List.of("catalogue.lock"),staged.map(p->p.getFileName().toString()).toList());
            }
        } finally {
            if(publisher.isConnected())publisher.disconnect().waitForCompletion(10000); publisher.close();
            if(em.getTransaction().isActive())em.getTransaction().rollback();
            em.getTransaction().begin();
            if(page.getId()!=null) {
                em.createNativeQuery("DELETE FROM marquee WHERE fragment_id IN (SELECT id FROM fragment WHERE page_id=:id)").setParameter("id",page.getId()).executeUpdate();
                em.createNativeQuery("DELETE FROM fragment WHERE page_id=:id").setParameter("id",page.getId()).executeUpdate();
                context.getPageRepository().deleteById(page.getId());
            }
            if(diary.getId()!=null)context.getDiaryRepository().deleteById(diary.getId());
            em.getTransaction().commit();
        }
    }

    private void step11RejectedSql(String sql, String expectedState) {
        try(var writer=factory.createEntityManager()) {
            writer.getTransaction().begin();
            try {
                var error=assertThrows(PersistenceException.class,()->writer.createNativeQuery(sql).executeUpdate());
                Throwable cause=error;
                while(cause!=null && !(cause instanceof java.sql.SQLException))cause=cause.getCause();
                assertNotNull(cause); assertEquals(expectedState,((java.sql.SQLException)cause).getSQLState());
            } finally { if(writer.getTransaction().isActive())writer.getTransaction().rollback(); }
        }
    }

    private Map<String,String> step11RetainedSnapshot(String broker, org.eclipse.paho.mqttv5.client.MqttAsyncClient publisher, String... filters) throws Exception {
        var snapshot=new java.util.concurrent.ConcurrentHashMap<String,String>();
        var callback=new com.rsmaxwell.diaries.responder.sync.SynchroniseCallback(snapshot);
        var observer=new org.eclipse.paho.mqttv5.client.MqttAsyncClient(broker,"snapshot-"+java.util.UUID.randomUUID(),
                new org.eclipse.paho.mqttv5.client.persist.MemoryPersistence());
        try {
            publisher.publish("fixture/flush",new byte[]{1},1,false).waitForCompletion(10000);
            observer.setCallback(callback);
            var options=new org.eclipse.paho.mqttv5.client.MqttConnectionOptions();
            options.setCleanStart(true);
            options.setReceiveMaximum(20);
            observer.connect(options).waitForCompletion(10000);
            String[] requested=filters.length==0?new String[]{"diaries/#"}:filters;
            observer.subscribe(callback.barrierFilter(),1).waitForCompletion(10000);
            for(String filter:requested) {
                observer.subscribe(filter,1).waitForCompletion(10000);
                callback.awaitDrained(publisher);
            }
            return new HashMap<>(snapshot);
        } finally { if(observer.isConnected())observer.disconnect().waitForCompletion(10000); observer.close(); }
    }

    @Test
    void referenceAwareDeletionSerializesWithAttachment(@org.junit.jupiter.api.io.TempDir java.nio.file.Path root) throws Exception {
        var files = root.resolve("files"); java.nio.file.Files.createDirectory(files);
        var fileConfig = new com.rsmaxwell.diaries.responder.config.DiariesConfig();
        fileConfig.setRoot(root.toString()); fileConfig.setFiles("files"); config.setDiaries(fileConfig);
        context.setSecret(java.util.Base64.getEncoder().encodeToString("01234567890123456789012345678901".getBytes()));
        var auth = List.of(new org.eclipse.paho.mqttv5.common.packet.UserProperty("accessToken",
                com.rsmaxwell.diaries.responder.utilities.Authorization.getTokenWithClaims(context.getSecret(), "access", 5,
                java.time.temporal.ChronoUnit.MINUTES, Map.of("status", "ACTIVE", "role", "EDITOR"))));
        var page = context.inflatePage(context.getPageRepository().findAll().iterator().next().getId());
        var ids = new java.util.ArrayList<Long>();
        var retained = new java.util.concurrent.ConcurrentHashMap<Long, String>();
        var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        var releaseDelete = new java.util.concurrent.CountDownLatch(1);
        var handler = step9Handler(factory, retained);
        try {
            Image shared = context.saveImage(candidate("shared.png"));
            java.nio.file.Files.writeString(files.resolve("shared.png"), "original");
            retained.put(shared.getId(), new ImagePublishDTO(shared).toJson());
            var fragment = Fragment.builder().page(page).type(FragmentType.IMAGE).image(shared)
                    .year(2092).month(3).day(9).sequence(BigDecimal.ONE).text("Reference test").build();
            ids.add(context.saveImageFragment(fragment).getFragment().getId());
            ids.add(context.saveImageFragment(fragment).getFragment().getId());
            for (Long id : List.copyOf(ids)) {
                var conflict = assertThrows(com.rsmaxwell.mqtt.rpc.exceptions.RpcStatusException.class,
                        () -> handler.handleRequest(context, Map.of("name", "shared.png"), auth));
                assertEquals(409, conflict.getStatus().code());
                assertEquals("original", java.nio.file.Files.readString(files.resolve("shared.png")));
                assertTrue(retained.containsKey(shared.getId()));
                em.getTransaction().begin(); context.getFragmentRepository().deleteById(id); em.getTransaction().commit();
                assertTrue(context.getImageRepository().existsById(shared.getId()));
            }
            assertEquals(200, handler.handleRequest(context, Map.of("name", "shared.png"), auth).status().code());
            assertFalse(context.getImageRepository().existsById(shared.getId()));
            assertFalse(java.nio.file.Files.exists(files.resolve("shared.png")));
            assertFalse(retained.containsKey(shared.getId()));

            // Attach owns the shared row lock and has an uncommitted reference. The advisory check
            // cannot see it; deletion stages bytes and must wait, then restore them on conflict.
            Image attachFirst = context.saveImage(candidate("attach-first.png"));
            java.nio.file.Files.writeString(files.resolve("attach-first.png"), "race bytes");
            retained.put(attachFirst.getId(), "metadata"); fragment.setImage(attachFirst);
            em.getTransaction().begin();
            fragment.setImage(context.lockImageForFragmentWrite(attachFirst.getId()));
            Long attachedId = context.getFragmentRepository().save(fragment); ids.add(attachedId);
            var deletion = pool.submit(() -> assertThrows(com.rsmaxwell.mqtt.rpc.exceptions.RpcStatusException.class,
                    () -> handler.handleRequest(context, Map.of("name", "attach-first.png"), auth)));
            awaitStep9ImageLockWait();
            em.getTransaction().commit();
            assertEquals(409, deletion.get(15, java.util.concurrent.TimeUnit.SECONDS).getStatus().code());
            assertEquals("race bytes", java.nio.file.Files.readString(files.resolve("attach-first.png")));
            assertTrue(retained.containsKey(attachFirst.getId()));
            assertEquals(attachFirst.getId(), context.getFragmentRepository().findById(attachedId).orElseThrow().getImageId());
            em.getTransaction().begin(); context.getFragmentRepository().deleteById(attachedId); em.getTransaction().commit();

            // Pause the real deletion immediately after acquiring its exclusive Image lock.
            // Attachment must wait and then report a missing Image, never persist a dangling FK.
            Image deleteFirst = context.saveImage(candidate("delete-first.png"));
            java.nio.file.Files.writeString(files.resolve("delete-first.png"), "delete bytes");
            retained.put(deleteFirst.getId(), "metadata");
            var locked = new java.util.concurrent.CountDownLatch(1);
            var pausedFactory = step9PauseAfterImageLock(factory, () -> {
                locked.countDown();
                try { assertTrue(releaseDelete.await(15, java.util.concurrent.TimeUnit.SECONDS)); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
            });
            var deletingHandler = step9Handler(pausedFactory, retained);
            var deleted = pool.submit(() -> deletingHandler.handleRequest(context, Map.of("name", "delete-first.png"), auth));
            if (!locked.await(15, java.util.concurrent.TimeUnit.SECONDS)) {
                if (deleted.isDone()) deleted.get();
                fail("Deletion did not acquire its Image lock");
            }
            var attaching = pool.submit(() -> {
                try (var attachingEm = factory.createEntityManager()) {
                    var attachingContext = Responder.createContext(config, factory, attachingEm);
                    var pending = Fragment.builder().page(page).type(FragmentType.IMAGE).image(deleteFirst)
                            .year(2092).month(3).day(9).sequence(BigDecimal.ONE).text("Must fail").build();
                    return assertThrows(IllegalArgumentException.class, () -> attachingContext.saveImageFragment(pending));
                }
            });
            awaitStep9ImageLockWait(); releaseDelete.countDown();
            assertEquals(200, deleted.get(15, java.util.concurrent.TimeUnit.SECONDS).status().code());
            assertNotNull(attaching.get(15, java.util.concurrent.TimeUnit.SECONDS));
            assertFalse(context.getFragmentRepository().existsByImageId(deleteFirst.getId()));
            assertFalse(context.getImageRepository().existsById(deleteFirst.getId()));
            assertFalse(retained.containsKey(deleteFirst.getId()));
            assertFalse(java.nio.file.Files.exists(files.resolve("delete-first.png")));
            try (var staged = java.nio.file.Files.list(files.resolve(".image-staging"))) {
                assertEquals(List.of("catalogue.lock"), staged.map(p -> p.getFileName().toString()).toList());
            }
        } finally {
            releaseDelete.countDown();
            if (em.getTransaction().isActive()) em.getTransaction().rollback();
            pool.shutdown(); assertTrue(pool.awaitTermination(30, java.util.concurrent.TimeUnit.SECONDS));
            em.getTransaction().begin();
            for (Long id : ids) context.getFragmentRepository().deleteById(id);
            em.getTransaction().commit();
        }
    }

    private com.rsmaxwell.diaries.responder.handlers.DeleteImage step9Handler(EntityManagerFactory deletionFactory,
            Map<Long, String> retained) {
        return new com.rsmaxwell.diaries.responder.handlers.DeleteImage() {
            @Override protected com.rsmaxwell.diaries.responder.utilities.ImageCatalogueService.Catalogue deletionCatalogue(DiaryContext ignored) {
                return com.rsmaxwell.diaries.responder.utilities.ImageCatalogueService.jpaCatalogue(deletionFactory);
            }
            @Override protected com.rsmaxwell.diaries.responder.utilities.ImageCatalogueService.TombstonePublication deletionPublication(DiaryContext ignored) {
                return image -> {
                    try (var reader = factory.createEntityManager()) {
                        assertFalse(new ImageRepositoryImpl(reader).existsById(image.id()));
                    }
                    retained.remove(image.id());
                };
            }
        };
    }

    private void awaitStep9ImageLockWait() throws Exception {
        long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
        try (var observer = factory.createEntityManager()) {
            do {
                Number waiting = (Number) observer.createNativeQuery("SELECT count(*) FROM pg_stat_activity WHERE datname=current_database() AND wait_event_type='Lock' AND query ILIKE '%image%'").getSingleResult();
                if (waiting.intValue() > 0) return;
                Thread.sleep(20);
            } while (System.nanoTime() < deadline);
        }
        fail("Expected a concurrent Image row-lock wait");
    }

    private EntityManagerFactory step9PauseAfterImageLock(EntityManagerFactory delegate, Runnable afterLock) {
        return (EntityManagerFactory) java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),
                new Class<?>[] {EntityManagerFactory.class}, (proxy, method, args) -> {
                    try {
                        Object result = method.invoke(delegate, args);
                        if (!method.getName().equals("createEntityManager")) return result;
                        EntityManager manager = (EntityManager) result;
                        return java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {EntityManager.class},
                                (p, m, a) -> {
                                    try {
                                        Object value = m.invoke(manager, a);
                                        if (m.getName().equals("find") && a[0] == Image.class) {
                                            assertTrue(manager.getTransaction().isActive());
                                            assertEquals(jakarta.persistence.LockModeType.PESSIMISTIC_WRITE, manager.getLockMode(value));
                                            afterLock.run();
                                        }
                                        return value;
                                    } catch (java.lang.reflect.InvocationTargetException e) { throw e.getCause(); }
                                });
                    } catch (java.lang.reflect.InvocationTargetException e) { throw e.getCause(); }
                });
    }

    @Test
    void mixedFragmentLifecyclePreservesReusableImage(@org.junit.jupiter.api.io.TempDir java.nio.file.Path files) throws Exception {
        Image image=context.saveImage(candidate("step8/shared.png"));
        var file=files.resolve("files/step8/shared.png");java.nio.file.Files.createDirectories(file.getParent());java.nio.file.Files.writeString(file,"unchanged image bytes");
        var fileConfig=new com.rsmaxwell.diaries.responder.config.DiariesConfig();fileConfig.setRoot(files.toString());fileConfig.setFiles("files");context.getConfig().setDiaries(fileConfig);
        var page=context.inflatePage(context.getPageRepository().findAll().iterator().next().getId());
        context.setSecret(java.util.Base64.getEncoder().encodeToString("01234567890123456789012345678901".getBytes()));
        String token=com.rsmaxwell.diaries.responder.utilities.Authorization.getTokenWithClaims(context.getSecret(),"access",5,
                java.time.temporal.ChronoUnit.MINUTES,Map.of("status","ACTIVE","role","EDITOR","userId",42L,"sessionId","step8","username","editor","knownAs","Editor"));
        var auth=List.of(new org.eclipse.paho.mqttv5.common.packet.UserProperty("accessToken",token));
        var retained=new HashMap<String,String>();
        String imageTopic="diaries/images/"+image.getId();retained.put(imageTopic,new ImagePublishDTO(image).toJson());
        String imagePayload=retained.get(imageTopic);
        var publisher=new org.eclipse.paho.mqttv5.client.MqttAsyncClient("tcp://localhost:1883","step8",new org.eclipse.paho.mqttv5.client.persist.MemoryPersistence()) {
            @Override public org.eclipse.paho.mqttv5.client.IMqttToken publish(String topic,byte[] bytes,int qos,boolean retain) {
                assertFalse(em.getTransaction().isActive());assertEquals(1,qos);assertTrue(retain);
                assertFalse(topic.startsWith("diaries/images/"));
                if(bytes.length==0)retained.remove(topic);else retained.put(topic,new String(bytes,java.nio.charset.StandardCharsets.UTF_8));return null;
            }
        };
        context.setPublisherClient(publisher);
        var ids=new java.util.ArrayList<Long>();var marqueeIds=new java.util.ArrayList<Long>();
        try {
            int[] sequence={40,20,10,30};
            for(int i=0;i<4;i++) {
                boolean isImage=i%2==1;
                var fragment=Fragment.builder().page(page).type(isImage?FragmentType.IMAGE:FragmentType.MARQUEE)
                        .image(isImage?image:null).year(2091).month(3).day(8).sequence(BigDecimal.valueOf(sequence[i])).text("Mixed").build();
                if(isImage)ids.add(context.saveImageFragment(fragment).getFragment().getId());
                else {
                    var marquee=com.rsmaxwell.diaries.responder.model.Marquee.builder().fragment(fragment).page(page).x(0d).y(0d).width(40d).height(40d).build();
                    var saved=context.saveMarqueeFragment(fragment,marquee);ids.add(saved.getFragment().getId());marqueeIds.add(saved.getMarquee().getId());
                    String marqueePayload=new com.rsmaxwell.diaries.responder.dto.MarqueePublishDTO(saved.getMarquee()).toJson();
                    retained.put("diaries/marquees/"+saved.getMarquee().getId(),marqueePayload);
                    retained.put("diaries/diaries/"+page.getDiary().getId()+"/"+page.getId()+"/"+saved.getMarquee().getId(),marqueePayload);
                }
            }
            // All four use the same lock/unlock handlers, independent of associated type.
            for(Long id:ids) {
                new com.rsmaxwell.diaries.responder.handlers.LockFragment().handleRequest(context,Map.of("id",id),auth);
                assertTrue(context.getFragmentRepository().findById(id).orElseThrow().getLock().isLocked());
                new com.rsmaxwell.diaries.responder.handlers.UnlockFragment().handleRequest(context,Map.of("id",id),auth);
                assertNull(context.getFragmentRepository().findById(id).orElseThrow().getLock());
            }
            new com.rsmaxwell.diaries.responder.handlers.NormaliseFragments().handleRequest(context,Map.of("year",2091,"month",3,"day",8),auth);
            int[] expected={4,2,1,3};var mapper=new com.fasterxml.jackson.databind.ObjectMapper();
            for(int i=0;i<4;i++) {
                var row=context.getFragmentRepository().findById(ids.get(i)).orElseThrow();
                assertEquals(0,BigDecimal.valueOf(expected[i]).compareTo(row.getSequence()));
                assertEquals(i%2==1?image.getId():null,row.getImageId());
                var payload=mapper.readTree(retained.get("diaries/fragments/"+ids.get(i)));
                if(i%2==1){assertEquals(image.getId().longValue(),payload.get("imageId").longValue());assertTrue(payload.get("marqueeId").isNull());}
            }
            var geometry=new HashMap<String,Object>(Map.of("pageId",page.getId(),"fragmentId",ids.get(1),"x",0d,"y",0d,"width",40d,"height",40d));
            assertThrows(com.rsmaxwell.mqtt.rpc.exceptions.RpcStatusException.class,()->new com.rsmaxwell.diaries.responder.handlers.AddMarquee().handleRequest(context,geometry,auth));
            // Seed a legacy-invalid relationship to exercise rejection and explicit repair.
            var invalid=com.rsmaxwell.diaries.responder.model.Marquee.builder().page(page).fragment(context.inflateFragment(ids.get(1))).x(0d).y(0d).width(40d).height(40d).build();
            em.getTransaction().begin();Long invalidId=context.getMarqueeRepository().save(invalid);em.getTransaction().commit();marqueeIds.add(invalidId);
            geometry.put("id",invalidId);geometry.put("version",0L);
            assertThrows(com.rsmaxwell.mqtt.rpc.exceptions.RpcStatusException.class,()->new com.rsmaxwell.diaries.responder.handlers.UpdateMarquee().handleRequest(context,geometry,auth));
            assertThrows(com.rsmaxwell.mqtt.rpc.exceptions.RpcStatusException.class,()->new com.rsmaxwell.diaries.responder.handlers.DeleteFragment().handleRequest(context,Map.of("id",ids.get(1)),auth));
            new com.rsmaxwell.diaries.responder.handlers.LockFragment().handleRequest(context,Map.of("id",ids.get(1)),auth);
            new com.rsmaxwell.diaries.responder.handlers.DeleteMarquee().handleRequest(context,Map.of("id",invalidId),auth);
            var repaired=context.getFragmentRepository().findById(ids.get(1)).orElseThrow();assertEquals(FragmentType.IMAGE,repaired.getType());assertEquals(image.getId(),repaired.getImageId());
            for(Long id:ids) {
                new com.rsmaxwell.diaries.responder.handlers.DeleteFragment().handleRequest(context,Map.of("id",id),auth);
                assertFalse(context.getFragmentRepository().existsById(id));
                assertFalse(retained.containsKey("diaries/fragments/"+id));assertFalse(retained.containsKey("diaries/dates/2091/3/8/"+id));
                assertTrue(context.getImageRepository().existsById(image.getId()));assertEquals(imagePayload,retained.get(imageTopic));
                assertEquals("unchanged image bytes",java.nio.file.Files.readString(file));
            }
            for(Long id:marqueeIds) {
                assertFalse(context.getMarqueeRepository().existsById(id));
                assertFalse(retained.containsKey("diaries/marquees/"+id));
                assertFalse(retained.containsKey("diaries/diaries/"+page.getDiary().getId()+"/"+page.getId()+"/"+id));
            }
        } finally {
            publisher.close();if(em.getTransaction().isActive())em.getTransaction().rollback();
            em.getTransaction().begin();for(Long id:marqueeIds)context.getMarqueeRepository().deleteById(id);
            for(Long id:ids)context.getFragmentRepository().deleteById(id);em.getTransaction().commit();
        }
    }

    @Test
    void imageAwareUpdatesAreAtomicAndPublishCommittedState() throws Exception {
        var images = new Image[] {context.saveImage(candidate("step7/one.png")),context.saveImage(candidate("step7/two.png"))};
        var page = context.inflatePage(context.getPageRepository().findAll().iterator().next().getId());
        var created = new java.util.ArrayList<Long>();
        var retained = new HashMap<String,String>();
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        context.setSecret(java.util.Base64.getEncoder().encodeToString("01234567890123456789012345678901".getBytes()));
        String token = com.rsmaxwell.diaries.responder.utilities.Authorization.getTokenWithClaims(context.getSecret(),"access",5,
                java.time.temporal.ChronoUnit.MINUTES,Map.of("status","ACTIVE","role","EDITOR","userId",42L,"sessionId","step7"));
        var auth=List.of(new org.eclipse.paho.mqttv5.common.packet.UserProperty("accessToken",token));
        var handler=new com.rsmaxwell.diaries.responder.handlers.UpdateFragment();
        var publisher=new org.eclipse.paho.mqttv5.client.MqttAsyncClient("tcp://localhost:1883","step7-recorder",new org.eclipse.paho.mqttv5.client.persist.MemoryPersistence()) {
            @Override public org.eclipse.paho.mqttv5.client.IMqttToken publish(String topic,byte[] bytes,int qos,boolean retain) {
                assertFalse(em.getTransaction().isActive());assertEquals(1,qos);assertTrue(retain);
                if(bytes.length==0)retained.remove(topic);
                else {
                    try(EntityManager reader=factory.createEntityManager()) {
                        var payload=mapper.readTree(bytes);
                        var dto=new com.rsmaxwell.diaries.responder.repositoryImpl.FragmentRepositoryImpl(reader).findById(payload.get("id").longValue()).orElseThrow();
                        assertEquals(dto.getVersion().longValue(),payload.get("version").longValue());
                        if(dto.getImageId()==null)assertTrue(payload.get("imageId").isNull());
                        else assertEquals(dto.getImageId().longValue(),payload.get("imageId").longValue());
                        assertNull(dto.getLock());
                        retained.put(topic,new String(bytes,java.nio.charset.StandardCharsets.UTF_8));
                    } catch(Exception failure){throw new AssertionError(failure);}
                }
                return null;
            }
        };
        context.setPublisherClient(publisher);
        Long marqueeId=null;
        var repository=context.getFragmentRepository();
        try {
            var candidate=Fragment.builder().page(page).type(FragmentType.IMAGE).image(images[0]).year(2090).month(3).day(8)
                    .sequence(new BigDecimal("1.0000")).text("Before").build();
            Long id=context.saveImageFragment(candidate).getFragment().getId();created.add(id);
            candidate.setImage(null);candidate.setSequence(new BigDecimal("2.0000"));
            Long sibling=context.saveImageFragment(candidate).getFragment().getId();created.add(sibling);
            Long siblingVersion=repository.findById(sibling).orElseThrow().getVersion();
            // Omission, replacement, explicit clear, and attachment each use one normal edit.
            for(int operation=0;operation<4;operation++) {
                Fragment before=lockStep7(id,42L);
                var args=step7Args(before);args.put("text","Edit "+operation);
                if(operation==1)args.put("imageId",images[1].getId());
                if(operation==2)args.put("imageId",null);
                if(operation==3)args.put("imageId",images[0].getId());
                assertEquals(200,handler.handleRequest(context,args,auth).status().code());
                var after=repository.findById(id).orElseThrow();
                Long expected=operation==2?null:(operation==1?images[1].getId():images[0].getId());
                assertEquals(expected,after.getImageId());assertNull(after.getLock());
                assertEquals(before.getVersion()+1,after.getVersion());
                assertEquals(siblingVersion,repository.findById(sibling).orElseThrow().getVersion());
            }
            for(String failure:List.of("missing","stale","owner","type","page")) {
                Fragment before=lockStep7(id,failure.equals("owner")?43L:42L);
                var snapshot=repository.findById(id).orElseThrow();var topics=new HashMap<>(retained);
                var args=step7Args(before);
                switch(failure) {
                    case "missing" -> args.put("imageId",Long.MAX_VALUE);
                    case "stale" -> args.put("version",before.getVersion()-1);
                    case "type" -> args.put("type","MARQUEE");
                    case "page" -> args.put("pageId",page.getId()+1);
                }
                assertThrows(com.rsmaxwell.mqtt.rpc.exceptions.RpcStatusException.class,()->handler.handleRequest(context,args,auth));
                assertEquals(snapshot,repository.findById(id).orElseThrow());assertEquals(topics,retained);
            }
            Fragment before=lockStep7(id,42L);var args=step7Args(before);args.put("day",9);args.put("sequence",new BigDecimal("7.0000"));args.put("imageId",images[1].getId());
            var snapshot=repository.findById(id).orElseThrow();var topics=new HashMap<>(retained);
            // Fail normalisation after the initial update: Image/date/lock/version must all roll back.
            context.setFragmentRepository((com.rsmaxwell.diaries.responder.repository.FragmentRepository)java.lang.reflect.Proxy.newProxyInstance(
                    getClass().getClassLoader(),new Class<?>[]{com.rsmaxwell.diaries.responder.repository.FragmentRepository.class},
                    (proxy,method,values)->{if(method.getName().equals("updateSequence"))throw new IllegalStateException("injected sequence failure");return method.invoke(repository,values);}));
            assertEquals("injected sequence failure",assertThrows(IllegalStateException.class,()->handler.handleRequest(context,args,auth)).getMessage());context.setFragmentRepository(repository);
            assertEquals(snapshot,repository.findById(id).orElseThrow());assertEquals(topics,retained);
            handler.handleRequest(context,args,auth);
            var moved=repository.findById(id).orElseThrow();assertEquals(images[1].getId(),moved.getImageId());assertEquals(9,moved.getDay());
            assertEquals(0,BigDecimal.ONE.compareTo(moved.getSequence()));assertNull(moved.getLock());
            assertFalse(retained.containsKey("diaries/dates/2090/3/8/"+id));
            assertEquals(retained.get("diaries/fragments/"+id),retained.get("diaries/dates/2090/3/9/"+id));
            candidate.setType(FragmentType.MARQUEE);candidate.setImage(null);candidate.setDay(10);
            var marquee=com.rsmaxwell.diaries.responder.model.Marquee.builder().fragment(candidate).page(page).x(0d).y(0d).width(40d).height(40d).build();
            var saved=context.saveMarqueeFragment(candidate,marquee);Long marqueeFragment=saved.getFragment().getId();created.add(marqueeFragment);marqueeId=saved.getMarquee().getId();
            var marqueeBefore=lockStep7(marqueeFragment,42L);handler.handleRequest(context,step7Args(marqueeBefore),auth);
            assertNull(repository.findById(marqueeFragment).orElseThrow().getImageId());
            marqueeBefore=lockStep7(marqueeFragment,42L);var rejected=step7Args(marqueeBefore);rejected.put("imageId",images[0].getId());
            assertThrows(com.rsmaxwell.mqtt.rpc.exceptions.RpcStatusException.class,()->handler.handleRequest(context,rejected,auth));
        } finally {
            context.setFragmentRepository(repository);publisher.close();
            if(em.getTransaction().isActive())em.getTransaction().rollback();
            em.getTransaction().begin();if(marqueeId!=null)context.getMarqueeRepository().deleteById(marqueeId);
            for(Long id:created)repository.deleteById(id);em.getTransaction().commit();
        }
    }

    private Fragment lockStep7(Long id,Long userId) throws Exception {
        Fragment fragment=context.inflateFragment(id);
        fragment.setLock(new LockInfo(userId,"editor","Editor",1L,"step7"));
        em.getTransaction().begin();context.getFragmentRepository().update(fragment);em.getTransaction().commit();
        return fragment;
    }

    private Map<String,Object> step7Args(Fragment fragment) {
        return new HashMap<>(Map.of("id",fragment.getId(),"version",fragment.getVersion(),"year",fragment.getYear(),
                "month",fragment.getMonth(),"day",fragment.getDay(),"sequence",fragment.getSequence(),"text",fragment.getText()));
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "DIARIES_BROWSER_TEST_CLIENT", matches = ".+")
    void filesDialogDeletionEndToEnd(@org.junit.jupiter.api.io.TempDir java.nio.file.Path root) throws Exception {
        ImageDeletionBrowserFixture.run(context, root);
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "DIARIES_IMAGE_MQTT_TEST_URL", matches = ".+")
    void registeredAddImageFragmentCommitsAndPublishes() throws Exception {
        String broker = System.getenv("DIARIES_IMAGE_MQTT_TEST_URL");
        assertTrue(broker.matches("tcp://127\\.0\\.0\\.1:[0-9]+"));
        Image image = context.saveImage(candidate("step6/reference.png"));
        long marquees = context.getMarqueeRepository().count();
        Long pageId = context.getPageRepository().findAll().iterator().next().getId();
        context.setSecret(java.util.Base64.getEncoder().encodeToString("01234567890123456789012345678901".getBytes()));
        String token = com.rsmaxwell.diaries.responder.utilities.Authorization.getTokenWithClaims(context.getSecret(),"access",5,
                java.time.temporal.ChronoUnit.MINUTES,Map.of("status","ACTIVE","role","EDITOR"));
        var messages = new java.util.concurrent.LinkedBlockingQueue<Map.Entry<String,org.eclipse.paho.mqttv5.common.MqttMessage>>();
        var publisher = new org.eclipse.paho.mqttv5.client.MqttAsyncClient(broker,"add-pub-"+java.util.UUID.randomUUID(),new org.eclipse.paho.mqttv5.client.persist.MemoryPersistence());
        var observer = new org.eclipse.paho.mqttv5.client.MqttAsyncClient(broker,"add-sub-"+java.util.UUID.randomUUID(),new org.eclipse.paho.mqttv5.client.persist.MemoryPersistence());
        var created = new java.util.ArrayList<Long>();
        try {
            publisher.connect().waitForCompletion(10000);context.setPublisherClient(publisher);
            observer.setCallback(new com.rsmaxwell.mqtt.rpc.common.Adapter() {
                public void messageArrived(String t,org.eclipse.paho.mqttv5.common.MqttMessage m) {messages.add(Map.entry(t,m));}
            });
            observer.connect().waitForCompletion(10000);
            observer.subscribe("test/add/reply",1).waitForCompletion(10000);
            Responder.messageHandler.setContext(context);Responder.messageHandler.setPublisherClient(publisher);
            var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            for (Long reference : new Long[] {image.getId(),null}) {
                var args = new HashMap<String,Object>(Map.of("pageId",pageId,"year",1830,"month",3,"day",8,"sequence","2.1250","text","RPC image","type","MARQUEE"));
                if(reference!=null)args.put("imageId",reference);
                var request = new org.eclipse.paho.mqttv5.common.MqttMessage(mapper.writeValueAsBytes(Map.of("function","addImageFragment","args",args)));
                var props = new org.eclipse.paho.mqttv5.common.packet.MqttProperties();
                props.setResponseTopic("test/add/reply");props.setCorrelationData(new byte[]{6});
                props.setUserProperties(List.of(new org.eclipse.paho.mqttv5.common.packet.UserProperty("accessToken",token)));request.setProperties(props);
                Responder.messageHandler.messageArrived("diaries/rpc/request",request);
                var reply = messages.poll(5,java.util.concurrent.TimeUnit.SECONDS);assertNotNull(reply);
                assertEquals("test/add/reply",reply.getKey());
                var status=reply.getValue().getProperties().getUserProperties().stream().filter(v->v.getKey().equals("status")).findFirst().orElseThrow();
                assertEquals(200,mapper.readTree(status.getValue()).get("code").intValue());
                assertArrayEquals(new byte[]{6},reply.getValue().getProperties().getCorrelationData());
                var payload=mapper.readTree(reply.getValue().getPayload());
                Long id=payload.get("id").longValue();created.add(id);
                assertEquals("IMAGE",payload.get("type").asText());assertTrue(payload.get("marqueeId").isNull());
                try(EntityManager reader=factory.createEntityManager()) {
                    Fragment row=reader.find(Fragment.class,id);assertNotNull(row);assertEquals(reference,row.getImageId());
                }
                assertEquals(marquees,context.getMarqueeRepository().count());
                String canonical="diaries/fragments/"+id, date="diaries/dates/1830/3/8/"+id;
                // Subscribe after success: both payloads must have been retained by the broker.
                for(String topic:List.of(canonical,date)) {
                    observer.subscribe(topic,1).waitForCompletion(10000);
                    var retained=messages.poll(5,java.util.concurrent.TimeUnit.SECONDS);assertNotNull(retained);
                    assertEquals(topic,retained.getKey());assertTrue(retained.getValue().isRetained());
                    assertEquals(payload,mapper.readTree(retained.getValue().getPayload()));
                }
            }
        } finally {
            Responder.messageHandler.setContext(null);Responder.messageHandler.setPublisherClient(null);
            if(observer.isConnected())observer.disconnect().waitForCompletion(10000);observer.close();
            if(publisher.isConnected())publisher.disconnect().waitForCompletion(10000);publisher.close();
            em.getTransaction().begin();for(Long id:created)context.getFragmentRepository().deleteById(id);em.getTransaction().commit();
        }
    }

	@Test
	void freshContextReplaysCommittedImageFragmentContract() throws Exception {
		Image image = context.saveImage(candidate("step5/replay.png"));
		var page = context.inflatePage(context.getPageRepository().findAll().iterator().next().getId());
		var created = new java.util.ArrayList<Fragment>();
		try {
			for (Image reference : new Image[] {image, null}) {
				var fragment = Fragment.builder().version(0L).page(page).type(FragmentType.IMAGE).image(reference)
						.year(1830).month(3).day(8).sequence(new BigDecimal("1.0000")).text("Replay contract").build();
				created.add(context.saveImageFragment(fragment).getFragment());
			}
			// New persistence context and repository wiring reproduce startup replay from committed rows.
			try (EntityManager reader = factory.createEntityManager()) {
				var restarted = Responder.createContext(config, factory, reader);
				var retained = restarted.loadFromDatabase();
				var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
				for (Fragment fragment : created) {
					String canonical = retained.get("diaries/fragments/" + fragment.getId());
					assertEquals(canonical, retained.get("diaries/dates/1830/3/8/" + fragment.getId()));
					assertEquals(mapper.readTree(new com.rsmaxwell.diaries.responder.dto.FragmentPublishDTO(fragment, null).toJson()),
							mapper.readTree(canonical));
					assertTrue(mapper.readTree(canonical).has("imageId"));
					assertTrue(mapper.readTree(canonical).get("marqueeId").isNull());
				}
				for (var entry : retained.entrySet()) {
					if (entry.getKey().startsWith("diaries/fragments/")) {
						var payload = mapper.readTree(entry.getValue());
						assertTrue(payload.has("imageId"));
						if ("MARQUEE".equals(payload.path("type").asText())) assertTrue(payload.get("imageId").isNull());
					}
				}
			}
		} finally {
			em.getTransaction().begin();
			for (Fragment fragment : created) context.getFragmentRepository().deleteById(fragment.getId());
			em.getTransaction().commit();
		}
	}

	@Test
	void fragmentCreationResolutionRollbackAndImageLocking() throws Exception {
		var fragments = context.getFragmentRepository();
		var marquees = context.getMarqueeRepository();
		long originalCount = fragments.count();
		var page = context.inflatePage(context.getPageRepository().findAll().iterator().next().getId());
		Image image = context.saveImage(candidate("step4/shared.png"));
		var created = new java.util.ArrayList<Long>();
		Long marqueeId = null;
		try {
			Fragment candidate = Fragment.builder().version(0L).page(page).type(FragmentType.IMAGE)
					.year(1830).month(3).day(8).sequence(BigDecimal.ONE).text("Step 4").image(image).build();
			var first = context.saveImageFragment(candidate);
			created.add(first.getFragment().getId());
			assertNull(candidate.getId());
			assertFalse(em.getTransaction().isActive());
			assertNull(first.getMarquee());
			assertEquals(image.getId(), first.getImage().getId());
			var second = context.saveImageFragment(candidate);
			created.add(second.getFragment().getId());
			candidate.setImage(null);
			var empty = context.saveImageFragment(candidate);
			created.add(empty.getFragment().getId());
			assertNull(empty.getImage());
			var resolved = context.resolveFragmentState(context.inflateFragment(first.getFragment().getId()));
			resolved.validateForWrite();
			assertEquals(image.getId(), resolved.getImage().getId());
			assertNull(resolved.getMarquee());
			assertTrue(context.loadFromDatabase().containsKey("diaries/fragments/" + empty.getFragment().getId()));
			candidate.setPersistedImageId(Long.MAX_VALUE);
			assertThrows(IllegalArgumentException.class, () -> context.saveImageFragment(candidate));
			assertFalse(em.getTransaction().isActive());
			candidate.setImage(null);
			candidate.setType(FragmentType.MARQUEE);
			assertThrows(IllegalArgumentException.class, () -> context.saveImageFragment(candidate));
			var marquee = com.rsmaxwell.diaries.responder.model.Marquee.builder().version(0L)
					.fragment(candidate).page(page).x(1d).y(2d).width(40d).height(50d).build();
			candidate.setImage(image);
			assertThrows(IllegalArgumentException.class, () -> context.saveMarqueeFragment(candidate, marquee));
			candidate.setImage(null);
			assertThrows(IllegalArgumentException.class, () -> context.saveMarqueeFragment(candidate, null));
			// Force failure after Fragment INSERT, proving both writes share a transaction.
			context.setMarqueeRepository((com.rsmaxwell.diaries.responder.repository.MarqueeRepository)
					java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),
							new Class<?>[] {com.rsmaxwell.diaries.responder.repository.MarqueeRepository.class},
							(proxy, method, args) -> { throw new IllegalStateException("injected marquee failure"); }));
			assertThrows(IllegalStateException.class, () -> context.saveMarqueeFragment(candidate, marquee));
			context.setMarqueeRepository(marquees);
			assertEquals(originalCount + 3, fragments.count());
			assertNull(candidate.getId());
			assertNull(marquee.getId());
			assertFalse(em.getTransaction().isActive());
			var savedMarquee = context.saveMarqueeFragment(candidate, marquee);
			created.add(savedMarquee.getFragment().getId());
			marqueeId = savedMarquee.getMarquee().getId();
			context.resolveFragmentState(context.inflateFragment(savedMarquee.getFragment().getId())).validateForWrite();
			// Helpers must not commit or roll back somebody else's transaction.
			em.getTransaction().begin();
			assertThrows(IllegalStateException.class, () -> context.saveMarqueeFragment(candidate, marquee));
			assertTrue(em.getTransaction().isActive());
			em.getTransaction().rollback();
			assertThrows(IllegalStateException.class, () -> context.lockImageForFragmentWrite(image.getId()));
			// PostgreSQL must block a concurrent deletion while the attach lock is held.
			Image unreferenced = context.saveImage(candidate("step4/lock.png"));
			em.getTransaction().begin();
			context.lockImageForFragmentWrite(unreferenced.getId());
			try (EntityManager deleting = factory.createEntityManager()) {
				deleting.getTransaction().begin();
				deleting.createNativeQuery("SET LOCAL lock_timeout = '200ms'").executeUpdate();
				PersistenceException blocked = assertThrows(PersistenceException.class, () -> deleting.createNativeQuery(
						"DELETE FROM image WHERE id = :id").setParameter("id", unreferenced.getId()).executeUpdate());
				assertTrue(blocked.getMessage().contains("lock timeout"));
				deleting.getTransaction().rollback();
				em.getTransaction().rollback();
				deleting.getTransaction().begin();
				assertEquals(1, deleting.createNativeQuery("DELETE FROM image WHERE id = :id")
						.setParameter("id", unreferenced.getId()).executeUpdate());
				deleting.getTransaction().rollback();
			}
		} finally {
			context.setMarqueeRepository(marquees);
			if (em.getTransaction().isActive()) em.getTransaction().rollback();
			em.clear();
			em.getTransaction().begin();
			if (marqueeId != null) marquees.deleteById(marqueeId);
			for (Long id : created) fragments.deleteById(id);
			em.getTransaction().commit();
		}
		assertEquals(originalCount, fragments.count());
	}

	@Test
	void fragmentImageRepositoryRoundTripsAndCountsReferences() throws Exception {
		var fragments = new FragmentRepositoryImpl(em);
		var images = context.getImageRepository();
		em.getTransaction().begin();
		try {
			Long imageId = images.save(candidate("step3/shared.png"));
			assertFalse(fragments.existsByImageId(imageId));
			assertFalse(fragments.existsByImageId(null));
			assertFalse(fragments.existsByImageId(-25003L));
			var baseline = fragments.findAll().iterator().next();
			var fragment = new Fragment(baseline);
			fragment.setType(FragmentType.IMAGE);
			fragment.setPersistedImageId(imageId);
			fragment.setLock(new LockInfo(42L, "alice", "Ali", 1L, "step3"));
			Long first = fragments.save(fragment);
			assertTrue(fragments.existsByImageId(imageId));
			Long second = fragments.save(fragment);
			assertTrue(fragments.existsByImageId(imageId));
			var dto = fragments.findById(first).orElseThrow();
			assertEquals(imageId, dto.getImageId());
			assertEquals(baseline.getPageId(), dto.getPageId());
			assertEquals(fragment.getLock(), dto.getLock());
			assertEquals(FragmentType.IMAGE, dto.getType());
			assertEquals(imageId, em.find(Fragment.class, first).getImage().getId());
			for (var projection : List.of(fragments.findAll(), fragments.findAllByDate(dto.getYear(), dto.getMonth(), dto.getDay()),
					fragments.findAllWithoutMarquee(), fragments.findStaleLocks(Instant.ofEpochMilli(2)))) {
				var found = StreamSupport.stream(projection.spliterator(), false)
						.filter(row -> row.getId().equals(first)).findFirst().orElseThrow();
				assertEquals(dto, found);
			}
			// Execute the joined projection on real legacy MARQUEE rows as well.
			var marquee = context.getMarqueeRepository().findAll().iterator().next();
			var legacy = fragments.findById(marquee.getFragmentId()).orElseThrow();
			var joined = fragments.findAllFragmentsWithMarqueesonDate(legacy.getYear(), legacy.getMonth(), legacy.getDay());
			assertEquals(legacy, StreamSupport.stream(joined.spliterator(), false)
					.filter(row -> row.getId().equals(legacy.getId())).findFirst().orElseThrow());
			assertNull(legacy.getImageId());
			var update = new Fragment(dto);
			update.setText("Step 3 edited text");
			assertEquals(1, fragments.update(update));
			assertEquals(imageId, fragments.findById(first).orElseThrow().getImageId());
			var updated = fragments.findById(first).orElseThrow();
			assertEquals("Step 3 edited text", updated.getText());
			assertEquals(dto.getLock(), updated.getLock());
			assertEquals(dto.getPageId(), updated.getPageId());
			assertEquals(1, fragments.updateSequence(first, updated.getVersion(), new BigDecimal("7.0000")));
			assertEquals(imageId, fragments.findById(first).orElseThrow().getImageId());
			assertEquals(1, fragments.deleteById(first));
			assertTrue(fragments.existsByImageId(imageId));
			assertTrue(images.existsById(imageId));
			var remaining = new Fragment(fragments.findById(second).orElseThrow());
			remaining.setImage(null);
			assertEquals(1, fragments.update(remaining));
			assertNull(fragments.findById(second).orElseThrow().getImageId());
			assertFalse(fragments.existsByImageId(imageId));
			remaining.setPersistedImageId(imageId);
			assertEquals(1, fragments.update(remaining));
			assertTrue(fragments.existsByImageId(imageId));
			assertEquals(1, fragments.deleteById(second));
			assertFalse(fragments.existsByImageId(imageId));
			assertTrue(images.existsById(imageId));
		} finally {
			em.getTransaction().rollback();
			em.clear();
		}
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

    @Test
    void catalogueDeletionCommitsAndRejectsStaleSnapshots(@org.junit.jupiter.api.io.TempDir java.nio.file.Path root) throws Exception {
        var store = com.rsmaxwell.diaries.responder.utilities.ImageCatalogueService.jpaCatalogue(factory);
        Image saved = context.saveImage(candidate("maps/Caf\u00e9.png"));
        Image stale = store.find("MAPS/CAF\u00c9.PNG").orElseThrow();
        assertEquals(saved.getId(), stale.getId());
        saved.setCaption("changed after lookup");
        em.getTransaction().begin();
        context.getImageRepository().update(saved);
        em.getTransaction().commit();
        var failure = assertThrows(com.rsmaxwell.diaries.responder.utilities.ImageCatalogueService.DeleteFailedException.class,
            () -> store.delete(stale));
        assertFalse(failure.outcomeUnknown());
        assertEquals("changed after lookup", store.find(saved.getRelativePath()).orElseThrow().getCaption());

        var target = root.resolve(saved.getRelativePath());
        java.nio.file.Files.createDirectories(target.getParent());
        java.nio.file.Files.writeString(target, "original");
        var service = new com.rsmaxwell.diaries.responder.utilities.ImageCatalogueService(
            new com.rsmaxwell.diaries.responder.utilities.ImagePathPolicy(root),
            new com.rsmaxwell.diaries.responder.utilities.ImageMetadataInspector(), store);
        var deleted = service.delete("maps/Caf\u00e9.png", dto -> {
            assertTrue(store.find(dto.relativePath()).isEmpty(), "tombstone must follow visible commit");
            assertEquals(saved.getId().longValue(), dto.id());
            assertFalse(java.nio.file.Files.exists(target));
        });
        assertEquals(saved.getId().longValue(), deleted.id());
        assertEquals(0, context.getImageRepository().count());
        assertTrue(store.find(saved.getRelativePath()).isEmpty());
        var missing = assertThrows(com.rsmaxwell.diaries.responder.utilities.ImageCatalogueService.DeleteFailedException.class,
            () -> store.delete(saved));
        assertFalse(missing.outcomeUnknown());
    }

    @Test
    @EnabledIfEnvironmentVariable(named="DIARIES_IMAGE_MQTT_TEST_URL", matches=".+")
    void registeredDeleteImageRemovesRowFileAndRetainedTopic(@org.junit.jupiter.api.io.TempDir java.nio.file.Path root) throws Exception {
        String broker = System.getenv("DIARIES_IMAGE_MQTT_TEST_URL");
        assertTrue(broker.matches("tcp://127\\.0\\.0\\.1:[0-9]+"));
        Image saved = context.saveImage(candidate("diary/images/test.png"));
        var target = root.resolve("files/" + saved.getRelativePath());
        java.nio.file.Files.createDirectories(target.getParent()); java.nio.file.Files.writeString(target,"original");
        var cfg = new Config(); var files = new com.rsmaxwell.diaries.responder.config.DiariesConfig();
        files.setRoot(root.toString()); files.setFiles("files"); cfg.setDiaries(files);
        var ctx = new DiaryContext(); ctx.setConfig(cfg); ctx.setEntityManagerFactory(factory);
        ctx.setSecret(java.util.Base64.getEncoder().encodeToString("01234567890123456789012345678901".getBytes()));
        var token = com.rsmaxwell.diaries.responder.utilities.Authorization.getTokenWithClaims(ctx.getSecret(),"access",5,
            java.time.temporal.ChronoUnit.MINUTES,Map.of("status","ACTIVE","role","EDITOR"));
        var messages = new java.util.concurrent.LinkedBlockingQueue<Map.Entry<String,org.eclipse.paho.mqttv5.common.MqttMessage>>();
        var publisher = new org.eclipse.paho.mqttv5.client.MqttAsyncClient(broker,"delete-pub-"+java.util.UUID.randomUUID(),new org.eclipse.paho.mqttv5.client.persist.MemoryPersistence());
        var observer = new org.eclipse.paho.mqttv5.client.MqttAsyncClient(broker,"delete-sub-"+java.util.UUID.randomUUID(),new org.eclipse.paho.mqttv5.client.persist.MemoryPersistence());
        var late = new org.eclipse.paho.mqttv5.client.MqttAsyncClient(broker,"delete-late-"+java.util.UUID.randomUUID(),new org.eclipse.paho.mqttv5.client.persist.MemoryPersistence());
        String topic = "diaries/images/"+saved.getId();
        try {
            publisher.connect().waitForCompletion(10000); ctx.setPublisherClient(publisher);
            observer.setCallback(new com.rsmaxwell.mqtt.rpc.common.Adapter() {
                public void messageArrived(String t, org.eclipse.paho.mqttv5.common.MqttMessage m) { messages.add(Map.entry(t,m)); }
            });
            observer.connect().waitForCompletion(10000);
            publisher.publish(topic,new ImagePublishDTO(saved).toJsonAsBytes(),1,true).waitForCompletion(10000);
            observer.subscribe(topic,1).waitForCompletion(10000);
            var initial = messages.poll(5,java.util.concurrent.TimeUnit.SECONDS);
            assertNotNull(initial); assertTrue(initial.getValue().isRetained());
            observer.subscribe("test/delete/reply",1).waitForCompletion(10000);
            var request = new org.eclipse.paho.mqttv5.common.MqttMessage("{\"function\":\"deleteImage\",\"args\":{\"subdir\":\"diary/images\",\"name\":\"test.png\"}}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
            var props = new org.eclipse.paho.mqttv5.common.packet.MqttProperties();
            props.setResponseTopic("test/delete/reply"); props.setCorrelationData(new byte[]{1,2,3});
            props.setUserProperties(List.of(new org.eclipse.paho.mqttv5.common.packet.UserProperty("accessToken",token)));
            request.setProperties(props);
            Responder.messageHandler.setContext(ctx); Responder.messageHandler.setPublisherClient(publisher);
            Responder.messageHandler.messageArrived("diaries/rpc/request",request);
            var responses = new HashMap<String,org.eclipse.paho.mqttv5.common.MqttMessage>();
            for (int i=0;i<2;i++) {
                var msg=messages.poll(5,java.util.concurrent.TimeUnit.SECONDS); assertNotNull(msg); responses.put(msg.getKey(),msg.getValue());
            }
            assertEquals(0,responses.get(topic).getPayload().length);
            assertEquals(1,responses.get(topic).getQos());
            var reply=responses.get("test/delete/reply"); assertNotNull(reply); assertFalse(reply.isRetained());
            assertArrayEquals(new byte[]{1,2,3},reply.getProperties().getCorrelationData());
            var mapper=new com.fasterxml.jackson.databind.ObjectMapper();
            var status=reply.getProperties().getUserProperties().stream().filter(p->p.getKey().equals("status")).findFirst().orElseThrow();
            assertEquals(200,mapper.readTree(status.getValue()).get("code").asInt());
            var body=mapper.readTree(reply.getPayload()); assertEquals(saved.getId().longValue(),body.get("id").asLong());
            assertEquals(saved.getRelativePath(),body.get("relativePath").asText()); assertTrue(body.get("deleted").asBoolean());
            assertFalse(java.nio.file.Files.exists(target)); assertEquals(0,context.getImageRepository().count());
            var retained=new java.util.concurrent.LinkedBlockingQueue<String>();
            late.setCallback(new com.rsmaxwell.mqtt.rpc.common.Adapter() {
                public void messageArrived(String t,org.eclipse.paho.mqttv5.common.MqttMessage m) { retained.add(t); }
            });
            late.connect().waitForCompletion(10000); late.subscribe(topic,1).waitForCompletion(10000);
            assertNull(retained.poll(1500,java.util.concurrent.TimeUnit.MILLISECONDS),"tombstone must clear retained state");
        } finally {
            Responder.messageHandler.setContext(null); Responder.messageHandler.setPublisherClient(null);
            for (var client : List.of(late,observer,publisher)) { if(client.isConnected())client.disconnect().waitForCompletion(5000);client.close(); }
        }
    }


    @Test
    @EnabledIfEnvironmentVariable(named="DIARIES_IMAGE_MQTT_TEST_URL", matches=".+")
    void completedDeletionStaysAbsentAfterRestart(@org.junit.jupiter.api.io.TempDir java.nio.file.Path root) throws Exception {
        String broker = System.getenv("DIARIES_IMAGE_MQTT_TEST_URL");
        assertTrue(broker.matches("tcp://127\\.0\\.0\\.1:[0-9]+"));
        Image deleted = context.saveImage(candidate("deleted.png"));
        Image survivor = context.saveImage(candidate("survivor.png"));
        java.nio.file.Files.writeString(root.resolve("deleted.png"), "deleted-original");
        java.nio.file.Files.writeString(root.resolve("survivor.png"), "survivor-original");
        var publisher = new org.eclipse.paho.mqttv5.client.MqttAsyncClient(broker,"restart-delete-"+java.util.UUID.randomUUID(),new org.eclipse.paho.mqttv5.client.persist.MemoryPersistence());
        try {
            publisher.connect().waitForCompletion(10000);
            publisher.publish("diaries/images/"+deleted.getId(),new ImagePublishDTO(deleted).toJsonAsBytes(),1,true).waitForCompletion(10000);
            publisher.publish("diaries/images/"+survivor.getId(),new ImagePublishDTO(survivor).toJsonAsBytes(),1,true).waitForCompletion(10000);
            var service = new com.rsmaxwell.diaries.responder.utilities.ImageCatalogueService(
                new com.rsmaxwell.diaries.responder.utilities.ImagePathPolicy(root),
                new com.rsmaxwell.diaries.responder.utilities.ImageMetadataInspector(),
                com.rsmaxwell.diaries.responder.utilities.ImageCatalogueService.jpaCatalogue(factory));
            service.delete("deleted.png", image -> ImagePublishDTO.builder().id(image.id()).build().removeAndAwait(publisher));
        } finally {
            if (publisher.isConnected()) publisher.disconnect().waitForCompletion(5000);
            publisher.close();
        }
        // Recreate persistence factory/context and run the actual startup synchronisation path.
        try (var restartedFactory = GetEntityManager.adminFactory(config.getDb()); var fresh = restartedFactory.createEntityManager()) {
            var restarted = Responder.createContext(config,restartedFactory,fresh);
            assertNull(fresh.find(Image.class, deleted.getId()));
            assertEquals(1, restarted.getImageRepository().count());
            var syncConfig = new Config(); syncConfig.setNormaliseOnStartup(false);
            var user = new User(); user.setUsername("step10-fixture"); user.setPassword("fixture");
            new com.rsmaxwell.diaries.responder.sync.Synchronise().perform(syncConfig,restarted,broker,user);
            assertNull(fresh.find(Image.class, deleted.getId()));
            assertNotNull(fresh.find(Image.class, survivor.getId()));
        }
        var received = new java.util.concurrent.LinkedBlockingQueue<Map.Entry<String,String>>();
        var observer = new org.eclipse.paho.mqttv5.client.MqttAsyncClient(broker,"restart-proof-"+java.util.UUID.randomUUID(),new org.eclipse.paho.mqttv5.client.persist.MemoryPersistence());
        try {
            observer.setCallback(new com.rsmaxwell.mqtt.rpc.common.Adapter() {
                public void messageArrived(String topic, org.eclipse.paho.mqttv5.common.MqttMessage message) {
                    received.add(Map.entry(topic,new String(message.getPayload(),java.nio.charset.StandardCharsets.UTF_8)));
                }
            });
            observer.connect().waitForCompletion(10000);
            observer.subscribe("diaries/images/#",1).waitForCompletion(10000);
            var retained = new java.util.TreeMap<String,String>();
            Map.Entry<String,String> message;
            while ((message=received.poll(1500,java.util.concurrent.TimeUnit.MILLISECONDS)) != null) retained.put(message.getKey(),message.getValue());
            assertEquals(Map.of("diaries/images/"+survivor.getId(),new ImagePublishDTO(survivor).toJson()),retained);
            assertFalse(java.nio.file.Files.exists(root.resolve("deleted.png")));
            assertEquals("survivor-original",java.nio.file.Files.readString(root.resolve("survivor.png")));
            try (var staging = java.nio.file.Files.list(root.resolve(".image-staging"))) {
                assertEquals(List.of("catalogue.lock"),staging.map(p->p.getFileName().toString()).toList());
            }
        } finally {
            if(observer.isConnected())observer.disconnect().waitForCompletion(5000);
            observer.close();
        }
    }

    @org.junit.jupiter.params.ParameterizedTest(name="startup replay with {0} Images")
    @org.junit.jupiter.params.provider.ValueSource(ints={0,1,3})
    @EnabledIfEnvironmentVariable(named="DIARIES_IMAGE_MQTT_TEST_URL", matches=".+")
    void startupReconcilesZeroOneAndMultipleImagesAgainstActualDatabaseAndBroker(int count) throws Exception {
        String broker=System.getenv("DIARIES_IMAGE_MQTT_TEST_URL");
        assertTrue(broker.matches("tcp://127\\.0\\.0\\.1:[0-9]+"));
        Map<String,String> expectedImages=new java.util.TreeMap<>();
        for(int i=0;i<count;i++) {
            Image image=context.saveImage(candidate("startup/image-"+i+".png"));
            expectedImages.put("diaries/images/"+image.getId(),new ImagePublishDTO(image).toJson());
        }
        var user=new User();user.setUsername("diaries-responder");user.setPassword("phase4-fixture");
        var syncConfig=new Config();syncConfig.setNormaliseOnStartup(false);
        // A new persistence context follows the same factory/wiring and synchronization path as startup.
        try(EntityManager fresh=factory.createEntityManager()) {
            DiaryContext restarted=Responder.createContext(config,factory,fresh);
            assertEquals(count,restarted.getImageRepository().count());
            new com.rsmaxwell.diaries.responder.sync.Synchronise().perform(syncConfig,restarted,broker,user);
            assertEquals(count,restarted.getImageRepository().count());
        }
        var messages=new java.util.concurrent.LinkedBlockingQueue<Map.Entry<String,org.eclipse.paho.mqttv5.common.MqttMessage>>();
        var observer=new org.eclipse.paho.mqttv5.client.MqttAsyncClient(broker,"startup-proof-"+java.util.UUID.randomUUID(),new org.eclipse.paho.mqttv5.client.persist.MemoryPersistence());
        try {
            observer.setCallback(new com.rsmaxwell.mqtt.rpc.common.Adapter(){
                @Override public void messageArrived(String topic,org.eclipse.paho.mqttv5.common.MqttMessage message){messages.add(Map.entry(topic,message));}
            });
            var options=new org.eclipse.paho.mqttv5.client.MqttConnectionOptions();options.setCleanStart(true);
            options.setUserName(user.getUsername());options.setPassword(user.getPassword().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            observer.connect(options).waitForCompletion(10000);
            observer.subscribe(new org.eclipse.paho.mqttv5.common.MqttSubscription("diaries/images/#",1)).waitForCompletion(10000);
            Map<String,String> retained=new java.util.TreeMap<>();
            Map.Entry<String,org.eclipse.paho.mqttv5.common.MqttMessage> message;
            while((message=messages.poll(1200,java.util.concurrent.TimeUnit.MILLISECONDS))!=null) {
                assertTrue(message.getValue().isRetained());assertEquals(1,message.getValue().getQos());
                assertNull(retained.put(message.getKey(),new String(message.getValue().getPayload(),java.nio.charset.StandardCharsets.UTF_8)));
            }
            assertEquals(expectedImages,retained);
            assertEquals(chronologyBefore,chronology());
        } finally {
            if(observer.isConnected())observer.disconnect().waitForCompletion(5000);
            observer.close();
        }
    }
}
