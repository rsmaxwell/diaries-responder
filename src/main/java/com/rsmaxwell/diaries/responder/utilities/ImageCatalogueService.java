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
    /** Deliberately serialises file operations, including Unicode aliases, without approximating PostgreSQL folding. */
    private static final Object PROCESS_LOCK = new Object();
    private final ImagePathPolicy paths;
    private final ImageMetadataInspector inspector;
    private final Catalogue catalogue;
    private final Publication publisher;

    public interface Catalogue {
        boolean owns(String canonicalPath) throws Exception;
        /** Deletion must protect exact paths and slash-delimited descendants, even if bytes are missing. */
        default boolean ownsAtOrBelow(String canonicalPath) throws Exception {
            throw new IllegalStateException("Catalogue descendant guard is not configured");
        }
        /** Return only after commit; unknown outcomes must be explicitly distinguished. */
        Image insert(Image image) throws WriteFailedException;
    }
    @FunctionalInterface public interface Publication { void publish(ImagePublishDTO image) throws Exception; }

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

    /** Each call owns a fresh EntityManager, so concurrent requests never share one. */
    public static Catalogue jpaCatalogue(EntityManagerFactory factory) {
        return new Catalogue() {
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
