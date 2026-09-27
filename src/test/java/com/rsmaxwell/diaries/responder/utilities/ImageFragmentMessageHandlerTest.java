package com.rsmaxwell.diaries.responder.utilities;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import java.lang.reflect.Proxy;
import org.junit.jupiter.api.*;
import org.eclipse.paho.mqttv5.client.*;
import org.eclipse.paho.mqttv5.client.persist.MemoryPersistence;
import org.eclipse.paho.mqttv5.common.MqttMessage;
import org.eclipse.paho.mqttv5.common.packet.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rsmaxwell.mqtt.rpc.common.Response;
import com.rsmaxwell.mqtt.rpc.exceptions.RpcStatusException;
import com.rsmaxwell.mqtt.rpc.responder.RequestHandler;

class ImageFragmentMessageHandlerTest {
    final ObjectMapper mapper=new ObjectMapper();
    final ImageFragmentMessageHandler dispatcher=new ImageFragmentMessageHandler();
    Map<String,Object> received;
    MqttMessage reply;
    String replyTopic;
    boolean reject;
    MqttAsyncClient publisher;
    @BeforeEach void setup() throws Exception {
        publisher=new MqttAsyncClient("tcp://localhost:1883","null-contract",new MemoryPersistence()) {
            @Override public IMqttToken publish(String topic,MqttMessage message) {
                replyTopic=topic;reply=message;
                return (IMqttToken)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{IMqttToken.class},
                        (p,m,a)-> {if(m.getName().equals("waitForCompletion"))return null;throw new AssertionError(m.getName());});
            }
        };
        dispatcher.setPublisherClient(publisher);dispatcher.setContext("context");
        for(String function:List.of("addImageFragment","updateFragment","other")) dispatcher.putHandler(function,new RequestHandler() {
            public Response handleRequest(Object context,Map<String,Object> args,List<UserProperty> properties) throws Exception {
                assertEquals("context",context);assertEquals("fixture",properties.get(0).getValue());
                received=args;if(reject)throw RpcStatusException.unauthorized("Denied");return Response.success(123L);
            }
        });
    }
    @AfterEach void close() throws Exception {publisher.close();}
    MqttMessage request(String function,Map<String,Object> args) throws Exception {
        var message=new MqttMessage(mapper.writeValueAsBytes(Map.of("function",function,"args",args)));
        var properties=new MqttProperties();properties.setCorrelationData(new byte[]{1,2,3});properties.setResponseTopic("fixture/reply");
        properties.setUserProperties(List.of(new UserProperty("accessToken","fixture")));message.setProperties(properties);return message;
    }
    int status() throws Exception {
        return mapper.readTree(reply.getProperties().getUserProperties().stream().filter(p->p.getKey().equals("status")).findFirst().orElseThrow().getValue()).get("code").intValue();
    }
    @Test void explicitNullReachesBothImageHandlersWithOriginalPropertiesAndReplyContract() throws Exception {
        for(String function:List.of("addImageFragment","updateFragment")) {
            var args=new HashMap<String,Object>();args.put("imageId",null);args.put("id",123);
            dispatcher.messageArrived("diaries/rpc/request",request(function,args));
            assertTrue(received.containsKey("imageId"));assertNull(received.get("imageId"));assertEquals(123,received.get("id"));
            assertEquals(200,status());assertEquals("fixture/reply",replyTopic);assertEquals(1,reply.getQos());assertFalse(reply.isRetained());
            assertArrayEquals(new byte[]{1,2,3},reply.getProperties().getCorrelationData());assertEquals(123L,mapper.readTree(reply.getPayload()).longValue());
        }
    }
    @Test void absentAndNonNullImageIdsStillUseTheExistingContract() throws Exception {
        for(var args:List.of(Map.<String,Object>of(),Map.<String,Object>of("imageId",91))) {
            dispatcher.messageArrived("diaries/rpc/request",request("updateFragment",args));assertEquals(args,received);assertEquals(200,status());
        }
        dispatcher.messageArrived("diaries/rpc/request",request("other",Map.of()));assertEquals(200,status());
    }
    @Test void handlerAuthorizationFailureIsNotBypassedForNullSelection() throws Exception {
        reject=true;var args=new HashMap<String,Object>();args.put("imageId",null);
        dispatcher.messageArrived("diaries/rpc/request",request("updateFragment",args));assertEquals(401,status());
    }
    @Test void missingCorrelationDropsNullRequestWithoutCallingTheHandler() throws Exception {
        var args=new HashMap<String,Object>();args.put("imageId",null);
        var message=request("updateFragment",args);message.getProperties().setCorrelationData(null);
        dispatcher.messageArrived("diaries/rpc/request",message);assertNull(received);assertNull(reply);
    }
}
