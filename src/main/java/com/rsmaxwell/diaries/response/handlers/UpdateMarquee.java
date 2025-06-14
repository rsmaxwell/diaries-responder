package com.rsmaxwell.diaries.response.handlers;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.paho.mqttv5.client.MqttAsyncClient;
import org.eclipse.paho.mqttv5.common.packet.UserProperty;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rsmaxwell.diaries.response.dto.FragmentDBDTO;
import com.rsmaxwell.diaries.response.model.Fragment;
import com.rsmaxwell.diaries.response.model.Marquee;
import com.rsmaxwell.diaries.response.repository.MarqueeRepository;
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
		log.info("UpdateMarquee.handleRequest: Authorization.check: OK!");

		MarqueeRepository marqueeRepository = context.getMarqueeRepository();

		log.info("UpdateMarquee.handleRequest: make new Marquee");
		log.info("UpdateMarquee.handleRequest: args: " + mapper.writeValueAsString(args));

		Fragment fragment;
		Marquee marquee;
		try {
			Long fragmentId = Utilities.getLong(args, "fragmentId");
			Double x = Utilities.getDouble(args, "x");
			Double y = Utilities.getDouble(args, "y");
			Double width = Utilities.getDouble(args, "width");
			Double height = Utilities.getDouble(args, "height");

			Optional<FragmentDBDTO> optionalFragmentDTO = context.findFragmentWithMarqueeById(fragmentId);
			if (optionalFragmentDTO.isEmpty()) {
				throw new Exception("Fragment not found: id: " + fragmentId);
			}
			FragmentDBDTO fragmentDTO = optionalFragmentDTO.get();
			fragment = context.fragmentInflateDBDTO(fragmentDTO);
			marquee = fragment.getMarquee();
			marquee.setX(x);
			marquee.setY(y);
			marquee.setWidth(width);
			marquee.setHeight(height);

		} catch (Exception e) {
			log.info("UpdateMarquee.handleRequest: Exception: args: " + mapper.writeValueAsString(args));
			throw new BadRequest(e.getMessage(), e);
		}

		// First update the fragment in the database
		EntityManager em = context.getEntityManager();
		EntityTransaction tx = em.getTransaction();

		tx.begin();
		try {
			int count = marqueeRepository.update(marquee);
			if (count != 1) {
				log.info("UpdateMarquee.handleRequest: number of records updated: {}", count);
			}
			tx.commit();
		} catch (Exception e) {
			tx.rollback();
			return Response.internalError(e.getMessage());
		}

		// Now publish the Fragment to the topic tree
		MqttAsyncClient client = context.getPublisherClient();
		marquee.publish(client);
		fragment.publish(client);

		return Response.success(marquee.getId());
	}
}
