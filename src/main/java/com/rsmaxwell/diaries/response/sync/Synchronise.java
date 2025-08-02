package com.rsmaxwell.diaries.response.sync;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.stream.StreamSupport;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.paho.mqttv5.client.MqttAsyncClient;
import org.eclipse.paho.mqttv5.client.MqttClientPersistence;
import org.eclipse.paho.mqttv5.client.MqttConnectionOptions;
import org.eclipse.paho.mqttv5.client.persist.MemoryPersistence;
import org.eclipse.paho.mqttv5.common.MqttMessage;
import org.eclipse.paho.mqttv5.common.MqttSubscription;

import com.rsmaxwell.diaries.common.config.User;
import com.rsmaxwell.diaries.response.dto.DiaryDTO;
import com.rsmaxwell.diaries.response.dto.FragmentDBDTO;
import com.rsmaxwell.diaries.response.dto.FragmentPublishDTO;
import com.rsmaxwell.diaries.response.dto.PageDTO;
import com.rsmaxwell.diaries.response.model.Diary;
import com.rsmaxwell.diaries.response.model.Fragment;
import com.rsmaxwell.diaries.response.model.Page;
import com.rsmaxwell.diaries.response.repository.DiaryRepository;
import com.rsmaxwell.diaries.response.repository.FragmentRepository;
import com.rsmaxwell.diaries.response.repository.PageRepository;
import com.rsmaxwell.diaries.response.utilities.DiaryContext;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityTransaction;

public class Synchronise {

	private static final Logger log = LogManager.getLogger(Synchronise.class);

	static final String clientID_sync_pub = "syncronise-pub";
	static final String clientID_sync_sub = "syncronise-sub";

	private static final String[] topicFilters = { "diaries/#", "dates/#", "pages/#", "fragments/#", "marquees/#" };

	public void perform(DiaryContext context, String server, User user) throws Exception {

		// Setup the Publish Client
		String clientId_pub = clientID_sync_pub + "-" + System.currentTimeMillis();
		MqttClientPersistence pubPersistence = new MemoryPersistence();
		MqttAsyncClient client_pub = new MqttAsyncClient(server, clientId_pub, pubPersistence);

		log.info(String.format("Connecting to broker '%s' as '%s'", server, clientId_pub));
		MqttConnectionOptions connOpts_pub = new MqttConnectionOptions();
		connOpts_pub.setUserName(user.getUsername());
		connOpts_pub.setPassword(user.getPassword().getBytes());
		connOpts_pub.setCleanStart(true);
		connOpts_pub.setAutomaticReconnect(true);
		connOpts_pub.setReceiveMaximum(20);
		client_pub.connect(connOpts_pub).waitForCompletion();

		// Setup the Subscribe Client
		String clientId_sub = clientID_sync_sub + "-" + System.currentTimeMillis();
		MqttClientPersistence subPersistence = new MemoryPersistence();
		MqttAsyncClient client_sub = new MqttAsyncClient(server, clientId_sub, subPersistence);

		log.info(String.format("Connecting to broker '%s' as '%s'", server, clientId_sub));
		MqttConnectionOptions connOpts_sub = new MqttConnectionOptions();
		connOpts_sub.setUserName(user.getUsername());
		connOpts_sub.setPassword(user.getPassword().getBytes());
		connOpts_sub.setCleanStart(true);
		connOpts_sub.setAutomaticReconnect(true);
		client_sub.connect(connOpts_sub).waitForCompletion();

		// Set up the callback and subscribe to all the topics
		ConcurrentHashMap<String, String> topicTreeMap = new ConcurrentHashMap<>();
		SynchroniseCallback sync = new SynchroniseCallback(topicTreeMap);
		client_sub.setCallback(sync);

		for (String topic : topicFilters) {
			MqttSubscription sub = new MqttSubscription(topic, 1);
			log.info(String.format("SUBSCRIBED %s", sub));
			client_sub.subscribe(sub).waitForCompletion();
			// sync.waitForMessages();
		}

		Map<String, String> databaseMap = context.loadFromDatabase();

		log.info("sizeof(topicTreeMap) = {}", topicTreeMap.size());
		log.info("sizeof(databaseMap) = {}", databaseMap.size());

		addNewEntries(client_pub, topicTreeMap, databaseMap);
		removeOrphanEntries(client_pub, topicTreeMap, databaseMap);

		// Wait for broker to actually drop the retained messages
		sync.waitForMessages();
		normaliseDiarySequence(client_pub, context);
		normalisePageSequence(client_pub, context);
		normaliseFragmentSequence(client_pub, context);

		// Wait again for those publishes to arrive
		sync.waitForMessages();

		validateMapKeys(topicTreeMap, databaseMap);

		client_sub.unsubscribe(topicFilters).waitForCompletion();
		client_sub.disconnect().waitForCompletion();
		client_pub.disconnect().waitForCompletion();
	}

	private void normaliseDiarySequence(MqttAsyncClient client, DiaryContext context) throws Exception {
		log.info("normaliseDiarySequence");

		EntityManager em = context.getEntityManager();
		EntityTransaction tx = em.getTransaction();

		DiaryRepository diaryRepository = context.getDiaryRepository();

		// Sort the diaries by (1) sequence (if not null) and (2) Fallback: id (as a tie-breaker)
		// @formatter:off
		List<DiaryDTO> diaryList = StreamSupport
			.stream(diaryRepository.findAll().spliterator(), false)
		    .sorted(Comparator
			    .comparing(DiaryDTO::getSequence, Comparator.nullsLast(BigDecimal::compareTo))
			    .thenComparing(DiaryDTO::getId)).collect(Collectors.toList());
		// @formatter:on

		List<Diary> updatedDiaries = new ArrayList<>();

		BigDecimal sequence = new BigDecimal("1.0000");
		BigDecimal increment = new BigDecimal("1.0000");

		tx.begin();
		try {
			for (DiaryDTO dto : diaryList) {
				BigDecimal currentSeq = dto.getSequence();

				if (currentSeq != null && currentSeq.compareTo(sequence) == 0) {
					// log.info(String.format("diary id:%d, name:%s already has correct sequence number", dto.getId(), dto.getName()));
				} else {
					String currentSeqStr = (currentSeq != null) ? currentSeq.toPlainString() : "null";
					log.info(String.format("Updating diary id:%d: name:%s: sequence %s -> %s", dto.getId(), dto.getName(), currentSeqStr, sequence.toPlainString()));

					Diary diary = new Diary(dto);
					diary.setSequence(sequence);
					diaryRepository.update(diary);
					updatedDiaries.add(diary);
				}

				sequence = sequence.add(increment);
			}
			tx.commit();

		} catch (Exception ex) {
			tx.rollback();
			throw ex;
		}

		// Publish the updated diaries
		for (Diary diary : updatedDiaries) {
			log.info(String.format("publishing diary id: %d, name: %s, sequence: %s", diary.getId(), diary.getName(), diary.getSequence().toPlainString()));
			DiaryDTO diaryDTO = new DiaryDTO(diary);
			diaryDTO.publish(client);
		}
	}

	private void normalisePageSequence(MqttAsyncClient client, DiaryContext context) throws Exception {
		log.info("normalisePageSequence");

		EntityManager em = context.getEntityManager();
		EntityTransaction tx = em.getTransaction();

		DiaryRepository diaryRepository = context.getDiaryRepository();
		PageRepository pageRepository = context.getPageRepository();

		for (DiaryDTO diaryDTO : diaryRepository.findAll()) {
			Long diaryId = diaryDTO.getId();
			Diary diary;
			Optional<DiaryDTO> optional = diaryRepository.findById(diaryId);
			if (optional.isEmpty()) {
				throw new Exception(String.format("Could not find diaryId: %d", diaryId));
			} else {
				diary = new Diary(optional.get());
			}

			// Sort the diaries by (1) sequence (if not null) and (2) Fallback: id (as a tie-breaker)
			// @formatter:off
			List<PageDTO> pageList = StreamSupport
				.stream(pageRepository.findAllByDiary(diaryId).spliterator(), false)
					.sorted(Comparator
					.comparing(PageDTO::getSequence, Comparator.nullsLast(BigDecimal::compareTo))
					.thenComparing(PageDTO::getId)).collect(Collectors.toList());
			// @formatter:on

			List<Page> updatedPages = new ArrayList<>();

			BigDecimal sequence = new BigDecimal("1.0000");
			BigDecimal increment = new BigDecimal("1.0000");

			tx.begin();
			try {
				for (PageDTO dto : pageList) {
					BigDecimal currentSeq = dto.getSequence();

					if (currentSeq != null && currentSeq.compareTo(sequence) == 0) {
						// log.info(String.format("page id:%d, name:%s already has correct sequence number", dto.getId(), dto.getName()));
					} else {
						String currentSeqStr = (currentSeq != null) ? currentSeq.toPlainString() : "null";
						log.info(String.format("Updating diary id:%d: name:%s: sequence %s -> %s", dto.getId(), dto.getName(), currentSeqStr, sequence.toPlainString()));

						Page page = new Page(diary, dto);
						page.setSequence(sequence);
						pageRepository.update(page);
						updatedPages.add(page);
					}

					sequence = sequence.add(increment);
				}
				tx.commit();

			} catch (Exception ex) {
				tx.rollback();
				throw ex;
			}

			// Publish the updated pages
			for (Page page : updatedPages) {
				PageDTO pageDTO = new PageDTO(page);
				pageDTO.publish(client);
			}
		}
	}

	private void normaliseFragmentSequence(MqttAsyncClient client, DiaryContext context) throws Exception {
		log.info("normaliseFragmentSequence");

		EntityManager em = context.getEntityManager();
		EntityTransaction tx = em.getTransaction();

		FragmentRepository fragmentRepository = context.getFragmentRepository();

		BigDecimal initial = new BigDecimal("1.0000");
		BigDecimal increment = new BigDecimal("1.0000");
		BigDecimal sequence = initial;
		Integer lastYear = 0;
		Integer lastMonth = 0;
		Integer lastDay = 0;

		for (FragmentDBDTO fragmentDTO : fragmentRepository.findAll()) {
			Fragment fragment = new Fragment(fragmentDTO);

			List<Fragment> updatedFragments = new ArrayList<>();

			tx.begin();
			try {
				String fragmentName = String.format("id:%d, date: %s.%s.%s", fragment.getId(), fragment.getYear(), fragment.getMonth(), fragment.getDay());

				BigDecimal currentSeq = fragment.getSequence();
				if (currentSeq != null && currentSeq.compareTo(sequence) == 0) {
					// log.info(String.format("fragment %s already has correct sequence number", fragmentName));
				} else {
					String currentSeqStr = (currentSeq != null) ? currentSeq.toPlainString() : "null";
					log.info(String.format("       fragment: %s", fragmentName));
					log.info(String.format("       %d/%d/%d: sequence %s -> %s", fragment.getYear(), fragment.getMonth(), fragment.getDay(), currentSeqStr,
							sequence.toPlainString()));

					fragment.setSequence(sequence);
					fragmentRepository.update(fragment);
					updatedFragments.add(fragment);
				}

				if ((lastYear == fragment.getYear()) || (lastMonth == fragment.getMonth()) || (lastDay == fragment.getDay())) {
					sequence.add(increment);
				} else {
					sequence = initial;
					lastYear = fragment.getYear();
					lastMonth = fragment.getMonth();
					lastDay = fragment.getDay();
				}
				tx.commit();

			} catch (Exception ex) {
				tx.rollback();
				throw ex;
			}

			// Publish the updated fragments
			for (Fragment f : updatedFragments) {
				FragmentPublishDTO dto = new FragmentPublishDTO(f);
				dto.publish(client);
			}
		}
	}

	private void addNewEntries(MqttAsyncClient client, Map<String, String> topicTreeMap, Map<String, String> databaseMap) throws Exception {

		log.info("addNewEntries");
		int count = 0;

		// Make sure there is a matching topicTree entry for every database entry
		for (Map.Entry<String, String> entry : databaseMap.entrySet()) {
			String topic = entry.getKey();
			String string1 = entry.getValue();
			String string2 = topicTreeMap.get(topic);

			if (!Objects.equals(string1, string2)) {
				log.info(String.format("Difference detected on topic: %s", topic));
				log.info(String.format("          DB:   %s", string1));
				log.info(String.format("          Tree: %s", string2));
				count++;
				publish(client, topic, string1);
			}
		}
		log.info(String.format("addNewEntries: count: %d", count));
	}

	private void removeOrphanEntries(MqttAsyncClient client, Map<String, String> topicTreeMap, Map<String, String> databaseMap) throws Exception {

		log.info("removeOrphanEntries");
		int count = 0;

		// If there is an entry in the topicTree but not in the database, then delete it
		for (Map.Entry<String, String> entry : topicTreeMap.entrySet()) {
			String topic = entry.getKey();
			String string1 = entry.getValue();
			String string2 = databaseMap.get(topic);

			if ((string1 != null) && (string2 == null)) {
				log.info(String.format("removeOrphanEntries: removing: topic: %s, value: %s", topic, string1));
				count++;
				publish(client, topic, null);

				if (databaseMap.containsKey(topic)) {
					log.warn(String.format("Warning: tried to remove topic %s, but it's in the database!", topic));
				}
			}
		}

		log.info(String.format("removeOrphanEntries: count: %d", count));
	}

	private void publish(MqttAsyncClient client, String topic, String value) throws Exception {
		MqttMessage message;
		if (value != null) {
			message = new MqttMessage(value.getBytes());
		} else {
			message = new MqttMessage(new byte[0]); // use empty payload to delete retained
		}

		message.setRetained(true); // <-- This is critical for deletes to work
		client.publish(topic, message).waitForCompletion();
		log.info(String.format("publish: topic: %s", topic));
		log.info(String.format("         value: %s", value));
	}

	private void validateMapKeys(Map<String, String> topicTreeMap, Map<String, String> databaseMap) {

		Set<String> topicKeys = topicTreeMap.keySet();
		Set<String> dbKeys = databaseMap.keySet();

		// Keys in topic tree but not in database
		Set<String> topicOnly = new TreeSet<>(topicKeys);
		topicOnly.removeAll(dbKeys);

		// Keys in database but not in topic tree
		Set<String> dbOnly = new TreeSet<>(dbKeys);
		dbOnly.removeAll(topicKeys);

		if (topicOnly.isEmpty() && dbOnly.isEmpty()) {
			log.info("synchronise: ok");
		} else {
			if (!topicOnly.isEmpty()) {
				log.warn("synchronise: Keys present in topic tree but missing in database:");
				topicOnly.forEach(key -> log.warn("   -> Orphan in topic tree: " + key));
			}

			if (!dbOnly.isEmpty()) {
				log.warn("synchronise: Keys present in database but missing in topic tree:");
				dbOnly.forEach(key -> log.warn("   -> Missing topic: " + key));
			}
		}
	}
}
