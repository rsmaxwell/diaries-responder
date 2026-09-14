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
    private final Map<String,String> topicMap;
    private final String barrierPrefix="diaries/diaries/_sync/"+UUID.randomUUID()+"/";
    private record PendingBarrier(String topic,CountDownLatch latch) { }
    private volatile PendingBarrier barrier;
    private volatile boolean disconnected;

    public SynchroniseCallback(Map<String,String> topicMap) { this.topicMap=topicMap; }

    @Override public void messageArrived(String topic,MqttMessage message) {
        if(topic.startsWith(barrierPrefix)) {
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

    public void awaitDrained(MqttAsyncClient publisher) throws Exception {
        awaitDrained(topic->publisher.publish(topic,new byte[]{1},1,false).waitForCompletion(10000),30000);
    }

    @FunctionalInterface interface BarrierPublisher { void publish(String topic) throws Exception; }

    void awaitDrained(BarrierPublisher publisher,long timeoutMillis) throws Exception {
        if(disconnected)throw new IllegalStateException("Snapshot connection was lost");
        PendingBarrier pending=new PendingBarrier(barrierPrefix+UUID.randomUUID(),new CountDownLatch(1));
        barrier=pending;
        publisher.publish(pending.topic());
        if(!pending.latch().await(timeoutMillis,TimeUnit.MILLISECONDS))throw new TimeoutException("Retained snapshot drain marker was not received");
        if(disconnected)throw new IllegalStateException("Snapshot connection was lost");
    }
}
