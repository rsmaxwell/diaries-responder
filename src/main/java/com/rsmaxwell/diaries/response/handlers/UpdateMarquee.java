package com.rsmaxwell.diaries.response.handlers;

import java.util.List;
import java.util.Map;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.eclipse.paho.mqttv5.client.MqttAsyncClient;
import org.eclipse.paho.mqttv5.common.packet.UserProperty;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rsmaxwell.diaries.response.dto.MarqueeDBDTO;
import com.rsmaxwell.diaries.response.dto.MarqueePublishDTO;
import com.rsmaxwell.diaries.response.model.Diary;
import com.rsmaxwell.diaries.response.model.Fragment;
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

		Marquee incomingMarquee;
		Marquee originalMarquee;
		try {
			Long id = Utilities.getLong(args, "id");
			Long version = Utilities.getLong(args, "version");
			Long pageId = Utilities.getLong(args, "pageId");
			Long fragmentId = Utilities.getLong(args, "fragmentId");
			Double x = Utilities.getDouble(args, "x");
			Double y = Utilities.getDouble(args, "y");
			Double width = Utilities.getDouble(args, "width");
			Double height = Utilities.getDouble(args, "height");

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

		} catch (Exception e) {
			log.info("UpdateMarquee.handleRequest: Exception: args: " + mapper.writeValueAsString(args));
			throw new BadRequest(e.getMessage(), e);
		}

		// First update the marquee in the database
		EntityManager em = context.getEntityManager();
		EntityTransaction tx = em.getTransaction();

		tx.begin();
		try {
			// (1) get the original Marquee
			originalMarquee = context.inflateMarquee(incomingMarquee.getId());

			// (2) check the incoming version number
			if (incomingMarquee.getVersion() != originalMarquee.getVersion()) {
				throw new BadRequest(String.format("Stale update. incoming version: %d, original version: %d", incomingMarquee.getVersion(), originalMarquee.getVersion()));
			}

			// (3) bump the version
			Long version = incomingMarquee.getVersion();
			incomingMarquee.setVersion(version + 1);

			// (4) save to database
			int count = marqueeRepository.update(incomingMarquee);
			if (count != 1) {
				log.info("UpdateMarquee.handleRequest: number of records updated: {}", count);
			}
			tx.commit();

		} catch (BadRequest e) {
			tx.rollback();
			return Response.badRequest(e.getMessage());
		} catch (Exception e) {
			tx.rollback();
			return Response.internalError(e.getMessage());
		}

		// (5) Remove the original Marquee from the TopicTree & add the incoming Marquee
		MqttAsyncClient client = context.getPublisherClient();
		if (originalMarquee.keyFieldsChanged(incomingMarquee)) {
			log.info("UpdateMarquee.handleRequest: removing the original marquee from the TopicTree");
			Page page = originalMarquee.getPage();
			Diary diary = page.getDiary();
			MarqueePublishDTO dto = new MarqueePublishDTO(originalMarquee);
			dto.remove(client, diary.getId());
		}

		// Now publish the Marquee to the topic tree
		Page page = incomingMarquee.getPage();
		Diary diary = page.getDiary();
		MarqueePublishDTO dto = new MarqueePublishDTO(incomingMarquee);
		dto.publish(client, diary.getId());

		return Response.success(incomingMarquee.getId());
	}
}
