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
import com.rsmaxwell.diaries.responder.model.Marquee;
import com.rsmaxwell.diaries.responder.model.Page;
import com.rsmaxwell.diaries.responder.model.Role;
import com.rsmaxwell.diaries.responder.repository.FragmentRepository;
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

public class DeleteFragment extends RequestHandler {

	private static final Logger log = LoggerFactory.getLogger(DeleteFragment.class);
	static private ObjectMapper mapper = new ObjectMapper();

	@Override
	public Response handleRequest(Object ctx, Map<String, Object> args, List<UserProperty> userProperties) throws Exception {

		log.info("DeleteFragment.handleRequest: args: " + mapper.writeValueAsString(args));

		String accessToken = Authorization.getAccessToken(userProperties);
		DiaryContext context = (DiaryContext) ctx;
		Claims claims = Authorization.checkToken(context, "access", accessToken);
		Authorization.checkActive(claims);
		Authorization.checkRoleAtLeast(claims, Role.EDITOR);
		log.info("DeleteFragment.handleRequest: Authorization.check: OK!");

		FragmentRepository fragmentRepository = context.getFragmentRepository();
		MarqueeRepository marqueeRepository = context.getMarqueeRepository();

		EntityManager em = context.getEntityManager();
		EntityTransaction tx = em.getTransaction();

		Fragment fragment = null;
		Marquee marquee = null;

		try {
			Long id = Utilities.getLong(args, "id");
			fragment = context.inflateFragment(id);

			Optional<MarqueeDBDTO> optionalMarqueeDTO = marqueeRepository.findByFragment(fragment);
			if (optionalMarqueeDTO.isPresent()) {
				marquee = context.inflateMarquee(optionalMarqueeDTO.get());
			}

		} catch (Exception e) {
			log.info("DeleteFragment.handleRequest: bad args: {}", mapper.writeValueAsString(args));
			throw RpcStatusException.badRequest(e.getMessage());
		}

		try {
			tx.begin();

			if (marquee != null) {
				context.deleteMarquee(marquee);
			}

			context.deleteFragment(fragment);

			tx.commit();

		} catch (Exception e) {
			log.error("UpdateFragment.handleRequest: unexpected error; rolling back transaction", e);
			if (tx.isActive()) {
				tx.rollback();
			}
			throw RpcStatusException.internalError(e.getMessage());
		}

		MqttAsyncClient client = context.getPublisherClient();

		if (marquee != null) {
			Page page = marquee.getPage();
			MarqueePublishDTO marqueePublishDTO = new MarqueePublishDTO(marquee);
			marqueePublishDTO.remove(client, page.getDiary().getId());
		}

		log.info("DeleteFragment.handleRequest: removing the fragment from the TopicTree");
		FragmentPublishDTO fragmentPublishDTO = new FragmentPublishDTO(fragment, marquee);
		fragmentPublishDTO.remove(client);

		return Response.success(fragment.getId());
	}
}
