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
		log.info("AddFragment.handleRequest: Authorization.check: OK!");

		DiaryRepository diaryRepository = context.getDiaryRepository();
		PageRepository pageRepository = context.getPageRepository();
		MarqueeRepository marqueeRepository = context.getMarqueeRepository();

		Diary diary;
		Page page;
		Marquee marquee;
		try {
			Long id = Utilities.getLong(args, "id");
			Double x = Utilities.getDouble(args, "x");
			Double y = Utilities.getDouble(args, "y");
			Double width = Utilities.getDouble(args, "width");
			Double height = Utilities.getDouble(args, "height");

			BigDecimal sequence = new BigDecimal(123);

			Optional<MarqueeDTO> optionalMarqueeDTO = marqueeRepository.findById(id);
			if (optionalMarqueeDTO.isEmpty()) {
				return Response.internalError("Marquee not found: id: " + id);
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
			marquee = new Marquee(id, page, x, y, width, height, sequence);

		} catch (Exception e) {
			log.info("UpdateMarquee.handleRequest: args: " + mapper.writeValueAsString(args));
			throw new BadRequest(e.getMessage(), e);
		}

		// First update the marquee in the database
		EntityManager em = context.getEntityManager();
		EntityTransaction tx = em.getTransaction();

		tx.begin();
		try {
			marqueeRepository.update(marquee);
			tx.commit();
		} catch (Exception e) {
			tx.rollback();
			return Response.internalError(e.getMessage());
		}

		// Now update the marquee in the topic tree
		MarqueeDTO dto = marquee.toDTO();
		String topic = "diary/" + diary.getId() + "/" + page.getId() + "/" + marquee.getId();
		byte[] payload = mapper.writeValueAsBytes(dto);

		MqttAsyncClient client = context.getClientResponder();
		int qos = 1;
		boolean retained = true;

		log.info("UpdateMarquee.handleRequest: Publishing topic: {}, marquee: {}", topic, mapper.writeValueAsString(dto));
		client.publish(topic, payload, qos, retained).waitForCompletion();

		return Response.success(marquee.getId());
	}
}
