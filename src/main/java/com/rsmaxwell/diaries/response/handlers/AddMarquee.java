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

public class AddMarquee extends RequestHandler {

	private static final Logger log = LogManager.getLogger(AddMarquee.class);
	static private ObjectMapper mapper = new ObjectMapper();

	@Override
	public Response handleRequest(Object ctx, Map<String, Object> args, List<UserProperty> userProperties) throws Exception {

		log.info("AddMarquee.handleRequest");

		String accessToken = Authorization.getAccessToken(userProperties);
		DiaryContext context = (DiaryContext) ctx;
		if (Authorization.checkToken(context, "access", accessToken) == null) {
			log.info("AddMarquee.handleRequest: Authorization.check: Failed!");
			throw new Unauthorised();
		}
		log.info("Authorization.check: OK!");

		DiaryRepository diaryRepository = context.getDiaryRepository();
		PageRepository pageRepository = context.getPageRepository();
		MarqueeRepository marqueeRepository = context.getMarqueeRepository();

		Diary diary;
		Page page;
		Marquee marquee;
		try {
			Long pageId = Utilities.getLong(args, "pageId");
			Double x = Utilities.getDouble(args, "x");
			Double y = Utilities.getDouble(args, "y");
			Double width = Utilities.getDouble(args, "width");
			Double height = Utilities.getDouble(args, "height");

			BigDecimal sequence = new BigDecimal(123.0000).setScale(4);

			if (width < 4.0) {
				width = 4.0;
			}
			if (height < 4.0) {
				height = 4.0;
			}

			Optional<PageDTO> optionalPageDTO = pageRepository.findById(pageId);
			if (optionalPageDTO.isEmpty()) {
				return Response.internalError("Page not found: id: " + pageId);
			}
			PageDTO pageDTO = optionalPageDTO.get();

			Optional<DiaryDTO> optionalDiaryDTO = diaryRepository.findById(pageDTO.getDiaryId());
			if (optionalDiaryDTO.isEmpty()) {
				return Response.internalError("Diary not found: id: " + pageDTO.getDiaryId());
			}
			DiaryDTO diaryDTO = optionalDiaryDTO.get();

			diary = new Diary(diaryDTO);
			page = new Page(diary, pageDTO);
			marquee = new Marquee(page, x, y, width, height, sequence);
			log.info("marquee:          " + mapper.writeValueAsString(marquee));

		} catch (Exception e) {
			log.info("AddMarquee.handleRequest: args: " + mapper.writeValueAsString(args));
			throw new BadRequest(e.getMessage(), e);
		}

		// First add the new Marquee to the database
		try {
			marqueeRepository.save(marquee); // this also updates the 'marquee.id'
		} catch (Exception e) {
			log.info("AddMarquee.handleRequest: Exception: " + e.getMessage());
			return Response.internalError(e.getMessage());
		}

		// Now publish the new Marquee to the topic tree
		MarqueeDTO dto = marquee.toDTO();
		String topic = "diary/" + diary.getId() + "/" + page.getId() + "/" + marquee.getId();
		byte[] payload = mapper.writeValueAsBytes(dto);
		String payloadStr = new String(payload);

		MqttAsyncClient client = context.getClientResponder();
		int qos = 1;
		boolean retained = true;

		log.info("Publishing topic: " + topic);
		log.info("         marquee: " + payloadStr);

		client.publish(topic, payload, qos, retained).waitForCompletion();

		return Response.success(marquee.getId());
	}
}
