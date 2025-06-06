package com.rsmaxwell.diaries.response.handlers;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.paho.mqttv5.client.MqttAsyncClient;
import org.eclipse.paho.mqttv5.common.packet.UserProperty;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rsmaxwell.diaries.response.dto.DiaryDTO;
import com.rsmaxwell.diaries.response.dto.FragmentDTO;
import com.rsmaxwell.diaries.response.dto.PageDTO;
import com.rsmaxwell.diaries.response.model.Diary;
import com.rsmaxwell.diaries.response.model.Fragment;
import com.rsmaxwell.diaries.response.model.Marquee;
import com.rsmaxwell.diaries.response.model.Page;
import com.rsmaxwell.diaries.response.repository.DiaryRepository;
import com.rsmaxwell.diaries.response.repository.FragmentRepository;
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

public class UpdateMarquee extends RequestHandler {

	private static final Logger log = LogManager.getLogger(UpdateMarquee.class);
	static private ObjectMapper mapper = new ObjectMapper();

	@Override
	public Response handleRequest(Object ctx, Map<String, Object> args, List<UserProperty> userProperties) throws Exception {

		log.info("UpdateMarquee.handleRequest");

		String accessToken = Authorization.getAccessToken(userProperties);
		DiaryContext context = (DiaryContext) ctx;
		if (Authorization.checkToken(context, "access", accessToken) == null) {
			log.info("UpdateMarquee.handleRequest: Authorization.check: Failed!");
			throw new Unauthorised();
		}
		log.info("UpdateMarquee.handleRequest: Authorization.check: OK!");

		DiaryRepository diaryRepository = context.getDiaryRepository();
		PageRepository pageRepository = context.getPageRepository();
		FragmentRepository fragmentRepository = context.getFragmentRepository();

		log.info("UpdateMarquee.handleRequest: make new Marquee");

		Marquee marquee;
		try {
			Long id = Utilities.getLong(args, "id");
			Double x = Utilities.getDouble(args, "x");
			Double y = Utilities.getDouble(args, "y");
			Double width = Utilities.getDouble(args, "width");
			Double height = Utilities.getDouble(args, "height");

			marquee = new Marquee(id, x, y, width, height);

		} catch (Exception e) {
			log.info("UpdateMarquee.handleRequest: Exception: args: " + mapper.writeValueAsString(args));
			throw new BadRequest(e.getMessage(), e);
		}

		// First update the fragment in the database
		EntityManager em = context.getEntityManager();
		EntityTransaction tx = em.getTransaction();

		tx.begin();
		try {
			int count = fragmentRepository.updateWithMarquee(marquee);
			if (count != 1) {
				log.info("UpdateMarquee.handleRequest: number of records updated: {}", count);
			}
			tx.commit();
		} catch (Exception e) {
			tx.rollback();
			return Response.internalError(e.getMessage());
		}

		// Reconstruct the Fragment
		Optional<FragmentDTO> optionalFragmentDTO = fragmentRepository.findById(marquee.getId());
		if (optionalFragmentDTO.isEmpty()) {
			return Response.internalError("Fragment not found: id: " + marquee.getId());
		}
		FragmentDTO fragmentDTO = optionalFragmentDTO.get();

		Optional<PageDTO> optionalPageDTO = pageRepository.findById(fragmentDTO.getPageId());
		if (optionalPageDTO.isEmpty()) {
			return Response.internalError("Page not found: id: " + fragmentDTO.getPageId());
		}
		PageDTO pageDTO = optionalPageDTO.get();

		Optional<DiaryDTO> optionalDiaryDTO = diaryRepository.findById(pageDTO.getDiaryId());
		if (optionalDiaryDTO.isEmpty()) {
			return Response.internalError("Diary not found: id: " + pageDTO.getDiaryId());
		}
		DiaryDTO diaryDTO = optionalDiaryDTO.get();
		Diary diary = new Diary(diaryDTO);
		Page page = new Page(diary, pageDTO);
		Fragment fragment = new Fragment(page, fragmentDTO);

		// Now publish the Fragment to the topic tree
		MqttAsyncClient client = context.getPublisherClient();
		fragment.publish(client);

		return Response.success(fragmentDTO.getId());
	}
}
