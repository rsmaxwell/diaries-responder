package com.rsmaxwell.diaries.responder.handlers;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import org.eclipse.paho.mqttv5.common.packet.UserProperty;
import com.rsmaxwell.diaries.responder.config.*;
import com.rsmaxwell.diaries.responder.model.Image;
import com.rsmaxwell.diaries.responder.utilities.*;
import com.rsmaxwell.mqtt.rpc.exceptions.RpcStatusException;

class DeleteImageTest {
    @TempDir Path root;
    DiaryContext context;
    Image row;
    boolean rollback, publicationFailure, referenced;
    final Map<String,String> retained = new HashMap<>();
    @BeforeEach void setup() throws Exception {
        context = new DiaryContext();
        context.setSecret(Base64.getEncoder().encodeToString("01234567890123456789012345678901".getBytes()));
        var config = new Config(); var files = new DiariesConfig();
        files.setRoot(root.toString()); files.setFiles("files"); config.setDiaries(files); context.setConfig(config);
        Files.createDirectory(root.resolve("files"));
        row = Image.builder().id(85L).relativePath("diary/images/image.png").mimeType("image/png")
            .originalFilename("image.png").width(1).height(1).checksum("a".repeat(64)).build();
        Files.createDirectories(root.resolve("files/diary/images"));
        Files.writeString(root.resolve("files/" + row.getRelativePath()), "original");
        retained.put("diaries/images/85", "metadata");
    }
    List<UserProperty> token(String role, String status, int minutes) {
        return List.of(new UserProperty("accessToken", Authorization.getTokenWithClaims(context.getSecret(), "access", minutes,
            java.time.temporal.ChronoUnit.MINUTES, Map.of("status", status, "role", role))));
    }
    Map<String,Object> args() { return Map.of("subdir", "diary/images", "name", "image.png"); }
    DeleteImage handler() {
        return new DeleteImage() {
            protected ImageCatalogueService.Catalogue deletionCatalogue(DiaryContext ignored) {
                return new ImageCatalogueService.Catalogue() {
                    public boolean owns(String path) { return row != null && path.equals(row.getRelativePath()); }
                    public Optional<Image> find(String path) { return owns(path) ? Optional.of(row) : Optional.empty(); }
                    public Image insert(Image image) { throw new AssertionError(); }
                    public void delete(Image image) throws ImageCatalogueService.DeleteFailedException {
                        if (referenced) throw new ImageCatalogueService.ImageReferencedException();
                        if (rollback) throw new ImageCatalogueService.DeleteFailedException(false, new Exception(root + "/private"));
                        row = null;
                    }
                };
            }
            protected ImageCatalogueService.TombstonePublication deletionPublication(DiaryContext ignored) {
                return image -> {
                    assertNull(row);
                    if (publicationFailure) throw new java.io.IOException(root + "/secret");
                    retained.remove("diaries/images/" + image.id());
                };
            }
        };
    }
    void status(int code, Map<String,Object> args, List<UserProperty> auth) {
        var failure = assertThrows(RpcStatusException.class, () -> handler().handleRequest(context, args, auth));
        assertEquals(code, failure.getStatus().code());
        assertFalse(failure.getMessage().contains(root.toString()));
    }
    @Test void successAndRepeatMatchContract() throws Exception {
        var response = handler().handleRequest(context, args(), token("EDITOR","ACTIVE",5));
        assertEquals(200, response.status().code());
        assertEquals(Map.of("id",85L,"relativePath","diary/images/image.png","deleted",true), response.payload());
        assertFalse(Files.exists(root.resolve("files/diary/images/image.png")));
        assertTrue(retained.isEmpty());
        status(404,args(),token("EDITOR","ACTIVE",5));
    }
    @Test void omittedSubdirMeansRoot() throws Exception {
        row.setRelativePath("root.png"); Files.writeString(root.resolve("files/root.png"),"root");
        var response=handler().handleRequest(context,Map.of("name","root.png"),token("EDITOR","ACTIVE",5));
        assertEquals("root.png",((Map<?,?>)response.payload()).get("relativePath"));
    }
    @Test void rejectsMissingInvalidExpiredInactiveAndViewerCredentialsBeforeFileWork() {
        for (var auth : List.of(List.<UserProperty>of(), List.of(new UserProperty("accessToken","malformed")),
                token("EDITOR","ACTIVE",-1), token("EDITOR","DISABLED",5), token("VIEWER","ACTIVE",5))) {
            status(401,args(),auth);
        }
        assertNotNull(row); assertFalse(Files.exists(root.resolve("files/.image-staging")));
    }
    @Test void rejectsMalformedArgumentsAndPaths() {
        var cases = new ArrayList<Map<String,Object>>();
        cases.add(null); cases.add(Map.of()); cases.add(Map.of("name",42)); cases.add(Map.of("name",""));
        var nullSubdir = new HashMap<String,Object>(args()); nullSubdir.put("subdir",null); cases.add(nullSubdir);
        for (String dir : List.of("../escape","/absolute","C:/escape",".image-staging")) cases.add(Map.of("name","image.png","subdir",dir));
        cases.add(Map.of("name","dir/image.png"));
        for (var args : cases) status(400,args,token("EDITOR","ACTIVE",5));
        assertNotNull(row); assertFalse(Files.exists(root.resolve("files/.image-staging")));
    }
    @Test void notCataloguedDoesNotDeleteGenericFile() throws Exception {
        row=null; status(404,args(),token("EDITOR","ACTIVE",5));
        assertEquals("original",Files.readString(root.resolve("files/diary/images/image.png")));
    }
    @Test void missingAndDirectoryTargetsConflict() throws Exception {
        var path=root.resolve("files/diary/images/image.png"); Files.delete(path);
        status(409,args(),token("EDITOR","ACTIVE",5)); Files.createDirectory(path);
        status(409,args(),token("EDITOR","ACTIVE",5)); assertNotNull(row);
    }
    @Test void referenceConflictIs409WithRestoredFileAndRetainedMetadata() throws Exception {
        referenced = true; status(409, args(), token("EDITOR", "ACTIVE", 5));
        assertEquals("original", Files.readString(root.resolve("files/diary/images/image.png")));
        assertNotNull(row); assertEquals("metadata", retained.get("diaries/images/85"));
    }
    @Test void rollbackAndPostCommitFailureAreSafe500Responses() throws Exception {
        rollback=true; status(500,args(),token("EDITOR","ACTIVE",5));
        assertEquals("original",Files.readString(root.resolve("files/diary/images/image.png")));
        rollback=false; publicationFailure=true; status(500,args(),token("EDITOR","ACTIVE",5));
        assertNull(row); assertFalse(retained.isEmpty());
        try(var files=Files.list(root.resolve("files/.image-staging"))) {
            assertTrue(files.anyMatch(p->p.toString().endsWith(".delete-backup")));
        }
    }
}
