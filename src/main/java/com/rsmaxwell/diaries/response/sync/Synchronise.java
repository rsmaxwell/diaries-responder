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
import org.eclipse.paho.mqttv5.common.MqttMessage;
import org.eclipse.paho.mqttv5.common.MqttSubscription;

import com.rsmaxwell.diaries.common.config.User;
import com.rsmaxwell.diaries.response.dto.DiaryDTO;
import com.rsmaxwell.diaries.response.dto.FragmentDTO;
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

	static final String clientID_syncroniser = "syncroniser";

	public void perform(DiaryContext context, String server, User user, MqttClientPersistence persistence) throws Exception {

		String clientId = clientID_syncroniser + "-" + System.currentTimeMillis();
		MqttAsyncClient client_sync = new MqttAsyncClient(server, clientId, persistence);

		SynchroniseCallback sync = new SynchroniseCallback();
		client_sync.setCallback(sync);

		log.info(String.format("Connecting to broker '%s' as '%s'", server, clientId));
		MqttConnectionOptions connOpts_sync = new MqttConnectionOptions();
		connOpts_sync.setUserName(user.getUsername());
		connOpts_sync.setPassword(user.getPassword().getBytes());
		connOpts_sync.setCleanStart(false);
		connOpts_sync.setAutomaticReconnect(true);
		client_sync.connect(connOpts_sync).waitForCompletion();

		Map<String, String> topicTreeMap = loadFromTopicTree(client_sync, sync);
		Map<String, String> databaseMap = loadFromDatabase(context);

		addNewEntries(client_sync, topicTreeMap, databaseMap);
		removeOrphanEntries(client_sync, topicTreeMap, databaseMap);

		// Wait for broker to actually drop retained messages
		Thread.sleep(500);
		topicTreeMap = loadFromTopicTree(client_sync, sync);
		normaliseDiarySequence(client_sync, context);
		normalisePageSequence(client_sync, context);
		normaliseFragmentSequence(client_sync, context);

		validateMapKeys(topicTreeMap, databaseMap);

		client_sync.disconnect().waitForCompletion();
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
			diary.publish(client);
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
				page.publish(client);
			}
		}
	}

	private void normaliseFragmentSequence(MqttAsyncClient client, DiaryContext context) throws Exception {
		log.info("normaliseFragmentSequence");

		EntityManager em = context.getEntityManager();
		EntityTransaction tx = em.getTransaction();

		DiaryRepository diaryRepository = context.getDiaryRepository();
		PageRepository pageRepository = context.getPageRepository();
		FragmentRepository fragmentRepository = context.getFragmentRepository();

		BigDecimal initial = new BigDecimal("1.0000");
		BigDecimal increment = new BigDecimal("1.0000");
		BigDecimal sequence = initial;
		Integer lastYear = 0;
		Integer lastMonth = 0;
		Integer lastDay = 0;

		for (FragmentDTO fragmentDTO : fragmentRepository.findAll()) {

			Long pageId = fragmentDTO.getPageId();

			Optional<PageDTO> optionalPageDTO = pageRepository.findById(pageId);
			if (optionalPageDTO.isEmpty()) {
				throw new Exception(String.format("Could not find pageId: %d", pageId));
			}
			PageDTO pageDTO = optionalPageDTO.get();

			Long diaryId = pageDTO.getDiaryId();
			Optional<DiaryDTO> optionalDiaryDTO = diaryRepository.findById(diaryId);
			if (optionalDiaryDTO.isEmpty()) {
				throw new Exception(String.format("Could not find diaryId: %d", diaryId));
			}
			DiaryDTO diaryDTO = optionalDiaryDTO.get();

			Diary diary = new Diary(diaryDTO);
			Page page = new Page(diary, pageDTO);
			Fragment fragment = new Fragment(page, fragmentDTO);

			List<Fragment> updatedFragments = new ArrayList<>();

			tx.begin();
			try {
				String fragmentName = String.format("id:%d, date: %s.%s.%s", fragment.getId(), fragment.getYear(), fragment.getMonth(), fragment.getDay());

				BigDecimal currentSeq = fragment.getSequence();
				if (currentSeq != null && currentSeq.compareTo(sequence) == 0) {
					log.info(String.format("fragment %s already has correct sequence number", fragmentName));
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
				}
				tx.commit();

			} catch (Exception ex) {
				tx.rollback();
				throw ex;
			}

			// Publish the updated fragments
			for (Fragment f : updatedFragments) {
				f.publish(client);
			}
		}
	}

	private Map<String, String> loadFromTopicTree(MqttAsyncClient client, SynchroniseCallback sync) throws Exception {

		// @formatter:off
		MqttSubscription[] subscriptions = {
			    new MqttSubscription("fragments/#", 1),
			    new MqttSubscription("diaries/#", 1),
			    new MqttSubscription("dates/#", 1)
		};
		// @formatter:on		

		client.subscribe(subscriptions).waitForCompletion();
		sync.waitForRetainedMessages();

		Map<String, String> map = sync.getTopicMap();
		// log.info(String.format("loadFromTopicTree: sizeof map: %d", map.size()));
		return map;
	}

	private Map<String, String> loadFromDatabase(DiaryContext context) throws Exception {
		ConcurrentHashMap<String, String> map = new ConcurrentHashMap<>();

		DiaryRepository diaryRepository = context.getDiaryRepository();
		PageRepository pageRepository = context.getPageRepository();
		FragmentRepository fragmentRepository = context.getFragmentRepository();

		Iterable<DiaryDTO> diaries = diaryRepository.findAll();
		for (DiaryDTO diaryDTO : diaries) {
			Diary diary = diaryDTO.toDiary();
			diary.publish(map);

			Iterable<PageDTO> pages = pageRepository.findAllByDiary(diaryDTO.getId());
			for (PageDTO pageDTO : pages) {
				Page page = new Page(diary, pageDTO);
				page.publish(map);

				Iterable<FragmentDTO> fragments = fragmentRepository.findAllByPage(pageDTO.getId());
				for (FragmentDTO fragmentDTO : fragments) {
					Fragment fragment = new Fragment(page, fragmentDTO);
					fragment.publish(map);
				}
			}
		}

		return map;
	}

	private void addNewEntries(MqttAsyncClient client, Map<String, String> topicTreeMap, Map<String, String> databaseMap) throws Exception {

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
