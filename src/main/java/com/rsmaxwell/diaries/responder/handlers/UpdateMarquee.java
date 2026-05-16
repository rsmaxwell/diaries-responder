package com.rsmaxwell.diaries.responder.handlers;

import java.util.List;
import java.util.Map;

import org.eclipse.paho.mqttv5.client.MqttAsyncClient;
import org.eclipse.paho.mqttv5.common.packet.UserProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rsmaxwell.diaries.responder.dto.MarqueeDBDTO;
import com.rsmaxwell.diaries.responder.dto.MarqueePublishDTO;
import com.rsmaxwell.diaries.responder.model.Diary;
import com.rsmaxwell.diaries.responder.model.Fragment;
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
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityTransaction;

public class UpdateMarquee extends RequestHandler {

	private static final Logger log = LoggerFactory.getLogger(UpdateMarquee.class);
	static private ObjectMapper mapper = new ObjectMapper();

	@Override
	public Response handleRequest(Object ctx, Map<String, Object> args, List<UserProperty> userProperties) throws Exception {

		log.info("UpdateMarquee.handleRequest: args: " + mapper.writeValueAsString(args));

		String accessToken = Authorization.getAccessToken(userProperties);
		DiaryContext context = (DiaryContext) ctx;
		Claims claims = Authorization.checkToken(context, "access", accessToken);
		Authorization.checkActive(claims);
		Authorization.checkRoleAtLeast(claims, Role.EDITOR);
		log.info("UpdateMarquee.handleRequest: Authorization.check: OK!");

		MarqueeRepository marqueeRepository = context.getMarqueeRepository();

		Marquee incomingMarquee;
		Marquee originalMarquee;

		// First update the marquee in the database
		EntityManager em = context.getEntityManager();
		EntityTransaction tx = em.getTransaction();

		tx.begin();
		try {

			Long id = Utilities.getLong(args, "id");
			Long version = Utilities.getLong(args, "version");
			Long pageId = Utilities.getLong(args, "pageId");
			Long fragmentId = Utilities.getLong(args, "fragmentId");
			Double x = Utilities.getDouble(args, "x");
			Double y = Utilities.getDouble(args, "y");
			Double width = Utilities.getDouble(args, "width");
			Double height = Utilities.getDouble(args, "height");

			// (1) get the original Marquee
			originalMarquee = context.inflateMarquee(id);

			// (2) get the incoming Marquee

			//@formatter:off
			MarqueeDBDTO marqueeDTO = MarqueeDBDTO.builder()
					.id(id)
					.version(version)
					.pageId(pageId)
					.x(x)
					.y(y)
					.width(width)
					.height(height)
					.build();
			//@formatter:on

			Page incomingPage = context.inflatePage(pageId);
			Fragment incomingFragment = context.inflateFragment(fragmentId);
			incomingMarquee = new Marquee(incomingPage, incomingFragment, marqueeDTO);

			// (3) check and bump the version
			incomingMarquee.checkAndIncrementVersion(originalMarquee);

			// (4) save to database
			int count = marqueeRepository.update(incomingMarquee);
			if (count != 1) {
				log.info("UpdateMarquee.handleRequest: number of records updated: {}", count);
			}
			tx.commit();

		} catch (RpcStatusException e) {
			log.warn("UpdateMarquee.handleRequest: request failed; rolling back transaction: {}", e.getMessage(), e);
			if (tx.isActive()) {
				tx.rollback();
			}
			throw e;
		} catch (Exception e) {
			log.error("UpdateMarquee.handleRequest: unexpected error; rolling back transaction", e);
			if (tx.isActive()) {
				tx.rollback();
			}
			throw e;
		}

		// (5) Remove the original Marquee from the TopicTree
		MqttAsyncClient client = context.getPublisherClient();
		if (originalMarquee.keyFieldsChanged(incomingMarquee)) {
			log.info("UpdateMarquee.handleRequest: removing the original marquee from the TopicTree");
			Page page = originalMarquee.getPage();
			Diary diary = page.getDiary();
			MarqueePublishDTO dto = new MarqueePublishDTO(originalMarquee);
			dto.remove(client, diary.getId());
		}

		// (6) publish the Marquee to the topic tree
		Page page = incomingMarquee.getPage();
		Diary diary = page.getDiary();
		MarqueePublishDTO dto = new MarqueePublishDTO(incomingMarquee);
		dto.publish(client, diary.getId());

		return Response.success(dto);
	}
}
