package com.rsmaxwell.diaries.responder.sync;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

    // The aggregate fixture deliberately exceeds the historical 10,000-message
    // queue threshold that exposed the startup defect. The Step 13 broker fixture now
    // uses max_queued_messages=0, while the responder still drains non-overlapping
    // branches sequentially so the large retained snapshot remains bounded and observable.
    private static final int TOPICS_PER_BRANCH = 1_400;
    private static final int TOPIC_COUNT = TOPICS_PER_BRANCH * Synchronise.SNAPSHOT_TOPIC_FILTERS.length;
    private static final int PAYLOAD_PADDING = 128;

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
        int ordinal=0;
        for(String filter:Synchronise.SNAPSHOT_TOPIC_FILTERS) {
            assertTrue(filter.endsWith("/#"));
            String root=filter.substring(0,filter.length()-1);
            for(int index=0;index<TOPICS_PER_BRANCH;index++) {
                long id=9_000_000L+ordinal++;
                String topic=root+id;
                String payload="{\"id\":"+id+",\"version\":0,\"text\":\""+padding+index+"\"}";
                result.put(topic,payload);
            }
        }
        assertEquals(TOPIC_COUNT,result.size());
        assertTrue(result.size()>10_000,"Fixture must exceed the historical 10,000-message queue threshold");
        return result;
    }

    private static Map<String,String> retainedSnapshot(String url,int expected) throws Exception {
        Map<String,String> result=new ConcurrentHashMap<>();
        AtomicInteger duplicates=new AtomicInteger();
        AtomicReference<String> invalidPublication=new AtomicReference<>();
        String barrierRoot="diaries-sync/test-verify/"+UUID.randomUUID()+"/";
        String barrierFilter=barrierRoot+"#";
        AtomicReference<String> pendingBarrierTopic=new AtomicReference<>();
        AtomicReference<CountDownLatch> pendingBarrierLatch=new AtomicReference<>();

        try(FixtureClient publisher=connect(url,false); FixtureClient client=connect(url,true)) {
            client.setCallback(new Adapter() {
                @Override public void messageArrived(String topic,MqttMessage message) {
                    if(topic.startsWith(barrierRoot)) {
                        CountDownLatch latch=pendingBarrierLatch.get();
                        if(latch!=null && topic.equals(pendingBarrierTopic.get()))latch.countDown();
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

            client.subscribe(new MqttSubscription(barrierFilter,SynchroniseCallback.BARRIER_QOS)).waitForCompletion(10_000);

            // Verify independently using the same branch-by-branch snapshot structure
            // as the responder. The broker fixture has no message-count queue ceiling.
            for(String filter:Synchronise.SNAPSHOT_TOPIC_FILTERS) {
                CountDownLatch drained=new CountDownLatch(1);
                String barrierTopic=barrierRoot+UUID.randomUUID();
                pendingBarrierTopic.set(barrierTopic);
                pendingBarrierLatch.set(drained);

                client.subscribe(new MqttSubscription(filter,SynchroniseCallback.SNAPSHOT_QOS)).waitForCompletion(10_000);
                publisher.publish(barrierTopic,new byte[]{1},SynchroniseCallback.BARRIER_QOS,false).waitForCompletion(10_000);

                assertTrue(drained.await(SynchroniseCallback.DEFAULT_DRAIN_TIMEOUT_MILLIS,TimeUnit.MILLISECONDS),
                        "Timed out waiting for retained-snapshot drain marker for "+filter+
                        " after receiving "+result.size()+" of "+expected+" retained messages");
            }

            pendingBarrierLatch.set(null);
            pendingBarrierTopic.set(null);
            assertNull(invalidPublication.get(),invalidPublication.get());
            assertEquals(0,duplicates.get(),"Duplicate retained Diaries publications");
            assertEquals(expected,result.size(),
                    "Independent retained snapshot was incomplete after its branch drain markers");
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
