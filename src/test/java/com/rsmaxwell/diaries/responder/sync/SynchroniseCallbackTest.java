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

    @Test void helperBarrierFilterIsStableSpecificAndAllMarkersRemainExcluded() throws Exception {
        var map=new ConcurrentHashMap<String,String>();
        var callback=new SynchroniseCallback(map);
        String filter=callback.barrierFilter();
        assertTrue(filter.startsWith("diaries-sync/"));
        assertTrue(filter.endsWith("/#"));
        assertFalse(filter.startsWith("diaries/"));
        assertEquals(1,SynchroniseCallback.SNAPSHOT_QOS);
        assertEquals(20,SynchroniseCallback.SNAPSHOT_RECEIVE_MAXIMUM);
        assertEquals(1,SynchroniseCallback.BARRIER_QOS);
        assertEquals(filter,callback.barrierFilter());

        var published=new java.util.concurrent.atomic.AtomicReference<String>();
        callback.awaitDrained(topic->{
            published.set(topic);
            assertTrue(topic.startsWith(filter.substring(0,filter.length()-1)));
            callback.messageArrived(topic,new MqttMessage(new byte[]{1}));
        },100);
        assertNotNull(published.get());

        callback.messageArrived("diaries-sync/another-responder/checkpoint",new MqttMessage(new byte[]{1}));
        assertTrue(map.isEmpty());
    }

    @Test void disconnectCannotMasqueradeAsCompletedReplay() {
        var callback=new SynchroniseCallback(new ConcurrentHashMap<>());
        assertThrows(IllegalStateException.class,()->callback.awaitDrained(topic->callback.disconnected(null),100));
        assertThrows(IllegalStateException.class,()->callback.awaitDrained(topic->{},100));
    }
    @Test void retainedSnapshotUsesNonOverlappingTopLevelBranches() {
        assertArrayEquals(new String[] {
                "diaries/diaries/#",
                "diaries/pages/#",
                "diaries/fragments/#",
                "diaries/marquees/#",
                "diaries/images/#",
                "diaries/dates/#",
                "diaries/people/#",
                "diaries/roles/#"
        },Synchronise.SNAPSHOT_TOPIC_FILTERS);

        for(int left=0;left<Synchronise.SNAPSHOT_TOPIC_FILTERS.length;left++) {
            String leftRoot=Synchronise.SNAPSHOT_TOPIC_FILTERS[left].substring(0,
                    Synchronise.SNAPSHOT_TOPIC_FILTERS[left].length()-1);
            for(int right=left+1;right<Synchronise.SNAPSHOT_TOPIC_FILTERS.length;right++) {
                String rightRoot=Synchronise.SNAPSHOT_TOPIC_FILTERS[right].substring(0,
                        Synchronise.SNAPSHOT_TOPIC_FILTERS[right].length()-1);
                assertFalse(leftRoot.startsWith(rightRoot) || rightRoot.startsWith(leftRoot),
                        "Snapshot branches must not overlap: "+leftRoot+" and "+rightRoot);
            }
        }
    }

}
