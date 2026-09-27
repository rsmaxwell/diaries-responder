package com.rsmaxwell.diaries.responder.sync;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.eclipse.paho.mqttv5.client.MqttAsyncClient;
import org.eclipse.paho.mqttv5.client.MqttDisconnectResponse;
import org.eclipse.paho.mqttv5.common.MqttMessage;

import com.rsmaxwell.mqtt.rpc.common.Adapter;

/** Collects a temporary retained snapshot and acknowledges explicit stream-drain markers. */
public class SynchroniseCallback extends Adapter {
    static final long DEFAULT_DRAIN_TIMEOUT_MILLIS = 60_000L;
    static final int BARRIER_QOS = 1;
    static final int SNAPSHOT_QOS = 1;
    static final int SNAPSHOT_RECEIVE_MAXIMUM = 20;
    private static final String BARRIER_ROOT = "diaries-sync/";

    private final Map<String,String> topicMap;
    private final String barrierPrefix=BARRIER_ROOT+UUID.randomUUID()+"/";
    private record PendingBarrier(String topic,CountDownLatch latch) { }
    private volatile PendingBarrier barrier;
    private volatile boolean disconnected;

    public SynchroniseCallback(Map<String,String> topicMap) { this.topicMap=topicMap; }

    /**
     * Dedicated non-overlapping barrier namespace. Both the retained snapshot and the
     * barrier use QoS 1, but the barrier remains outside {@code diaries/#} so the same
     * marker is never delivered through overlapping subscriptions.
     */
    public String barrierFilter() { return barrierPrefix+"#"; }

    @Override public void messageArrived(String topic,MqttMessage message) {
        if(topic.startsWith(BARRIER_ROOT)) {
            PendingBarrier pending=barrier;
            if(pending!=null && topic.equals(pending.topic()))pending.latch().countDown();
            return;
        }
        byte[] payload=message.getPayload();
        if(payload==null || payload.length==0)topicMap.remove(topic);
        else topicMap.put(topic,new String(payload,StandardCharsets.UTF_8));
    }

    @Override public void disconnected(MqttDisconnectResponse response) {
        disconnected=true;
        PendingBarrier pending=barrier;
        if(pending!=null)pending.latch().countDown();
    }

    /**
     * Publish a non-retained QoS-1 marker and wait until the snapshot subscriber receives
     * it through the dedicated {@code diaries-sync/...} subscription. The retained
     * snapshot itself is also QoS 1. Mosquitto must therefore be configured with an
     * outgoing queue large enough for the complete retained tree plus the marker.
     *
     * <p>The snapshot connection advertises a Receive Maximum of
     * {@link #SNAPSHOT_RECEIVE_MAXIMUM}, which provides protocol-level pacing instead of
     * artificial sleeps. The marker publisher remains the separate reconciliation
     * publisher; after the snapshot SUBACK has completed Mosquitto has queued the retained
     * replay, so the marker is queued after it on the snapshot client's reliable stream.
     */
    public void awaitDrained(MqttAsyncClient markerPublisher) throws Exception {
        awaitDrained(topic -> markerPublisher.publish(topic,new byte[]{1},BARRIER_QOS,false)
                .waitForCompletion(10_000),DEFAULT_DRAIN_TIMEOUT_MILLIS);
    }

    void awaitDrained(BarrierPublisher publisher,long timeoutMillis) throws Exception {
        if(disconnected)throw new IllegalStateException("Snapshot connection was lost");
        PendingBarrier pending=new PendingBarrier(barrierPrefix+UUID.randomUUID(),new CountDownLatch(1));
        barrier=pending;
        try {
            publisher.publish(pending.topic());
            if(!pending.latch().await(timeoutMillis,TimeUnit.MILLISECONDS))throw new TimeoutException("Retained snapshot drain marker was not received");
            if(disconnected)throw new IllegalStateException("Snapshot connection was lost");
        } finally {
            if(barrier==pending)barrier=null;
        }
    }

    @FunctionalInterface interface BarrierPublisher { void publish(String topic) throws Exception; }
}
