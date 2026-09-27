package com.rsmaxwell.diaries.responder.handlers;

import static org.junit.jupiter.api.Assertions.*;
import java.lang.reflect.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.jupiter.api.*;
import org.eclipse.paho.mqttv5.client.*;
import org.eclipse.paho.mqttv5.client.persist.MemoryPersistence;
import org.eclipse.paho.mqttv5.common.packet.UserProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rsmaxwell.diaries.responder.config.Config;
import com.rsmaxwell.diaries.responder.dto.*;
import com.rsmaxwell.diaries.responder.model.*;
import com.rsmaxwell.diaries.responder.repository.*;
import com.rsmaxwell.diaries.responder.utilities.*;
import com.rsmaxwell.mqtt.rpc.exceptions.RpcStatusException;
import jakarta.persistence.*;

/** Handler-level contracts with strict in-memory seams; database races live in the integration suite. */
class FragmentLifecycleContractTest {
    final Map<Long, Fragment> rows = new HashMap<>();
    final Map<Long, Marquee> marquees = new HashMap<>();
    final Map<String, String> retained = new HashMap<>();
    final List<String> publications = new ArrayList<>();
    final Image shared = Image.builder().id(91L).relativePath("shared.png").mimeType("image/png")
            .originalFilename("shared.png").width(5).height(6).checksum("a".repeat(64)).build();
    final Page page = new Page();
    DiaryContext context;
    MqttAsyncClient publisher;
    boolean active;
    int commits, rollbacks, writes;

    @BeforeEach void setup() throws Exception {
        Diary diary = new Diary(); diary.setId(7L); page.setId(22L); page.setDiary(diary);
        EntityTransaction tx = proxy(EntityTransaction.class, (p, m, a) -> switch (m.getName()) {
            case "isActive" -> active;
            case "begin" -> { assertFalse(active); active = true; yield null; }
            case "commit" -> { assertTrue(active); active = false; commits++; yield null; }
            case "rollback" -> { assertTrue(active); active = false; rollbacks++; yield null; }
            default -> throw new AssertionError(m.getName());
        });
        EntityManager em = proxy(EntityManager.class, (p, m, a) -> switch (m.getName()) {
            case "getTransaction" -> tx;
            case "createNativeQuery" -> {
                assertTrue(active); assertTrue(((String) a[0]).contains("for update"));
                Long[] id = {null};
                yield proxy(TypedQuery.class, (q, method, args) -> switch (method.getName()) {
                    case "setParameter" -> { id[0] = (Long) args[1]; yield q; }
                    case "getResultList" -> rows.containsKey(id[0]) ? List.of(id[0]) : List.of();
                    default -> throw new AssertionError(method.getName());
                });
            }
            default -> throw new AssertionError(m.getName());
        });
        FragmentRepository fragments = proxy(FragmentRepository.class, (p, m, a) -> switch (m.getName()) {
            case "findById" -> Optional.ofNullable(rows.get(a[0])).map(this::dbDto);
            case "update" -> { assertTrue(active); Fragment f = (Fragment) a[0]; rows.put(f.getId(), f); writes++; yield 1; }
            default -> throw new AssertionError(m.getName());
        });
        MarqueeRepository marqueeRepository = proxy(MarqueeRepository.class, (p, m, a) -> switch (m.getName()) {
            case "findByFragment" -> Optional.ofNullable(marquees.get(((Fragment) a[0]).getId())).map(mq -> MarqueeDBDTO.builder().id(mq.getId()).build());
            case "delete" -> { assertTrue(active); writes++; yield marquees.remove(((Marquee) a[0]).getFragment().getId()) == null ? 0 : 1; }
            default -> throw new AssertionError("Unexpected Marquee operation: " + m.getName());
        });
        context = new DiaryContext() {
            @Override public Fragment inflateFragment(Long id) { return copy(rows.get(id)); }
            @Override public Fragment inflateFragment(FragmentDBDTO dto) { return copy(rows.get(dto.getId())); }
            @Override public Page inflatePage(Long id) { assertEquals(page.getId(), id); return page; }
            @Override public Marquee inflateMarquee(Long id) { return marquees.values().stream().filter(m -> m.getId().equals(id)).findFirst().orElseThrow(); }
            @Override public ResolvedFragmentState resolveFragmentState(Fragment f) { return new ResolvedFragmentState(f, marquees.get(f.getId())); }
            @Override public Integer deleteFragment(Fragment f) { assertTrue(active); writes++; return rows.remove(f.getId()) == null ? 0 : 1; }
            @Override public Image lockImageForFragmentWrite(Long id) {
                assertTrue(active);
                if (!shared.getId().equals(id)) throw new IllegalArgumentException("Image not found");
                return shared;
            }
        };
        context.setConfig(new Config()); context.getConfig().setImageFragmentWritesEnabled(true); context.setEntityManager(em);
        context.setFragmentRepository(fragments); context.setMarqueeRepository(marqueeRepository);
        context.setImageRepository(proxy(ImageRepository.class, (p, m, a) -> { throw new AssertionError("Fragment lifecycle must not write/delete Image: " + m.getName()); }));
        context.setSecret(Base64.getEncoder().encodeToString("01234567890123456789012345678901".getBytes(StandardCharsets.UTF_8)));
        publisher = new MqttAsyncClient("tcp://localhost:1883", "fragment-contract", new MemoryPersistence()) {
            @Override public IMqttToken publish(String topic, byte[] bytes, int qos, boolean retain) {
                assertFalse(active); assertTrue(commits > 0); assertEquals(1, qos); assertTrue(retain);
                publications.add(topic);
                if (bytes.length == 0) retained.remove(topic); else retained.put(topic, new String(bytes, StandardCharsets.UTF_8));
                return null;
            }
        };
        context.setPublisherClient(publisher);
        retained.put("diaries/images/91", new ImagePublishDTO(shared).toJson());
    }
    @AfterEach void close() throws Exception { publisher.close(); }
    Fragment copy(Fragment f) {
        Fragment result = new Fragment(new FragmentPublishDTO(f, null)); result.setPage(page);
        if (f.getImage() != null) result.setImage(f.getImage()); return result;
    }
    FragmentDBDTO dbDto(Fragment f) {
        return FragmentDBDTO.builder().id(f.getId()).version(f.getVersion()).pageId(f.getPageId()).type(f.getType())
                .imageId(f.getImageId()).lock(f.getLock()).year(f.getYear()).month(f.getMonth()).day(f.getDay()).sequence(f.getSequence()).text(f.getText()).build();
    }
    Fragment seed(long id, FragmentType type, boolean marquee) throws Exception {
        Fragment f = Fragment.builder().id(id).version(0L).page(page).type(type).image(type == FragmentType.IMAGE ? shared : null)
                .year(1830).month(3).day(8).sequence(BigDecimal.ONE).text("Entry").build();
        rows.put(id, f);
        if (marquee) marquees.put(id, Marquee.builder().id(id + 100).page(page).fragment(f).x(0d).y(0d).width(40d).height(40d).build());
        FragmentPublishDTO dto = new FragmentPublishDTO(f, marquees.get(id));
        retained.put("diaries/fragments/" + id, dto.toJson()); retained.put("diaries/dates/1830/3/8/" + id, dto.toJson());
        return f;
    }
    List<UserProperty> auth(long user, String session) {
        return List.of(new UserProperty("accessToken", Authorization.getTokenWithClaims(context.getSecret(), "access", 5,
                java.time.temporal.ChronoUnit.MINUTES, Map.of("role", "EDITOR", "status", "ACTIVE", "userId", user,
                        "sessionId", session, "username", "editor", "knownAs", "Editor"))));
    }
    @Test void sameLockOwnershipConflictAndUnlockContractForBothTypes() throws Exception {
        for (FragmentType type : FragmentType.values()) {
            long id = type == FragmentType.IMAGE ? 123L : 124L; seed(id, type, type == FragmentType.MARQUEE);
            var response = new LockFragment().handleRequest(context, Map.of("id", id), auth(42L, "mine"));
            assertEquals(200, response.status().code()); assertEquals(id, response.payload());
            assertTrue(rows.get(id).getLock().isLockedBy(42L, "mine"));
            var json = new ObjectMapper().readTree(retained.get("diaries/fragments/" + id));
            assertEquals(type.name(), json.get("type").textValue()); assertFalse(json.get("lock").isNull());
            int published = publications.size();
            for (var other : List.of(auth(43L, "other"), auth(42L, "other-session"))) {
                assertEquals(409, assertThrows(RpcStatusException.class, () -> new LockFragment().handleRequest(context, Map.of("id", id), other)).getStatus().code());
                assertEquals(409, assertThrows(RpcStatusException.class, () -> new UnlockFragment().handleRequest(context, Map.of("id", id), other)).getStatus().code());
                assertTrue(rows.get(id).getLock().isLockedBy(42L, "mine"));
            }
            assertEquals(published, publications.size());
            assertEquals(id, new UnlockFragment().handleRequest(context, Map.of("id", id), auth(42L, "mine")).payload());
            assertNull(rows.get(id).getLock()); assertEquals(type, rows.get(id).getType()); assertEquals(22L, rows.get(id).getPageId());
            assertEquals(type == FragmentType.IMAGE ? 91L : null, rows.get(id).getImageId());
            assertTrue(new ObjectMapper().readTree(retained.get("diaries/fragments/" + id)).get("lock").isNull());
        }
        assertTrue(publications.stream().allMatch(t -> t.startsWith("diaries/fragments/") || t.startsWith("diaries/dates/")));
    }
    @Test void deletingOneSharedReferenceLeavesOtherFragmentAndImageUntouched() throws Exception {
        seed(123L, FragmentType.IMAGE, false); seed(124L, FragmentType.IMAGE, false);
        String imageBefore = retained.get("diaries/images/91"), otherBefore = retained.get("diaries/fragments/124");
        String metadataBefore = new ImagePublishDTO(shared).toJson();
        var response = new DeleteFragment().handleRequest(context, Map.of("id", 123L), auth(42L, "mine"));
        assertEquals(200, response.status().code()); assertEquals(123L, response.payload());
        assertFalse(rows.containsKey(123L)); assertEquals(91L, rows.get(124L).getImageId());
        assertEquals(otherBefore, retained.get("diaries/fragments/124"));
        assertEquals(imageBefore, retained.get("diaries/images/91")); assertEquals(metadataBefore, new ImagePublishDTO(shared).toJson());
        assertEquals(Set.of("diaries/fragments/123", "diaries/dates/1830/3/8/123"), new HashSet<>(publications));
        assertFalse(retained.containsKey("diaries/fragments/123")); assertFalse(retained.containsKey("diaries/dates/1830/3/8/123"));
    }
    @Test void updateRejectsCrossTypeMutationsBeforeAnyWriteOrPublication() throws Exception {
        for (FragmentType type : FragmentType.values()) {
            Fragment row = seed(123L, type, false); row.setLock(LockInfo.lockedNow(42L, "editor", "Editor", "mine"));
            for (var change : List.of(Map.<String,Object>of("type", type == FragmentType.IMAGE ? "MARQUEE" : "IMAGE"),
                    Map.<String,Object>of("pageId", 23L), Map.<String,Object>of("imageId", 999L))) {
                var args = new HashMap<String,Object>(Map.of("id",123L,"version",0L,"year",1830,"month",3,"day",8,"sequence",1,"text","Edited"));
                args.putAll(change);
                int before = writes;
                assertEquals(400, assertThrows(RpcStatusException.class, () -> new UpdateFragment().handleRequest(context, args, auth(42L,"mine"))).getStatus().code());
                assertEquals(before, writes); assertSame(row, rows.get(123L)); assertTrue(publications.isEmpty()); assertFalse(active);
            }
        }
    }
    @Test void imageWithMarqueeCannotBeUpdatedOrDeletedThroughOrdinaryHandlers() throws Exception {
        Fragment row = seed(123L, FragmentType.IMAGE, true); row.setLock(LockInfo.lockedNow(42L,"editor","Editor","mine"));
        var args = Map.<String,Object>of("id",123L,"version",0L,"year",1830,"month",3,"day",8,"sequence",1,"text","Edited");
        assertEquals(400, assertThrows(RpcStatusException.class, () -> new UpdateFragment().handleRequest(context,args,auth(42L,"mine"))).getStatus().code());
        assertEquals(409, assertThrows(RpcStatusException.class, () -> new DeleteFragment().handleRequest(context,Map.of("id",123L),auth(42L,"mine"))).getStatus().code());
        assertEquals(0,writes); assertTrue(publications.isEmpty()); assertEquals(2,rollbacks); assertSame(row,rows.get(123L));
    }
    @Test void marqueeHandlersRejectImageShapeWithoutUnlockingOrPublishing() throws Exception {
        for (Long reference : new Long[] {null, 91L}) {
            Fragment row = seed(123L, FragmentType.IMAGE, false);
            if (reference == null) row.setImage(null);
            row.setLock(LockInfo.lockedNow(42L, "editor", "Editor", "mine"));
            var args = new HashMap<String,Object>(Map.of("pageId",22L,"fragmentId",123L,"x",0d,"y",0d,"width",40d,"height",40d));
            assertEquals(400, assertThrows(RpcStatusException.class, () -> new AddMarquee().handleRequest(context,args,auth(42L,"mine"))).getStatus().code());
            // An already-invalid legacy pair must not become editable or silently unlock the Fragment.
            marquees.put(123L, Marquee.builder().id(223L).page(page).fragment(row).x(0d).y(0d).width(40d).height(40d).build());
            args.put("id",223L); args.put("version",0L);
            assertEquals(400, assertThrows(RpcStatusException.class, () -> new UpdateMarquee().handleRequest(context,args,auth(42L,"mine"))).getStatus().code());
            assertEquals(0,writes); assertTrue(publications.isEmpty()); assertTrue(row.getLock().isLockedBy(42L,"mine"));
            assertEquals(reference,row.getImageId()); assertEquals(FragmentType.IMAGE,row.getType());
            marquees.clear();
        }
    }

    @SuppressWarnings("unchecked") static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler);
    }
}
