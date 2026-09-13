package com.rsmaxwell.diaries.responder;
import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.net.*;
import java.net.http.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import com.sun.net.httpserver.HttpServer;

class StagingHttpTest {
    @TempDir Path root;
    @Test void httpGetAndHeadCannotReadStagingIncludingEncodedAndCaseAliases() throws Exception {
        Files.createDirectory(root.resolve(".image-staging"));
        Files.writeString(root.resolve(".image-staging/private.part"),"private");
        Files.writeString(root.resolve("public.txt"),"public");
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/files",Responder.staticFileHandler("/files",root)); server.start();
        try(var client=HttpClient.newHttpClient()) {
            String base="http://127.0.0.1:"+server.getAddress().getPort()+"/files/";
            assertEquals(200,client.send(HttpRequest.newBuilder(URI.create(base+"public.txt")).build(),HttpResponse.BodyHandlers.discarding()).statusCode());
            for(String path:java.util.List.of(".image-staging/private.part","%2eimage-staging/private.part",".IMAGE-STAGING/private.part","a/../.image-staging/private.part",".image-staging%5cprivate.part"))
                for(String method:java.util.List.of("GET","HEAD")) {
                    var response=client.send(HttpRequest.newBuilder(URI.create(base+path)).method(method,HttpRequest.BodyPublishers.noBody()).build(),HttpResponse.BodyHandlers.ofString());
                    assertEquals(404,response.statusCode(),path); assertFalse(response.body().contains("private"));
                }
        } finally { server.stop(0); }
    }
}
