import java.nio.file.*;
import com.rsmaxwell.diaries.responder.utilities.*;
import com.rsmaxwell.diaries.responder.model.Image;

/** Run against only the distribution fat JAR, on Windows or Linux; no application services needed. */
public class PackagedInspectionProbe {
    public static void main(String[] args) throws Exception {
        Path fixtures = Path.of(args[0]);
        var inspector = new ImageMetadataInspector();
        for (String name : new String[]{"sample.jpg", "sample.png", "sample.gif", "sample.webp", "lossless.webp", "animated.gif", "animated.webp"}) {
            var image = inspector.inspect(fixtures.resolve(name), "application/octet-stream", null).image().orElseThrow();
            if (image.width() != 16 || image.height() != 12) throw new AssertionError(name);
            System.out.println(name + " " + image.mimeType() + " " + image.width() + "x" + image.height());
        }
        Path root = Files.createTempDirectory("image-probe-");
        try {
            var paths = new ImagePathPolicy(root);
            if (!paths.canonicalPath("Maps\\.\\Cafe\u0301 50%_1.png").equals("Maps/Caf\u00e9 50%_1.png")) throw new AssertionError("canonical path");
            for (String path : new String[]{"../escape", "a/../escape", "C:\\escape", "/escape", "\\\\host\\share", "https://example/a"}) {
                try { paths.resolve(path); throw new AssertionError(path); }
                catch (IllegalArgumentException expected) { }
            }
            Files.createDirectory(root.resolve("Maps"));
            try { paths.resolve("maps/test.png"); throw new AssertionError("case alias"); }
            catch (java.io.IOException expected) { }
            if (!System.getProperty("os.name").startsWith("Windows")) {
                Files.createSymbolicLink(root.resolve("escape"), root.getParent());
                try { paths.resolve("escape/test"); throw new AssertionError("symlink escape"); }
                catch (java.io.IOException expected) { }
                Files.delete(root.resolve("escape"));
            }
            var catalogue = new ImageCatalogueService.Catalogue() {
                public boolean owns(String path) { return false; }
                public Image insert(Image image) throws ImageCatalogueService.WriteFailedException {
                    throw new ImageCatalogueService.WriteFailedException(false, new Exception("injected rollback"));
                }
            };
            var service = new ImageCatalogueService(paths, inspector, catalogue, dto -> { throw new AssertionError("publication after rollback"); });
            service.verifyStorageCapabilities();
            Files.writeString(root.resolve("image.png"), "original");
            Path fixture = fixtures.resolve("sample.png");
            var upload = service.stage(Files.newInputStream(fixture), "", "image.png", "image/png", Files.size(fixture), null);
            try { service.complete(upload, true); throw new AssertionError("insert should fail"); }
            catch (ImageCatalogueService.WriteFailedException expected) { }
            if (!Files.readString(root.resolve("image.png")).equals("original")) throw new AssertionError("backup restoration");
            System.out.println("Portable path, hard-link promotion, file locking and rollback checks passed on " + System.getProperty("os.name"));
        } finally {
            // Only this probe's freshly created temporary root; never follow symlinks.
            try (var files = Files.walk(root)) {
                for (Path file : files.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(file);
            }
        }
    }
}
