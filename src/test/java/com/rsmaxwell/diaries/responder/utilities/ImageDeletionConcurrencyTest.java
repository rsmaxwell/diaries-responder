package com.rsmaxwell.diaries.responder.utilities;

import static org.junit.jupiter.api.Assertions.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import com.rsmaxwell.diaries.responder.model.Image;
import com.rsmaxwell.diaries.responder.utilities.ImageCatalogueService.*;

class ImageDeletionConcurrencyTest {
    @TempDir Path root;
    Image row;
    final Map<Long,String> retained = new HashMap<>();
    final Catalogue catalogue = new Catalogue() {
        public boolean owns(String path) { return row != null && row.getRelativePath().equals(path); }
        public Optional<Image> find(String path) { return owns(path) ? Optional.of(row) : Optional.empty(); }
        public Image insert(Image image) { assertNull(row); image.setId(86L); row = image; return image; }
        public void delete(Image expected) { assertEquals(expected.getId(), row.getId()); row = null; }
    };
    byte[] bytes() throws IOException {
        try (var input = getClass().getResourceAsStream("/image-inspection/sample.png")) { return input.readAllBytes(); }
    }
    void seed() throws Exception {
        Files.write(root.resolve("same.png"), bytes());
        row = Image.builder().id(85L).relativePath("same.png").mimeType("image/png")
            .originalFilename("same.png").width(16).height(12).checksum("a".repeat(64)).build();
        retained.put(85L, "same.png");
    }
    void assertNoBackup() throws IOException {
        try (var files = Files.list(root.resolve(".image-staging"))) {
            assertEquals(List.of("catalogue.lock"), files.map(p -> p.getFileName().toString()).sorted().toList());
        }
    }
    static void await(CountDownLatch latch) throws Exception {
        assertTrue(latch.await(5, TimeUnit.SECONDS), "Worker timed out");
    }

    @ParameterizedTest @ValueSource(booleans = {true, false})
    void uploadAndDeleteSerializeThroughPublication(boolean deleteFirst) throws Exception {
        if (deleteFirst) seed();
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var attempted = new CountDownLatch(1);
        var uploadService = new ImageCatalogueService(new ImagePathPolicy(root), new ImageMetadataInspector(), catalogue, dto -> {
            if (!deleteFirst) { entered.countDown(); await(release); }
            retained.put(dto.getId(), dto.getRelativePath());
        });
        var deleteService = new ImageCatalogueService(new ImagePathPolicy(root), new ImageMetadataInspector(), catalogue);
        byte[] bytes = bytes();
        var staged = uploadService.stage(new ByteArrayInputStream(bytes), "", "same.png", "image/png", bytes.length, null);
        Callable<Object> upload = () -> uploadService.complete(staged, false);
        Callable<Object> delete = () -> deleteService.delete("same.png", image -> {
            if (deleteFirst) { entered.countDown(); await(release); }
            retained.remove(image.id());
        });
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(deleteFirst ? delete : upload);
            try {
                await(entered);
                var second = pool.submit(() -> { attempted.countDown(); return (deleteFirst ? upload : delete).call(); });
                await(attempted);
                assertThrows(TimeoutException.class, () -> second.get(100, TimeUnit.MILLISECONDS));
                release.countDown();
                first.get(5, TimeUnit.SECONDS); second.get(5, TimeUnit.SECONDS);
            } finally { release.countDown(); }
        }
        if (deleteFirst) {
            assertEquals(86L, row.getId());
            assertArrayEquals(bytes, Files.readAllBytes(root.resolve("same.png")));
            assertEquals(Map.of(86L, "same.png"), retained);
        } else {
            assertNull(row); assertFalse(Files.exists(root.resolve("same.png"))); assertTrue(retained.isEmpty());
        }
        assertNoBackup();
    }

    @Test void concurrentDuplicateDeletesCommitAndPublishOnlyOnce() throws Exception {
        seed();
        var entered = new CountDownLatch(1); var release = new CountDownLatch(1); var attempted = new CountDownLatch(1);
        var calls = new AtomicInteger();
        var service = new ImageCatalogueService(new ImagePathPolicy(root), new ImageMetadataInspector(), catalogue);
        Callable<DeletedImage> deletion = () -> service.delete("same.png", image -> {
            calls.incrementAndGet(); entered.countDown(); await(release); retained.remove(image.id());
        });
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(deletion);
            try {
                await(entered);
                var second = pool.submit(() -> { attempted.countDown(); return deletion.call(); });
                await(attempted);
                assertThrows(TimeoutException.class, () -> second.get(100, TimeUnit.MILLISECONDS));
                release.countDown();
                assertEquals(85L, first.get(5, TimeUnit.SECONDS).id());
                assertInstanceOf(ImageNotFoundException.class, assertThrows(ExecutionException.class, () -> second.get(5, TimeUnit.SECONDS)).getCause());
            } finally { release.countDown(); }
        }
        assertEquals(1, calls.get()); assertNull(row); assertTrue(retained.isEmpty());
        assertFalse(Files.exists(root.resolve("same.png"))); assertNoBackup();
    }
}
