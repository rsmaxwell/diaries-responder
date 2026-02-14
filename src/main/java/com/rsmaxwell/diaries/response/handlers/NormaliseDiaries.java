package com.rsmaxwell.diaries.response.handlers;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.paho.mqttv5.client.MqttAsyncClient;
import org.eclipse.paho.mqttv5.common.packet.UserProperty;

import com.rsmaxwell.diaries.response.dto.DiaryDTO;
import com.rsmaxwell.diaries.response.model.Diary;
import com.rsmaxwell.diaries.response.repository.DiaryRepository;
import com.rsmaxwell.diaries.response.utilities.Authorization;
import com.rsmaxwell.diaries.response.utilities.DiaryContext;
import com.rsmaxwell.mqtt.rpc.common.Response;
import com.rsmaxwell.mqtt.rpc.responder.RequestHandler;
import com.rsmaxwell.mqtt.rpc.utilities.Unauthorised;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityTransaction;

public class NormaliseDiaries extends RequestHandler {

	private static final Logger log = LogManager.getLogger(NormaliseDiaries.class);

	@Override
	public Response handleRequest(Object ctx, Map<String, Object> args, List<UserProperty> userProperties) throws Exception {

		log.info("NormaliseDiaries.handleRequest");

		String accessToken = Authorization.getAccessToken(userProperties);
		DiaryContext context = (DiaryContext) ctx;
		if (Authorization.checkToken(context, "access", accessToken) == null) {
			log.info("NormaliseDiaries.handleRequest: Authorization.check: Failed!");
			throw new Unauthorised();
		}
		log.info("NormaliseDiaries.handleRequest: Authorization.check: OK!");

		MqttAsyncClient client = context.getPublisherClient();
		DiaryRepository diaryRepository = context.getDiaryRepository();

		EntityManager em = context.getEntityManager();
		EntityTransaction tx = em.getTransaction();

		List<Diary> updates = new ArrayList<>();

		BigDecimal sequence = new BigDecimal("1.0000");
		BigDecimal increment = new BigDecimal("1.0000");

		tx.begin();
		try {
			for (DiaryDTO diaryDTO : diaryRepository.findAll()) {
				BigDecimal currentSeq = diaryDTO.getSequence();

				if (currentSeq != null && currentSeq.compareTo(sequence) == 0) {
					// log.info(String.format("diary id:%d, name:%s already has correct sequence number", dto.getId(), dto.getName()));
				} else {
					String currentSeqStr = (currentSeq != null) ? currentSeq.toPlainString() : "null";
					log.info(String.format("Updating diary id: %d, name: %s, sequence: %s --> %s", diaryDTO.getId(), diaryDTO.getName(), currentSeqStr, sequence.toPlainString()));

					Diary diary = new Diary(diaryDTO);

					diary.setSequence(sequence);
					diary.incrementVersion();
					diaryRepository.update(diary);
					updates.add(diary);
				}

				sequence = sequence.add(increment);
			}
			tx.commit();

		} catch (Exception ex) {
			tx.rollback();
			throw ex;
		}

		// Publish the updates
		for (Diary diary : updates) {
			log.info(String.format("Publishing diary id:%d, name:%s --> sequence: %s", diary.getId(), diary.getName(), diary.getSequence().toPlainString()));
			DiaryDTO dto = new DiaryDTO(diary);
			dto.publish(client);
		}

		return Response.success(updates.size());
	}
}
