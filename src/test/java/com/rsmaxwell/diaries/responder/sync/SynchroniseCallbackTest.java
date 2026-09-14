package com.rsmaxwell.diaries.responder.sync;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeoutException;
import org.eclipse.paho.mqttv5.common.MqttMessage;
import org.junit.jupiter.api.Test;

class SynchroniseCallbackTest {
    @Test void drainMarkerIsExcludedAndEveryCheckpointNeedsItsOwnAcknowledgement() throws Exception {
        var map=new ConcurrentHashMap<String,String>();var callback=new SynchroniseCallback(map);
        var firstMarker=new java.util.concurrent.atomic.AtomicReference<String>();
        callback.awaitDrained(topic->{
            firstMarker.set(topic);
            callback.messageArrived("diaries/images/1",new MqttMessage("Caf\u00e9".getBytes(StandardCharsets.UTF_8)));
            callback.messageArrived(topic,new MqttMessage(new byte[]{1}));
        },100);
        assertEquals(java.util.Map.of("diaries/images/1","Caf\u00e9"),map);
        assertThrows(TimeoutException.class,()->callback.awaitDrained(topic->callback.messageArrived(firstMarker.get(),new MqttMessage(new byte[]{1})),1));
        callback.awaitDrained(topic->{
            callback.messageArrived("diaries/images/1",new MqttMessage(new byte[0]));
            callback.messageArrived(topic,new MqttMessage(new byte[]{1}));
        },100);
        assertTrue(map.isEmpty());
    }
    @Test void disconnectCannotMasqueradeAsCompletedReplay() {
        var callback=new SynchroniseCallback(new ConcurrentHashMap<>());
        assertThrows(IllegalStateException.class,()->callback.awaitDrained(topic->callback.disconnected(null),100));
        assertThrows(IllegalStateException.class,()->callback.awaitDrained(topic->{},100));
    }
}
