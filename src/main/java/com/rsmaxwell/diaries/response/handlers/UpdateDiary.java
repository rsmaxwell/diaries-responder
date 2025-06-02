package com.rsmaxwell.diaries.response.handlers;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

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
import com.rsmaxwell.mqtt.rpc.common.Utilities;
import com.rsmaxwell.mqtt.rpc.response.RequestHandler;
import com.rsmaxwell.mqtt.rpc.utilities.BadRequest;
import com.rsmaxwell.mqtt.rpc.utilities.Unauthorised;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityTransaction;

public class UpdateDiary extends RequestHandler {

	private static final Logger log = LogManager.getLogger(UpdateDiary.class);
	static private ObjectMapper mapper = new ObjectMapper();

	@Override
	public Response handleRequest(Object ctx, Map<String, Object> args, List<UserProperty> userProperties) throws Exception {

		log.info("UpdateDiary.handleRequest");

		String accessToken = Authorization.getAccessToken(userProperties);
		DiaryContext context = (DiaryContext) ctx;
		if (Authorization.checkToken(context, "access", accessToken) == null) {
			log.info("UpdateDiary.handleRequest: Authorization.check: Failed!");
			throw new Unauthorised();
		}
		log.info("UpdateDiary.handleRequest: Authorization.check: OK!");

		DiaryRepository diaryRepository = context.getDiaryRepository();

		Diary diary;
		try {
			Long id = Utilities.getLong(args, "id");
			BigDecimal sequence = Utilities.getBigDecimal(args, "sequence");
			String name = Utilities.getString(args, "name");

			Optional<DiaryDTO> optionalDiaryDTO = diaryRepository.findById(id);
			if (optionalDiaryDTO.isEmpty()) {
				return Response.internalError("Diary not found: id: " + id);
			}
			DiaryDTO diaryDTO = optionalDiaryDTO.get();
			diaryDTO.setSequence(sequence.setScale(4));
			diaryDTO.setName(name);

			diary = new Diary(diaryDTO);

		} catch (Exception e) {
			log.info("UpdateDiary.handleRequest: args: " + mapper.writeValueAsString(args));
			throw new BadRequest(e.getMessage(), e);
		}

		// First update the Diary in the database
		EntityManager em = context.getEntityManager();
		EntityTransaction tx = em.getTransaction();

		tx.begin();
		try {
			diaryRepository.update(diary);
			tx.commit();
		} catch (Exception e) {
			tx.rollback();
			return Response.internalError(e.getMessage());
		}

		// Now update the Diary in the topic tree
		DiaryDTO diaryDTO = diary.toDTO();
		byte[] payload = mapper.writeValueAsBytes(diaryDTO);
		String payloadStr = new String(payload);

		MqttAsyncClient client = context.getClientResponder();
		int qos = 1;
		boolean retained = true;
		log.info("diary: " + payloadStr);

		String topic = String.format("diaries/%d", diary.getId());
		log.info("  --> topic: " + topic);
		client.publish(topic, payload, qos, retained).waitForCompletion();

		return Response.success(diary.getId());
	}
}
