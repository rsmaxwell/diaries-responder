package com.rsmaxwell.diaries.response.handlers;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.paho.mqttv5.client.MqttAsyncClient;
import org.eclipse.paho.mqttv5.common.packet.UserProperty;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rsmaxwell.diaries.response.dto.FragmentDBDTO;
import com.rsmaxwell.diaries.response.model.Fragment;
import com.rsmaxwell.diaries.response.model.Marquee;
import com.rsmaxwell.diaries.response.model.Page;
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

		Page page;
		Fragment fragment;
		Marquee marquee;
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

			page = context.inflatePage(pageId);

			Long id = 0L;
			Integer year = 0;
			Integer month = 0;
			Integer day = 0;
			Long version = 0L;
			String text = "Hello World!";

			marquee = new Marquee(id, page, null, x, y, width, height);
			FragmentDBDTO fragmentDTO = new FragmentDBDTO(id, null, year, month, day, sequence, version, text);
			fragment = new Fragment(fragmentDTO);

			fragment.setMarquee(marquee);
			marquee.setFragment(fragment);

			log.info("fragmentDTO: " + mapper.writeValueAsString(fragmentDTO));

		} catch (Exception e) {
			log.info("AddFragment.handleRequest: args: " + mapper.writeValueAsString(args));
			throw new BadRequest(e.getMessage(), e);
		}

		// First add the new Fragment to the database
		try {
			context.save(fragment); // This saves both the fragment and the marquee to the database
		} catch (Exception e) {
			log.info("AddFragment.handleRequest: Exception: " + e.getMessage());
			return Response.internalError(e.getMessage());
		}

		// Now publish the Fragment (and its marquee) to the topic tree
		MqttAsyncClient client = context.getPublisherClient();
		fragment.publish(client);
		marquee.publish(client);

		return Response.success(marquee.getId());
	}
}
