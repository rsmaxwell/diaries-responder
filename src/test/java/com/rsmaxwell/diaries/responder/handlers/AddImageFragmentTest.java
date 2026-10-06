package com.rsmaxwell.diaries.responder.handlers;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.*;
import org.eclipse.paho.mqttv5.client.*;
import org.eclipse.paho.mqttv5.client.persist.MemoryPersistence;
import org.eclipse.paho.mqttv5.common.packet.UserProperty;
import com.rsmaxwell.diaries.responder.dto.FragmentPublishDTO;
import com.rsmaxwell.diaries.responder.model.*;
import com.rsmaxwell.diaries.responder.utilities.*;
import com.rsmaxwell.mqtt.rpc.exceptions.RpcStatusException;

class AddImageFragmentTest {
    boolean committed, missingPage, missingImage, saveFailure, publishFailure;
    Fragment saved;
    final Map<String, String> retained = new HashMap<>();
    RecordingClient client;
    DiaryContext context;
    @BeforeEach void setup() throws Exception {
        client = new RecordingClient();
        context = new DiaryContext() {
            @Override public Page inflatePage(Long id) throws Exception {
                if (missingPage) throw new Exception("missing");
                Page page = new Page(); page.setId(id); return page;
            }
            @Override public FragmentCreationResult saveImageFragmentAndNormalise(Fragment fragment) {
                if (missingImage) throw new IllegalArgumentException("missing");
                if (saveFailure) throw new IllegalStateException("private database details");
                saved = fragment; saved.setId(123L); committed = true;
                return new FragmentCreationResult(new ResolvedFragmentState(saved, null), List.of());
            }
        };
        context.setSecret(Base64.getEncoder().encodeToString("01234567890123456789012345678901".getBytes()));
        context.setPublisherClient(client);
        var config = new com.rsmaxwell.diaries.responder.config.Config(); config.setImageFragmentWritesEnabled(true); context.setConfig(config);
    }
    @AfterEach void close() throws Exception { client.close(); }
    List<UserProperty> auth(String role, String status, int minutes) {
        return List.of(new UserProperty("accessToken", Authorization.getTokenWithClaims(context.getSecret(), "access",
                minutes, ChronoUnit.MINUTES, Map.of("role",role,"status",status))));
    }
    Map<String,Object> args() { return new HashMap<>(Map.of("pageId",22,"year",1830,"month",3,"day",8,"sequence","2.1250","text","Entry")); }
    void status(int expected, Map<String,Object> args, List<UserProperty> auth) {
        var error = assertThrows(RpcStatusException.class, () -> new AddImageFragment().handleRequest(context,args,auth));
        assertEquals(expected,error.getStatus().code());
    }
    @Test void disabledGateRejectsBeforePageLookupPersistenceOrPublication() {
        context.getConfig().setImageFragmentWritesEnabled(null);
        missingPage = true;
        status(403,args(),auth("EDITOR","ACTIVE",5));
        status(401,args(),auth("VIEWER","ACTIVE",5));
        context.setConfig(null);
        status(403,args(),auth("EDITOR","ACTIVE",5));
        assertFalse(committed); assertTrue(retained.isEmpty());
    }
    @Test void authorizationPrecedesPersistence() {
        for(var credentials : List.of(List.<UserProperty>of(),List.of(new UserProperty("accessToken","bad")),
                auth("EDITOR","ACTIVE",-1),auth("EDITOR","DISABLED",5),auth("VIEWER","ACTIVE",5)))
            status(401,args(),credentials);
        assertFalse(committed); assertTrue(retained.isEmpty());
    }
    @Test void validOptionalReferencesAndUnexpectedFields() throws Exception {
        for (Object reference : new Object[] {null,91L}) {
            var args=args(); args.put("imageId",reference); args.put("type","MARQUEE"); args.put("marqueeId",44);
            var reply=new AddImageFragment().handleRequest(context,args,auth("EDITOR","ACTIVE",5));
            assertEquals(200,reply.status().code());
            var dto=(FragmentPublishDTO)reply.payload();
            assertEquals(FragmentType.IMAGE,dto.getType()); assertEquals(reference,dto.getImageId()); assertNull(dto.getMarqueeId());
            assertEquals(4,dto.getSequence().scale());
            assertEquals(Set.of("diaries/fragments/123","diaries/dates/1830/3/8/123"),retained.keySet());
            assertEquals(dto.toJson(),retained.get("diaries/fragments/123"));
            assertEquals(1,new HashSet<>(retained.values()).size());
        }
        new AddImageFragment().handleRequest(context,args(),auth("EDITOR","ACTIVE",5));
        assertNull(saved.getImageId());
    }
    @Test void rejectsMalformedFields() {
        status(400,null,auth("EDITOR","ACTIVE",5));
        for(String field : List.of("pageId","year","month","day","sequence","text")) {
            var args=args(); args.remove(field); status(400,args,auth("EDITOR","ACTIVE",5));
        }
        for(var entry : Map.<String,List<Object>>of("pageId",List.of(0,-1,1.5),"imageId",List.of(0,-1,1.5,"bad"),
                "month",List.of(0,13),"day",List.of(0,32),"year",List.of(1830.5),
                "sequence",List.of("bad","0.00001","1000000"),"text",List.of(3,"x".repeat(4097))).entrySet()) {
            for(Object value:entry.getValue()) { var args=args();args.put(entry.getKey(),value);status(400,args,auth("EDITOR","ACTIVE",5)); }
        }
        var args=args();args.put("month",2);args.put("day",30);status(400,args,auth("EDITOR","ACTIVE",5));
        assertFalse(committed);assertTrue(retained.isEmpty());
    }
    @Test void missingReferencesAreControlledAndFailureDoesNotPublish() {
        missingPage=true; status(400,args(),auth("EDITOR","ACTIVE",5));missingPage=false;
        missingImage=true;var args=args();args.put("imageId",91);status(400,args,auth("EDITOR","ACTIVE",5));missingImage=false;
        saveFailure=true;status(500,args(),auth("EDITOR","ACTIVE",5));
        assertFalse(committed);assertTrue(retained.isEmpty());
    }
    @Test void publicationFailureReportsCommittedIdentity() {
        publishFailure=true;
        var error=assertThrows(RpcStatusException.class,()->new AddImageFragment().handleRequest(context,args(),auth("EDITOR","ACTIVE",5)));
        assertEquals(500,error.getStatus().code());assertTrue(error.getMessage().contains("123"));assertTrue(committed);
    }
    class RecordingClient extends MqttAsyncClient {
        RecordingClient() throws Exception { super("tcp://localhost:1883","add-image-test",new MemoryPersistence()); }
        @Override public IMqttToken publish(String topic,byte[] payload,int qos,boolean retain) {
            assertTrue(committed); assertEquals(1,qos); assertTrue(retain);
            if(publishFailure) throw new IllegalStateException("broker unavailable");
            retained.put(topic,new String(payload,java.nio.charset.StandardCharsets.UTF_8));return null;
        }
    }
}
