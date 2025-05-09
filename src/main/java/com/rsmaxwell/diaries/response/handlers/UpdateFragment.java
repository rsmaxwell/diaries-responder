package com.rsmaxwell.diaries.response.handlers;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.paho.mqttv5.common.packet.UserProperty;

import com.rsmaxwell.diaries.response.dto.DiaryDTO;
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

public class UpdateFragment extends RequestHandler {

	private static final Logger log = LogManager.getLogger(UpdateFragment.class);

	@Override
	public Response handleRequest(Object ctx, Map<String, Object> args, List<UserProperty> userProperties) throws Exception {

		log.info("UpdateFragment.handleRequest");

		String accessToken = Authorization.getAccessToken(userProperties);
		DiaryContext context = (DiaryContext) ctx;
		if (Authorization.checkToken(context, "access", accessToken) == null) {
			log.info("UpdateFragment.handleRequest: Authorization.check: Failed!");
			throw new Unauthorised();
		}
		log.info("AddFragment.handleRequest: Authorization.check: OK!");

		DiaryRepository diaryRepository = context.getDiaryRepository();
		PageRepository pageRepository = context.getPageRepository();
		FragmentRepository fragmentRepository = context.getFragmentRepository();

		Fragment fragment;
		try {
			Long id = Utilities.getLong(args, "id");
			Long pageId = Utilities.getLong(args, "pageId");
			Double x = Utilities.getDouble(args, "x");
			Double y = Utilities.getDouble(args, "y");
			Double width = Utilities.getDouble(args, "width");
			Double height = Utilities.getDouble(args, "height");

			BigDecimal sequence = new BigDecimal(123);
			String text = "";

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
			Diary diary = new Diary(diaryDTO);
			Page page = new Page(diary, pageDTO);
			fragment = new Fragment(id, page, x, y, width, height, sequence, text);

		} catch (Exception e) {
			throw new BadRequest(e.getMessage(), e);
		}

		try {
			fragmentRepository.update(fragment);
			return Response.success(fragment.getId());
		} catch (Exception e) {
			return Response.internalError(e.getMessage());
		}
	}
}
