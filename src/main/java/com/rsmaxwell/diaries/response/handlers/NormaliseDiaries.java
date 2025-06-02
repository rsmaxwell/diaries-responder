package com.rsmaxwell.diaries.response.handlers;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.paho.mqttv5.client.MqttAsyncClient;
import org.eclipse.paho.mqttv5.common.packet.UserProperty;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rsmaxwell.diaries.response.dto.DiaryDTO;
import com.rsmaxwell.diaries.response.model.Diary;
import com.rsmaxwell.diaries.response.repository.DiaryRepository;
import com.rsmaxwell.diaries.response.utilities.Authorization;
import com.rsmaxwell.diaries.response.utilities.DiaryContext;
import com.rsmaxwell.mqtt.rpc.common.Response;
import com.rsmaxwell.mqtt.rpc.response.RequestHandler;
import com.rsmaxwell.mqtt.rpc.utilities.Unauthorised;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityTransaction;

public class NormaliseDiaries extends RequestHandler {

	private static final Logger log = LogManager.getLogger(NormaliseDiaries.class);
	static private ObjectMapper mapper = new ObjectMapper();

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

		MqttAsyncClient client = context.getClientResponder();
		DiaryRepository diaryRepository = context.getDiaryRepository();

		EntityManager em = context.getEntityManager();
		EntityTransaction tx = em.getTransaction();

		List<DiaryDTO> updates = new ArrayList<>();

		BigDecimal sequence = new BigDecimal("1.0000");
		BigDecimal increment = new BigDecimal("1.0000");

		tx.begin();
		try {
			for (DiaryDTO dto : diaryRepository.findAll()) {
				BigDecimal currentSeq = dto.getSequence();

				if (currentSeq != null && currentSeq.compareTo(sequence) == 0) {
					// log.info(String.format("diary id:%d, name:%s already has correct sequence number", dto.getId(), dto.getName()));
				} else {
					String currentSeqStr = (currentSeq != null) ? currentSeq.toPlainString() : "null";
					log.info(String.format("Updating diary id:%d: name:%s: sequence %s -> %s", dto.getId(), dto.getName(), currentSeqStr, sequence.toPlainString()));

					Diary diary = new Diary(dto);
					diary.setSequence(sequence);
					diaryRepository.update(diary);
					updates.add(dto);
				}

				sequence = sequence.add(increment);
			}
			tx.commit();

		} catch (Exception ex) {
			tx.rollback();
			throw ex;
		}

		int qos = 1;
		boolean retained = true;

		// Publish the updates
		for (DiaryDTO dto : updates) {
			String topic = String.format("diaries/%d", dto.getId());
			byte[] payload = mapper.writeValueAsBytes(dto);
			client.publish(topic, payload, qos, retained).waitForCompletion();
		}

		return Response.success(updates.size());
	}
}
