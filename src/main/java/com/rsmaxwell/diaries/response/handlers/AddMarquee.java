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
import com.rsmaxwell.diaries.response.dto.PageDTO;
import com.rsmaxwell.diaries.response.model.Diary;
import com.rsmaxwell.diaries.response.model.Fragment;
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

public class AddMarquee extends RequestHandler {

	private static final Logger log = LogManager.getLogger(AddMarquee.class);
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
		FragmentRepository fragmentRepository = context.getFragmentRepository();

		Diary diary;
		Page page;
		Fragment fragment;
		try {
			Long pageId = Utilities.getLong(args, "pageId");
			Double x = Utilities.getDouble(args, "x");
			Double y = Utilities.getDouble(args, "y");
			Double width = Utilities.getDouble(args, "width");
			Double height = Utilities.getDouble(args, "height");

			BigDecimal sequence = new BigDecimal(123.0000).setScale(4);

			if (width < 40.0) {
				width = 40.0;
			}
			if (height < 40.0) {
				height = 40.0;
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

			Long id = 0L;
			Integer year = 0;
			Integer month = 0;
			Integer day = 0;
			String text = "";

			FragmentDTO dto = new FragmentDTO(id, page.getId(), x, y, width, height, year, month, day, sequence, text);
			fragment = new Fragment(page, dto);
			log.info("fragment:          " + mapper.writeValueAsString(fragment));

		} catch (Exception e) {
			log.info("AddFragment.handleRequest: args: " + mapper.writeValueAsString(args));
			throw new BadRequest(e.getMessage(), e);
		}

		// First add the new Fragment to the database
		try {
			fragmentRepository.save(fragment); // this also updates 'fragment.id'
		} catch (Exception e) {
			log.info("AddFragment.handleRequest: Exception: " + e.getMessage());
			return Response.internalError(e.getMessage());
		}

		// Now publish the Fragment to the topic tree
		MqttAsyncClient client = context.getClientResponder();
		fragment.publish(client);

		return Response.success(fragment.getId());
	}
}
