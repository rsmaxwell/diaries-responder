package com.rsmaxwell.diaries.responder.handlers;

import java.util.List;
import java.util.Map;

import org.eclipse.paho.mqttv5.client.MqttAsyncClient;
import org.eclipse.paho.mqttv5.common.packet.UserProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rsmaxwell.diaries.responder.dto.MarqueePublishDTO;
import com.rsmaxwell.diaries.responder.model.Marquee;
import com.rsmaxwell.diaries.responder.model.Page;
import com.rsmaxwell.diaries.responder.model.Role;
import com.rsmaxwell.diaries.responder.repository.MarqueeRepository;
import com.rsmaxwell.diaries.responder.utilities.Authorization;
import com.rsmaxwell.diaries.responder.utilities.DiaryContext;
import com.rsmaxwell.mqtt.rpc.common.Response;
import com.rsmaxwell.mqtt.rpc.common.Utilities;
import com.rsmaxwell.mqtt.rpc.exceptions.RpcStatusException;
import com.rsmaxwell.mqtt.rpc.responder.RequestHandler;

import io.jsonwebtoken.Claims;

public class DeleteMarquee extends RequestHandler {

	private static final Logger log = LoggerFactory.getLogger(DeleteMarquee.class);
	static private ObjectMapper mapper = new ObjectMapper();

	@Override
	public Response handleRequest(Object ctx, Map<String, Object> args, List<UserProperty> userProperties) throws Exception {

		log.info("DeleteMarquee.handleRequest");

		String accessToken = Authorization.getAccessToken(userProperties);
		DiaryContext context = (DiaryContext) ctx;
		Claims claims = Authorization.checkToken(context, "access", accessToken);
		Authorization.checkActive(claims);
		Authorization.checkRoleAtLeast(claims, Role.EDITOR);
		log.info("DeleteMarquee.handleRequest: Authorization.check: OK!");

		MarqueeRepository marqueeRepository = context.getMarqueeRepository();

		Marquee marquee;
		try {
			Long id = Utilities.getLong(args, "id");
			marquee = context.inflateMarquee(id);

		} catch (Exception e) {
			log.info("DeleteMarquee.handleRequest: args: " + mapper.writeValueAsString(args));
			throw RpcStatusException.badRequest(e.getMessage());
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
