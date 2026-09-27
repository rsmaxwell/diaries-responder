package com.rsmaxwell.diaries.responder.sync;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.paho.mqttv5.client.MqttAsyncClient;
import org.eclipse.paho.mqttv5.client.MqttConnectionOptions;
import org.eclipse.paho.mqttv5.client.persist.MemoryPersistence;
import org.eclipse.paho.mqttv5.common.MqttMessage;
import org.eclipse.paho.mqttv5.common.MqttSubscription;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import com.rsmaxwell.diaries.responder.config.Config;
import com.rsmaxwell.diaries.responder.config.User;
import com.rsmaxwell.diaries.responder.utilities.DiaryContext;
import com.rsmaxwell.mqtt.rpc.common.Adapter;

/**
 * Regression coverage for startup synchronisation when the broker already contains a
 * development-sized retained Diaries tree.
 *
 * <p>Run only against a fresh disposable Mosquitto broker because Synchronise owns the
 * complete {@code diaries/#} retained namespace and removes entries absent from the
 * database snapshot. The broker must use the synchronisation queue settings in
 * {@code config/mosquitto/mosquitto-large-tree-test.conf}.
 */
@EnabledIfEnvironmentVariable(named = "DIARIES_IMAGE_MQTT_TEST_URL", matches = ".+")
class SynchroniseLargeRetainedTreeMqttIntegrationTest {

    private static final int TOPIC_COUNT = 3_000;
    private static final int PAYLOAD_PADDING = 2_048;

    @Test
    @Timeout(value = 3, unit = TimeUnit.MINUTES)
    void repeatedSynchroniseCompletesAfterLargeRetainedReplayAndPreservesSnapshot() throws Exception {
        String url=System.getenv("DIARIES_IMAGE_MQTT_TEST_URL");
        assertTrue(url.matches("tcp://127\\.0\\.0\\.1:[0-9]+"));

        Config config=new Config();
        config.setNormaliseOnStartup(false);
        User user=new User();
        user.setUsername("diaries-responder");
        user.setPassword("phase4-fixture");

        Map<String,String> database=largeDatabaseSnapshot();
        DiaryContext context=new DiaryContext() {
            @Override public Map<String,String> loadFromDatabase() { return Map.copyOf(database); }
        };

        Synchronise synchronise=new Synchronise();

        // Establish (or reconcile) the large retained tree. The disposable broker may
        // already contain the same fixture tree from a previous diagnostic run.
        synchronise.perform(config,context,url,user);

        // The regression: subsequent startups must drain the already-large retained
        // replay reliably rather than losing the QoS-1 drain marker and timing out.
        synchronise.perform(config,context,url,user);
        synchronise.perform(config,context,url,user);

        assertEquals(database,retainedSnapshot(url,database.size()));
    }

    private static Map<String,String> largeDatabaseSnapshot() {
        Map<String,String> result=new HashMap<>();
        String padding="x".repeat(PAYLOAD_PADDING);
        for(int index=0;index<TOPIC_COUNT;index++) {
            long id=9_000_000L+index;
            String topic="diaries/fragments/"+id;
            String payload="{\"id\":"+id+",\"version\":0,\"text\":\""+padding+index+"\"}";
            result.put(topic,payload);
        }
        return result;
    }

    private static Map<String,String> retainedSnapshot(String url,int expected) throws Exception {
        Map<String,String> result=new ConcurrentHashMap<>();
        AtomicInteger duplicates=new AtomicInteger();
        AtomicReference<String> invalidPublication=new AtomicReference<>();
        CountDownLatch drained=new CountDownLatch(1);
        String barrierRoot="diaries-sync/test-verify/"+UUID.randomUUID()+"/";
        String barrierFilter=barrierRoot+"#";
        String barrierTopic=barrierRoot+UUID.randomUUID();

        try(FixtureClient publisher=connect(url,false); FixtureClient client=connect(url,true)) {
            client.setCallback(new Adapter() {
                @Override public void messageArrived(String topic,MqttMessage message) {
                    if(topic.equals(barrierTopic)) {
                        drained.countDown();
                        return;
                    }
                    if(topic.startsWith("diaries-sync/"))return;
                    if(!message.isRetained()) {
                        invalidPublication.compareAndSet(null,"Expected retained publication for "+topic);
                        return;
                    }
                    String previous=result.put(topic,new String(message.getPayload(),StandardCharsets.UTF_8));
                    if(previous!=null)duplicates.incrementAndGet();
                }
            });

            // Mirror the production drain protocol independently: retained snapshot and
            // non-overlapping barrier are both QoS 1. The snapshot client advertises a
            // Receive Maximum of 20, so Mosquitto paces the reliable replay while its
            // configured queue retains the complete tree. Only after both SUBACKs have
            // completed does the separate publisher send the barrier.
            client.subscribe(new MqttSubscription(barrierFilter,SynchroniseCallback.BARRIER_QOS)).waitForCompletion(10_000);
            client.subscribe(new MqttSubscription("diaries/#",SynchroniseCallback.SNAPSHOT_QOS)).waitForCompletion(10_000);
            publisher.publish(barrierTopic,new byte[]{1},SynchroniseCallback.BARRIER_QOS,false).waitForCompletion(10_000);

            assertTrue(drained.await(SynchroniseCallback.DEFAULT_DRAIN_TIMEOUT_MILLIS,TimeUnit.MILLISECONDS),
                    "Timed out waiting for independent retained-snapshot drain marker after receiving "+result.size()+" of "+expected+" retained messages");
            assertNull(invalidPublication.get(),invalidPublication.get());
            assertEquals(0,duplicates.get(),"Duplicate retained Diaries publications");
            assertEquals(expected,result.size(),
                    "Independent retained snapshot was incomplete after its drain marker");
            return Map.copyOf(result);
        }
    }

    private static FixtureClient connect(String url,boolean snapshot) throws Exception {
        FixtureClient client=new FixtureClient(url);
        MqttConnectionOptions options=new MqttConnectionOptions();
        options.setCleanStart(true);
        options.setUserName("diaries-responder");
        options.setPassword("phase4-fixture".getBytes(StandardCharsets.UTF_8));
        if(snapshot)options.setReceiveMaximum(SynchroniseCallback.SNAPSHOT_RECEIVE_MAXIMUM);
        client.connect(options).waitForCompletion(10_000);
        return client;
    }

    private static final class FixtureClient extends MqttAsyncClient implements AutoCloseable {
        FixtureClient(String url) throws org.eclipse.paho.mqttv5.common.MqttException {
            super(url,"sync-large-tree-"+UUID.randomUUID(),new MemoryPersistence());
        }

        @Override public void close() throws org.eclipse.paho.mqttv5.common.MqttException {
            try {
                if(isConnected())disconnectForcibly(0,1_000,false);
            } finally {
                super.close();
            }
        }
    }
}
