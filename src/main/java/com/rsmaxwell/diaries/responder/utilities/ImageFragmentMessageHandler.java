package com.rsmaxwell.diaries.responder.utilities;

import java.util.HashMap;
import java.util.Map;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rsmaxwell.mqtt.rpc.common.Response;
import com.rsmaxwell.mqtt.rpc.common.Status;
import com.rsmaxwell.mqtt.rpc.exceptions.RpcStatusException;
import com.rsmaxwell.mqtt.rpc.responder.MessageHandler;
import com.rsmaxwell.mqtt.rpc.responder.RequestHandler;
import org.eclipse.paho.mqttv5.common.MqttMessage;
import org.eclipse.paho.mqttv5.common.packet.MqttProperties;
import org.eclipse.paho.mqttv5.common.packet.UserProperty;

/**
 * mqtt-rpc 0.0.8 Request uses Map.copyOf, which rejects explicit null values.
 * Preserve the documented imageId:null contract for the two Image authoring RPCs.
 * Other requests retain the library dispatcher. Remove this adapter once the
 * dependency provides a null-preserving Request argument map.
 */
public class ImageFragmentMessageHandler extends MessageHandler {
    private final ObjectMapper mapper = new ObjectMapper();
    private final Map<String, RequestHandler> imageHandlers = new HashMap<>();
    private Object context;
    public record Envelope(String function, Map<String,Object> args) { }

    @Override public void putHandler(String name, RequestHandler handler) {
        super.putHandler(name, handler);
        if (name.equals("addImageFragment") || name.equals("updateFragment")) imageHandlers.put(name, handler);
    }
    @Override public void setContext(Object context) { super.setContext(context); this.context = context; }

    @Override public void messageArrived(String topic, MqttMessage message) throws Exception {
        byte[] payload = message.getPayload();
        // Match the dependency's transport limit before attempting JSON decoding.
        if (payload == null || payload.length > 20 * 1024 * 1024) { super.messageArrived(topic,message); return; }
        Envelope request;
        try { request = mapper.readValue(payload, Envelope.class); }
        catch (Exception invalid) { super.messageArrived(topic,message); return; }
        if (request == null || !imageHandlers.containsKey(request.function()) || request.args() == null
                || !request.args().containsKey("imageId") || request.args().get("imageId") != null) {
            super.messageArrived(topic,message); return;
        }
        var incoming = message.getProperties();
        if (incoming == null || incoming.getCorrelationData() == null || incoming.getResponseTopic() == null
                || incoming.getResponseTopic().isEmpty()) return;
        Response response;
        try {
            // Preserve null versus absence without changing handler validation or authentication.
            response = imageHandlers.get(request.function()).handleRequest(context, request.args(), incoming.getUserProperties());
        } catch (io.jsonwebtoken.ExpiredJwtException expired) {
            response = Response.status(Status.BAD_REQUEST, expired.getMessage());
        } catch (RpcStatusException rejected) {
            response = Response.status(rejected.getStatus(), rejected.getMessage());
        } catch (Exception failure) {
            org.slf4j.LoggerFactory.getLogger(getClass()).error("Image Fragment RPC failed", failure);
            response = Response.status(Status.INTERNAL_ERROR, "Image Fragment RPC failed");
        }
        if (response == null) response = Response.status(Status.INTERNAL_ERROR, "Missing RPC response");
        var properties = new MqttProperties(); properties.setCorrelationData(incoming.getCorrelationData());
        properties.getUserProperties().add(new UserProperty("status", getStatusAsJson(response.status())));
        var reply = new MqttMessage(mapper.writeValueAsBytes(response.payload())); reply.setProperties(properties); reply.setQos(1);
        getPublisherClient().publish(incoming.getResponseTopic(),reply).waitForCompletion();
    }
}
