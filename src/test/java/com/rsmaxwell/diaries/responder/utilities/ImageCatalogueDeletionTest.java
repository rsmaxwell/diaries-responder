package com.rsmaxwell.diaries.responder.utilities;

import static org.junit.jupiter.api.Assertions.*;
import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import com.rsmaxwell.diaries.responder.model.Image;
import com.rsmaxwell.diaries.responder.utilities.ImageCatalogueService.*;

class ImageCatalogueDeletionTest {
    @TempDir Path root;
    Image row;
    final List<String> events = new ArrayList<>();
    boolean rollback, unknown, replacement, referenced, referencedAfterStaging;
    Catalogue store = new Catalogue() {
        public boolean owns(String path) { return row != null && row.getRelativePath().equalsIgnoreCase(path); }
        public Image insert(Image image) { throw new UnsupportedOperationException(); }
        public Optional<Image> find(String path) {
            events.add("lookup");
            return owns(path) ? Optional.of(row) : Optional.empty();
        }
        public void checkUnreferenced(Image expected) throws ImageReferencedException {
            if (referenced) throw new ImageReferencedException();
        }
        public void delete(Image expected) throws DeleteFailedException {
            assertFalse(Files.exists(root.resolve(expected.getRelativePath())));
            events.add("delete");
            if (referencedAfterStaging) throw new ImageReferencedException();
            if (replacement) {
                try { Files.writeString(root.resolve(expected.getRelativePath()), "external"); }
                catch (IOException e) { throw new DeleteFailedException(false, e); }
            }
            if (rollback || unknown) throw new DeleteFailedException(unknown, new IOException("injected"));
            row = null;
            events.add("commit");
        }
    };
    ImageCatalogueService service() throws IOException {
        return new ImageCatalogueService(new ImagePathPolicy(root), new ImageMetadataInspector(), store);
    }
    ImageCatalogueService service(ImageCatalogueService.DeletionFiles files) throws IOException {
        return new ImageCatalogueService(new ImagePathPolicy(root), new ImageMetadataInspector(), store, files);
    }
    Path seed() throws IOException {
        row = Image.builder().id(85L).relativePath("diary/images/Caf\u00e9.png")
            .mimeType("image/png").originalFilename("Caf\u00e9.png").width(1).height(1)
            .checksum("a".repeat(64)).build();
        Path target = root.resolve(row.getRelativePath());
        Files.createDirectories(target.getParent());
        return Files.writeString(target, "original");
    }
    void cleanStaging() throws IOException {
        try (var files = Files.list(root.resolve(".image-staging"))) {
            assertEquals(List.of("catalogue.lock"), files.map(p -> p.getFileName().toString()).toList());
        }
    }
    @Test void deleteCommitsThenPublishesWithStableIdentityAndRemovesBackup() throws Exception {
        Path target = seed();
        var result = service().delete("DIARY\\images\\CAFE\u0301.PNG", deleted -> {
            assertNull(row);
            assertFalse(Files.exists(target));
            try (var files = Files.list(root.resolve(".image-staging"))) {
                Path backup = files.filter(p -> p.toString().endsWith(".delete-backup")).findFirst().orElseThrow();
                assertEquals("original", Files.readString(backup));
            }
            events.add("tombstone");
            assertEquals(new DeletedImage(85L, "diary/images/Caf\u00e9.png"), deleted);
        });
        assertEquals(85L, result.id());
        assertEquals(List.of("lookup", "delete", "commit", "tombstone"), events);
        cleanStaging();
        assertThrows(ImageNotFoundException.class, () -> service().delete(result.relativePath(), d -> fail()));
    }
    @Test void notCataloguedLeavesFileAlone() throws Exception {
        Path target = Files.writeString(root.resolve("plain.png"), "unowned");
        assertThrows(ImageNotFoundException.class, () -> service().delete("plain.png", d -> fail()));
        assertEquals("unowned", Files.readString(target));
        assertEquals(List.of("lookup"), events);
    }
    @Test void missingOrDirectoryBytesConflictWithoutDeletingRow() throws Exception {
        Path target = seed();
        Files.delete(target);
        assertThrows(ImageFileConflictException.class, () -> service().delete(row.getRelativePath(), d -> fail()));
        Files.createDirectory(target);
        assertThrows(ImageFileConflictException.class, () -> service().delete(row.getRelativePath(), d -> fail()));
        assertNotNull(row);
        assertFalse(events.contains("delete"));
    }
    @Test void knownReferenceAvoidsStagingAndPublication() throws Exception {
        Path target = seed(); referenced = true;
        assertThrows(ImageReferencedException.class, () -> service().delete(row.getRelativePath(), d -> fail()));
        assertEquals("original", Files.readString(target));
        assertEquals(List.of("lookup"), events);
        assertNotNull(row); cleanStaging();
    }
    @Test void referenceAttachedAfterPrecheckRestoresBytesAsCleanConflict() throws Exception {
        Path target = seed(); referencedAfterStaging = true;
        assertThrows(ImageReferencedException.class, () -> service().delete(row.getRelativePath(), d -> fail()));
        assertEquals("original", Files.readString(target));
        assertNotNull(row); cleanStaging();
    }
    @Test void rollbackRestoresBytesWithoutPublication() throws Exception {
        Path target = seed(); rollback = true;
        var failure = assertThrows(DeleteFailedException.class, () -> service().delete(row.getRelativePath(), d -> fail()));
        assertFalse(failure.outcomeUnknown());
        assertEquals("original", Files.readString(target));
        assertNotNull(row); cleanStaging();
    }
    @Test void unknownCommitPreservesBackupWithoutRestoringOrPublishing() throws Exception {
        Path target = seed(); unknown = true;
        var failure = assertThrows(DeletionRecoveryRequiredException.class, () -> service().delete(row.getRelativePath(), d -> fail()));
        assertEquals(DeletionOutcome.UNKNOWN, failure.outcome());
        assertEquals(DeletionPhase.COMMITTING, failure.phase());
        assertFalse(Files.exists(target));
        assertEquals("original", Files.readString(failure.backup()));
        assertEquals(85L, failure.image().id());
    }
    @Test void restoreNeverOverwritesExternalReplacement() throws Exception {
        Path target = seed(); rollback = true; replacement = true;
        var failure = assertThrows(DeletionRecoveryRequiredException.class, () -> service().delete(row.getRelativePath(), d -> fail()));
        assertEquals(DeletionOutcome.ROLLED_BACK, failure.outcome());
        assertEquals("external", Files.readString(target));
        assertEquals("original", Files.readString(failure.backup()));
        assertNotNull(row);
    }
    @Test void failedTombstoneRetainsBackupAndCommittedOutcome() throws Exception {
        Path target = seed();
        var failure = assertThrows(DeletionRecoveryRequiredException.class, () -> service().delete(row.getRelativePath(), d -> { throw new IOException("broker"); }));
        assertEquals(DeletionOutcome.COMMITTED, failure.outcome());
        assertEquals(DeletionPhase.PUBLISHING, failure.phase());
        assertNull(row);
        assertFalse(Files.exists(target));
        assertEquals("original", Files.readString(failure.backup()));
    }
    @Test void invalidPathFailsBeforeLookup() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> service().delete("../bad.png", d -> fail()));
        assertTrue(events.isEmpty());
        assertFalse(Files.exists(root.resolve(".image-staging")));
    }
    @Test void stagingMoveFailurePreservesOriginalAndNeverTouchesDatabase() throws Exception {
        Path target = seed();
        var service = service(new ImageCatalogueService.DeletionFiles() {
            public void stage(Path target, Path backup) throws IOException { throw new IOException("injected move failure"); }
        });
        var failure = assertThrows(DeletionRecoveryRequiredException.class, () -> service.delete(row.getRelativePath(), d -> fail()));
        assertEquals(DeletionPhase.STAGING, failure.phase());
        assertEquals(DeletionOutcome.ROLLED_BACK, failure.outcome());
        assertEquals("original", Files.readString(target));
        assertTrue(Files.exists(failure.backup()));
        assertEquals(List.of("lookup"), events);
        assertNotNull(row);
    }
    @Test void failedMoveAfterMovingEmptyBytesDoesNotDiscardOnlyCopy() throws Exception {
        Path target = seed(); Files.writeString(target, "");
        var service = service(new ImageCatalogueService.DeletionFiles() {
            public void stage(Path target, Path backup) throws IOException {
                ImageCatalogueService.DeletionFiles.super.stage(target, backup);
                throw new IOException("provider reported failure after move");
            }
        });
        var failure = assertThrows(DeletionRecoveryRequiredException.class, () -> service.delete(row.getRelativePath(), d -> fail()));
        assertEquals(DeletionPhase.STAGING, failure.phase());
        assertFalse(Files.exists(target));
        assertEquals("", Files.readString(failure.backup()));
        assertEquals(List.of("lookup"), events);
        assertNotNull(row);
    }
    @Test void restoreFailurePreservesOriginalBackupAndRollbackDiagnostic() throws Exception {
        Path target = seed(); rollback = true;
        var service = service(new ImageCatalogueService.DeletionFiles() {
            public void restore(Path target, Path backup) throws IOException { throw new IOException("restore denied"); }
        });
        var failure = assertThrows(DeletionRecoveryRequiredException.class, () -> service.delete(row.getRelativePath(), d -> fail()));
        assertEquals(DeletionPhase.RESTORING, failure.phase());
        assertEquals(DeletionOutcome.ROLLED_BACK, failure.outcome());
        assertEquals("restore denied", failure.getCause().getSuppressed()[0].getMessage());
        assertFalse(Files.exists(target));
        assertEquals("original", Files.readString(failure.backup()));
        assertNotNull(row);
    }
    @Test void cleanupFailureAfterCommitKeepsBackupAndDoesNotResurrectImage() throws Exception {
        Path target = seed();
        var service = service(new ImageCatalogueService.DeletionFiles() {
            public void cleanup(Path backup) throws IOException { throw new IOException("cleanup denied"); }
        });
        var failure = assertThrows(DeletionRecoveryRequiredException.class,
            () -> service.delete(row.getRelativePath(), d -> events.add("tombstone")));
        assertEquals(DeletionPhase.CLEANUP, failure.phase());
        assertEquals(DeletionOutcome.COMMITTED, failure.outcome());
        assertEquals(85L, failure.image().id());
        assertEquals(target, failure.target());
        assertNull(row);
        assertFalse(Files.exists(target));
        assertEquals("original", Files.readString(failure.backup()));
        assertEquals(List.of("lookup", "delete", "commit", "tombstone"), events);
    }
    @Test void cleanupFailureAfterRollbackPreservesRestoredBytesAndBackup() throws Exception {
        Path target = seed(); rollback = true;
        var service = service(new ImageCatalogueService.DeletionFiles() {
            public void cleanup(Path backup) throws IOException { throw new IOException("cleanup denied"); }
        });
        var failure = assertThrows(DeletionRecoveryRequiredException.class, () -> service.delete(row.getRelativePath(), d -> fail()));
        assertEquals(DeletionOutcome.ROLLED_BACK, failure.outcome());
        assertEquals("original", Files.readString(target));
        assertTrue(Files.isSameFile(target, failure.backup()));
        assertEquals(DeletionPhase.CLEANUP, failure.phase());
        assertNotNull(row);
    }
    @Test void deletionWaitsForSharedReconciliationLock() throws Exception {
        seed();
        var service = service();
        var locked = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var attempted = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var holder = pool.submit(() -> service.withCatalogueLock(() -> {
                locked.countDown();
                if (!release.await(5, TimeUnit.SECONDS)) throw new IOException("test timeout");
                return null;
            }));
            try {
                assertTrue(locked.await(5, TimeUnit.SECONDS));
                var deletion = pool.submit(() -> {
                    attempted.countDown();
                    return service.delete("diary/images/Caf\u00e9.png", d -> {});
                });
                assertTrue(attempted.await(5, TimeUnit.SECONDS));
                assertThrows(TimeoutException.class, () -> deletion.get(100, TimeUnit.MILLISECONDS));
                assertTrue(events.isEmpty());
                release.countDown();
                holder.get(5, TimeUnit.SECONDS);
                assertEquals(85L, deletion.get(5, TimeUnit.SECONDS).id());
            } finally { release.countDown(); }
        }
    }
}
