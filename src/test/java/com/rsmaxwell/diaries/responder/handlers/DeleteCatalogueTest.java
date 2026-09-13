package com.rsmaxwell.diaries.responder.handlers;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.eclipse.paho.mqttv5.common.packet.UserProperty;
import com.rsmaxwell.diaries.responder.config.*;
import com.rsmaxwell.diaries.responder.utilities.*;
import com.rsmaxwell.mqtt.rpc.exceptions.RpcStatusException;

class DeleteCatalogueTest {
    @TempDir Path root;
    DiaryContext context;
    List<UserProperty> editor;
    final Set<String> owned=new HashSet<>();
    java.util.function.Predicate<String> guard=path -> owned.stream().anyMatch(p -> p.equals(path.toLowerCase(Locale.ROOT)) || p.startsWith(path.toLowerCase(Locale.ROOT)+"/"));
    @BeforeEach void setup() throws Exception {
        context=new DiaryContext();
        context.setSecret(Base64.getEncoder().encodeToString("01234567890123456789012345678901".getBytes()));
        var config=new Config(); var files=new DiariesConfig(); files.setRoot(root.toString()); files.setFiles("files");
        config.setDiaries(files); context.setConfig(config); Files.createDirectory(root.resolve("files"));
        editor=List.of(new UserProperty("accessToken",Authorization.getTokenWithClaims(context.getSecret(),"access",5,
            java.time.temporal.ChronoUnit.MINUTES,Map.of("status","ACTIVE","role","EDITOR"))));
    }
    DeleteFile handler() {
        return new DeleteFile() {
            @Override protected ImageCatalogueService.Catalogue deletionCatalogue(DiaryContext ignored) {
                return new ImageCatalogueService.Catalogue() {
                    public boolean owns(String p) { throw new AssertionError("must guard descendants"); }
                    public boolean ownsAtOrBelow(String p) { return guard.test(p); }
                    public com.rsmaxwell.diaries.responder.model.Image insert(com.rsmaxwell.diaries.responder.model.Image image) { throw new AssertionError("deletion must not write catalogue"); }
                };
            }
        };
    }
    void conflict(String subdir,String name) {
        var failure=assertThrows(RpcStatusException.class,()->handler().handleRequest(context,Map.of("subdir",subdir,"name",name),editor));
        assertEquals(409,failure.getStatus().code()); assertFalse(failure.getMessage().contains(root.toString()));
        assertTrue(failure.getMessage().contains("Image catalogue protects path:"));
    }
    @Test void protectsFilesAndParentsIncludingMissingBytesAndAliases() throws Exception {
        owned.add("maps/caf\u00e9 50%_!.png");
        Path directory=Files.createDirectory(root.resolve("files/maps")); Path file=Files.writeString(directory.resolve("caf\u00e9 50%_!.png"),"original");
        for(boolean missing:List.of(false,true)) {
            if(missing) Files.delete(file);
            conflict("maps","caf\u00e9 50%_!.png"); conflict("MAPS\\.","CAFE\u0301 50%_!.PNG");
            conflict("","maps"); conflict("","MAPS");
            if(!missing) assertEquals("original",Files.readString(file)); else assertFalse(Files.exists(file));
        }
        Files.delete(directory); conflict("","maps");
        assertFalse(Files.exists(directory)); assertEquals(Set.of("maps/caf\u00e9 50%_!.png"),owned);
    }
    @Test void missingUncataloguedPathIsIdempotentAndCreatesNoDirectory() throws Exception {
        for(int i=0;i<2;i++) {
            var reply=handler().handleRequest(context,Map.of("subdir","missing/deep","name","file.txt"),editor);
            var payload=(Map<?,?>)reply.payload(); assertEquals(Set.of("name","subdir","path"),payload.keySet());
            assertEquals("missing/deep",payload.get("subdir"));
        }
        try(var paths=Files.list(root.resolve("files"))) { assertEquals(0,paths.count()); }
    }
    @Test void unrelatedFilesAndEmptyDirectoriesCanBeDeletedButNotNonemptyDirectories() throws Exception {
        owned.add("maps/image.png");
        Files.writeString(root.resolve("files/maps-more"),"unrelated");
        handler().handleRequest(context,Map.of("name","maps-more"),editor); assertFalse(Files.exists(root.resolve("files/maps-more")));
        Files.createDirectory(root.resolve("files/empty")); handler().handleRequest(context,Map.of("name","empty"),editor);
        assertFalse(Files.exists(root.resolve("files/empty")));
        Files.createDirectory(root.resolve("files/nonempty")); Files.writeString(root.resolve("files/nonempty/keep"),"keep");
        var failure=assertThrows(RpcStatusException.class,()->handler().handleRequest(context,Map.of("name","nonempty"),editor));
        assertEquals(409,failure.getStatus().code()); assertEquals("keep",Files.readString(root.resolve("files/nonempty/keep")));
    }
    @Test void pathAttacksAndStagingAreRejected() throws Exception {
        for(String subdir:List.of("../escape","a/../escape","C:/escape","//host/share","https://host/path",".image-staging")) {
            assertThrows(RpcStatusException.class,()->handler().handleRequest(context,Map.of("subdir",subdir,"name","file"),editor));
        }
        for(String name:List.of("..",".","a/b","a\\b","C:escape"))
            assertThrows(RpcStatusException.class,()->handler().handleRequest(context,Map.of("name",name),editor));
        try(var paths=Files.list(root.resolve("files"))) { assertEquals(0,paths.count()); }
    }
    @Test void symlinkOrJunctionCannotDeleteExternalBytes(@TempDir Path outside) throws Exception {
        Files.writeString(outside.resolve("keep.txt"),"keep"); Path link=root.resolve("files/escape");
        try {
            try { Files.createSymbolicLink(link,outside); }
            catch(java.io.IOException unavailable) {
                if(!System.getProperty("os.name").startsWith("Windows")) throw unavailable;
                var process=new ProcessBuilder("cmd.exe","/c","mklink","/J",link.toString(),outside.toString()).redirectErrorStream(true).start();
                String output=new String(process.getInputStream().readAllBytes()); assertEquals(0,process.waitFor(),output);
            }
            assertThrows(RpcStatusException.class,()->handler().handleRequest(context,Map.of("subdir","escape","name","keep.txt"),editor));
            assertThrows(RpcStatusException.class,()->handler().handleRequest(context,Map.of("name","escape"),editor));
            assertEquals("keep",Files.readString(outside.resolve("keep.txt")));
        } finally { Files.deleteIfExists(link); }
    }
    @Test void ownershipIsRecheckedBeforeDeleting() throws Exception {
        Files.writeString(root.resolve("files/file.txt"),"keep");
        var calls=new java.util.concurrent.atomic.AtomicInteger(); guard=p -> calls.incrementAndGet()>=2;
        conflict("","file.txt"); assertEquals(2,calls.get()); assertEquals("keep",Files.readString(root.resolve("files/file.txt")));
    }
    @Test void unavailableCatalogueAndMissingAuthenticationFailClosed() throws Exception {
        Files.writeString(root.resolve("files/file.txt"),"keep");
        assertThrows(RpcStatusException.class,()->new DeleteFile().handleRequest(context,Map.of("name","file.txt"),editor));
        assertThrows(RpcStatusException.class,()->handler().handleRequest(context,Map.of("name","file.txt"),List.of()));
        guard=p -> {throw new IllegalStateException("database unavailable");};
        assertThrows(IllegalStateException.class,()->handler().handleRequest(context,Map.of("name","file.txt"),editor));
        assertEquals("keep",Files.readString(root.resolve("files/file.txt")));
    }
}
