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
import com.rsmaxwell.diaries.response.dto.PageDTO;
import com.rsmaxwell.diaries.response.model.Diary;
import com.rsmaxwell.diaries.response.model.Page;
import com.rsmaxwell.diaries.response.repository.DiaryRepository;
import com.rsmaxwell.diaries.response.repository.PageRepository;
import com.rsmaxwell.diaries.response.utilities.Authorization;
import com.rsmaxwell.diaries.response.utilities.DiaryContext;
import com.rsmaxwell.mqtt.rpc.common.Response;
import com.rsmaxwell.mqtt.rpc.common.Utilities;
import com.rsmaxwell.mqtt.rpc.response.RequestHandler;
import com.rsmaxwell.mqtt.rpc.utilities.BadRequest;
import com.rsmaxwell.mqtt.rpc.utilities.Unauthorised;

import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityTransaction;

public class UpdatePage extends RequestHandler {

	private static final Logger log = LogManager.getLogger(UpdatePage.class);
	static private ObjectMapper mapper = new ObjectMapper();

	@Override
	public Response handleRequest(Object ctx, Map<String, Object> args, List<UserProperty> userProperties) throws Exception {

		log.info("UpdatePage.handleRequest");

		String accessToken = Authorization.getAccessToken(userProperties);
		DiaryContext context = (DiaryContext) ctx;
		if (Authorization.checkToken(context, "access", accessToken) == null) {
			log.info("UpdatePage.handleRequest: Authorization.check: Failed!");
			throw new Unauthorised();
		}
		log.info("UpdatePage.handleRequest: Authorization.check: OK!");

		DiaryRepository diaryRepository = context.getDiaryRepository();
		PageRepository pageRepository = context.getPageRepository();

		Diary diary;
		Page page;
		try {
			Long id = Utilities.getLong(args, "id");
			BigDecimal sequence = Utilities.getBigDecimal(args, "sequence");
			String name = Utilities.getString(args, "name");

			Optional<PageDTO> optionalPageDTO = pageRepository.findById(id);
			if (optionalPageDTO.isEmpty()) {
				return Response.internalError("Page not found: id: " + id);
			}
			PageDTO pageDTO = optionalPageDTO.get();
			pageDTO.setSequence(sequence.setScale(4));
			pageDTO.setName(name);

			Optional<DiaryDTO> optionalDiaryDTO = diaryRepository.findById(pageDTO.getDiaryId());
			if (optionalDiaryDTO.isEmpty()) {
				return Response.internalError("Diary not found: id: " + pageDTO.getDiaryId());
			}
			DiaryDTO diaryDTO = optionalDiaryDTO.get();

			diary = new Diary(diaryDTO);
			page = new Page(diary, pageDTO);

		} catch (Exception e) {
			log.info("UpdatePage.handleRequest: args: " + mapper.writeValueAsString(args));
			throw new BadRequest(e.getMessage(), e);
		}

		// First update the Page in the database
		EntityManager em = context.getEntityManager();
		EntityTransaction tx = em.getTransaction();

		tx.begin();
		try {
			pageRepository.update(page);
			tx.commit();
		} catch (Exception e) {
			tx.rollback();
			return Response.internalError(e.getMessage());
		}

		// Now update the Page in the topic tree
		PageDTO pageDTO = new PageDTO(page);
		byte[] payload = mapper.writeValueAsBytes(pageDTO);
		String payloadStr = new String(payload);

		MqttAsyncClient client = context.getPublisherClient();
		int qos = 1;
		boolean retained = true;
		log.info("page: " + payloadStr);

		String topic = String.format("diaries/%s/%s", diary.getId(), page.getId());
		log.info("  --> topic: " + topic);
		client.publish(topic, payload, qos, retained).waitForCompletion();

		return Response.success(page.getId());
	}
}
