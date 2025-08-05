package com.rsmaxwell.diaries.response.handlers;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.paho.mqttv5.client.MqttAsyncClient;
import org.eclipse.paho.mqttv5.common.packet.UserProperty;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rsmaxwell.diaries.response.dto.PageDTO;
import com.rsmaxwell.diaries.response.model.Diary;
import com.rsmaxwell.diaries.response.model.Page;
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

		log.info("UpdatePage.handleRequest: args: " + mapper.writeValueAsString(args));

		String accessToken = Authorization.getAccessToken(userProperties);
		DiaryContext context = (DiaryContext) ctx;
		if (Authorization.checkToken(context, "access", accessToken) == null) {
			log.info("UpdatePage.handleRequest: Authorization.check: Failed!");
			throw new Unauthorised();
		}
		log.info("UpdatePage.handleRequest: Authorization.check: OK!");

		PageRepository pageRepository = context.getPageRepository();

		EntityManager em = context.getEntityManager();
		EntityTransaction tx = em.getTransaction();

		Page incomingPage;
		Page originalPage;

		tx.begin();
		try {
			Long id = Utilities.getLong(args, "id");
			Long version = Utilities.getLong(args, "version");
			BigDecimal sequence = Utilities.getBigDecimal(args, "sequence");
			Long diaryId = Utilities.getLong(args, "diaryId");
			String name = Utilities.getString(args, "name");
			String extension = Utilities.getString(args, "extension");
			Integer width = Utilities.getInteger(args, "width");
			Integer height = Utilities.getInteger(args, "height");

			// (1) get the original Page
			originalPage = context.inflatePage(id);

			// (2) get the incoming Page

			//@formatter:off
			PageDTO pageDTO = PageDTO.builder()
					.id(id)
					.version(version)
					.sequence(sequence)
					.diaryId(diaryId)
					.name(name)
					.extension(extension)
					.width(width)
					.height(height)
					.build();
			//@formatter:on

			Diary incomingDiary = context.inflateDiary(diaryId);
			incomingPage = new Page(incomingDiary, pageDTO);

			// (3) check and bump the version
			incomingPage.checkAndIncrementVersion(originalPage);

			// (4) save to database
			int count = pageRepository.update(incomingPage);
			if (count != 1) {
				log.info("UpdatePage.handleRequest: number of records updated: {}", count);
			}
			tx.commit();

		} catch (BadRequest e) {
			tx.rollback();
			return Response.badRequest(e.getMessage());
		} catch (Exception e) {
			tx.rollback();
			return Response.internalError(e.getMessage());
		}

		// (5) Remove the original page from the TopicTree
		MqttAsyncClient client = context.getPublisherClient();
		if (originalPage.keyFieldsChanged(incomingPage)) {
			log.info("Updatepage.handleRequest: removing the original page from the TopicTree");
			PageDTO dto = new PageDTO(originalPage);
			dto.remove(client);
		}

		// (6) publish the Page to the topic tree
		PageDTO dto = new PageDTO(incomingPage);
		dto.publish(client);

		return Response.success(incomingPage.getId());
	}
}
