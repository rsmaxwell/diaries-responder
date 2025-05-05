package com.rsmaxwell.diaries.response.handlers;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.paho.mqttv5.common.packet.UserProperty;

import com.rsmaxwell.diaries.response.dto.FragmentDTO;
import com.rsmaxwell.diaries.response.repository.FragmentRepository;
import com.rsmaxwell.diaries.response.utilities.Authorization;
import com.rsmaxwell.diaries.response.utilities.DiaryContext;
import com.rsmaxwell.mqtt.rpc.common.Response;
import com.rsmaxwell.mqtt.rpc.common.Utilities;
import com.rsmaxwell.mqtt.rpc.response.RequestHandler;
import com.rsmaxwell.mqtt.rpc.utilities.BadRequest;
import com.rsmaxwell.mqtt.rpc.utilities.Unauthorised;

public class AddFragment extends RequestHandler {

	private static final Logger log = LogManager.getLogger(AddFragment.class);

	@Override
	public Response handleRequest(Object ctx, Map<String, Object> args, List<UserProperty> userProperties) throws Exception {

		log.info("AddFragment.handleRequest");

		String accessToken = Authorization.getAccessToken(userProperties);
		DiaryContext context = (DiaryContext) ctx;
		if (Authorization.checkToken(context, "access", accessToken) == null) {
			log.info("GetDiaries.handleRequest: Authorization.check: Failed!");
			throw new Unauthorised();
		}
		log.info("AddFragment.handleRequest: Authorization.check: OK!");

		FragmentDTO fragmentDTO;
		try {
			Long pageId = Utilities.getLong(args, "pageId");
			Double x = Utilities.getDouble(args, "x");
			Double y = Utilities.getDouble(args, "y");
			Double width = Utilities.getDouble(args, "width");
			Double height = Utilities.getDouble(args, "height");

			BigDecimal sequence = new BigDecimal(123);
			String text = "";

			fragmentDTO = new FragmentDTO(0L, pageId, x, y, width, height, sequence, text);

		} catch (Exception e) {
			throw new BadRequest(e.getMessage(), e);
		}

		FragmentRepository fragmentRepository = context.getFragmentRepository();

		try {
			FragmentDTO fragmentDTO2 = fragmentRepository.saveDTO(fragmentDTO);
			return Response.success(fragmentDTO2.getId());
		} catch (Exception e) {
			return Response.internalError(e.getMessage());
		}
	}
}
