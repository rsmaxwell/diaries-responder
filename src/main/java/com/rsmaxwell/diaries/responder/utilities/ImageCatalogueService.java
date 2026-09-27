package com.rsmaxwell.diaries.responder.utilities;

import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Optional;

import com.rsmaxwell.diaries.responder.dto.ImagePublishDTO;
import com.rsmaxwell.diaries.responder.model.Image;
import com.rsmaxwell.diaries.responder.repositoryImpl.ImageRepositoryImpl;
import jakarta.persistence.EntityManagerFactory;

/** Same-filesystem staging, conflict checks, durable registration, compensation and post-commit publication. */
public final class ImageCatalogueService {
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(ImageCatalogueService.class);
    /** Deliberately serialises file operations, including Unicode aliases, without approximating PostgreSQL folding. */
    private static final Object PROCESS_LOCK = new Object();
    private final ImagePathPolicy paths;
    private final ImageMetadataInspector inspector;
    private final Catalogue catalogue;
    private final Publication publisher;
    private DeletionFiles deletionFiles = new DeletionFiles() { };

    /** Narrow package-private seam for deterministic filesystem failure tests. */
    interface DeletionFiles {
        default void stage(Path target, Path backup) throws IOException {
            Files.move(target, backup, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        }
        default void restore(Path target, Path backup) throws IOException {
            ImagePathPolicy.verifyEntry(backup);
            Files.createLink(target, backup); // Never replace a concurrently created target.
        }
        default void cleanup(Path backup) throws IOException {
            ImagePathPolicy.verifyEntry(backup);
            Files.delete(backup);
        }
    }

    public interface Catalogue {
        boolean owns(String canonicalPath) throws Exception;
        /** Deletion must protect exact paths and slash-delimited descendants, even if bytes are missing. */
        default boolean ownsAtOrBelow(String canonicalPath) throws Exception {
            throw new IllegalStateException("Catalogue descendant guard is not configured");
        }
        /** Return only after commit; unknown outcomes must be explicitly distinguished. */
        Image insert(Image image) throws WriteFailedException;
        default Optional<Image> find(String canonicalPath) throws Exception {
            throw new IllegalStateException("Catalogue Image lookup is not configured");
        }
        /** Return only after commit. Implementations must reject a changed/missing row. */
        default void delete(Image expected) throws DeleteFailedException {
            throw new DeleteFailedException(false, new IllegalStateException("Catalogue Image deletion is not configured"));
        }
    }
    @FunctionalInterface public interface Publication { void publish(ImagePublishDTO image) throws Exception; }
    @FunctionalInterface public interface TombstonePublication { void publish(DeletedImage image) throws Exception; }
    public record DeletedImage(long id, String relativePath) { }
    public static final class DeleteFailedException extends Exception {
        private final boolean outcomeUnknown;
        public DeleteFailedException(boolean outcomeUnknown, Throwable cause) {
            super(outcomeUnknown ? "Image deletion commit outcome is unknown" : "Image deletion rolled back", cause);
            this.outcomeUnknown = outcomeUnknown;
        }
        public boolean outcomeUnknown() { return outcomeUnknown; }
    }
    public static final class ImageNotFoundException extends Exception {
        ImageNotFoundException() { super("Image not found"); }
    }
    public static final class ImageFileConflictException extends IOException {
        ImageFileConflictException() { super("Image file is missing or is not a regular file"); }
    }
    public static final class InvalidImagePathException extends IOException {
        InvalidImagePathException(Throwable cause) { super("Invalid or inaccessible image path", cause); }
    }
    public enum DeletionOutcome { ROLLED_BACK, UNKNOWN, COMMITTED }
    public enum DeletionPhase { STAGING, COMMITTING, RESTORING, PUBLISHING, CLEANUP }
    /** Internal recovery information; handlers must not expose filesystem paths to clients. */
    public static final class DeletionRecoveryRequiredException extends IOException {
        private final DeletedImage image;
        private final Path target, backup;
        private final DeletionOutcome outcome;
        private final DeletionPhase phase;
        DeletionRecoveryRequiredException(DeletedImage image, Path target, Path backup, DeletionOutcome outcome, DeletionPhase phase, Throwable cause) {
            super("Image deletion requires recovery; retained backup must be reviewed", cause);
            this.image = image; this.target = target; this.backup = backup; this.outcome = outcome; this.phase = phase;
        }
        public DeletedImage image() { return image; }
        public Path target() { return target; }
        public Path backup() { return backup; }
        public DeletionOutcome outcome() { return outcome; }
        public DeletionPhase phase() { return phase; }
    }

    public static final class WriteFailedException extends Exception {
        private final boolean outcomeUnknown;
        public WriteFailedException(boolean outcomeUnknown, Throwable cause) {
            super(outcomeUnknown ? "Image commit outcome is unknown" : "Image insert rolled back", cause);
            this.outcomeUnknown = outcomeUnknown;
        }
        public boolean outcomeUnknown() { return outcomeUnknown; }
    }
    public static final class PublicationFailedException extends Exception {
        private final ImagePublishDTO committedImage;
        PublicationFailedException(Image committed, Throwable cause) {
            super("Image committed; retained publication needs replay", cause);
            committedImage = new ImagePublishDTO(committed);
        }
        public ImagePublishDTO committedImage() { return new ImagePublishDTO(new Image(committedImage)); }
    }
    public static final class RecoveryRequiredException extends IOException {
        private final Path target;
        private final Path backup;
        RecoveryRequiredException(Path target, Path backup, Throwable cause) {
            super("Image registration requires administrator recovery; files preserved", cause);
            this.target = target;
            this.backup = backup;
        }
        public Path target() { return target; }
        public Path backup() { return backup; }
    }

    public ImageCatalogueService(ImagePathPolicy paths, ImageMetadataInspector inspector, Catalogue catalogue, Publication publisher) {
        this.paths = java.util.Objects.requireNonNull(paths);
        this.inspector = java.util.Objects.requireNonNull(inspector);
        this.catalogue = java.util.Objects.requireNonNull(catalogue);
        this.publisher = java.util.Objects.requireNonNull(publisher);
    }

    /** Staging-only use during Phase 6.1; cannot accidentally register or publish an Image. */
    public ImageCatalogueService(ImagePathPolicy paths, ImageMetadataInspector inspector) {
        this.paths = java.util.Objects.requireNonNull(paths);
        this.inspector = java.util.Objects.requireNonNull(inspector);
        this.catalogue = null;
        this.publisher = null;
    }

    /** Guarded generic promotion before Phase 6.3 enables Image registration. */
    public ImageCatalogueService(ImagePathPolicy paths, ImageMetadataInspector inspector, Catalogue catalogue) {
        this.paths = java.util.Objects.requireNonNull(paths);
        this.inspector = java.util.Objects.requireNonNull(inspector);
        this.catalogue = java.util.Objects.requireNonNull(catalogue);
        this.publisher = null;
    }

    ImageCatalogueService(ImagePathPolicy paths, ImageMetadataInspector inspector, Catalogue catalogue, DeletionFiles deletionFiles) {
        this(paths, inspector, catalogue);
        this.deletionFiles = java.util.Objects.requireNonNull(deletionFiles);
    }

    /** Each call owns a fresh EntityManager, so concurrent requests never share one. */
    public static Catalogue jpaCatalogue(EntityManagerFactory factory) {
        return new Catalogue() {
            @Override public Optional<Image> find(String path) {
                try (var em = factory.createEntityManager()) {
                    return new ImageRepositoryImpl(em).findByRelativePath(path).map(Image::new);
                }
            }
            @Override public void delete(Image expected) throws DeleteFailedException {
                boolean commitStarted = false;
                try (var em = factory.createEntityManager()) {
                    var tx = em.getTransaction();
                    try {
                        tx.begin();
                        var current = em.find(Image.class, expected.getId(), jakarta.persistence.LockModeType.PESSIMISTIC_WRITE);
                        if (current == null || !new ImagePublishDTO(current).equals(new ImagePublishDTO(expected)))
                            throw new IllegalStateException("Image changed before deletion");
                        if (new ImageRepositoryImpl(em).deleteById(expected.getId()) != 1)
                            throw new IllegalStateException("Expected one deleted Image row");
                        commitStarted = true;
                        tx.commit();
                    } catch (Exception failure) {
                        try { if (tx.isActive()) tx.rollback(); }
                        catch (Exception rollback) { failure.addSuppressed(rollback); commitStarted = true; }
                        throw failure;
                    }
                } catch (Exception failure) { throw new DeleteFailedException(commitStarted, failure); }
            }
            @Override public boolean owns(String path) {
                try (var em = factory.createEntityManager()) { return new ImageRepositoryImpl(em).findByRelativePath(path).isPresent(); }
            }
            @Override public boolean ownsAtOrBelow(String path) {
                try (var em = factory.createEntityManager()) { return new ImageRepositoryImpl(em).existsAtOrBelow(path); }
            }
            @Override public Image insert(Image candidate) throws WriteFailedException {
                boolean commitStarted = false;
                try (var em = factory.createEntityManager()) {
                    var tx = em.getTransaction();
                    try {
                        tx.begin();
                        new ImageRepositoryImpl(em).save(candidate);
                        commitStarted = true;
                        tx.commit();
                        return candidate;
                    } catch (Exception failure) {
                        try { if (tx.isActive()) tx.rollback(); }
                        catch (Exception rollback) { failure.addSuppressed(rollback); commitStarted = true; }
                        throw failure;
                    }
                } catch (Exception failure) { throw new WriteFailedException(commitStarted, failure); }
            }
        };
    }

    /** Input is already decoded by the caller (e.g. a streaming base64 decoder); this method closes it. */
    public ResolvedUpload stage(InputStream decoded, String subdirectory, String name, String mime,
            long expectedSize, String expectedChecksum) throws Exception {
        Path temporary = null;
        try (decoded) {
            if (expectedSize < 0 || expectedSize > inspector.maxBytes()) throw new IOException("Declared size exceeds upload limit");
            String canonical = paths.uploadPath(subdirectory, name);
            Path target = paths.resolve(canonical);
            Path work = workDirectory();
            temporary = privateFile(work, ".part");
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            long size = 0;
            try (var out = Files.newOutputStream(temporary)) {
                byte[] buffer = new byte[65536];
                int count;
                while ((count = decoded.read(buffer)) != -1) {
                    size += count;
                    if (size > inspector.maxBytes()) throw new IOException("Decoded bytes exceed upload limit");
                    digest.update(buffer, 0, count);
                    out.write(buffer, 0, count);
                }
            }
            if (size != expectedSize) throw new IOException("Decoded length mismatch");
            String checksum = HexFormat.of().formatHex(digest.digest());
            if (expectedChecksum != null && (!expectedChecksum.matches("[0-9a-fA-F]{64}")
                    || !expectedChecksum.equalsIgnoreCase(checksum))) throw new IOException("SHA-256 mismatch");
            var inspection = inspector.inspectStaged(temporary, mime, checksum);
            return new ResolvedUpload(temporary, target, canonical, name, inspection);
        } catch (Exception failure) {
            if (temporary != null) try { Files.deleteIfExists(temporary); } catch (IOException cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }

    /** Exercise required filesystem primitives using private disposable files on the actual mount. */
    public void verifyStorageCapabilities() throws IOException {
        synchronized (PROCESS_LOCK) {
            Path work = workDirectory();
            Path source = privateFile(work, ".probe");
            Path link = source.resolveSibling(source.getFileName() + ".link");
            Path moved = source.resolveSibling(source.getFileName() + ".moved");
            try {
                try (var channel = FileChannel.open(source, StandardOpenOption.WRITE);
                        var lock = channel.tryLock()) {
                    if (lock == null) throw new IOException("Filesystem locking unavailable");
                }
                Files.createLink(link, source);
                if (!Files.isSameFile(source, link)) throw new IOException("Filesystem hard links unavailable");
                Files.move(link, moved, StandardCopyOption.ATOMIC_MOVE);
            } finally {
                Files.deleteIfExists(moved);
                Files.deleteIfExists(link);
                Files.deleteIfExists(source);
            }
        }
    }

    /** Supported images return their committed row; generic files return Optional.empty(). */
    public Optional<Image> complete(ResolvedUpload upload, boolean overwrite) throws Exception {
        if (publisher == null) throw new IllegalStateException("Catalogue publication is not configured");
        return complete(upload, overwrite, true);
    }

    public void promoteUncatalogued(ResolvedUpload upload, boolean overwrite) throws Exception {
        complete(upload, overwrite, false);
    }

    private Optional<Image> complete(ResolvedUpload upload, boolean overwrite, boolean registerImage) throws Exception {
        if (catalogue == null) throw new IllegalStateException("Catalogue completion is not configured");
        Exception primary = null;
        try {
            synchronized (PROCESS_LOCK) {
                Path work = workDirectory();
                verifyStaged(upload, work);
                Path lockPath = work.resolve("catalogue.lock");
                if (Files.exists(lockPath, LinkOption.NOFOLLOW_LINKS)) ImagePathPolicy.verifyEntry(lockPath);
                try (var channel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
                        var lock = channel.lock()) {
                    return completeLocked(upload, overwrite, work, registerImage);
                }
            }
        } catch (Exception failure) {
            primary = failure;
            throw failure;
        } finally {
            try { discard(upload); }
            catch (IOException cleanup) { if (primary != null) primary.addSuppressed(cleanup); else throw cleanup; }
        }
    }

    private Optional<Image> completeLocked(ResolvedUpload upload, boolean overwrite, Path work, boolean registerImage) throws Exception {
        if (catalogue.owns(upload.relativePath())) throw new java.nio.file.FileAlreadyExistsException(upload.relativePath(), null, "Path belongs to the Image catalogue");
        Path target = paths.resolve(upload.relativePath());
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)
                && (!overwrite || !Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)))
            throw new java.nio.file.FileAlreadyExistsException(upload.relativePath());
        target = paths.createParentDirectories(upload.relativePath());
        Path backup = null;
        boolean promoted = false;
        boolean committed = false;
        boolean preserve = false;
        Exception primary = null;
        try {
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                Path reserved = privateFile(work, ".backup");
                try { Files.move(target, reserved, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
                catch (IOException failure) {
                    try { Files.deleteIfExists(reserved); } catch (IOException cleanup) { failure.addSuppressed(cleanup); }
                    throw failure;
                }
                backup = reserved;
            }
            paths.resolve(upload.relativePath());
            // Atomic creation with no replacement. ATOMIC_MOVE alone can overwrite an existing target.
            Files.createLink(target, upload.stagedFile());
            promoted = true;
            Optional<Image> result = Optional.empty();
            if (registerImage && upload.inspection().image().isPresent()) {
                InspectedImage metadata = upload.inspection().image().orElseThrow();
                Image candidate = Image.builder().relativePath(upload.relativePath()).originalFilename(upload.originalFilename())
                        .mimeType(metadata.mimeType()).width(metadata.width()).height(metadata.height()).checksum(metadata.checksum()).build();
                candidate.validate();
                result = Optional.of(catalogue.insert(candidate));
            }
            committed = true;
            if (result.isPresent()) {
                try { publisher.publish(new ImagePublishDTO(result.orElseThrow())); }
                catch (Exception failure) { throw new PublicationFailedException(result.orElseThrow(), failure); }
            }
            return result;
        } catch (Exception failure) {
            primary = failure;
            if (!committed) {
                if (failure instanceof WriteFailedException write && write.outcomeUnknown()) {
                    preserve = true;
                    throw new RecoveryRequiredException(target, backup, failure);
                }
                try {
                    paths.resolve(upload.relativePath());
                    if (promoted) {
                        if (!Files.isSameFile(upload.stagedFile(), target)) throw new IOException("Promoted target identity changed");
                        Files.delete(target);
                    }
                    if (backup != null) {
                        Files.createLink(target, backup);
                        Files.delete(backup);
                        backup = null;
                    }
                } catch (Exception compensation) {
                    preserve = true;
                    failure.addSuppressed(compensation);
                    throw new RecoveryRequiredException(target, backup, failure);
                }
            }
            throw failure;
        } finally {
            if (backup != null && !preserve) {
                try { Files.deleteIfExists(backup); }
                catch (IOException cleanup) {
                    if (primary != null) primary.addSuppressed(cleanup);
                    else throw new RecoveryRequiredException(target, backup, cleanup);
                }
            }
        }
    }

    /** Serializes administrative reconciliation with upload promotion and generic deletion. */
    public <T> T withCatalogueLock(java.util.concurrent.Callable<T> action) throws Exception {
        synchronized (PROCESS_LOCK) {
            Path work = workDirectory();
            Path lockPath = work.resolve("catalogue.lock");
            if (Files.exists(lockPath, LinkOption.NOFOLLOW_LINKS)) ImagePathPolicy.verifyEntry(lockPath);
            try (var channel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
                    var lock = channel.lock()) { return action.call(); }
        }
    }

    /** Non-recursive generic deletion, serialized with upload promotion and catalogue commit. */
    public Path deleteUncatalogued(String input) throws Exception {
        if (catalogue == null) throw new IllegalStateException("Catalogue deletion guard is not configured");
        String canonical = paths.canonicalPath(input);
        // Database identity comes before resolving case aliases or treating missing bytes as absent.
        if (catalogue.ownsAtOrBelow(canonical)) throw new java.nio.file.FileAlreadyExistsException(canonical);
        Path target = paths.resolve(canonical);
        if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) return target;
        synchronized (PROCESS_LOCK) {
            Path work = workDirectory();
            Path lockPath = work.resolve("catalogue.lock");
            if (Files.exists(lockPath, LinkOption.NOFOLLOW_LINKS)) ImagePathPolicy.verifyEntry(lockPath);
            try (var channel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
                    var lock = channel.lock()) {
                if (catalogue.ownsAtOrBelow(canonical)) throw new java.nio.file.FileAlreadyExistsException(canonical);
                target = paths.resolve(canonical);
                Files.deleteIfExists(target);
                return target;
            }
        }
    }

    /** Semantic deletion; the tombstone callback runs under the catalogue lock after commit. */
    public DeletedImage delete(String input, TombstonePublication tombstone) throws Exception {
        if (catalogue == null) throw new IllegalStateException("Catalogue deletion is not configured");
        java.util.Objects.requireNonNull(tombstone, "tombstone");
        String canonical = paths.canonicalPath(input);
        return withCatalogueLock(() -> {
            Image stored = catalogue.find(canonical).orElseThrow(ImageNotFoundException::new);
            Image expected = new Image(new ImagePublishDTO(stored));
            if (expected.getId() == null || expected.getId() <= 0) throw new IllegalStateException("Invalid catalogue identity");
            DeletedImage deleted = new DeletedImage(expected.getId(), expected.getRelativePath());
            // Resolve the stored spelling after the database has resolved path identity.
            Path target;
            try { target = paths.resolve(expected.getRelativePath()); }
            catch (IOException | IllegalArgumentException failure) { throw new InvalidImagePathException(failure); }
            if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) throw new ImageFileConflictException();
            Path backup = privateFile(workDirectory(), ".delete-backup");
            try { deletionFiles.stage(target, backup); }
            catch (IOException failure) {
                // A provider may throw after moving bytes. Preserve both paths, including empty files.
                throw deletionRecovery(deleted, target, backup, DeletionOutcome.ROLLED_BACK, DeletionPhase.STAGING, failure);
            }
            try { catalogue.delete(expected); }
            catch (Exception failure) {
                if (!(failure instanceof DeleteFailedException known) || known.outcomeUnknown())
                    throw deletionRecovery(deleted, target, backup, DeletionOutcome.UNKNOWN, DeletionPhase.COMMITTING, failure);
                try {
                    paths.resolve(expected.getRelativePath());
                    // No replacement: an external file must never be overwritten during compensation.
                    deletionFiles.restore(target, backup);
                } catch (Exception restore) {
                    failure.addSuppressed(restore);
                    throw deletionRecovery(deleted, target, backup, DeletionOutcome.ROLLED_BACK, DeletionPhase.RESTORING, failure);
                }
                try { deletionFiles.cleanup(backup); }
                catch (Exception cleanup) {
                    failure.addSuppressed(cleanup);
                    throw deletionRecovery(deleted, target, backup, DeletionOutcome.ROLLED_BACK, DeletionPhase.CLEANUP, failure);
                }
                throw failure;
            }
            try {
                tombstone.publish(deleted);
            } catch (Exception failure) {
                throw deletionRecovery(deleted, target, backup, DeletionOutcome.COMMITTED, DeletionPhase.PUBLISHING, failure);
            }
            try { deletionFiles.cleanup(backup); }
            catch (Exception failure) {
                throw deletionRecovery(deleted, target, backup, DeletionOutcome.COMMITTED, DeletionPhase.CLEANUP, failure);
            }
            return deleted;
        });
    }

    private DeletionRecoveryRequiredException deletionRecovery(DeletedImage image, Path target, Path backup,
            DeletionOutcome outcome, DeletionPhase phase, Throwable failure) {
        log.error("Image deletion recovery required: id={}, relativePath={}, databaseOutcome={}, phase={}, target={}, backup={}",
                image.id(), image.relativePath(), outcome, phase, target, backup, failure);
        return new DeletionRecoveryRequiredException(image, target, backup, outcome, phase, failure);
    }

    public void discard(ResolvedUpload upload) throws IOException {
        Path work = workDirectory();
        if (!upload.stagedFile().getParent().equals(work)) throw new IOException("Foreign staging file");
        Files.deleteIfExists(upload.stagedFile());
    }

    private void verifyStaged(ResolvedUpload upload, Path work) throws IOException {
        if (!upload.stagedFile().getParent().equals(work) || !upload.target().equals(paths.resolve(upload.relativePath())))
            throw new IOException("Foreign upload result");
        ImagePathPolicy.verifyEntry(upload.stagedFile());
        if (!Files.isRegularFile(upload.stagedFile()) || Files.size(upload.stagedFile()) != upload.inspection().size())
            throw new IOException("Staging file changed");
    }

    private Path workDirectory() throws IOException {
        synchronized (PROCESS_LOCK) {
            paths.verifyRoot();
            Path work = paths.root().resolve(ImagePathPolicy.STAGING);
            if (!Files.exists(work, LinkOption.NOFOLLOW_LINKS)) {
                try {
                    if (Files.getFileStore(paths.root()).supportsFileAttributeView("posix"))
                        Files.createDirectory(work, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
                    else Files.createDirectory(work);
                } catch (java.nio.file.FileAlreadyExistsException concurrentCreation) { /* Validate below. */ }
            }
            ImagePathPolicy.verifyEntry(work);
            if (!Files.isDirectory(work, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Staging area is not a directory");
            if (Files.getFileStore(work).supportsFileAttributeView("posix")
                    && !PosixFilePermissions.toString(Files.getPosixFilePermissions(work)).equals("rwx------"))
                throw new IOException("Staging directory requires owner-only permissions");
            return work;
        }
    }

    private static Path privateFile(Path work, String suffix) throws IOException {
        if (Files.getFileStore(work).supportsFileAttributeView("posix"))
            return Files.createTempFile(work, "upload-", suffix, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        return Files.createTempFile(work, "upload-", suffix);
    }
}
