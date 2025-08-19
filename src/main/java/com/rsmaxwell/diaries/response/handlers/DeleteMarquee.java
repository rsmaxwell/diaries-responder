package com.rsmaxwell.diaries.response.handlers;

import java.util.List;
import java.util.Map;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.paho.mqttv5.client.MqttAsyncClient;
import org.eclipse.paho.mqttv5.common.packet.UserProperty;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rsmaxwell.diaries.response.dto.MarqueePublishDTO;
import com.rsmaxwell.diaries.response.model.Marquee;
import com.rsmaxwell.diaries.response.model.Page;
import com.rsmaxwell.diaries.response.repository.MarqueeRepository;
import com.rsmaxwell.diaries.response.utilities.Authorization;
import com.rsmaxwell.diaries.response.utilities.DiaryContext;
import com.rsmaxwell.mqtt.rpc.common.Response;
import com.rsmaxwell.mqtt.rpc.common.Utilities;
import com.rsmaxwell.mqtt.rpc.response.RequestHandler;
import com.rsmaxwell.mqtt.rpc.utilities.BadRequest;
import com.rsmaxwell.mqtt.rpc.utilities.Unauthorised;

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

		MarqueeRepository marqueeRepository = context.getMarqueeRepository();

		Marquee marquee;
		try {
			Long id = Utilities.getLong(args, "id");

			marquee = context.inflateMarquee(id);

		} catch (Exception e) {
			log.info("DeleteMarquee.handleRequest: args: " + mapper.writeValueAsString(args));
			throw new BadRequest(e.getMessage(), e);
		}

		// First delete the marquee from the database
		marqueeRepository.delete(marquee);

		// Then remove the marquee from the topic tree
		MqttAsyncClient client = context.getPublisherClient();

		Page page = marquee.getPage();
		MarqueePublishDTO marqueePublishDTO = new MarqueePublishDTO(marquee);
		marqueePublishDTO.remove(client, page.getDiary().getId());

		return Response.success(marquee.getId());
	}
}
