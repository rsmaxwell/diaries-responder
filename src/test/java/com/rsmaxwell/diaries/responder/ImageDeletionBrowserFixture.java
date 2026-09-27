package com.rsmaxwell.diaries.responder;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import com.rsmaxwell.diaries.responder.utilities.*;
import com.rsmaxwell.diaries.responder.config.DiariesConfig;
import com.rsmaxwell.mqtt.rpc.common.Adapter;
import org.eclipse.paho.mqttv5.client.MqttAsyncClient;
import org.eclipse.paho.mqttv5.client.persist.MemoryPersistence;
import org.eclipse.paho.mqttv5.common.MqttMessage;

/** Opt-in browser fixture: real registered RPC handlers, disposable DB/broker/files only. */
final class ImageDeletionBrowserFixture {
  static void run(DiaryContext context, Path root) throws Exception {
    Path client = Path.of(System.getenv("DIARIES_BROWSER_TEST_CLIENT")).toAbsolutePath();
    Path evidence = Path.of(System.getenv("DIARIES_BROWSER_TEST_EVIDENCE")).toAbsolutePath();
    Path fixtureConfig = client.resolve("public/0030-step9-fixture.json");
    assertFalse(Files.exists(fixtureConfig), "Never overwrite existing fixture configuration");
    String broker = System.getenv("DIARIES_IMAGE_MQTT_TEST_URL");
    String websocket = System.getenv("DIARIES_BROWSER_TEST_MQTT");
    assertTrue(broker.matches("tcp://127\\.0\\.0\\.1:[0-9]+"));
    assertTrue(websocket.matches("ws://127\\.0\\.0\\.1:[0-9]+"));
    Path files = Files.createDirectories(root.resolve("files/step9/images"));
    javax.imageio.ImageIO.write(new java.awt.image.BufferedImage(12, 10, java.awt.image.BufferedImage.TYPE_INT_RGB), "png", files.resolve("uncatalogued.png").toFile());
    var config = new DiariesConfig(); config.setRoot(root.toString()); config.setFiles("files");
    context.getConfig().setDiaries(config);
    context.setSecret(Base64.getEncoder().encodeToString("01234567890123456789012345678901".getBytes()));
    String token = Authorization.getTokenWithClaims(context.getSecret(), "access", 10, java.time.temporal.ChronoUnit.MINUTES, Map.of("status","ACTIVE","role","EDITOR"));
    var mapper = new ObjectMapper();
    var server = HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/", exchange -> {
      try {
        exchange.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
        String path = exchange.getRequestURI().getPath();
        byte[] body;
        if (path.equals("/state")) {
          try (var em = context.getEntityManagerFactory().createEntityManager(); var stream = Files.list(files)) {
            var rows = em.createNativeQuery("SELECT id, relative_path FROM image ORDER BY id", Object[].class).getResultList();
            var names = stream.filter(Files::isRegularFile).map(p -> p.getFileName().toString()).sorted().toList();
            body = mapper.writeValueAsBytes(Map.of("rows", rows, "files", names));
          }
        } else if (path.equals("/evidence")) {
          body = exchange.getRequestBody().readAllBytes();
          Files.write(evidence.resolve("browser-checkpoints.json"), body);
          body = "{}".getBytes();
        } else {
          Path file = root.resolve(path.substring(1)).normalize();
          if (!file.startsWith(root) || !Files.isRegularFile(file)) { exchange.sendResponseHeaders(404, -1); return; }
          body = Files.readAllBytes(file);
          exchange.getResponseHeaders().add("Content-Type", "image/png");
        }
        exchange.sendResponseHeaders(200, body.length); exchange.getResponseBody().write(body);
      } catch (Exception failure) { exchange.sendResponseHeaders(500, -1); }
      finally { exchange.close(); }
    });
    var publisher = new MqttAsyncClient(broker, "step9-pub", new MemoryPersistence());
    var subscriber = new MqttAsyncClient(broker, "step9-rpc", new MemoryPersistence());
    Process browser = null;
    try {
      publisher.connect().waitForCompletion(10000);
      context.setPublisherClient(publisher);
      Responder.messageHandler.setContext(context); Responder.messageHandler.setPublisherClient(publisher);
      subscriber.setCallback(new Adapter() {
        public void messageArrived(String topic, MqttMessage message) throws Exception {
          Responder.messageHandler.messageArrived(topic, message);
        }
      });
      subscriber.connect().waitForCompletion(10000);
      subscriber.subscribe("diaries/rpc/request", 1).waitForCompletion(10000);
      server.start();
      Files.write(fixtureConfig, mapper.writeValueAsBytes(Map.of("baseUrl", "http://127.0.0.1:" + server.getAddress().getPort(), "broker", websocket, "token", token)));
      browser = new ProcessBuilder("cmd.exe", "/c", "npm.cmd", "test", "--", "--watch=false", "--browsers=ChromeHeadless", "--progress=false", "--include=src/app/files-list-dialog/files-list-dialog.e2e-spec.ts")
        .directory(client.toFile()).redirectErrorStream(true).redirectOutput(evidence.resolve("client-e2e.log").toFile()).start();
      assertTrue(browser.waitFor(120, TimeUnit.SECONDS), "Browser verification timed out");
      assertEquals(0, browser.exitValue(), "See client-e2e.log");
      assertTrue(Files.exists(evidence.resolve("browser-checkpoints.json")));
    } finally {
      if (browser != null && browser.isAlive()) browser.destroyForcibly();
      Files.deleteIfExists(fixtureConfig);
      server.stop(0);
      for (var mqtt : List.of(subscriber,publisher)) { if (mqtt.isConnected()) mqtt.disconnect().waitForCompletion(5000); mqtt.close(); }
      Responder.messageHandler.setContext(null); Responder.messageHandler.setPublisherClient(null);
    }
  }
}
