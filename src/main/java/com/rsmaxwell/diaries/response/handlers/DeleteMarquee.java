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
import com.rsmaxwell.diaries.response.dto.MarqueeDTO;
import com.rsmaxwell.diaries.response.dto.PageDTO;
import com.rsmaxwell.diaries.response.model.Diary;
import com.rsmaxwell.diaries.response.model.Marquee;
import com.rsmaxwell.diaries.response.model.Page;
import com.rsmaxwell.diaries.response.repository.DiaryRepository;
import com.rsmaxwell.diaries.response.repository.MarqueeRepository;
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

public class DeleteMarquee extends RequestHandler {

	private static final Logger log = LogManager.getLogger(DeleteMarquee.class);
	static private ObjectMapper mapper = new ObjectMapper();

	@Override
	public Response handleRequest(Object ctx, Map<String, Object> args, List<UserProperty> userProperties) throws Exception {

		log.info("DeleteMarquee.handleRequest");

		String accessToken = Authorization.getAccessToken(userProperties);
		DiaryContext context = (DiaryContext) ctx;
		if (Authorization.checkToken(context, "access", accessToken) == null) {
			log.info("UpdateFragment.handleRequest: Authorization.check: Failed!");
			throw new Unauthorised();
		}
		log.info("DeleteMarquee.handleRequest: Authorization.check: OK!");

		DiaryRepository diaryRepository = context.getDiaryRepository();
		PageRepository pageRepository = context.getPageRepository();
		MarqueeRepository marqueeRepository = context.getMarqueeRepository();

		Diary diary;
		Page page;
		Marquee marquee;
		try {
			Long id = Utilities.getLong(args, "id");

			Optional<MarqueeDTO> optionalMarqueeDTO = marqueeRepository.findById(id);
			if (optionalMarqueeDTO.isEmpty()) {
				return Response.internalError("Fragment not found: id: " + id);
			}
			MarqueeDTO marqueeDTO = optionalMarqueeDTO.get();

			Optional<PageDTO> optionalPageDTO = pageRepository.findById(marqueeDTO.getPageId());
			if (optionalPageDTO.isEmpty()) {
				return Response.internalError("Page not found: id: " + marqueeDTO.getPageId());
			}
			PageDTO pageDTO = optionalPageDTO.get();

			Optional<DiaryDTO> optionalDiaryDTO = diaryRepository.findById(pageDTO.getDiaryId());
			if (optionalDiaryDTO.isEmpty()) {
				return Response.internalError("Diary not found: id: " + pageDTO.getDiaryId());
			}
			DiaryDTO diaryDTO = optionalDiaryDTO.get();

			diary = new Diary(diaryDTO);
			page = new Page(diary, pageDTO);
			marquee = new Marquee(page, marqueeDTO);

		} catch (Exception e) {
			log.info("DeleteMarquee.handleRequest: args: " + mapper.writeValueAsString(args));
			throw new BadRequest(e.getMessage(), e);
		}

		// First delete the fragment from the database
		EntityManager em = context.getEntityManager();
		EntityTransaction tx = em.getTransaction();

		tx.begin();
		try {
			marqueeRepository.delete(marquee);
			tx.commit();
		} catch (Exception e) {
			return Response.internalError(e.getMessage());
		}

		// Now delete the fragment from the topic tree
		String topic = "diary/" + diary.getId() + "/" + page.getId() + "/" + marquee.getId();
		byte[] payload = new byte[0];

		MqttAsyncClient client = context.getClientResponder();
		int qos = 1;
		boolean retained = true;

		log.info("DeleteMarquee.handleRequest: Publishing topic: {}, fragment: {}", topic, payload);
		client.publish(topic, payload, qos, retained).waitForCompletion();

		return Response.success(marquee.getId());
	}
}
