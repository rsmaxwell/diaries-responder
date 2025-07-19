package com.rsmaxwell.diaries.response;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.Options;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.paho.mqttv5.client.MqttAsyncClient;
import org.eclipse.paho.mqttv5.client.MqttClientPersistence;
import org.eclipse.paho.mqttv5.client.MqttConnectionOptions;
import org.eclipse.paho.mqttv5.client.persist.MemoryPersistence;
import org.eclipse.paho.mqttv5.common.MqttException;

import com.rsmaxwell.diaries.common.config.Config;
import com.rsmaxwell.diaries.common.config.DbConfig;
import com.rsmaxwell.diaries.common.config.DiariesConfig;
import com.rsmaxwell.diaries.common.config.MqttConfig;
import com.rsmaxwell.diaries.common.config.User;
import com.rsmaxwell.diaries.response.handlers.AddMarquee;
import com.rsmaxwell.diaries.response.handlers.DeleteFragment;
import com.rsmaxwell.diaries.response.handlers.DeleteMarquee;
import com.rsmaxwell.diaries.response.handlers.NormaliseDiaries;
import com.rsmaxwell.diaries.response.handlers.NormaliseFragments;
import com.rsmaxwell.diaries.response.handlers.NormalisePages;
import com.rsmaxwell.diaries.response.handlers.Quit;
import com.rsmaxwell.diaries.response.handlers.RefreshToken;
import com.rsmaxwell.diaries.response.handlers.Register;
import com.rsmaxwell.diaries.response.handlers.Signin;
import com.rsmaxwell.diaries.response.handlers.UpdateDiary;
import com.rsmaxwell.diaries.response.handlers.UpdateMarquee;
import com.rsmaxwell.diaries.response.handlers.UpdatePage;
import com.rsmaxwell.diaries.response.repository.DiaryRepository;
import com.rsmaxwell.diaries.response.repository.FragmentRepository;
import com.rsmaxwell.diaries.response.repository.MarqueeRepository;
import com.rsmaxwell.diaries.response.repository.PageRepository;
import com.rsmaxwell.diaries.response.repository.PersonRepository;
import com.rsmaxwell.diaries.response.repositoryImpl.DiaryRepositoryImpl;
import com.rsmaxwell.diaries.response.repositoryImpl.FragmentRepositoryImpl;
import com.rsmaxwell.diaries.response.repositoryImpl.MarqueeRepositoryImpl;
import com.rsmaxwell.diaries.response.repositoryImpl.PageRepositoryImpl;
import com.rsmaxwell.diaries.response.repositoryImpl.PersonRepositoryImpl;
import com.rsmaxwell.diaries.response.sync.Synchronise;
import com.rsmaxwell.diaries.response.utilities.DiaryContext;
import com.rsmaxwell.diaries.response.utilities.GetEntityManager;
import com.rsmaxwell.diaries.response.utilities.MyMessageHandler;
import com.rsmaxwell.mqtt.rpc.response.MessageHandler;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;

public class Responder {

	private static final Logger log = LogManager.getLogger(Responder.class);

	static final String clientID_publisher = "responder";
	static final String clientID_listener = "listener";
	static final int qos = 0;
	static MessageHandler messageHandler = new MessageHandler();
	static MyMessageHandler myMessageHandler = new MyMessageHandler(messageHandler);

	static {
		messageHandler.putHandler("register", new Register());
		messageHandler.putHandler("signin", new Signin());
		messageHandler.putHandler("refreshToken", new RefreshToken());
		messageHandler.putHandler("normaliseDiaries", new NormaliseDiaries());
		messageHandler.putHandler("normalisePages", new NormalisePages());
		messageHandler.putHandler("normaliseFragments", new NormaliseFragments());
		messageHandler.putHandler("updatePage", new UpdatePage());
		messageHandler.putHandler("updateDiary", new UpdateDiary());
		messageHandler.putHandler("addMarquee", new AddMarquee());
		messageHandler.putHandler("updateMarquee", new UpdateMarquee());
		messageHandler.putHandler("deleteFragment", new DeleteFragment());
		messageHandler.putHandler("deleteMarquee", new DeleteMarquee());
		messageHandler.putHandler("quit", new Quit());
	}

	static Option createOption(String shortName, String longName, String argName, String description, boolean required) {
		return Option.builder(shortName).longOpt(longName).argName(argName).desc(description).hasArg().required(required).build();
	}

	public static void main(String[] args) throws Exception {

		Options options = new Options();
		options.addOption(createOption("c", "config", "Configuration", "Configuration", true));

		CommandLineParser commandLineParser = new DefaultParser();
		CommandLine commandLine = commandLineParser.parse(options, args);

		String filename = commandLine.getOptionValue("config");
		Config config = Config.read(filename);

		startFileServer(config);

		try {
			Responder responder = new Responder();
			responder.run(config);

		} catch (MqttException e) {
			Throwable cause = e.getCause();
			if (cause instanceof ConnectException) {
				System.err.println("Unable to connect to MQTT broker at: " + config.getMqtt().getServer());
				System.err.println("Possible reasons:");
				System.err.println(" - Mosquitto is not running");
				System.err.println(" - Another instance of the Responder is already running");
				System.err.println(" - Port " + config.getMqtt().getPort() + " is blocked or unavailable");
			} else {
				System.err.println("MQTT Exception occurred: " + e.getMessage());
				e.printStackTrace();
			}
			System.exit(1);

		} catch (Exception e) {
			System.err.println("Unexpected error: " + e.getMessage());
			e.printStackTrace();
			System.exit(2);
		}
	}

	// This will serve files like {baseurl}/{diary-name}/{page-name}.jpg
	// e.g. http://localhost:8081/images/diary-1837/img2556.jpg
	static void startFileServer(Config config) throws IOException {

		DiariesConfig diariesConfig = config.getDiaries();

		Path directory = Path.of(diariesConfig.getOriginal());
		HttpServer server = HttpServer.create(new InetSocketAddress(8081), 0);

		server.createContext("/images", new HttpHandler() {
			@Override
			public void handle(HttpExchange exchange) throws IOException {
				String path = exchange.getRequestURI().getPath().replace("/images/", "");
				File file = new File(directory.toFile(), path);

				if (!file.exists() || !file.isFile()) {
					exchange.sendResponseHeaders(404, -1);
					return;
				}

				String mime = Files.probeContentType(file.toPath());
				if (mime != null) {
					exchange.getResponseHeaders().add("Content-Type", mime);
				}

				exchange.sendResponseHeaders(200, file.length());

				try (OutputStream os = exchange.getResponseBody(); InputStream is = new FileInputStream(file)) {
					is.transferTo(os);
				}
			}
		});

		server.start();
		log.info("Static image server running at http://localhost:8081/images/");
		log.info(String.format("      from %s", diariesConfig.getOriginal()));
	}

	void run(Config config) throws Exception {

		DbConfig dbConfig = config.getDb();
		MqttConfig mqttConfig = config.getMqtt();
		String server = mqttConfig.getServer();
		User user = mqttConfig.getUser();

		log.info("Config:");
		log.info(String.format("    refreshPeriod:     %8s = %d Seconds", config.getRefreshPeriod(), config.getRefreshPeriodSeconds()));
		log.info(String.format("    refreshExpiration: %8s = %d Seconds", config.getRefreshExpiration(), config.getRefreshExpirationSeconds()));

		// @formatter:off
		try (EntityManagerFactory entityManagerFactory = GetEntityManager.adminFactory(dbConfig); 
			 EntityManager entityManager = entityManagerFactory.createEntityManager()) {
			// @formatter:on

			DiaryRepository diaryRepository = new DiaryRepositoryImpl(entityManager);
			PageRepository pageRepository = new PageRepositoryImpl(entityManager);
			PersonRepository personRepository = new PersonRepositoryImpl(entityManager);
			FragmentRepository fragmentRepository = new FragmentRepositoryImpl(entityManager);
			MarqueeRepository marqueeRepository = new MarqueeRepositoryImpl(entityManager);

			DiaryContext context = new DiaryContext();
			context.setEntityManager(entityManager);
			context.setDiaryRepository(diaryRepository);
			context.setPageRepository(pageRepository);
			context.setPersonRepository(personRepository);
			context.setFragmentRepository(fragmentRepository);
			context.setMarqueeRepository(marqueeRepository);
			context.setSecret(config.getSecret());
			context.setDiaries(config.getDiaries());
			context.setRefreshPeriod(config.getRefreshPeriodSeconds());
			context.setRefreshExpiration(config.getRefreshExpirationSeconds());

			// Synchronise the topic tree with the database
			Synchronise sync = new Synchronise();
			sync.perform(context, server, user);
			// sync.test(context, server, user);

			// Respond to user requests till asked to quit
			respond(context, server, user);

			log.info("Success");
		}
	}

	private void respond(DiaryContext context, String server, User user) throws Exception {

		MqttClientPersistence pubPersistence = new MemoryPersistence();
		MqttAsyncClient publisherClient = new MqttAsyncClient(server, clientID_publisher, pubPersistence);

		MqttClientPersistence subPersistence = new MemoryPersistence();
		MqttAsyncClient listenerClient = new MqttAsyncClient(server, clientID_listener, subPersistence);

		messageHandler.setContext(context);
		messageHandler.setPublisherClient(publisherClient);
		messageHandler.setListenerClient(listenerClient);
		context.setPublisherClient(publisherClient);
		listenerClient.setCallback(myMessageHandler);

		log.info(String.format("Connecting to broker '%s' as '%s'", server, clientID_publisher));
		MqttConnectionOptions publisherConnOpts = new MqttConnectionOptions();
		publisherConnOpts.setUserName(user.getUsername());
		publisherConnOpts.setPassword(user.getPassword().getBytes());
		publisherConnOpts.setCleanStart(false);
		publisherConnOpts.setAutomaticReconnect(true);
		publisherClient.connect(publisherConnOpts).waitForCompletion();

		log.info(String.format("Connecting to broker '%s' as '%s'", server, clientID_listener));
		MqttConnectionOptions listenerConnOpts = new MqttConnectionOptions();
		listenerConnOpts.setUserName(user.getUsername());
		listenerConnOpts.setPassword(user.getPassword().getBytes());
		listenerConnOpts.setCleanStart(false);
		listenerConnOpts.setAutomaticReconnect(true);
		listenerClient.connect(listenerConnOpts).waitForCompletion();

		// log.info(String.format("subscribing to: %s", requestTopic));
		// MqttSubscription subscription = new MqttSubscription(requestTopic);
		// client_listener.subscribe(subscription).waitForCompletion();

		// Wait till quit request received
		messageHandler.waitForCompletion();

		publisherClient.disconnect().waitForCompletion();
		listenerClient.disconnect().waitForCompletion();
	}

}
