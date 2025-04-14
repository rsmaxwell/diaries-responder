package com.rsmaxwell.diaries.response;

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
import org.eclipse.paho.mqttv5.client.persist.MqttDefaultFilePersistence;
import org.eclipse.paho.mqttv5.common.MqttMessage;
import org.eclipse.paho.mqttv5.common.MqttSubscription;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rsmaxwell.diaries.common.config.Config;
import com.rsmaxwell.diaries.common.config.DbConfig;
import com.rsmaxwell.diaries.common.config.MqttConfig;
import com.rsmaxwell.diaries.common.config.User;
import com.rsmaxwell.diaries.response.handlers.Calculator;
import com.rsmaxwell.diaries.response.handlers.GetDiaries;
import com.rsmaxwell.diaries.response.handlers.GetPages;
import com.rsmaxwell.diaries.response.handlers.Quit;
import com.rsmaxwell.diaries.response.handlers.RefreshToken;
import com.rsmaxwell.diaries.response.handlers.Register;
import com.rsmaxwell.diaries.response.handlers.Signin;
import com.rsmaxwell.diaries.response.model.Diary;
import com.rsmaxwell.diaries.response.model.DiaryResponse;
import com.rsmaxwell.diaries.response.repository.DiaryRepository;
import com.rsmaxwell.diaries.response.repository.PageRepository;
import com.rsmaxwell.diaries.response.repository.PersonRepository;
import com.rsmaxwell.diaries.response.repositoryImpl.DiaryRepositoryImpl;
import com.rsmaxwell.diaries.response.repositoryImpl.PageRepositoryImpl;
import com.rsmaxwell.diaries.response.repositoryImpl.PersonRepositoryImpl;
import com.rsmaxwell.diaries.response.utilities.DiaryContext;
import com.rsmaxwell.diaries.response.utilities.GetEntityManager;
import com.rsmaxwell.mqtt.rpc.response.MessageHandler;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityManagerFactory;

public class Responder {

	private static final Logger log = LogManager.getLogger(Responder.class);
	private static ObjectMapper mapper = new ObjectMapper();

	static final String clientID_responder = "responder";
	static final String clientID_listener = "listener";
	static final String requestTopic = "request";
	static final int qos = 0;
	static MessageHandler messageHandler = new MessageHandler();

	static {
		messageHandler.putHandler("calculator", new Calculator());
		messageHandler.putHandler("getPages", new GetPages());
		messageHandler.putHandler("getDiaries", new GetDiaries());
		messageHandler.putHandler("register", new Register());
		messageHandler.putHandler("signin", new Signin());
		messageHandler.putHandler("refreshToken", new RefreshToken());
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

		Responder responder = new Responder();
		responder.run(config);
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

			DiaryContext context = new DiaryContext();
			context.setEntityManager(entityManager);
			context.setDiaryRepository(diaryRepository);
			context.setPageRepository(pageRepository);
			context.setPersonRepository(personRepository);
			context.setSecret(config.getSecret());
			context.setDiaries(config.getDiaries());
			context.setRefreshPeriod(config.getRefreshPeriodSeconds());
			context.setRefreshExpiration(config.getRefreshExpirationSeconds());

			MqttClientPersistence persistence = new MqttDefaultFilePersistence();
			MqttAsyncClient client_responder = new MqttAsyncClient(server, clientID_responder, persistence);
			MqttAsyncClient client_listener = new MqttAsyncClient(server, clientID_listener, persistence);

			messageHandler.setContext(context);
			messageHandler.setClient(client_responder);
			client_listener.setCallback(messageHandler);

			log.info(String.format("Connecting to broker '%s' as '%s'", server, clientID_responder));
			MqttConnectionOptions connOpts_responder = new MqttConnectionOptions();
			connOpts_responder.setUserName(user.getUsername());
			connOpts_responder.setPassword(user.getPassword().getBytes());
			connOpts_responder.setCleanStart(true);
			client_responder.connect(connOpts_responder).waitForCompletion();

			log.info(String.format("Connecting to broker '%s' as '%s'", server, clientID_listener));
			MqttConnectionOptions connOpts_subscriber = new MqttConnectionOptions();
			connOpts_subscriber.setUserName(user.getUsername());
			connOpts_subscriber.setPassword(user.getPassword().getBytes());
			connOpts_subscriber.setCleanStart(true);
			client_listener.connect(connOpts_subscriber).waitForCompletion();

			updateTopicTree(context, diaryRepository, client_responder);

			log.info(String.format("subscribing to: %s", requestTopic));
			MqttSubscription subscription = new MqttSubscription(requestTopic);
			client_listener.subscribe(subscription).waitForCompletion();

			// Wait till quit request received
			messageHandler.waitForCompletion();

			log.info("disconnect");
			client_responder.disconnect().waitForCompletion();
			client_listener.disconnect().waitForCompletion();

			log.info("Success");

		} catch (Exception e) {
			log.catching(e);
			return;
		}
	}

	private void updateTopicTree(DiaryContext context, DiaryRepository diaryRepository, MqttAsyncClient client) throws Exception {
		log.info("updateTopicTree");
		update_Diaries(context, diaryRepository, client);
	}

	private void update_Diaries(DiaryContext context, DiaryRepository diaryRepository, MqttAsyncClient client) throws Exception {
		log.info("update_Diaries");
		Iterable<Diary> diaries = diaryRepository.findAll();
		for (Diary diary : diaries) {
			DiaryResponse diaryResponse = new DiaryResponse(diary, context);
			String topic = String.format("diary/%d", diary.getId());

			String bodyString = diaryResponse.toJson();
			log.info(String.format("update_Diaries: bodySting: %s", bodyString));

			byte[] body = diaryResponse.toJsonAsBytes();
			MqttMessage message = new MqttMessage(body);
			message.setQos(1); // Ensures at least one delivery
			message.setRetained(true); // Retain message on broker
			client.publish(topic, message).waitForCompletion();
		}

		byte[] jsonAsBytes = mapper.writeValueAsBytes(diaries);
		MqttMessage message = new MqttMessage(jsonAsBytes);
		message.setQos(1); // Ensures at least one delivery
		message.setRetained(true); // Retain message on broker
		client.publish("diaries", message).waitForCompletion();
	}
}
