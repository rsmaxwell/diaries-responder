package com.rsmaxwell.diaries.response.handlers;

import java.math.BigDecimal;
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
import com.rsmaxwell.mqtt.rpc.common.Utilities;
import com.rsmaxwell.mqtt.rpc.response.RequestHandler;
import com.rsmaxwell.mqtt.rpc.utilities.Unauthorised;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityTransaction;

public class UpdateDiary extends RequestHandler {

	private static final Logger log = LogManager.getLogger(UpdateDiary.class);
	static private ObjectMapper mapper = new ObjectMapper();

	@Override
	public Response handleRequest(Object ctx, Map<String, Object> args, List<UserProperty> userProperties) throws Exception {

		log.info("UpdateDiary.handleRequest: args: " + mapper.writeValueAsString(args));

		String accessToken = Authorization.getAccessToken(userProperties);
		DiaryContext context = (DiaryContext) ctx;
		if (Authorization.checkToken(context, "access", accessToken) == null) {
			log.info("UpdateDiary.handleRequest: Authorization.check: Failed!");
			throw new Unauthorised();
		}
		log.info("UpdateDiary.handleRequest: Authorization.check: OK!");

		DiaryRepository diaryRepository = context.getDiaryRepository();

		EntityManager em = context.getEntityManager();
		EntityTransaction tx = em.getTransaction();

		Diary incomingDiary;
		Diary originalDiary;

		tx.begin();
		try {
			Long id = Utilities.getLong(args, "id");
			Long version = Utilities.getLong(args, "version");
			BigDecimal sequence = Utilities.getBigDecimal(args, "sequence");
			String name = Utilities.getString(args, "name");

			// (1) get the original Diary
			originalDiary = context.inflateDiary(id);

			// (2) get the incoming Diary

			//@formatter:off
			DiaryDTO diaryDTO = DiaryDTO.builder()
					.id(id)
					.version(version)
					.sequence(sequence)
					.name(name)
					.build();
			//@formatter:on

			incomingDiary = new Diary(diaryDTO);

			// (3) check and bump the version
			incomingDiary.checkAndIncrementVersion(originalDiary);

			// (4) save to database
			int count = diaryRepository.update(incomingDiary);
			if (count != 1) {
				log.info("UpdateDiary.handleRequest: number of records updated: {}", count);
			}
			tx.commit();

		} catch (Exception e) {
			tx.rollback();
			return Response.internalError(e.getMessage());
		}

		// (5) Remove the original Diary from the TopicTree
		MqttAsyncClient client = context.getPublisherClient();
		if (originalDiary.keyFieldsChanged(incomingDiary)) {
			log.info("UpdateDiary.handleRequest: removing the original diary from the TopicTree");
			DiaryDTO dto = new DiaryDTO(originalDiary);
			dto.remove(client);
		}

		// (6) publish the Diary to the topic tree
		log.info("UpdateDiary.handleRequest: publishing the incomming diary to the TopicTree");
		DiaryDTO dto = new DiaryDTO(incomingDiary);
		dto.publish(client);

		return Response.success(incomingDiary.getId());
	}
}
