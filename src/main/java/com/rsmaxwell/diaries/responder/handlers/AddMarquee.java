package com.rsmaxwell.diaries.responder.handlers;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.eclipse.paho.mqttv5.client.MqttAsyncClient;
import org.eclipse.paho.mqttv5.common.packet.UserProperty;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.rsmaxwell.diaries.responder.dto.FragmentPublishDTO;
import com.rsmaxwell.diaries.responder.dto.MarqueeDBDTO;
import com.rsmaxwell.diaries.responder.dto.MarqueePublishDTO;
import com.rsmaxwell.diaries.responder.model.Fragment;
import com.rsmaxwell.diaries.responder.model.FragmentType;
import com.rsmaxwell.diaries.responder.model.Marquee;
import com.rsmaxwell.diaries.responder.model.Page;
import com.rsmaxwell.diaries.responder.model.Role;
import com.rsmaxwell.diaries.responder.repository.MarqueeRepository;
import com.rsmaxwell.diaries.responder.repository.FragmentRepository;
import com.rsmaxwell.diaries.responder.utilities.Authorization;
import com.rsmaxwell.diaries.responder.utilities.DiaryContext;
import com.rsmaxwell.diaries.responder.utilities.FragmentLocking;
import com.rsmaxwell.mqtt.rpc.common.Response;
import com.rsmaxwell.mqtt.rpc.common.Utilities;
import com.rsmaxwell.mqtt.rpc.exceptions.RpcStatusException;
import com.rsmaxwell.mqtt.rpc.responder.RequestHandler;

import io.jsonwebtoken.Claims;
import jakarta.persistence.EntityManager;
import jakarta.persistence.EntityTransaction;

public class AddMarquee extends RequestHandler {

	private static final Logger log = LoggerFactory.getLogger(AddMarquee.class);
	private static final ObjectMapper mapper = new ObjectMapper();

	private static final double MIN_MARQUEE_SIZE = 40.0;

	@Override
	public Response handleRequest(Object ctx, Map<String, Object> args, List<UserProperty> userProperties) throws Exception {

		log.info("AddMarquee.handleRequest: args: {}", mapper.writeValueAsString(args));

		String accessToken = Authorization.getAccessToken(userProperties);
		DiaryContext context = (DiaryContext) ctx;
		Claims claims = Authorization.checkToken(context, "access", accessToken);
		Authorization.checkActive(claims);
		Authorization.checkRoleAtLeast(claims, Role.EDITOR);
		log.info("AddMarquee.handleRequest: Authorization.check: OK!");

		MarqueeRepository marqueeRepository = context.getMarqueeRepository();
		FragmentRepository fragmentRepository = context.getFragmentRepository();

		Page page;
		Fragment fragment;
		Marquee marquee;

		try {
			Long pageId = Utilities.getLong(args, "pageId");
			Long fragmentId = Utilities.getLong(args, "fragmentId");

			Double x = Utilities.getDouble(args, "x");
			Double y = Utilities.getDouble(args, "y");
			Double width = Utilities.getDouble(args, "width");
			Double height = Utilities.getDouble(args, "height");

			width = Math.max(width, MIN_MARQUEE_SIZE);
			height = Math.max(height, MIN_MARQUEE_SIZE);

			page = context.inflatePage(pageId);
			fragment = context.inflateFragment(fragmentId);

			Optional<MarqueeDBDTO> existingMarqueeDTO = marqueeRepository.findByFragment(fragment);
			if (existingMarqueeDTO.isPresent()) {
				throw RpcStatusException.badRequest("Fragment already has a marquee");
			}
			FragmentLocking.requireMarqueeCompatible(fragment);
			if (fragment.getPageId() != null && !fragment.getPageId().equals(pageId)) {
				throw RpcStatusException.badRequest("Fragment belongs to a different page");
			}

			marquee = Marquee.builder().id(0L).page(page).fragment(fragment).x(x).y(y).width(width).height(height).version(0L).build();

		} catch (RpcStatusException e) {
			throw e;

		} catch (Exception e) {
			log.info("AddMarquee.handleRequest: bad args: {}", mapper.writeValueAsString(args));
			throw RpcStatusException.badRequest(e.getMessage());
		}

		EntityManager em = context.getEntityManager();
		EntityTransaction tx = em.getTransaction();

		Marquee savedMarquee;

		try {
			tx.begin();

			fragment.setPage(page);
			fragment.setType(FragmentType.MARQUEE);
			int fragmentCount = fragmentRepository.update(fragment);
			if (fragmentCount != 1) {
				throw new IllegalStateException("Expected to update one Fragment, updated " + fragmentCount);
			}

			Long marqueeId = marqueeRepository.save(marquee);
			marquee.setId(marqueeId);
			savedMarquee = marquee;

			tx.commit();

		} catch (Exception e) {
			if (tx.isActive()) {
				tx.rollback();
			}

			throw RpcStatusException.internalError(e.getMessage());
		}

		MqttAsyncClient client = context.getPublisherClient();

		FragmentPublishDTO fragmentPublishDTO = new FragmentPublishDTO(fragment, savedMarquee);
		fragmentPublishDTO.publish(client);

		MarqueePublishDTO marqueePublishDTO = new MarqueePublishDTO(savedMarquee);
		marqueePublishDTO.publish(client, page.getDiary().getId());

		return Response.success(marqueePublishDTO);
	}
}
