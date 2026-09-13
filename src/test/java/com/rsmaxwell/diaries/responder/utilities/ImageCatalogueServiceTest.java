package com.rsmaxwell.diaries.responder.utilities;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import com.rsmaxwell.diaries.responder.model.Image;
import com.rsmaxwell.diaries.responder.utilities.ImageCatalogueService.*;

class ImageCatalogueServiceTest {
    @TempDir Path root;
    final Map<String, Image> rows = new HashMap<>();
    final List<String> events = new ArrayList<>();

    Catalogue catalogue = new Catalogue() {
        public boolean owns(String path) { return rows.containsKey(path.toLowerCase(Locale.ROOT)); }
        public Image insert(Image image) throws WriteFailedException {
            try { assertTrue(Files.exists(root.resolve(image.getRelativePath()))); }
            catch (Exception failure) { throw new WriteFailedException(false, failure); }
            image.setId((long)rows.size() + 1);
            rows.put(image.getRelativePath().toLowerCase(Locale.ROOT), image);
            events.add("commit");
            return image;
        }
    };

    ImageCatalogueService service(Catalogue store, Publication publication) throws IOException {
        return new ImageCatalogueService(new ImagePathPolicy(root), new ImageMetadataInspector(), store, publication);
    }
    ImageCatalogueService service() throws IOException {
        return service(catalogue, dto -> {
            assertTrue(rows.containsKey(dto.getRelativePath().toLowerCase(Locale.ROOT)));
            events.add("publish");
        });
    }
    byte[] image() throws IOException {
        try (var in = getClass().getResourceAsStream("/image-inspection/sample.png")) { return in.readAllBytes(); }
    }
    ResolvedUpload stage(ImageCatalogueService service, String name) throws Exception {
        byte[] bytes = image();
        return service.stage(new ByteArrayInputStream(bytes), "", name, "application/octet-stream", bytes.length, null);
    }
    Catalogue failing(boolean unknown) {
        return new Catalogue() {
            public boolean owns(String path) { return false; }
            public Image insert(Image image) throws WriteFailedException {
                throw new WriteFailedException(unknown, new IOException("injected database failure"));
            }
        };
    }
    void noStagedBytes() throws IOException {
        try (var entries = Files.list(root.resolve(".image-staging"))) {
            assertTrue(entries.allMatch(p -> p.getFileName().toString().equals("catalogue.lock")));
        }
    }

    @Test void contentIdentityAndCommitPrecedePublication() throws Exception {
        var service = service();
        var upload = stage(service, "Caf\u00e9 50%_1.txt");
        assertFalse(Files.exists(upload.target()));
        Image saved = service.complete(upload, false).orElseThrow();
        assertEquals("image/png", saved.getMimeType());
        assertEquals("Caf\u00e9 50%_1.txt", saved.getRelativePath());
        assertEquals(16, saved.getWidth());
        assertArrayEquals(image(), Files.readAllBytes(upload.target()));
        assertEquals(List.of("commit", "publish"), events);
        noStagedBytes();
    }

    @Test void genericOctetStreamRemainsUncatalogued() throws Exception {
        var service = service();
        byte[] bytes = "plain data".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        var upload = service.stage(new ByteArrayInputStream(bytes), "nested", "data.bin", "application/octet-stream", bytes.length, null);
        assertTrue(service.complete(upload, false).isEmpty());
        assertArrayEquals(bytes, Files.readAllBytes(root.resolve("nested/data.bin")));
        assertTrue(rows.isEmpty());
        assertTrue(events.isEmpty());
        noStagedBytes();
    }

    @Test void rejectedStagingLeavesNoBytesOrDirectoriesAtTarget() throws Exception {
        var service = service();
        byte[] bytes = image();
        assertThrows(IOException.class, () -> service.stage(new ByteArrayInputStream(bytes), "new", "bad.png", "image/png", bytes.length + 1, null));
        assertThrows(IOException.class, () -> service.stage(new ByteArrayInputStream(bytes), "new", "bad.png", "image/png", bytes.length, "0".repeat(64)));
        assertThrows(IOException.class, () -> service.stage(new ByteArrayInputStream(bytes), "new", "bad.jpg", "image/jpeg", bytes.length, null));
        assertFalse(Files.exists(root.resolve("new")));
        noStagedBytes();
    }

    @Test void cataloguedPathIsProtectedEvenWithOverwriteAndMissingFile() throws Exception {
        var service = service();
        service.complete(stage(service, "image.png"), false);
        Files.delete(root.resolve("image.png"));
        assertThrows(java.nio.file.FileAlreadyExistsException.class, () -> service.complete(stage(service, "IMAGE.PNG"), true));
        assertFalse(Files.exists(root.resolve("IMAGE.PNG")));
        assertEquals(1, rows.size());
        assertEquals(2, events.size());
        noStagedBytes();
    }

    @Test void uncataloguedConflictAndSuccessfulOverwrite() throws Exception {
        Files.writeString(root.resolve("image.png"), "old");
        var service = service();
        assertThrows(java.nio.file.FileAlreadyExistsException.class, () -> service.complete(stage(service, "image.png"), false));
        assertEquals("old", Files.readString(root.resolve("image.png")));
        assertTrue(service.complete(stage(service, "image.png"), true).isPresent());
        assertArrayEquals(image(), Files.readAllBytes(root.resolve("image.png")));
        noStagedBytes();
    }

    @Test void definitiveRollbackRemovesNewFileAndRestoresOverwrittenBytes() throws Exception {
        var service = service(failing(false), dto -> fail("must not publish"));
        assertThrows(WriteFailedException.class, () -> service.complete(stage(service, "new.png"), false));
        assertFalse(Files.exists(root.resolve("new.png")));
        Files.writeString(root.resolve("old.png"), "original bytes");
        assertThrows(WriteFailedException.class, () -> service.complete(stage(service, "old.png"), true));
        assertEquals("original bytes", Files.readString(root.resolve("old.png")));
        noStagedBytes();
    }

    @Test void uncertainCommitPreservesPromotedFileAndBackupForRecovery() throws Exception {
        Files.writeString(root.resolve("old.png"), "original bytes");
        var service = service(failing(true), dto -> fail("must not publish"));
        var failure = assertThrows(RecoveryRequiredException.class, () -> service.complete(stage(service, "old.png"), true));
        assertArrayEquals(image(), Files.readAllBytes(failure.target()));
        assertEquals("original bytes", Files.readString(failure.backup()));
        try (var files = Files.list(root.resolve(".image-staging"))) {
            assertFalse(files.anyMatch(p -> p.toString().endsWith(".part")));
        }
    }

    @Test void publicationFailureRetainsCommittedImageAndProvidesReplayDto() throws Exception {
        var service = service(catalogue, dto -> { throw new IOException("broker unavailable"); });
        var failure = assertThrows(PublicationFailedException.class, () -> service.complete(stage(service, "image.png"), false));
        assertEquals(1, rows.size());
        assertEquals(rows.get("image.png").getId(), failure.committedImage().getId());
        assertArrayEquals(image(), Files.readAllBytes(root.resolve("image.png")));
        assertEquals(List.of("commit"), events);
        noStagedBytes();
    }

    @Test void compensationNeverDeletesAReplacementFile() throws Exception {
        var service = service(new Catalogue() {
            public boolean owns(String path) { return false; }
            public Image insert(Image image) throws WriteFailedException {
                try {
                    Files.delete(root.resolve(image.getRelativePath()));
                    Files.writeString(root.resolve(image.getRelativePath()), "external replacement");
                } catch (IOException failure) { throw new WriteFailedException(false, failure); }
                throw new WriteFailedException(false, new IOException("injected rollback"));
            }
        }, dto -> fail("must not publish"));
        Files.writeString(root.resolve("old.png"), "original bytes");
        var failure = assertThrows(RecoveryRequiredException.class, () -> service.complete(stage(service, "old.png"), true));
        assertEquals("external replacement", Files.readString(failure.target()));
        assertEquals("original bytes", Files.readString(failure.backup()));
    }

    @Test void simultaneousUploadsProduceOneCommittedWinner() throws Exception {
        var service = service();
        var first = stage(service, "same.png");
        var second = stage(service, "same.png");
        try (var pool = Executors.newFixedThreadPool(2)) {
            var futures = pool.invokeAll(List.of(
                () -> { try { service.complete(first, true); return true; } catch (java.nio.file.FileAlreadyExistsException expected) { return false; } },
                () -> { try { service.complete(second, true); return true; } catch (java.nio.file.FileAlreadyExistsException expected) { return false; } }));
            int winners = 0;
            for (var future : futures) if (Boolean.TRUE.equals(future.get())) winners++;
            assertEquals(1, winners);
        }
        assertEquals(1, rows.size());
        assertEquals(List.of("commit", "publish"), events);
        noStagedBytes();
    }
    @Test void deletionWaitsForUploadCommitAndCannotDeleteItsWinner() throws Exception {
        var promoted=new java.util.concurrent.CountDownLatch(1);
        var release=new java.util.concurrent.CountDownLatch(1);
        var firstGuard=new java.util.concurrent.CountDownLatch(1);
        var store=new Catalogue() {
            public boolean owns(String path) throws Exception { return catalogue.owns(path); }
            public boolean ownsAtOrBelow(String path) throws Exception {
                boolean result=owns(path); firstGuard.countDown(); return result;
            }
            public Image insert(Image image) throws WriteFailedException {
                promoted.countDown();
                try { if(!release.await(10,java.util.concurrent.TimeUnit.SECONDS)) throw new IOException("test gate timeout"); }
                catch(Exception failure) { throw new WriteFailedException(false,failure); }
                return catalogue.insert(image);
            }
        };
        var service=service(store,dto->events.add("publish"));
        var upload=stage(service,"winner.png");
        try(var pool=Executors.newFixedThreadPool(2)) {
            var writer=pool.submit(()->service.complete(upload,false));
            try {
                assertTrue(promoted.await(5,java.util.concurrent.TimeUnit.SECONDS));
                var deletion=pool.submit(()-> {
                    try { service.deleteUncatalogued("winner.png"); return null; }
                    catch(Exception failure) { return failure; }
                });
                assertTrue(firstGuard.await(5,java.util.concurrent.TimeUnit.SECONDS));
                release.countDown(); assertTrue(writer.get(10,java.util.concurrent.TimeUnit.SECONDS).isPresent());
                assertInstanceOf(java.nio.file.FileAlreadyExistsException.class,deletion.get(10,java.util.concurrent.TimeUnit.SECONDS));
            } finally { release.countDown(); }
        }
        assertArrayEquals(image(),Files.readAllBytes(root.resolve("winner.png")));
        assertEquals(1,rows.size()); assertEquals(List.of("commit","publish"),events); noStagedBytes();
    }

}
