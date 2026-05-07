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
import com.rsmaxwell.diaries.responder.model.Fragment;
import com.rsmaxwell.diaries.responder.model.LockInfo;
import com.rsmaxwell.diaries.responder.model.Marquee;
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

public class UnlockFragment extends RequestHandler {

	private static final Logger log = LoggerFactory.getLogger(UnlockFragment.class);
	static private ObjectMapper mapper = new ObjectMapper();

	@Override
	public Response handleRequest(Object ctx, Map<String, Object> args, List<UserProperty> userProperties) throws Exception {

		log.info("UnlockFragment.handleRequest: args: " + mapper.writeValueAsString(args));

		String accessToken = Authorization.getAccessToken(userProperties);
		DiaryContext context = (DiaryContext) ctx;
		Claims claims = Authorization.checkToken(context, "access", accessToken);
		Authorization.checkActive(claims);
		Authorization.checkRoleAtLeast(claims, Role.EDITOR);
		log.info("UnlockFragment.handleRequest: Authorization.check: OK!");

		FragmentRepository fragmentRepository = context.getFragmentRepository();
		MarqueeRepository marqueeRepository = context.getMarqueeRepository();

		EntityManager em = context.getEntityManager();
		EntityTransaction tx = em.getTransaction();

		Fragment fragment;

		tx.begin();
		try {

			// get the incoming Fragment ID and inflate it
			Long id = Utilities.getLong(args, "id");
			fragment = context.inflateFragment(id);

			// get the fields needed to check the lock
			Long userId = claims.get("userId", Long.class);
			String sessionId = claims.get("sessionId", String.class);

			// Ensure lock object exists
			LockInfo lock = fragment.getLock();
			if (lock == null) {
				lock = new LockInfo();
				fragment.setLock(lock);
			}

			// Prevent stealing someone else’s lock
			if (lock.isLocked() && !lock.isLockedBy(userId, sessionId)) {
				tx.rollback();
				throw RpcStatusException.conflict("Fragment is locked by another user.");
			}

			// Unlock the fragment
			lock.clear();

			// save to database
			int count = fragmentRepository.update(fragment);
			if (count != 1) {
				log.info("UnlockFragment.handleRequest: number of records updated: {}", count);
			}
			tx.commit();

		} catch (RpcStatusException e) {
			log.warn("UnlockFragment.handleRequest: request failed; rolling back transaction: {}", e.getMessage(), e);
			if (tx.isActive()) {
				tx.rollback();
			}
			throw e;
		} catch (Exception e) {
			log.error("UnlockFragment.handleRequest: unexpected error; rolling back transaction", e);
			if (tx.isActive()) {
				tx.rollback();
			}
			throw e;
		}

		// get the marquee associated with the fragment (can be null)
		Marquee marquee = null;
		Optional<MarqueeDBDTO> optionalMarqueeDTO = marqueeRepository.findByFragment(fragment);
		if (optionalMarqueeDTO.isPresent()) {
			MarqueeDBDTO marqueeDTO = optionalMarqueeDTO.get();
			marquee = context.inflateMarquee(marqueeDTO);
		}

		// (6) publish the unlocked Fragment to the topic tree
		MqttAsyncClient client = context.getPublisherClient();
		log.info("UnlockFragment.handleRequest: publishing the unlocked fragment to the TopicTree");
		FragmentPublishDTO dto = new FragmentPublishDTO(fragment, marquee);
		dto.publish(client);

		return Response.success(fragment.getId());
	}
}
