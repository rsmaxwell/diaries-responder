package com.rsmaxwell.diaries.response.handlers;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.paho.mqttv5.common.packet.UserProperty;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rsmaxwell.diaries.response.dto.PageDTO;
import com.rsmaxwell.diaries.response.repository.PageRepository;
import com.rsmaxwell.diaries.response.utilities.DiaryContext;
import com.rsmaxwell.mqtt.rpc.common.Result;
import com.rsmaxwell.mqtt.rpc.common.Utilities;
import com.rsmaxwell.mqtt.rpc.response.RequestHandler;
import com.rsmaxwell.mqtt.rpc.utilities.BadRequest;

public class GetPages extends RequestHandler {

	private static final Logger log = LogManager.getLogger(GetPages.class);

	static private ObjectMapper mapper = new ObjectMapper();

	public Result handleRequest(Object ctx, Map<String, Object> args, List<UserProperty> userProperties) throws Exception {
		log.traceEntry();

		try {
			DiaryContext context = (DiaryContext) ctx;

			Long diaryId;
			try {
				diaryId = Utilities.getLong(args, "diary");
			} catch (Exception e) {
				throw new BadRequest(e.getMessage(), e);
			}

			PageRepository pageRepository = context.getPageRepository();

			List<PageDTO> pages = new ArrayList<PageDTO>();
			Iterable<PageDTO> all = pageRepository.findAllByDiaryId(diaryId);
			for (PageDTO page : all) {
				pages.add(page);
			}

			String json = mapper.writeValueAsString(pages);
			log.info(json);

			return Result.success(pages);

		} catch (BadRequest e) {
			return Result.badRequest(e.getMessage());
		} catch (Exception e) {
			log.catching(e);
			return Result.badRequest(e.getMessage());
		}
	}
}
