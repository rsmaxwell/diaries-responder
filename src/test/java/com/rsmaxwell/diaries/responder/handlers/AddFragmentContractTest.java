package com.rsmaxwell.diaries.responder.handlers;

import static org.junit.jupiter.api.Assertions.*;
import java.util.*;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.eclipse.paho.mqttv5.client.*;
import org.eclipse.paho.mqttv5.client.persist.MemoryPersistence;
import org.eclipse.paho.mqttv5.common.packet.UserProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rsmaxwell.diaries.responder.dto.FragmentPublishDTO;
import com.rsmaxwell.diaries.responder.model.*;
import com.rsmaxwell.diaries.responder.utilities.*;

class AddFragmentContractTest {
    @Test void legacyRequestStillCreatesMarqueeAndReturnsFragmentWithAdditiveImageId() throws Exception {
        Map<String,String> retained = new HashMap<>();
        boolean[] committed = {false};
        Diary diary = new Diary(); diary.setId(7L);
        Page page = new Page(); page.setId(22L); page.setDiary(diary);
        var publisher = new MqttAsyncClient("tcp://localhost:1883", "legacy-add-contract", new MemoryPersistence()) {
            @Override public IMqttToken publish(String topic, byte[] bytes, int qos, boolean retain) {
                assertTrue(committed[0], "Publish only after persistence returns");
                assertEquals(1, qos); assertTrue(retain);
                retained.put(topic, new String(bytes, StandardCharsets.UTF_8)); return null;
            }
        };
        try {
            DiaryContext context = new DiaryContext() {
                @Override public Page inflatePage(Long id) { assertEquals(22L, id); return page; }
                @Override public FragmentCreationResult saveMarqueeFragmentAndNormalise(Fragment fragment, Marquee marquee) {
                    assertEquals(FragmentType.MARQUEE, fragment.getType()); assertNull(fragment.getImageId());
                    assertSame(fragment, marquee.getFragment()); assertSame(page, marquee.getPage());
                    assertEquals(4, fragment.getSequence().scale());
                    assertEquals(12d, marquee.getX()); assertEquals(13d, marquee.getY());
                    assertEquals(40d, marquee.getWidth()); assertEquals(50d, marquee.getHeight());
                    var state = new ResolvedFragmentState(fragment, marquee); state.validateForWrite();
                    fragment.setId(123L); marquee.setId(44L); committed[0] = true;
                    return new FragmentCreationResult(state, List.of());
                }
                @Override public FragmentCreationResult saveImageFragmentAndNormalise(Fragment fragment) { throw new AssertionError("Wrong creation path"); }
            };
            context.setSecret(Base64.getEncoder().encodeToString("01234567890123456789012345678901".getBytes(StandardCharsets.UTF_8)));
            context.setPublisherClient(publisher);
            var auth = List.of(new UserProperty("accessToken", Authorization.getTokenWithClaims(context.getSecret(), "access", 5,
                    java.time.temporal.ChronoUnit.MINUTES, Map.of("role", "EDITOR", "status", "ACTIVE"))));
            var args = new HashMap<String,Object>(Map.of("pageId", 22, "year", 1830, "month", 3, "day", 8,
                    "sequence", 2.125, "text", "Entry", "x", 12, "y", 13, "width", 5, "height", 50));
            // Existing request works unchanged; extra new fields cannot turn addFragment into IMAGE creation.
            for (boolean injected : new boolean[] {false, true}) {
                if (injected) { args.put("type", "IMAGE"); args.put("imageId", 91L); }
                committed[0] = false; retained.clear();
                var response = new AddFragment().handleRequest(context, args, auth);
                assertEquals(200, response.status().code());
                var payload = (FragmentPublishDTO) response.payload();
                var json = new ObjectMapper().readTree(payload.toJson());
                Set<String> fields = new HashSet<>(); json.fieldNames().forEachRemaining(fields::add);
                assertEquals(Set.of("id", "version", "pageId", "type", "imageId", "year", "month", "day", "sequence", "text", "marqueeId", "lock"),
                        fields);
                assertEquals(123L, json.get("id").longValue()); assertEquals(44L, json.get("marqueeId").longValue());
                assertEquals(22L, json.get("pageId").longValue()); assertEquals("MARQUEE", json.get("type").textValue());
                assertTrue(json.has("imageId")); assertTrue(json.get("imageId").isNull());
                assertEquals(Set.of("diaries/fragments/123", "diaries/dates/1830/3/8/123",
                        "diaries/marquees/44", "diaries/diaries/7/22/44"), retained.keySet());
                assertEquals(payload.toJson(), retained.get("diaries/fragments/123"));
                assertEquals(payload.toJson(), retained.get("diaries/dates/1830/3/8/123"));
                assertEquals(retained.get("diaries/marquees/44"), retained.get("diaries/diaries/7/22/44"));
            }
        } finally { publisher.close(); }
    }
}
