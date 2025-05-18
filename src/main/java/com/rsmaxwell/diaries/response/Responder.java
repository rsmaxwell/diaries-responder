package com.rsmaxwell.diaries.response;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
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
import org.eclipse.paho.mqttv5.common.MqttSubscription;

import com.rsmaxwell.diaries.common.config.Config;
import com.rsmaxwell.diaries.common.config.DbConfig;
import com.rsmaxwell.diaries.common.config.DiariesConfig;
import com.rsmaxwell.diaries.common.config.MqttConfig;
import com.rsmaxwell.diaries.common.config.User;
import com.rsmaxwell.diaries.response.handlers.AddMarquee;
import com.rsmaxwell.diaries.response.handlers.Calculator;
import com.rsmaxwell.diaries.response.handlers.DeleteMarquee;
import com.rsmaxwell.diaries.response.handlers.Quit;
import com.rsmaxwell.diaries.response.handlers.RefreshToken;
import com.rsmaxwell.diaries.response.handlers.Register;
import com.rsmaxwell.diaries.response.handlers.Signin;
import com.rsmaxwell.diaries.response.handlers.UpdateDiary;
import com.rsmaxwell.diaries.response.handlers.UpdateMarquee;
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
import com.rsmaxwell.mqtt.rpc.response.MessageHandler;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;

public class Responder {

	private static final Logger log = LogManager.getLogger(Responder.class);

	static final String clientID_responder = "responder";
	static final String clientID_listener = "listener";
	static final String requestTopic = "request";
	static final int qos = 0;
	static MessageHandler messageHandler = new MessageHandler();

	static {
		messageHandler.putHandler("calculator", new Calculator());
		messageHandler.putHandler("updateDiary", new UpdateDiary());
		messageHandler.putHandler("register", new Register());
		messageHandler.putHandler("signin", new Signin());
		messageHandler.putHandler("refreshToken", new RefreshToken());
		messageHandler.putHandler("addMarquee", new AddMarquee());
		messageHandler.putHandler("updateMarquee", new UpdateMarquee());
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

		Responder responder = new Responder();
		responder.run(config);
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

	void run(Config config) {

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
			MarqueeRepository marqueeRepository = new MarqueeRepositoryImpl(entityManager);
			FragmentRepository fragmentRepository = new FragmentRepositoryImpl(entityManager);

			DiaryContext context = new DiaryContext();
			context.setEntityManager(entityManager);
			context.setDiaryRepository(diaryRepository);
			context.setPageRepository(pageRepository);
			context.setPersonRepository(personRepository);
			context.setMarqueeRepository(marqueeRepository);
			context.setFragmentRepository(fragmentRepository);
			context.setSecret(config.getSecret());
			context.setDiaries(config.getDiaries());
			context.setRefreshPeriod(config.getRefreshPeriodSeconds());
			context.setRefreshExpiration(config.getRefreshExpirationSeconds());

			MqttClientPersistence persistence = new MemoryPersistence();

			// Synchronise the topic tree with the database
			Synchronise sync = new Synchronise();
			sync.perform(context, server, user, persistence);

			// Respond to user requests till asked to quit
			respond(context, server, user, persistence);

			log.info("Success");

		} catch (Exception e) {
			log.catching(e);
			return;
		}
	}

	private void respond(DiaryContext context, String server, User user, MqttClientPersistence persistence) throws Exception {
		MqttAsyncClient client_responder = new MqttAsyncClient(server, clientID_responder, persistence);
		MqttAsyncClient client_listener = new MqttAsyncClient(server, clientID_listener, persistence);

		messageHandler.setContext(context);
		messageHandler.setClient(client_responder);
		context.setClientResponder(client_responder);
		client_listener.setCallback(messageHandler);

		log.info(String.format("Connecting to broker '%s' as '%s'", server, clientID_responder));
		MqttConnectionOptions connOpts_responder = new MqttConnectionOptions();
		connOpts_responder.setUserName(user.getUsername());
		connOpts_responder.setPassword(user.getPassword().getBytes());
		connOpts_responder.setCleanStart(false);
		connOpts_responder.setAutomaticReconnect(true);
		client_responder.connect(connOpts_responder).waitForCompletion();

		log.info(String.format("Connecting to broker '%s' as '%s'", server, clientID_listener));
		MqttConnectionOptions connOpts_subscriber = new MqttConnectionOptions();
		connOpts_subscriber.setUserName(user.getUsername());
		connOpts_subscriber.setPassword(user.getPassword().getBytes());
		connOpts_subscriber.setCleanStart(false);
		connOpts_subscriber.setAutomaticReconnect(true);
		client_listener.connect(connOpts_subscriber).waitForCompletion();

		log.info(String.format("subscribing to: %s", requestTopic));
		MqttSubscription subscription = new MqttSubscription(requestTopic);
		client_listener.subscribe(subscription).waitForCompletion();

		// Wait till quit request received
		messageHandler.waitForCompletion();

		client_responder.disconnect().waitForCompletion();
		client_listener.disconnect().waitForCompletion();
	}

}
