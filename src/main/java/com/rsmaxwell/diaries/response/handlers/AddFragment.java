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
import com.rsmaxwell.diaries.response.dto.FragmentDTO;
import com.rsmaxwell.diaries.response.dto.MarqueeDTO;
import com.rsmaxwell.diaries.response.dto.PageDTO;
import com.rsmaxwell.diaries.response.model.Diary;
import com.rsmaxwell.diaries.response.model.Fragment;
import com.rsmaxwell.diaries.response.model.Marquee;
import com.rsmaxwell.diaries.response.model.Page;
import com.rsmaxwell.diaries.response.repository.DiaryRepository;
import com.rsmaxwell.diaries.response.repository.FragmentRepository;
import com.rsmaxwell.diaries.response.repository.MarqueeRepository;
import com.rsmaxwell.diaries.response.repository.PageRepository;
import com.rsmaxwell.diaries.response.utilities.Authorization;
import com.rsmaxwell.diaries.response.utilities.DiaryContext;
import com.rsmaxwell.mqtt.rpc.common.Response;
import com.rsmaxwell.mqtt.rpc.common.Utilities;
import com.rsmaxwell.mqtt.rpc.response.RequestHandler;
import com.rsmaxwell.mqtt.rpc.utilities.BadRequest;
import com.rsmaxwell.mqtt.rpc.utilities.Unauthorised;

public class AddFragment extends RequestHandler {

	private static final Logger log = LogManager.getLogger(AddFragment.class);
	static private ObjectMapper mapper = new ObjectMapper();

	@Override
	public Response handleRequest(Object ctx, Map<String, Object> args, List<UserProperty> userProperties) throws Exception {

		log.info("AddFragment.handleRequest");

		String accessToken = Authorization.getAccessToken(userProperties);
		DiaryContext context = (DiaryContext) ctx;
		if (Authorization.checkToken(context, "access", accessToken) == null) {
			log.info("AddFragment.handleRequest: Authorization.check: Failed!");
			throw new Unauthorised();
		}
		log.info("Authorization.check: OK!");

		DiaryRepository diaryRepository = context.getDiaryRepository();
		PageRepository pageRepository = context.getPageRepository();
		MarqueeRepository marqueeRepository = context.getMarqueeRepository();
		FragmentRepository fragmentRepository = context.getFragmentRepository();

		Diary diary;
		Page page;
		Marquee marquee;
		Fragment fragment;
		try {
			Long marqueeId = Utilities.getLong(args, "marqueeId");
			Integer year = Utilities.getInteger(args, "year");
			Integer month = Utilities.getInteger(args, "month");
			Integer day = Utilities.getInteger(args, "day");
			BigDecimal sequence = Utilities.getBigDecimal(args, "sequence");
			String text = Utilities.getString(args, "text");

			Optional<MarqueeDTO> optionalMarqueeDTO = marqueeRepository.findById(marqueeId);
			if (optionalMarqueeDTO.isEmpty()) {
				return Response.internalError("Marquee not found: id: " + marqueeId);
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

			fragment = new Fragment(year, month, day, sequence, marquee, text);

			log.info("fragment:          " + mapper.writeValueAsString(fragment));

		} catch (Exception e) {
			log.info("AddFragment.handleRequest: args: " + mapper.writeValueAsString(args));
			throw new BadRequest(e.getMessage(), e);
		}

		// First add the new Fragment to the database
		try {
			fragmentRepository.save(fragment); // this also updates the 'fragment.id'
		} catch (Exception e) {
			log.info("AddFragment.handleRequest: Exception: " + e.getMessage());
			return Response.internalError(e.getMessage());
		}

		// Now publish the new Fragment to the topic tree
		FragmentDTO dto = fragment.toDTO();
		String topic = String.format("diary/%s/%s/%s/%s", diary.getId(), page.getId(), marquee.getId(), fragment.getId());
		byte[] payload = mapper.writeValueAsBytes(dto);
		String payloadStr = new String(payload);

		MqttAsyncClient client = context.getClientResponder();
		int qos = 1;
		boolean retained = true;

		log.info("Publishing topic: " + topic);
		log.info("        fragment: " + payloadStr);

		client.publish(topic, payload, qos, retained).waitForCompletion();

		return Response.success(marquee.getId());
	}
}
